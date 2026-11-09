package com.hollow.net;

import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Session layer (HollowEngine 2.0 — Phase 2 networking).
 *  Authoritative server + clients over TcpChannel:
 *  - messages: JOIN(1) / WELCOME(2) / INPUT(3) / SNAPSHOT(4) / EVENT(5) / LEAVE(6)
 *  - server: accepts clients, assigns ids, ticks, broadcasts transform snapshots at a rate
 *  - client: connects, sends local input state, receives snapshots and interpolates
 *    remote entities between the last two snapshots (100ms buffer)
 *  Headless-testable fully in-process via loopback (see CLI `net test`).
 */
public final class Session {
    // message type ids
    public static final int JOIN = 1, WELCOME = 2, INPUT = 3, SNAPSHOT = 4, EVENT = 5, LEAVE = 6;

    public static final float SNAPSHOT_RATE = 15f;     // snapshots/sec
    public static final float INTERP_DELAY = 0.1f;     // client interpolation buffer

    // ---- wire helpers: type tag rides INSIDE the payload (TcpChannel frames raw bytes) ----
    public static NetMessage pack(int type, byte[] data){
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(4 + (data != null ? data.length : 0));
        bb.putInt(type);
        if(data != null) bb.put(data);
        return new NetMessage(type, bb.array());
    }
    public static int unpackType(NetMessage m){
        if(m == null || m.payload.length < 4) return 0;
        return ((m.payload[0] & 255) << 24) | ((m.payload[1] & 255) << 16)
             | ((m.payload[2] & 255) << 8) | (m.payload[3] & 255);
    }
    public static byte[] unpackData(NetMessage m){
        if(m == null || m.payload.length <= 4) return new byte[0];
        return java.util.Arrays.copyOfRange(m.payload, 4, m.payload.length);
    }

    // ==================================================================
    // Server
    // ==================================================================
    public static final class Server {
        private TcpChannel listener;
        public final List<Remote> clients = new ArrayList<>();
        public int nextId = 1;
        public int port = 27015;
        public boolean running = false;
        public long snapshotsSent = 0;
        private float snapAccum = 0f;
        /** tracked replicated entities: id -> (name, pos, rotY) */
        public final Map<String, ReplicatedState> world = new LinkedHashMap<>();

        public boolean start(int port){
            this.port = port;
            // validate port availability up front (lazy accept binds inside accept())
            try (java.net.ServerSocket test = new java.net.ServerSocket(port)){
                // bound and closed immediately — port was free
            } catch(java.io.IOException e){
                Log.w("Net.Server", "cannot listen on " + port + ": " + e.getMessage());
                return false;
            }
            listener = new TcpChannel();
            running = true;
            Log.i("Net.Server", "listening on " + port);
            return true;
        }

        /** per-tick update: accept, poll, broadcast snapshots */
        public void update(float dt){
            if(!running) return;
            // accept new
            TcpChannel ch;
            while((ch = listener.accept(port)) != null){
                Remote r = new Remote();
                r.id = nextId++;
                r.channel = ch;
                clients.add(r);
                Log.i("Net.Server", "client connected id=" + r.id);
                // welcome: {you:id}
                Map<String,Object> w = new LinkedHashMap<>();
                w.put("you", r.id);
                r.channel.send(pack(WELCOME, Json.write(w).getBytes()));
            }
            // poll messages
            for(Remote r : clients){
                NetMessage raw;
                while((raw = r.channel.poll()) != null){
                    NetMessage m = new NetMessage(unpackType(raw), unpackData(raw));
                    r.lastSeen = 0f;
                    switch(m.type){
                        case JOIN: {
                            String name = new String(m.payload);
                            r.name = name;
                            Log.i("Net.Server", "join: id=" + r.id + " name=" + name);
                            break;
                        }
                        case INPUT: {
                            // {fx,fz,ry} — server stores last input (games apply their own logic)
                            try {
                                Map<String,Object> in = Json.parseObj(new String(m.payload));
                                r.inputFx = (float)Json.num(in.get("fx"), 0);
                                r.inputFz = (float)Json.num(in.get("fz"), 0);
                            } catch(Exception ignored){}
                            break;
                        }
                        case EVENT: {
                            // relay to everyone else (forward the packed frame)
                            for(Remote o : clients){
                                if(o != r) o.channel.send(raw);
                            }
                            break;
                        }
                        case LEAVE: {
                            r.dead = true;
                            break;
                        }
                        default: break;
                    }
                }
            }
            // disconnect dead
            for(int i = clients.size() - 1; i >= 0; i--){
                Remote r = clients.get(i);
                r.lastSeen += dt;
                if(r.dead || !r.channel.isConnected() || r.lastSeen > 15f){
                    Log.i("Net.Server", "client left id=" + r.id);
                    try { r.channel.close(); } catch(Exception ignored){}
                    clients.remove(i);
                }
            }
            // snapshot broadcast
            snapAccum += dt;
            if(snapAccum >= 1f / SNAPSHOT_RATE){
                snapAccum = 0f;
                broadcastSnapshot();
            }
        }

        /** set replicated entity state (game calls this each tick for entities it wants synced) */
        public void replicate(String name, Vec3 pos, float rotY){
            ReplicatedState s = world.get(name);
            if(s == null){ s = new ReplicatedState(); world.put(name, s); }
            s.x = pos.x; s.y = pos.y; s.z = pos.z; s.rotY = rotY;
        }

        private void broadcastSnapshot(){
            if(clients.isEmpty()) return;
            StringBuilder sb = new StringBuilder("{\"t\":").append(++snapSeq).append(",\"e\":[");
            boolean first = true;
            for(Map.Entry<String, ReplicatedState> e : world.entrySet()){
                if(!first) sb.append(',');
                first = false;
                ReplicatedState s = e.getValue();
                sb.append("{\"n\":\"").append(e.getKey()).append('"')
                  .append(",\"x\":").append(s.x)
                  .append(",\"y\":").append(s.y)
                  .append(",\"z\":").append(s.z)
                  .append(",\"ry\":").append(s.rotY).append('}');
            }
            sb.append("]}");
            NetMessage snap = pack(SNAPSHOT, sb.toString().getBytes());
            for(Remote r : clients) r.channel.send(snap);
            snapshotsSent++;
        }
        private long snapSeq = 0;
    }

    public static final class ReplicatedState {
        public float x, y, z, rotY;
    }

    public static final class Remote {
        public int id;
        public String name = "player";
        public TcpChannel channel;
        public float lastSeen = 0f;
        public boolean dead = false;
        public float inputFx, inputFz;
    }

    // ==================================================================
    // Client
    // ==================================================================
    public static final class Client {
        private TcpChannel channel;
        public int myId = 0;
        public boolean connected = false;
        public long snapshotsReceived = 0;
        public float avgPing = 0f;

        // interpolation buffer of remote entity states
        public static final class Sample {
            public long seq;
            public double time;                 // client clock when received
            public final Map<String, float[]> states = new HashMap<>();  // name -> {x,y,z,ry}
        }
        private final Sample[] buf = new Sample[8];
        private int bufCount = 0;
        private double clock = 0;

        public boolean connect(String host, int port, String playerName){
            channel = new TcpChannel();
            if(!channel.connect(host, port)) return false;
            connected = true;
            channel.send(pack(JOIN, playerName.getBytes()));
            Log.i("Net.Client", "connected to " + host + ":" + port);
            return true;
        }

        public void disconnect(){
            if(channel != null && connected){
                channel.send(pack(LEAVE, new byte[0]));
            }
            connected = false;
            if(channel != null) channel.close();
        }

        /** send local input (fx/fz = movement axes, -1..1) */
        public void sendInput(float fx, float fz){
            if(!connected) return;
            Map<String,Object> in = new LinkedHashMap<>();
            in.put("fx", (double)fx);
            in.put("fz", (double)fz);
            channel.send(pack(INPUT, Json.write(in).getBytes()));
        }

        /** fire a game event to the server (relayed to other clients) */
        public void sendEvent(String name, Map<String,Object> data){
            if(!connected) return;
            Map<String,Object> env = new LinkedHashMap<>();
            env.put("event", name);
            if(data != null) env.put("data", data);
            channel.send(pack(EVENT, Json.write(env).getBytes()));
        }

        /** poll network; call every frame with dt */
        public void update(float dt){
            if(!connected) return;
            clock += dt;
            NetMessage raw;
            while((raw = channel.poll()) != null){
                NetMessage m = new NetMessage(unpackType(raw), unpackData(raw));
                switch(m.type){
                    case WELCOME: {
                        try {
                            Map<String,Object> w = Json.parseObj(new String(m.payload));
                            myId = (int)Json.num(w.get("you"), 0);
                            Log.i("Net.Client", "welcome: myId=" + myId);
                        } catch(Exception ignored){}
                        break;
                    }
                    case SNAPSHOT: {
                        try {
                            Map<String,Object> root = Json.parseObj(new String(m.payload));
                            Sample s = new Sample();
                            s.seq = (long)Json.num(root.get("t"), 0);
                            s.time = clock;
                            for(Object o : Json.arr(root.get("e"))){
                                if(!(o instanceof Map)) continue;
                                @SuppressWarnings("unchecked")
                                Map<String,Object> e = (Map<String,Object>)o;
                                String n = Json.str(e.get("n"), "");
                                s.states.put(n, new float[]{
                                    (float)Json.num(e.get("x"), 0),
                                    (float)Json.num(e.get("y"), 0),
                                    (float)Json.num(e.get("z"), 0),
                                    (float)Json.num(e.get("ry"), 0)});
                            }
                            push(s);
                            snapshotsReceived++;
                        } catch(Exception ignored){}
                        break;
                    }
                    case EVENT: {
                        try {
                            Map<String,Object> env = Json.parseObj(new String(m.payload));
                            lastEvent = Json.str(env.get("event"), "");
                            lastEventData = env.get("data");
                        } catch(Exception ignored){}
                        break;
                    }
                    default: break;
                }
            }
        }

        private void push(Sample s){
            if(bufCount < buf.length){
                buf[bufCount++] = s;
            } else {
                System.arraycopy(buf, 1, buf, 0, buf.length - 1);
                buf[buf.length - 1] = s;
            }
        }

        public String lastEvent = "";
        public Object lastEventData = null;

        /** interpolated state of a remote entity at (now - INTERP_DELAY); null if unknown */
        public float[] sampleEntity(String name){
            if(bufCount < 2) return bufCount == 1 ? buf[0].states.get(name) : null;
            double target = clock - INTERP_DELAY;
            Sample a = null, b = null;
            for(int i = bufCount - 1; i >= 1; i--){
                if(buf[i - 1].time <= target && buf[i].time >= target){
                    a = buf[i - 1]; b = buf[i];
                    break;
                }
            }
            if(a == null){
                // too old or too new — snap to the newest sample containing the entity
                Sample newest = buf[bufCount - 1];
                return newest != null ? newest.states.get(name) : null;
            }
            float[] sa = a.states.get(name), sb = b.states.get(name);
            if(sa == null || sb == null) return sb != null ? sb : sa;
            double span = b.time - a.time;
            float t = span > 1e-9 ? (float)((target - a.time) / span) : 1f;
            t = Math.max(0f, Math.min(1f, t));
            return new float[]{
                sa[0] + (sb[0] - sa[0]) * t,
                sa[1] + (sb[1] - sa[1]) * t,
                sa[2] + (sb[2] - sa[2]) * t,
                sa[3] + (sb[3] - sa[3]) * t
            };
        }

        public void clearBuffer(){ bufCount = 0; }
    }
}
