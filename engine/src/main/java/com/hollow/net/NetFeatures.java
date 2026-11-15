package com.hollow.net;

import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.MathX;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** v2.5 net batch — NET-013/015/016/017.
 *  Deterministic, fully loopback-testable (no sockets for logic tests).
 *
 *  NET-013 XorHandshake  — nonce-authenticated XOR stream cipher (seeded by SHA-256
 *                          of secret+nonces; per-packet rotation)
 *  NET-015 ObserverMode  — spectators receive state, never influence simulation
 *  NET-016 BlackboardSync— shared key/value store with revision counters (last-write-wins)
 *  NET-017 RemoteConsole — token-authenticated command channel for headless servers */
public final class NetFeatures {
    private NetFeatures() {}

    // =====================================================================
    // NET-013 — handshake encryption (XOR + nonce; stronger cipher later)
    // =====================================================================
    public static final class XorHandshake {
        public static final int STATE_HELLO = 0, STATE_CHALLENGE = 1, STATE_READY = 2, STATE_FAILED = -1;

        public int state = STATE_HELLO;
        public long clientNonce, serverNonce;
        private byte[] key = null;              // derived once from secret + nonces
        private int packetCounter = 0;
        public final String secret;
        public int handshakes = 0, rejected = 0;

        public XorHandshake(String secret){ this.secret = secret; }

        // ---- side A (client) ----
        public byte[] makeHello(){
            clientNonce = new Random().nextLong();
            return msg("HELLO", Map.of("nonce", Long.toHexString(clientNonce)));
        }
        // ---- side B (server) ----
        public byte[] receiveHello(byte[] m){
            Map<String,Object> d = parse(m, "HELLO");
            if(d == null){ state = STATE_FAILED; rejected++; return err("bad-hello"); }
            clientNonce = Long.parseUnsignedLong((String)d.get("nonce"), 16);
            serverNonce = new Random().nextLong();
            state = STATE_CHALLENGE;
            return msg("CHALLENGE", Map.of("nonce", Long.toHexString(serverNonce)));
        }
        // ---- side A derives key and confirms ----
        public byte[] receiveChallenge(byte[] m){
            Map<String,Object> d = parse(m, "CHALLENGE");
            if(d == null){ state = STATE_FAILED; return err("bad-challenge"); }
            serverNonce = Long.parseUnsignedLong((String)d.get("nonce"), 16);
            deriveKey();
            state = STATE_READY;
            handshakes++;
            return msg("READY", Map.of("proto", NetSystems.PROTOCOL_VERSION));
        }
        // ---- side B verifies READY and derives too ----
        public boolean receiveReady(byte[] m){
            Map<String,Object> d = parse(m, "READY");
            if(d == null){ state = STATE_FAILED; rejected++; return false; }
            deriveKey();
            state = STATE_READY;
            handshakes++;
            return true;
        }

        void deriveKey(){
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(secret.getBytes(StandardCharsets.UTF_8));
                md.update(longBytes(clientNonce));
                md.update(longBytes(serverNonce));
                key = md.digest();
            } catch(Exception e){ key = new byte[32]; }
        }

        /** encrypt: prepends the 8-byte per-packet counter (little-endian) documented above,
         *  then XORs with the key rotated by that counter.
         *  N12: the old code ignored the documented in-packet counter and used a local
         *  call-count offset — any asymmetric traffic (retransmit, dropped exchange)
         *  desynced both directions permanently with no recovery path. */
        public byte[] crypt(byte[] payload){
            if(key == null) throw new IllegalStateException("handshake not ready");
            long ctr = (packetCounter++ & 0xFFFFFFFFFFFFL);
            byte[] out = new byte[payload.length + 8];
            for(int i = 0; i < 8; i++) out[i] = (byte)(ctr >> (8 * i));
            int kOff = (int)(ctr * 7 % key.length);
            for(int i = 0; i < payload.length; i++)
                out[8 + i] = (byte)(payload[i] ^ key[(kOff + i) % key.length]);
            return out;
        }
        /** decrypt a crypt()'d packet: strip the leading counter and undo the XOR it selects. */
        public byte[] decrypt(byte[] packet){
            if(key == null) throw new IllegalStateException("handshake not ready");
            if(packet.length <= 8) return new byte[0];
            long ctr = 0;
            for(int i = 0; i < 8; i++) ctr |= (packet[i] & 0xFFL) << (8 * i);
            int kOff = (int)(ctr * 7 % key.length);
            int n = packet.length - 8;
            byte[] out = new byte[n];
            for(int i = 0; i < n; i++)
                out[i] = (byte)(packet[8 + i] ^ key[(kOff + i) % key.length]);
            return out;
        }

        byte[] msg(String kind, Map<String,Object> data){
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("t", kind); m.putAll(data);
            return Json.write(m).getBytes(StandardCharsets.UTF_8);
        }
        byte[] err(String why){ return msg("ERR", Map.of("why", why)); }
        static Map<String,Object> parse(byte[] m, String expect){
            try {
                Map<String,Object> d = Json.parseObj(new String(m, StandardCharsets.UTF_8));
                if(!expect.equals(d.get("t"))) return null;
                return d;
            } catch(Exception e){ return null; }
        }
        static byte[] longBytes(long v){
            return ByteBuffer.allocate(8).putLong(v).array();
        }
        public String status(){ return "handshake[state=" + state + " ready=" + (key != null) + " pkts=" + packetCounter + "]"; }
        public void reset(){ state = STATE_HELLO; key = null; packetCounter = 0; }
    }

    // =====================================================================
    // NET-015 — observer / spectator mode
    // =====================================================================
    /** Server tracks peers flagged as observers: they receive every snapshot but
     *  their inputs are dropped and they don't count toward player slots. Supports
     *  follow-target switching and an optional buffer delay ("broadcast delay"). */
    public static final class ObserverMode {
        public static final class Peer {
            public final int peerId; public final String name;
            public boolean observer; public float delay = 0f;   // seconds behind live
            public int followTarget = -1;
            public long snapshotsSent = 0, inputsDropped = 0;
            Peer(int id, String n){ peerId = id; name = n; }
        }
        public final Map<Integer, Peer> peers = new LinkedHashMap<>();
        public int maxPlayers = 8;          // observers don't consume slots
        public int playerCount = 0;
        public int observersJoined = 0;

        public Peer join(int peerId, String name, boolean asObserver){
            Peer p = new Peer(peerId, name);
            p.observer = asObserver;
            if(!asObserver){
                if(playerCount >= maxPlayers){ Log.w("Observer", "server full, demoted to observer"); p.observer = true; }
                else playerCount++;
            }
            if(asObserver) observersJoined++;
            peers.put(peerId, p);
            return p;
        }
        public void leave(int peerId){
            Peer p = peers.remove(peerId);
            if(p != null && !p.observer) playerCount = Math.max(0, playerCount - 1);
        }

        /** server-side input gate: observers' inputs never reach the sim. */
        public boolean acceptInput(int peerId){
            Peer p = peers.get(peerId);
            if(p == null) return false;
            if(p.observer){ p.inputsDropped++; return false; }
            return true;
        }

        /** should this peer receive the snapshot? (observers get all, with delay) */
        public boolean sendSnapshot(int peerId){
            Peer p = peers.get(peerId);
            if(p == null) return false;
            p.snapshotsSent++;
            return true;
        }

        /** cycle the observer's camera to the next player (client-side request). */
        public int nextFollowTarget(int peerId, List<Integer> livePlayers){
            Peer p = peers.get(peerId);
            if(p == null || livePlayers.isEmpty()) return -1;
            int idx = livePlayers.indexOf(p.followTarget);
            p.followTarget = livePlayers.get((idx + 1) % livePlayers.size());
            return p.followTarget;
        }

        public String status(){
            return "observer[peers=" + peers.size() + " players=" + playerCount + "/" + maxPlayers
                + " observers=" + observersJoined + "]";
        }
    }

    // =====================================================================
    // NET-016 — blackboard variable sync between clients
    // =====================================================================
    /** Replicates the shared world blackboard: every key carries a revision counter;
     *  a set bumps the revision, deltas carry (key, value, rev) and applying a delta
     *  with an older-or-equal revision is a no-op — last-write-wins, deterministic
     *  across clients that apply all deltas in order. */
    public static final class BlackboardSync {
        public static final class Entry {
            public Object value; public long rev = 0;
            Entry(Object v, long r){ value = v; rev = r; }
        }
        public final Map<String, Entry> table = new LinkedHashMap<>();
        public long localRev = 0;
        public int appliedDeltas = 0, staleDeltas = 0;

        /** authoritative local set — returns the delta packet (JSON bytes). */
        public byte[] setLocal(String key, Object value){
            Entry e = table.get(key);
            if(e != null) e.rev = ++localRev; else table.put(key, e = new Entry(value, ++localRev));
            e.value = value;
            Map<String,Object> d = new LinkedHashMap<>();
            d.put("t", "bb"); d.put("k", key);
            d.put("v", value instanceof Number || value instanceof String ? value : String.valueOf(value));
            d.put("rev", e.rev);
            return Json.write(d).getBytes(StandardCharsets.UTF_8);
        }

        /** apply a remote delta packet; returns true when accepted. */
        public boolean applyRemote(byte[] pkt){
            try {
                Map<String,Object> d = Json.parseObj(new String(pkt, StandardCharsets.UTF_8));
                if(!"bb".equals(d.get("t"))) return false;
                String k = (String)d.get("k");
                long rev = ((Number)d.get("rev")).longValue();
                Object v = d.get("v");
                Entry e = table.get(k);
                if(e != null && rev <= e.rev){ staleDeltas++; return false; }
                if(e != null) e.value = v; else table.put(k, e = new Entry(v, rev));
                e.rev = rev;
                localRev = Math.max(localRev, rev);
                appliedDeltas++;
                return true;
            } catch(Exception ex){ return false; }
        }

        public Object get(String key, Object def){
            Entry e = table.get(key);
            return e == null ? def : e.value;
        }
        public double num(String key, double def){
            Object v = get(key, null);
            return v instanceof Number n ? n.doubleValue() : def;
        }
        public int size(){ return table.size(); }
        public String status(){ return "bbsync[keys=" + size() + " rev=" + localRev + " applied=" + appliedDeltas + " stale=" + staleDeltas + "]"; }
    }

    // =====================================================================
    // NET-017 — remote console (headless server)
    // =====================================================================
    /** Token-authenticated command channel. The server registers an executor
     *  (usually the interactive Shell's dispatch); clients stream command strings
     *  and receive output lines. Rate-limited + audit-logged. */
    public static final class RemoteConsole {
        public interface Executor { List<String> execute(String commandLine); }
        public Executor executor = cmd -> List.of("unknown command: " + cmd);
        public String token = "";
        public final List<String> auditLog = new ArrayList<>();
        public int maxAudit = 128;
        public float minInterval = 0.05f;       // rate limit
        public boolean authed = false;
        public int executed = 0, rejected = 0;
        private float lastCmdTime = -999f;
        private float clock = 0f;

        public RemoteConsole(String token){ this.token = token; }

        /** client sends {"t":"auth","token":...} JSON — returns true on success. */
        public boolean auth(String supplied){
            authed = token.equals(supplied) && !token.isEmpty();
            audit(authed ? "auth ok" : "auth REJECTED");
            if(!authed) rejected++;
            return authed;
        }

        public List<String> command(String line, float time){
            clock = time;
            if(!authed){ rejected++; audit("cmd before auth: " + line); return List.of("error: not authenticated"); }
            if(time - lastCmdTime < minInterval){ rejected++; return List.of("error: rate limited"); }
            lastCmdTime = time;
            executed++;
            audit("> " + line);
            List<String> out;
            try { out = executor.execute(line); }
            catch(Exception e){ out = List.of("error: " + e.getMessage()); }
            for(String l : out) audit("< " + l);
            return out;
        }

        void audit(String line){
            auditLog.add(line);
            while(auditLog.size() > maxAudit) auditLog.remove(0);
        }
        public String tail(int n){
            int from = Math.max(0, auditLog.size() - n);
            return String.join("\n", auditLog.subList(from, auditLog.size()));
        }
    }
}
