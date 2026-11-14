package com.hollow.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.6.0 NET-ROLLBACK: deterministic rollback session over a Transport.
 *
 * Model (GGPO-lite, engine-sized, no threads):
 *  - fixed tick (default 60Hz). Both peers advance local sim with PREDICTED
 *    inputs for remote players (last-received input held).
 *  - when an input datagram for tick T < lastConfirmed arrives (a correction),
 *    the session rewinds to T, patches the input, and resimulates to now.
 *  - a game plugs in via Callbacks: saveState/restoreState are byte-level
 *    snapshots of ONLY netcode-relevant state (positions of replicated bodies).
 *
 * Determinism contract: the game step must be a pure function of
 * (state, inputs[tick..tick]) — no wall-clock, no RNG without a seeded stream,
 * no float-bit hazards across peers beyond what Java already guarantees.
 */
public final class RollbackSim {

    public static final float DEFAULT_TICK_HZ = 60f;

    /** game hooks — the integrator implements these */
    public interface Callbacks {
        /** serialize all rollback-relevant state (small!) */
        byte[] saveState();
        /** restore a state previously produced by saveState */
        void restoreState(byte[] state);
        /** advance the game exactly one fixed tick with the given per-player inputs */
        void stepTick(int tick, Map<Integer, InputBuffer.Frame> inputsByPlayer);
    }

    public final Transport transport;
    public final Callbacks game;
    public final int localPlayerId;
    public final List<Integer> remotePlayers = new ArrayList<>();

    public float tickHz = DEFAULT_TICK_HZ;
    public int maxRollbackFrames = 7;            // ~120ms — beyond this we stall instead
    public int currentTick = 0;                  // next tick to simulate
    public int lastConfirmedTick = -1;           // newest tick all players' inputs are known for
    public int mispredictions = 0, rollbacks = 0, resimulatedFrames = 0, stallFrames = 0;

    /** per-player input history; local = truth, remote = last known + prediction */
    public final Map<Integer, InputBuffer> inputs = new HashMap<>();

    /** ring of state snapshots: snapshot[i] = state BEFORE tick i (null = dirty) */
    private final byte[][] snapshots = new byte[InputBuffer.CAPACITY][];
    private final boolean[] snapshotValid = new boolean[InputBuffer.CAPACITY];

    /** local input source for the CURRENT tick — game sets this before pump() */
    public InputBuffer.Frame pendingLocalInput = null;

    public RollbackSim(Transport transport, int localPlayerId, Callbacks game){
        this.transport = transport;
        this.localPlayerId = localPlayerId;
        this.game = game;
        inputs.put(localPlayerId, new InputBuffer());
    }

    public void addRemotePlayer(int peerId){
        if(!inputs.containsKey(peerId)){
            remotePlayers.add(peerId);
            inputs.put(peerId, new InputBuffer());
        }
    }

    // ------------------------------------------------------------------
    // per-frame pump: call once per rendered frame with the real elapsed time
    // ------------------------------------------------------------------
    public void pump(float dt){
        transport.update(dt);
        // 1) drain remote input datagrams (redundant-encoded).
        // Merge-by-fill: copy EVERY frame the payload carries — the 3-tick
        // redundancy exists precisely to fill holes left by lost datagrams.
        for(Transport.Packet p : transport.poll(localPlayerId)){
            InputBuffer inbound = InputBuffer.decodeRedundant(p.data);
            InputBuffer mine = inputs.get(p.from);
            if(mine != null){
                for(int t = Math.max(0, inbound.newestTick() - InputBuffer.INPUT_REDUNDANCY);
                    t <= inbound.newestTick(); t++){
                    InputBuffer.Frame f = inbound.get(t);
                    if(f != null) mine.set(t, f.buttons, f.ax, f.ay);
                }
            }
        }
        // 2) simulate as many ticks as the budget allows
        float step = 1f / tickHz;
        while(dt >= step * 0.999f){
            dt -= step;
            tickOnce();
        }
    }

    /** one fixed tick: confirm what we can, predict the rest, rollback on corrections */
    private void tickOnce(){
        int t = currentTick;

        // record local input
        if(pendingLocalInput != null){
            inputs.get(localPlayerId).set(t, pendingLocalInput.buttons, pendingLocalInput.ax, pendingLocalInput.ay);
            pendingLocalInput = null;
        }

        // confirm prefix: newest tick where every player's input is known
        int confirmed = lastConfirmedTick;
        while(confirmed < t){
            boolean all = true;
            for(int p : remotePlayers){
                if(inputs.get(p).get(confirmed + 1) == null){ all = false; break; }
            }
            if(!all) break;
            confirmed++;
        }

        // rollback check: did a confirmed input change what we already simulated?
        if(confirmed < t){
            int rollbackTo = confirmed + 1;   // first tick needing resim
            if(rollbackTo < t - (maxRollbackFrames + InputBuffer.INPUT_REDUNDANCY + 2)){
                // too far back — stall (freeze local tick) rather than desync.
                // Grace = rollback window + redundancy tail + delivery slack:
                // only long outages (player disconnected) reach this.
                stallFrames++;
                return;
            }
            if(rollbackTo < t && snapshotValid[rollbackTo % InputBuffer.CAPACITY]){
                restoreAndResim(rollbackTo, t);
            }
        }
        lastConfirmedTick = Math.max(lastConfirmedTick, confirmed);

        // predict remote inputs for this tick (hold last known), snapshot, step
        Map<Integer, InputBuffer.Frame> frameInputs = new HashMap<>();
        for(Map.Entry<Integer, InputBuffer> e : inputs.entrySet()){
            InputBuffer.Frame f = e.getValue().get(t);
            if(f == null){
                InputBuffer.Frame last = e.getValue().latest();
                f = last != null ? last : new InputBuffer.Frame(t, 0L, 0f, 0f);
                if(e.getKey() != localPlayerId && !f.isDefault()) mispredictions++;   // predicted (non-default) remote input
            }
            frameInputs.put(e.getKey(), f);
        }

        saveSnapshot(t);
        game.stepTick(t, frameInputs);
        currentTick = t + 1;
    }

    /** rewind to `fromTick`, replay with corrected inputs up to (exclusive) `toTick` */
    private void restoreAndResim(int fromTick, int toTick){
        byte[] snap = snapshots[fromTick % InputBuffer.CAPACITY];
        game.restoreState(snap);
        rollbacks++;
        for(int t = fromTick; t < toTick; t++){
            Map<Integer, InputBuffer.Frame> frameInputs = new HashMap<>();
            for(Map.Entry<Integer, InputBuffer> e : inputs.entrySet()){
                InputBuffer.Frame f = e.getValue().get(t);
                if(f == null){
                    InputBuffer.Frame last = e.getValue().latest();
                    f = last != null ? last : new InputBuffer.Frame(t, 0L, 0f, 0f);
                }
                frameInputs.put(e.getKey(), f);
            }
            // keep snapshots during resim so a second correction inside the window works
            game.stepTick(t, frameInputs);
            resimulatedFrames++;
        }
        currentTick = toTick;
    }

    private void saveSnapshot(int tick){
        int slot = tick % InputBuffer.CAPACITY;
        snapshots[slot] = game.saveState();
        snapshotValid[slot] = true;
    }

    /** broadcast local input for `tick` with redundancy (call after recording it) */
    public void broadcastInput(int tick){
        byte[] payload = inputs.get(localPlayerId).encodeRedundant(tick);
        for(int p : remotePlayers) transport.send(p, payload);
    }

    /** call once per tick BEFORE pump's step: sets and broadcasts the local input */
    public void setLocalInput(long buttons, float ax, float ay, int tick){
        inputs.get(localPlayerId).set(tick, buttons, ax, ay);
        pendingLocalInput = null;   // already recorded — stepTick must not duplicate
        broadcastInput(tick);
    }
}
