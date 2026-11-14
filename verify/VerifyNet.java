import com.hollow.net.InputBuffer;
import com.hollow.net.Transport;
import com.hollow.net.RollbackSim;

import java.util.HashMap;
import java.util.Map;

/**
 * v2.6.0 netcode-rollback regression suite.
 * Drives TWO RollbackSims through one Relay transport with latency/loss and
 * a deterministic physics-ish game (1D mover per player) to prove:
 * convergence after rollbacks, input redundancy surviving drops, no desync.
 */
public class VerifyNet {
    static int fails = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        if(!ok) fails++;
    }

    /** deterministic tiny game: two movers on X axis; input ax steers own mover */
    static class MoverGame implements RollbackSim.Callbacks {
        float[] x = new float[2];               // player 0 / player 1 positions
        long checksum = 0;

        static MoverGame gameFor(int id){
            MoverGame g = new MoverGame();
            g.ownerId = id;
            return g;
        }
        int ownerId;

        @Override public byte[] saveState(){
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(16);
            for(float v : x){ int i = Float.floatToIntBits(v); b.write(i); b.write(i>>8); b.write(i>>16); b.write(i>>24); }
            return b.toByteArray();
        }
        @Override public void restoreState(byte[] s){
            for(int p = 0; p < 2; p++){
                int i = (s[p*4]&0xFF) | ((s[p*4+1]&0xFF)<<8) | ((s[p*4+2]&0xFF)<<16) | ((s[p*4+3]&0xFF)<<24);
                x[p] = Float.intBitsToFloat(i);
            }
        }
        @Override public void stepTick(int tick, Map<Integer, InputBuffer.Frame> inputs){
            // deterministic: integrate own input only; both peers run identical code
            for(int p = 0; p < 2; p++){
                InputBuffer.Frame f = inputs.get(p);
                float ax = f != null ? f.ax : 0f;
                x[p] += ax * 0.1f;    // 6 units/sec at full stick
                // clamp
                x[p] = Math.max(-50f, Math.min(50f, x[p]));
            }
            checksum = checksum * 31 + Float.floatToIntBits(x[0]) + Float.floatToIntBits(x[1]) * 7;
        }

        float checksumF(){ return x[0] * 1000f + x[1]; }
    }

    public static void main(String[] a){
        // ================= 1) InputBuffer round-trip =================
        InputBuffer ib = new InputBuffer();
        ib.set(10, 0b1010L, 0.5f, -0.25f);
        ib.set(11, 0b0110L, -1f, 1f);
        byte[] wire = ib.encodeRedundant(11);
        InputBuffer decoded = InputBuffer.decodeRedundant(wire);
        check("input redundancy wire round-trip (tick 11 survives)",
              decoded.get(11) != null && decoded.get(11).buttons == 0b0110L
              && Math.abs(decoded.get(11).ax - (-1f)) < 1e-6f,
              "tick11=" + decoded.get(11));
        check("redundant payload includes tick 10 (history)",
              decoded.get(10) != null && decoded.get(10).buttons == 0b1010L, "tick10 missing");
        boolean wasMissing = ib.get(9) == null;
        ib.set(9, 1L, 0f, 0f);
        check("late-arriving older input merges (set(9) after set(11))",
              wasMissing && ib.get(9) != null && ib.get(9).buttons == 1L, "merge failed");

        // ================= 2) Relay transport: latency + loss =================
        Transport.Relay relay = new Transport.Relay(100, 200);
        relay.latencySec = 0.05f; relay.jitterSec = 0f; relay.lossRate = 0.3f;
        int sent = 0, deliveredAny = 0;
        for(int i = 0; i < 100; i++){
            relay.send(200, new byte[]{1});
            sent++;
        }
        for(int f = 0; f < 40; f++) relay.update(0.016f);   // ~640ms
        deliveredAny = relay.poll(200).size();
        check("relay delivers under 30% loss (some dropped, some through)",
              deliveredAny > 30 && deliveredAny < 95 && relay.packetsDropped > 0,
              "delivered=" + deliveredAny + " dropped=" + relay.packetsDropped);

        // ================= 3) two peers converge (rollback works) =================
        // player 100 (local A) and player 200 (local B), 60Hz, 80ms one-way
        Transport.Relay r2 = new Transport.Relay(100, 200);
        r2.latencySec = 0.08f; r2.jitterSec = 0.02f; r2.lossRate = 0.05f;
        MoverGame gA = MoverGame.gameFor(100), gB = MoverGame.gameFor(200);
        RollbackSim simA = new RollbackSim(r2, 100, gA);
        RollbackSim simB = new RollbackSim(r2, 200, gB);
        simA.addRemotePlayer(200);
        simB.addRemotePlayer(100);

        // drive 300 ticks (5s): A steers right, B steers left, with a wobble
        java.util.Random rng = new java.util.Random(99L);
        for(int t = 0; t < 300; t++){
            float axA = 0.7f + 0.3f * (float)Math.sin(t * 0.05);
            float axB = -0.6f + 0.2f * (float)Math.cos(t * 0.07);
            simA.setLocalInput(0L, axA, 0f, t);
            simB.setLocalInput(0L, axB, 0f, t);
            simA.pump(1f / 60f);
            simB.pump(1f / 60f);
        }
        // let the tail propagate: keep pumping with zero input
        for(int t = 300; t < 360; t++){
            simA.setLocalInput(0L, 0f, 0f, t);
            simB.setLocalInput(0L, 0f, 0f, t);
            simA.pump(1f / 60f);
            simB.pump(1f / 60f);
        }
        float diff = Math.abs(gA.checksumF() - gB.checksumF());
        check("both peers converge to identical state after 6s (diff < 1e-3)",
              diff < 1e-3f, "A=" + gA.checksumF() + " B=" + gB.checksumF() + " diff=" + diff);
        check("peer A actually rolled back at least once (corrections happened)",
              simA.rollbacks > 0 || simB.rollbacks > 0, "A.rb=" + simA.rollbacks + " B.rb=" + simB.rollbacks);
        check("no stall frames within 7-frame rollback window",
              simA.stallFrames == 0 && simB.stallFrames == 0, "A.stall=" + simA.stallFrames + " B.stall=" + simB.stallFrames);

        // ================= 4) determinism: same inputs, no network => bit-equal =================
        MoverGame g1 = new MoverGame(), g2 = new MoverGame();
        Map<Integer, InputBuffer.Frame> ins = new HashMap<>();
        for(int t = 0; t < 120; t++){
            ins.put(0, new InputBuffer.Frame(t, t % 7, 0.33f * (t % 3) - 0.33f, 0f));
            ins.put(1, new InputBuffer.Frame(t, 0, -0.5f, 0f));
            g1.stepTick(t, ins);
            g2.stepTick(t, ins);
        }
        check("pure-function stepping is deterministic (bit-equal checksums)",
              Float.floatToIntBits(g1.x[0]) == Float.floatToIntBits(g2.x[0])
              && Float.floatToIntBits(g1.x[1]) == Float.floatToIntBits(g2.x[1]),
              "g1=" + g1.x[0] + " g2=" + g2.x[0]);

        System.out.println(fails == 0 ? "ALL NETCODE REGRESSION TESTS PASSED" : fails + " TESTS FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
