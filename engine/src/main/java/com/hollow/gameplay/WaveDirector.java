package com.hollow.gameplay;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AAA-05 — wave director (encounter/arena spawner, HollowEngine 4.0).
 *  Data-driven waves (enemy type → count + pacing), states INTERMISSION → SPAWNING →
 *  FIGHTING → CLEARED, difficulty escalation across waves, spawn-budget pacing and
 *  deterministic RNG for jitter. The game layer supplies the spawn callback and
 *  reports deaths; the director owns timing, order and progression.
 */
public final class WaveDirector {

    public enum State { IDLE, INTERMISSION, SPAWNING, FIGHTING, DONE }

    public static final class WaveDef {
        public final Map<String, Integer> enemies = new LinkedHashMap<>();   // type -> count
        public float spawnInterval = 0.6f;    // seconds between spawn requests
        public float restSec = 5f;            // intermission before this wave
        public WaveDef enemy(String type, int count){ enemies.put(type, count); return this; }
        public WaveDef pace(float interval){ spawnInterval = interval; return this; }
        public WaveDef rest(float sec){ restSec = sec; return this; }
        public int total(){ int s = 0; for(int c : enemies.values()) s += c; return s; }
    }

    public interface Spawner { /** spawn one enemy; return false to refuse (budget stays for retry) */
        boolean spawn(String type, int waveIndex); }
    public interface Listener { void onWaveEvent(String type, int waveIndex, String detail); }
    /** death notifications from the game layer ("enemy died") */
    public interface DeathSink { void report(); }

    public final List<WaveDef> waves = new ArrayList<>();
    public Spawner spawner;
    public Listener listener;
    /** difficulty multiplier applied to counts when a wave list loops (endless mode) */
    public float loopScale = 1.15f;
    public boolean endless = false;

    public State state = State.IDLE;
    public int waveIndex = -1;                // 0-based
    public int spawnedThisWave = 0, killedThisWave = 0, totalSpawned = 0, totalKilled = 0;
    public long seed = 0x0CEF0001L;

    private float timer = 0f;
    private float spawnAccum = 0f;
    private List<String> queue = new ArrayList<>();
    private int loops = 0;

    public WaveDirector(){ }
    public WaveDirector(WaveDef... defs){
        for(WaveDef d : defs) waves.add(d);
    }

    public void start(){
        if(waves.isEmpty() || spawner == null) return;
        waveIndex = 0;                            // first wave pending, intermission runs first
        state = State.INTERMISSION;
        timer = 0f;
    }
    public void stop(){ state = State.IDLE; queue.clear(); }

    /** game layer calls this whenever one of OUR spawned enemies dies */
    public void notifyDeath(){
        killedThisWave++;
        totalKilled++;
    }

    public void update(float dt){
        if(dt <= 0f || state == State.IDLE || state == State.DONE) return;
        switch(state){
            case INTERMISSION: {
                WaveDef w = current();
                timer += dt;
                if(w == null || timer >= w.restSec){
                    beginWave();
                }
                break;
            }
            case SPAWNING: {
                WaveDef w = current();
                if(w == null){ state = State.DONE; break; }
                spawnAccum += dt;
                float interval = Math.max(0.05f, w.spawnInterval);
                while(spawnAccum >= interval && !queue.isEmpty()){
                    spawnAccum -= interval;
                    String type = queue.remove(0);
                    if(spawner.spawn(type, waveIndex)){
                        spawnedThisWave++;
                        totalSpawned++;
                        if(listener != null) listener.onWaveEvent("spawn", waveIndex, type);
                    } else {
                        queue.add(type);                 // refused — retry on a later tick
                        break;
                    }
                }
                if(queue.isEmpty()) state = State.FIGHTING;
                break;
            }
            case FIGHTING: {
                if(killedThisWave >= spawnedThisWave && spawnedThisWave > 0){
                    if(listener != null) listener.onWaveEvent("wave_cleared", waveIndex, null);
                    advance();
                }
                break;
            }
            default: break;
        }
    }

    private WaveDef current(){
        if(waveIndex < 0 || waveIndex >= waves.size()) return null;
        return waves.get(waveIndex);
    }

    private void beginWave(){
        WaveDef w = current();
        if(w == null){ state = State.DONE; return; }
        spawnedThisWave = 0;
        killedThisWave = 0;
        spawnAccum = 0f;
        queue = buildQueue(w, waveIndex);
        state = State.SPAWNING;
        if(listener != null) listener.onWaveEvent("wave_start", waveIndex, "count=" + queue.size());
    }

    /** expand counts → shuffled spawn queue (deterministic RNG) */
    private List<String> buildQueue(WaveDef w, int idx){
        List<String> out = new ArrayList<>();
        for(Map.Entry<String, Integer> kv : w.enemies.entrySet())
            for(int i = 0; i < kv.getValue(); i++) out.add(kv.getKey());
        MathX.Rng rng = new MathX.Rng(seed ^ idx * 0x9E3779B97F4A7C15L);
        for(int i = out.size() - 1; i > 0; i--){
            int j = rng.nextInt(i + 1);
            String t = out.get(i); out.set(i, out.get(j)); out.set(j, t);
        }
        return out;
    }

    private void advance(){
        waveIndex++;
        if(waveIndex >= waves.size()){
            if(endless){
                loops++;
                // scale every wave list up for the next loop
                for(WaveDef w : waves)
                    for(Map.Entry<String, Integer> kv : w.enemies.entrySet())
                        kv.setValue(Math.round(kv.getValue() * loopScale));
                waveIndex = 0;
                state = State.INTERMISSION;
                timer = 0f;
                if(listener != null) listener.onWaveEvent("loop", loops, "scale=" + Math.round(loopScale * 100f) / 100f);
                return;
            }
            state = State.DONE;
            if(listener != null) listener.onWaveEvent("all_waves_done", Math.max(0, waves.size() - 1), null);
            return;
        }
        state = State.INTERMISSION;
        timer = 0f;
    }

    public float intermissionLeft(){
        WaveDef w = current();
        if(state != State.INTERMISSION || w == null) return 0f;
        return Math.max(0f, w.restSec - timer);
    }
    public int pendingSpawnCount(){ return queue.size(); }
    public int loopCount(){ return loops; }

    // ---------------- serialization (arena definitions live in JSON) ----------------
    public static WaveDirector fromJson(String json){
        WaveDirector d = new WaveDirector();
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            List<Object> ws = n.list("waves");
            if(ws != null) for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                WaveDef w = new WaveDef().pace((float)Json.num(m.get("interval"), 0.6f)).rest((float)Json.num(m.get("rest"), 5f));
                Map<String,Object> en = Json.obj(m.get("enemies"));
                if(en != null) for(Map.Entry<String,Object> kv : en.entrySet())
                    w.enemy(kv.getKey(), (int)Json.num(kv.getValue(), 1));
                d.waves.add(w);
            }
            d.endless = n.bool("endless", false);
            d.loopScale = n.flt("loopScale", 1.15f);
        } catch(Exception ignored){ }
        return d;
    }

    public String toJson(){
        JsonW w = new JsonW();
        w.obj().kv("endless", endless).kv("loopScale", loopScale);
        w.arr("waves");
        for(WaveDef def : waves){
            w.obj().kv("interval", def.spawnInterval).kv("rest", def.restSec);
            w.obj("enemies");
            for(Map.Entry<String, Integer> kv : def.enemies.entrySet()) w.kv(kv.getKey(), kv.getValue());
            w.endObj();
            w.endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }
}
