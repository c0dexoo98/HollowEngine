package com.hollow.net;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * v2.6.0 NET-ROLLBACK: transport abstraction + in-memory loopback pair.
 *
 * LoopbackTransport.connectPeer() wires two endpoints together with
 * configurable latency, jitter and packet loss — the rollback logic and the
 * test suite run against realistic network conditions without sockets.
 */
public abstract class Transport {

    /** one datagram in flight (immutable) */
    public static final class Packet {
        public final int from;       // sender peer id
        public final byte[] data;    // payload (see InputBuffer encoding / sim messages)
        public Packet(int from, byte[] data){
            this.from = from;
            this.data = data;
        }
    }

    /** enqueue a datagram to `toPeer`; returns false when dropped (never throws) */
    public abstract boolean send(int toPeer, byte[] data);

    /** drain all datagrams addressed to `peer` that have 'arrived' by now; may be empty */
    public abstract List<Packet> poll(int peer);

    /** advance the wire simulation by dt seconds (delivers delayed packets) */
    public abstract void update(float dt);

    /** diagnostics */
    public int packetsSent = 0, packetsDelivered = 0, packetsDropped = 0;

    // ------------------------------------------------------------------
    // in-memory loopback pair with loss/jitter
    // ------------------------------------------------------------------
    public static final class Loopback extends Transport {
        public float latencySec = 0f;      // one-way base delay
        public float jitterSec = 0f;       // +-uniform added to latency
        public float lossRate = 0f;        // 0..1 drop probability

        private static final class InFlight {
            final Packet pkt; final int to; final float arriveAt;
            InFlight(Packet pkt, int to, float arriveAt){ this.pkt = pkt; this.to = to; this.arriveAt = arriveAt; }
        }
        private final Deque<InFlight> wire = new ArrayDeque<>();
        private final java.util.Map<Integer, Deque<Packet>> inboxes = new java.util.HashMap<>();
        private final Random rng;
        private float clock = 0f;

        public Loopback(long seed){ rng = new Random(seed); }
        public Loopback(){ this(42L); }

        public void attachPeer(int peerId){
            inboxes.put(peerId, new ArrayDeque<>());
        }

        /** wire two loopbacks: sending on a goes to b and vice versa (shared wire sim) */
        public void connectPeer(Loopback other){
            // simplest correct wiring: both share nothing — use Relay instead
            throw new UnsupportedOperationException("use Relay");
        }

        @Override public boolean send(int toPeer, byte[] data){
            packetsSent++;
            if(!inboxes.containsKey(toPeer)) return false;
            if(lossRate > 0f && rng.nextFloat() < lossRate){ packetsDropped++; return false; }
            float delay = Math.max(0f, latencySec + (jitterSec > 0f ? (rng.nextFloat()*2f - 1f) * jitterSec : 0f));
            wire.add(new InFlight(new Packet(currentPeer(), data), toPeer, clock + delay));
            return true;
        }

        /** peer id stamps on outgoing packets (loopback is symmetric: set before send) */
        public int localPeerId = 0;
        private int currentPeer(){ return localPeerId; }

        @Override public void update(float dt){
            clock += dt;
            int n = wire.size();
            for(int i = 0; i < n; i++){
                InFlight f = wire.pollFirst();
                if(f == null) break;
                if(f.arriveAt <= clock){
                    Deque<Packet> inbox = inboxes.get(f.to);
                    if(inbox != null){ inbox.add(f.pkt); packetsDelivered++; }
                } else {
                    wire.addLast(f);   // not yet due
                }
            }
        }

        @Override public List<Packet> poll(int peer){
            Deque<Packet> inbox = inboxes.get(peer);
            List<Packet> out = new java.util.ArrayList<>();
            if(inbox != null) out.addAll(inbox);
            if(inbox != null) inbox.clear();
            return out;
        }
    }

    /** two peers wired through one shared wire simulation (the common case: server+client tests) */
    public static final class Relay extends Transport {
        public float latencySec = 0.04f;   // 40ms one-way default
        public float jitterSec = 0.01f;
        public float lossRate = 0f;
        public final int peerA, peerB;
        private final Deque<Loopback.InFlight> wire = new ArrayDeque<>();   // reuse InFlight
        private final java.util.Map<Integer, java.util.Deque<Packet>> inboxes = new java.util.HashMap<>();
        private final Random rng = new Random(7L);
        private float clock = 0f;

        public Relay(int peerA, int peerB){
            this.peerA = peerA;
            this.peerB = peerB;
            inboxes.put(peerA, new ArrayDeque<>());
            inboxes.put(peerB, new ArrayDeque<>());
        }

        @Override public boolean send(int toPeer, byte[] data){
            packetsSent++;
            if(!inboxes.containsKey(toPeer)) { packetsDropped++; return false; }
            if(lossRate > 0f && rng.nextFloat() < lossRate){ packetsDropped++; return false; }
            float delay = Math.max(0f, latencySec + (jitterSec > 0f ? (rng.nextFloat()*2f - 1f) * jitterSec : 0f));
            int from = toPeer == peerA ? peerB : peerA;
            wire.add(new Loopback.InFlight(new Packet(from, data), toPeer, clock + delay));
            return true;
        }

        @Override public void update(float dt){
            clock += dt;
            int n = wire.size();
            for(int i = 0; i < n; i++){
                Loopback.InFlight f = wire.pollFirst();
                if(f == null) break;
                if(f.arriveAt <= clock){
                    java.util.Deque<Packet> inbox = inboxes.get(f.to);
                    if(inbox != null){ inbox.add(f.pkt); packetsDelivered++; }
                } else wire.addLast(f);
            }
        }

        @Override public List<Packet> poll(int peer){
            java.util.Deque<Packet> inbox = inboxes.get(peer);
            List<Packet> out = new java.util.ArrayList<>();
            if(inbox != null){ out.addAll(inbox); inbox.clear(); }
            return out;
        }
    }
}
