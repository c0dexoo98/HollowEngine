package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AAA-06 — replay recorder / player (HollowEngine 4.0).
 *  Records named float channels (position xyz + yaw per entity, health, input axes...)
 *  as compressed keyframe tracks; samples them back with linear interpolation for
 *  ghost playback, kill-cams and spectate-after-the-fact. JSON save/load.
 *
 *  Keyframe compression: a new key is stored only when it deviates from the
 *  extrapolated line of the previous two keys by more than `epsilon` — smooth
 *  motion collapses to near-zero storage.
 */
public final class Replay {

    public static final int MAX_KEYS = 60_000;   // ~16 min at 60 Hz full-rate

    public static final class Track {
        public String name;
        public final List<Long> times = new ArrayList<>();     // tick stamps
        public final List<float[]> values = new ArrayList<>(); // up to 4 floats

        public void push(long t, float a, float b, float c, float d, float epsilon){
            if(!values.isEmpty()){
                int n = values.size();
                if(n >= 2){
                    // linear extrapolation from the last two keys
                    long t0 = times.get(n - 2), t1 = times.get(n - 1);
                    float[] v0 = values.get(n - 2), v1 = values.get(n - 1);
                    float dt = Math.max(1L, t1 - t0);
                    float k = (t - t1) / dt;
                    float err = 0f;
                    for(int i = 0; i < 4; i++)
                        err = Math.max(err, Math.abs(v1[i] + (v1[i] - v0[i]) * k - pick(i, a, b, c, d)));
                    if(err <= epsilon && t - t1 < 600) return;   // still on the line — skip key
                } else if(n == 1){
                    float[] v1 = values.get(0);
                    float err = 0f;
                    for(int i = 0; i < 4; i++) err = Math.max(err, Math.abs(v1[i] - pick(i, a, b, c, d)));
                    if(err <= epsilon) return;                   // stationary — skip key
                }
            }
            if(times.size() >= MAX_KEYS) return;                 // hard cap
            times.add(t);
            values.add(new float[]{ a, b, c, d });
        }
        private static float pick(int i, float a, float b, float c, float d){
            switch(i){ case 0: return a; case 1: return b; case 2: return c; default: return d; }
        }
        /** interpolated sample; false when the track has nothing at/around t.
         *  Beyond the last key: linear extrapolation (mirrors the compressor's own
         *  assumption), clamped to a 600-tick window. */
        public boolean sample(long t, float[] out){
            if(values.isEmpty()) return false;
            if(t <= times.get(0)){ System.arraycopy(values.get(0), 0, out, 0, 4); return true; }
            int last = times.size() - 1;
            if(t >= times.get(last)){
                if(last >= 1 && t - times.get(last) < 600){
                    long t0 = times.get(last - 1), t1 = times.get(last);
                    float[] v0 = values.get(last - 1), v1 = values.get(last);
                    float k = (float)(t - t1) / Math.max(1L, t1 - t0);
                    for(int i = 0; i < 4; i++) out[i] = v1[i] + (v1[i] - v0[i]) * k;
                    return true;
                }
                System.arraycopy(values.get(last), 0, out, 0, 4);
                return true;
            }
            int lo = 0, hi = last;
            while(hi - lo > 1){                                  // binary search
                int mid = (lo + hi) >>> 1;
                if(times.get(mid) <= t) lo = mid; else hi = mid;
            }
            long t0 = times.get(lo), t1 = times.get(hi);
            float[] v0 = values.get(lo), v1 = values.get(hi);
            float k = (float)(t - t0) / Math.max(1L, t1 - t0);
            for(int i = 0; i < 4; i++) out[i] = v0[i] + (v1[i] - v0[i]) * k;
            return true;
        }
    }

    public final Map<String, Track> tracks = new LinkedHashMap<>();
    public long firstTick = -1, lastTick = -1;
    public float epsilon = 0.01f;             // deviation threshold for key insertion
    public String sceneName = "";

    public void record(long tick, String channel, float a, float b, float c, float d){
        Track t = tracks.computeIfAbsent(channel, k -> { Track nt = new Track(); nt.name = k; return nt; });
        t.push(tick, a, b, c, d, epsilon);
        if(firstTick < 0 || tick < firstTick) firstTick = tick;
        if(tick > lastTick) lastTick = tick;
    }
    public boolean sample(long tick, String channel, float[] out){
        Track t = tracks.get(channel);
        return t != null && t.sample(tick, out);
    }
    public long durationTicks(){ return lastTick > firstTick ? lastTick - firstTick : 0; }
    public int channelCount(){ return tracks.size(); }
    public int keyCount(){
        int s = 0;
        for(Track t : tracks.values()) s += t.times.size();
        return s;
    }
    public void clear(){ tracks.clear(); firstTick = -1; lastTick = -1; }

    // ---------------- serialization ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("scene", sceneName).kv("eps", epsilon).kv("first", firstTick).kv("last", lastTick);
        w.arr("tracks");
        for(Track t : tracks.values()){
            w.obj().kv("name", t.name);
            StringBuilder ts = new StringBuilder(), vs = new StringBuilder();
            for(int i = 0; i < t.times.size(); i++){
                if(i > 0){ ts.append(','); vs.append(';'); }
                ts.append(t.times.get(i));
                float[] v = t.values.get(i);
                vs.append(Math.round(v[0] * 1000f) / 1000f).append(',')
                  .append(Math.round(v[1] * 1000f) / 1000f).append(',')
                  .append(Math.round(v[2] * 1000f) / 1000f).append(',')
                  .append(Math.round(v[3] * 1000f) / 1000f);
            }
            w.kv("t", ts.toString());
            w.kv("v", vs.toString());
            w.endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public static Replay load(String json){
        Replay r = new Replay();
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            r.sceneName = n.str("scene", "");
            r.epsilon = n.flt("eps", 0.01f);
            r.firstTick = (long)n.flt("first", -1);
            r.lastTick = (long)n.flt("last", -1);
            List<Object> ts = n.list("tracks");
            if(ts != null) for(Object o : ts){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Track t = new Track();
                t.name = Json.str(m.get("name"), "?");
                String[] times = Json.str(m.get("t"), "").split(",");
                String[] vals = Json.str(m.get("v"), "").split(";");
                for(int i = 0; i < times.length && i < vals.length; i++){
                    String[] f = vals[i].split(",");
                    try {
                        long tt = Long.parseLong(times[i].trim());
                        t.times.add(tt);
                        t.values.add(new float[]{
                            Float.parseFloat(f[0]), Float.parseFloat(f[1]),
                            Float.parseFloat(f[2]), Float.parseFloat(f[3])});
                    } catch(Exception ignored){ }
                }
                if(!t.times.isEmpty()) r.tracks.put(t.name, t);
            }
        } catch(Exception ignored){ }
        return r;
    }
}
