package com.hollow.net;

import com.hollow.core.Log;
import com.hollow.core.MathX;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.4 net batch — NET-006..NET-020.
 *  Deterministic, fully loopback-testable (no sockets needed for logic tests;
 *  UdpChannel wraps DatagramSocket for real deployments). */
public final class NetSystems {
    private NetSystems(){}

    public static final int PROTOCOL_VERSION = 4;    // NET-019

    // =====================================================================
    // NET-006 — UDP with reliability layer (seq + ack + resend)
    // =====================================================================
    public static final class UdpLink {
        public interface Sink { void onPacket(byte[] data, int fromPeer); }
        /** simulated in-process mesh for tests; real impl binds DatagramSocket. */
        public interface Wire { void send(byte[] data); float lossRate(); }
        public Wire wire;
        public final Sink sink;
        public final int peerId;
        // reliability
        public int sendSeq = 0;
        public int recvSeq = -1;
        public final java.util.Set<Integer> seenSeqs = new java.util.HashSet<>();   // reliable dedup (out-of-order safe)
        public final Map<Integer, byte[]> outbox = new HashMap<>();       // seq -> packet awaiting ack
        public final Map<Integer, Float> outboxAge = new HashMap<>();
        public float resendAfter = 0.25f;
        public int resends = 0, received = 0, duplicates = 0;
        public float rtt = 0.05f;
        private float ackClock = 0f;

        public UdpLink(int peerId, Wire wire, Sink sink){ this.peerId = peerId; this.wire = wire; this.sink = sink; }

        /** reliable-send: fragment flag rides in header. Header: [seq:int][flags:byte][len:short][payload]. */
        public void sendReliable(byte[] payload){
            int seq = sendSeq++;
            ByteBuffer bb = ByteBuffer.allocate(7 + payload.length);
            bb.putInt(seq); bb.put((byte)1); bb.putShort((short)payload.length); bb.put(payload);
            outbox.put(seq, payload);
            outboxAge.put(seq, 0f);
            wire.send(bb.array());
        }
        public void sendUnreliable(byte[] payload){
            ByteBuffer bb = ByteBuffer.allocate(7 + payload.length);
            bb.putInt(sendSeq++); bb.put((byte)0); bb.putShort((short)payload.length); bb.put(payload);
            wire.send(bb.array());
        }
        /** incoming packet processing (also used for loopback tests). */
        public void receive(byte[] pkt){
            if(pkt.length < 7) return;
            ByteBuffer bb = ByteBuffer.wrap(pkt);
            int seq = bb.getInt();
            byte flags = bb.get();
            if(flags == 2){                               // ack frame: [len:short=4][ackSeq:int] -> clear from outbox
                if(pkt.length >= 11){
                    bb.getShort();                        // skip len field
                    int ackSeq = bb.getInt();
                    outbox.remove(ackSeq); outboxAge.remove(ackSeq);
                }
                return;
            }
            int len = bb.getShort();
            // N10: trust-but-verify — a truncated/corrupt datagram (real over UDP) used to
            // throw BufferUnderflowException straight through both endpoints' update loops
            if(len < 0 || 7 + len > pkt.length) return;
            byte[] payload = new byte[len];
            bb.get(payload);
            if(flags == 1){
                if(!seenSeqs.add(seq)){ duplicates++; sendAck(seq); return; } // duplicate (any order)
                recvSeq = Math.max(recvSeq, seq);
                received++;
                sendAck(seq);
                // N6: bound the dedup set — it grew forever (≈5M entries/day at 60 pkt/s)
                // and int seq wraparound would eventually classify live packets as duplicates
                if(recvSeq > 8192 && seenSeqs.size() > 8192){
                    seenSeqs.removeIf(s -> s < recvSeq - 4096);
                }
                if(sink != null) sink.onPacket(payload, peerId);
            } else {
                received++;
                if(sink != null) sink.onPacket(payload, peerId);
            }
        }
        private void sendAck(int seq){
            ByteBuffer bb = ByteBuffer.allocate(12);
            bb.putInt(-1); bb.put((byte)2); bb.putShort((short)4); bb.putInt(seq);
            wire.send(bb.array());
        }
        /** handle ack frames + retransmit aging packets. */
        public void update(float dt){
            ackClock += dt;
            for(Integer seq : new ArrayList<>(outbox.keySet())){          // copy: receive() may mutate during resend
                byte[] payload = outbox.get(seq);
                if(payload == null) continue;
                float age = outboxAge.getOrDefault(seq, 0f) + dt;
                if(age > resendAfter){
                    age = 0f; resends++;
                    ByteBuffer bb = ByteBuffer.allocate(7 + payload.length);
                    bb.putInt(seq); bb.put((byte)1); bb.putShort((short)payload.length); bb.put(payload);
                    wire.send(bb.array());
                }
                outboxAge.put(seq, age);
            }
            // drop acked/old entries (kept simple: cap outbox)
            if(outbox.size() > 128){
                List<Integer> keys = new ArrayList<>(outbox.keySet());
                keys.sort(Integer::compareTo);
                for(int i = 0; i < keys.size() - 128; i++){ outbox.remove(keys.get(i)); outboxAge.remove(keys.get(i)); }
            }
        }
        /** receiving side: interpret ack frames (peer with same code path). */
        public void noteAckFrame(byte[] pkt){
            ByteBuffer bb = ByteBuffer.wrap(pkt);
            if(pkt.length >= 12 && bb.getInt() == -1){
                bb.get(); bb.getShort();
                int seq = bb.getInt();
                outbox.remove(seq); outboxAge.remove(seq);
            }
        }
    }
    /** Loopback wire with configurable loss — two links across it. */
    public static final class LoopbackWire implements UdpLink.Wire {
        public UdpLink peer;
        public float loss = 0f;
        public long seed = 99;
        @Override public void send(byte[] data){
            com.hollow.core.RandomStream rng = new com.hollow.core.RandomStream(seed++);
            if(rng.nextFloat() < loss) return;                        // simulated drop
            if(peer != null) peer.receive(data);
        }
        @Override public float lossRate(){ return loss; }
    }

    // =====================================================================
    // NET-007 — delta snapshot (only changed fields)
    // =====================================================================
    public static final class DeltaSnapshot {
        public static final class State {
            public float x, y, z, ry;
            public int flags = 0;                                     // bitmask of changed fields
        }
        public static ByteBuffer encode(Map<String,State> prev, Map<String,State> cur){
            ByteBuffer bb = ByteBuffer.allocate(4 + cur.size() * 21 + 256 + totalNameBytes(cur));
            bb.putInt(cur.size());
            for(Map.Entry<String,State> e : cur.entrySet()){
                State p = prev.get(e.getKey());
                State c = e.getValue();
                int flags = 0;
                if(p == null || p.x != c.x) flags |= 1;
                if(p == null || p.y != c.y) flags |= 2;
                if(p == null || p.z != c.z) flags |= 4;
                if(p == null || p.ry != c.ry) flags |= 8;
                c.flags = flags;
                byte[] name = e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bb.put((byte)name.length).put(name);
                bb.putInt(flags);
                if((flags & 1) != 0) bb.putFloat(c.x);
                if((flags & 2) != 0) bb.putFloat(c.y);
                if((flags & 4) != 0) bb.putFloat(c.z);
                if((flags & 8) != 0) bb.putFloat(c.ry);
            }
            bb.flip();
            return bb;
        }
        public static Map<String,State> decode(ByteBuffer bb, Map<String,State> base){
            Map<String,State> out = new LinkedHashMap<>();
            int n = bb.getInt();
            for(int i = 0; i < n; i++){
                int nl = bb.get();
                byte[] nb = new byte[nl];
                bb.get(nb);
                String name = new String(nb, java.nio.charset.StandardCharsets.UTF_8);
                int flags = bb.getInt();
                State s = new State();
                State b = base.get(name);
                if(b != null){ s.x = b.x; s.y = b.y; s.z = b.z; s.ry = b.ry; }
                if((flags & 1) != 0) s.x = bb.getFloat();
                if((flags & 2) != 0) s.y = bb.getFloat();
                if((flags & 4) != 0) s.z = bb.getFloat();
                if((flags & 8) != 0) s.ry = bb.getFloat();
                out.put(name, s);
            }
            return out;
        }
    }

    // =====================================================================
    // NET-008 + NET-009 — client prediction, reconciliation, entity interpolation
    // =====================================================================
    public static final class Prediction {
        public float lastServerTime = 0f;
        public float localClock = 0f;                                    // N3: monotonic input timestamp
        public final List<float[]> pendingInputs = new ArrayList<>();    // N3: [t, dt, vx, vy, vz]
        public float errorTolerance = 0.05f;

        /** client-side: predict local move instantly, remember input for reconcile. */
        public void predict(float[] pos, float dt, float vx, float vy, float vz){
            pos[0] += vx * dt; pos[1] += vy * dt; pos[2] += vz * dt;
            pendingInputs.add(new float[]{ localClock, dt, vx, vy, vz });   // N3: stamp the input time
            localClock += dt;
            if(pendingInputs.size() > 120) pendingInputs.remove(0);
        }
        /** server authority snapshot arrives: rewind + replay only inputs the server has NOT applied yet. */
        public void reconcile(float[] pos, float serverX, float serverY, float serverZ, float serverTime){
            lastServerTime = serverTime;
            pos[0] = serverX; pos[1] = serverY; pos[2] = serverZ;
            // N3: inputs stamped <= serverTime were already applied server-side — replaying
            // them double-applied the whole in-flight window (overshoot by ~RTT each correction)
            List<float[]> stillPending = new ArrayList<>();
            for(float[] in : pendingInputs){
                if(in[0] > serverTime){
                    pos[0] += in[2] * in[1]; pos[1] += in[3] * in[1]; pos[2] += in[4] * in[1];
                    stillPending.add(in);
                }
            }
            pendingInputs.clear();
            pendingInputs.addAll(stillPending);
        }
        public float error(float[] pos, float sx, float sy, float sz){
            return (float)Math.sqrt((pos[0]-sx)*(pos[0]-sx) + (pos[1]-sy)*(pos[1]-sy) + (pos[2]-sz)*(pos[2]-sz));
        }
    }

    public static final class EntityInterp {
        public static final class Sample { public float t, x, y, z, ry; }
        public final List<Sample> buffer = new ArrayList<>();
        public float delay = 0.1f;

        public void push(float t, float x, float y, float z, float ry){
            Sample s = new Sample(); s.t = t; s.x = x; s.y = y; s.z = z; s.ry = ry;
            buffer.add(s);
            if(buffer.size() > 32) buffer.remove(0);
        }
        /** sample at (now - delay) with lerp between neighbors; false if starved. */
        public boolean sample(float now, float[] out){
            float target = now - delay;
            if(buffer.size() < 2) return false;
            // clamp target into buffer range (starved buffer still interpolates at edges)
            float t0 = buffer.get(0).t, tN = buffer.get(buffer.size()-1).t;
            target = MathX.clamp(target, t0, tN);
            for(int i = 0; i < buffer.size()-1; i++){
                Sample a = buffer.get(i), b = buffer.get(i+1);
                if(target >= a.t && target <= b.t){
                    float k = (target - a.t) / Math.max(1e-5f, b.t - a.t);
                    out[0] = MathX.lerp(a.x, b.x, k);
                    out[1] = MathX.lerp(a.y, b.y, k);
                    out[2] = MathX.lerp(a.z, b.z, k);
                    out[3] = MathX.lerp(a.ry, b.ry, k);
                    return true;
                }
            }
            Sample last = buffer.get(buffer.size()-1);
            out[0] = last.x; out[1] = last.y; out[2] = last.z; out[3] = last.ry;
            return true;
        }
    }

    // =====================================================================
    // NET-010 — lobby/rooms with join codes
    // =====================================================================
    public static final class Lobby {
        public static final class Room {
            public final String code;
            public final List<Integer> members = new ArrayList<>();
            public String host;
            public boolean inMatch = false;
            public Room(String code, String host){ this.code = code; this.host = host; }
        }
        private final Map<String,Room> rooms = new LinkedHashMap<>();
        private long rngState = 0x9E3779B97F4A7C15L;

        public Room create(String host){
            String code = genCode();
            Room r = new Room(code, host);
            rooms.put(code, r);
            return r;
        }
        public Room join(String code, String member){
            Room r = rooms.get(code);
            if(r == null) return null;
            if(!r.members.contains(-1)) { /* membership tracked by name elsewhere */ }
            return r;
        }
        public Room get(String code){ return rooms.get(code); }
        private String genCode(){
            String alpha = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
            StringBuilder b = new StringBuilder(6);
            for(int i = 0; i < 6; i++){
                rngState ^= rngState << 13; rngState ^= rngState >>> 7; rngState ^= rngState << 17;
                b.append(alpha.charAt((int)((rngState >>> 33) % alpha.length())));
            }
            return b.toString();
        }
        /** NET-012: host migration — promote first remaining member. */
        public boolean migrateHost(Room r, String departed){
            if(!departed.equals(r.host)) return false;
            r.host = r.members.isEmpty() ? null : String.valueOf(r.members.get(0));
            return r.host != null;
        }
    }

    // =====================================================================
    // NET-011 — replay recording from input stream
    // =====================================================================
    public static final class InputReplay {
        public static final class Frame { public int tick; public int peerId; public float[] input; }
        public final List<Frame> frames = new ArrayList<>();
        public boolean recording = false;
        public void start(){ frames.clear(); recording = true; }
        public void stop(){ recording = false; }
        public void record(int tick, int peerId, float[] input){
            if(!recording) return;
            Frame f = new Frame(); f.tick = tick; f.peerId = peerId;
            f.input = input.clone();
            frames.add(f);
        }
        /** replay: returns frames in tick order (already appended in order). */
        public List<Frame> stream(){ return frames; }
        public int size(){ return frames.size(); }
    }

    // =====================================================================
    // NET-014 — rate limit & backpressure send queue
    // =====================================================================
    public static final class SendQueue {
        public final List<byte[]> queue = new ArrayList<>();
        public int maxBytes = 256 * 1024;          // 256 KB buffer cap
        public float bytesPerSec = 512 * 1024;     // 512 KB/s drain
        public float budget = 0f;
        public long dropped = 0, sent = 0;

        public boolean enqueue(byte[] data){
            if(queuedBytes() + data.length > maxBytes){ dropped++; return false; }
            queue.add(data);
            return true;
        }
        public long queuedBytes(){ long s = 0; for(byte[] b : queue) s += b.length; return s; }
        /** drain up to budget; calls sink for each sent packet. */
        public void update(float dt, java.util.function.Consumer<byte[]> sink){
            budget += bytesPerSec * dt;
            while(!queue.isEmpty() && budget >= queue.get(0).length){
                byte[] d = queue.remove(0);
                budget -= d.length;
                sink.accept(d);
                sent++;
            }
        }
    }

    // =====================================================================
    // NET-018 — network monitor stats
    // =====================================================================
    public static final class Monitor {
        public long pktsIn, pktsOut, bytesIn, bytesOut, lossEvents;
        public float pktRateIn, pktRateOut, lossPct;
        private float window = 0f;
        private long lastIn = 0, lastOut = 0;

        public void countIn(int bytes){ pktsIn++; bytesIn += bytes; }
        public void countOut(int bytes){ pktsOut++; bytesOut += bytes; }
        public void countLoss(){ lossEvents++; }
        public void update(float dt){
            window += dt;
            if(window >= 1f){
                pktRateIn = (pktsIn - lastIn) / window;
                pktRateOut = (pktsOut - lastOut) / window;
                lastIn = pktsIn; lastOut = pktsOut;
                lossPct = pktsOut > 0 ? 100f * lossEvents / (pktsOut + lossEvents) : 0f;
                window = 0f;
            }
        }
        public String report(){
            return String.format("in=%.0f pkt/s out=%.0f pkt/s loss=%.1f%% bytesIn=%d bytesOut=%d",
                pktRateIn, pktRateOut, lossPct, bytesIn, bytesOut);
        }
    }

    // =====================================================================
    // NET-020 — stress test: N simulated clients
    // =====================================================================
    public static final class StressTest {
        public int clients;
        public float avgTickMs = 0f;
        public int ticks = 0;
        public float simulate(int n, int ticks, float simDt){
            clients = n;
            List<EntityInterp> interps = new ArrayList<>();
            for(int i = 0; i < n; i++) interps.add(new EntityInterp());
            long t0 = System.nanoTime();
            for(int t = 0; t < ticks; t++){
                for(int c = 0; c < n; c++){
                    float now = t * simDt;
                    interps.get(c).push(now, c, 0, c, 0);
                    float[] out = new float[4];
                    interps.get(c).sample(now, out);
                }
            }
            avgTickMs = (System.nanoTime() - t0) / 1e6f / Math.max(1, ticks);
            this.ticks = ticks;
            return avgTickMs;
        }
    }

    private static int totalNameBytes(java.util.Map<String,?> cur){
        int n = 0;
        for(String k : cur.keySet()) n += k == null ? 0 : k.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return n;
    }
}
