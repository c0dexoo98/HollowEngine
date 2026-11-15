package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v12.0 — pasture system (HollowEngine 12.0 'trades & crafts').
 *  Fenced herds per settlement: flocks grow a little each day up to the
 *  pasture's capacity, sheep grow wool that shearing collects, and a
 *  wolf visit can thin the flock (deterministic roll).
 */
public final class PastureSystem {

    public static final int DEFAULT_CAPACITY = 50;

    public static final class Pasture {
        public final String settlementId;
        public final int capacity;
        public int sheep;
        public float wool = 0f;
        public float growthAcc = 0f;                 // fractional lambs carried between days
        public int wolfVisits = 0;
        public Pasture(String settlementId, int sheep, int capacity){
            this.settlementId = settlementId;
            this.capacity = Math.max(1, capacity);
            this.sheep = Math.max(0, sheep);
        }
    }

    public interface Listener { void onPastureEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Pasture> pastures = new LinkedHashMap<>();
    public long seed = 0x511E1L;
    public int day = 0;

    /** fence a pasture; one per settlement */
    public boolean fence(String settlementId, int sheep){
        if(settlementId == null || settlementId.isEmpty() || sheep < 1
           || pastures.containsKey(settlementId)) return false;
        pastures.put(settlementId, new Pasture(settlementId, sheep, DEFAULT_CAPACITY));
        if(listener != null) listener.onPastureEvent("fenced", settlementId, sheep);
        return true;
    }

    /** daily tick: lambs, wool growth, wolves */
    public void tickDay(){
        day++;
        for(Pasture p : pastures.values()){
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ p.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.04f && p.sheep > 0){
                int lost = Math.max(1, Math.round(p.sheep * 0.05f));
                p.sheep = Math.max(0, p.sheep - lost);
                p.wolfVisits++;
                if(listener != null) listener.onPastureEvent("wolves", p.settlementId, lost);
            }
            // fractional lambs accumulate so even small flocks grow (a plain
            // round(sheep*0.02) would stall every flock below 25 head)
            p.growthAcc += p.sheep * 0.02f;
            int lambs = (int)p.growthAcc;
            p.growthAcc -= lambs;
            p.sheep = Math.min(p.capacity, p.sheep + lambs);
            p.wool += p.sheep * 0.1f;
        }
    }

    /** collect the accumulated wool; returns what was taken */
    public float shear(String settlementId){
        Pasture p = pastures.get(settlementId);
        if(p == null || p.wool <= 0f) return 0f;
        float taken = p.wool;
        p.wool = 0f;
        if(listener != null) listener.onPastureEvent("sheared", settlementId, (int)taken);
        return taken;
    }

    public float woolOf(String settlementId){
        Pasture p = pastures.get(settlementId);
        return p == null ? 0f : p.wool;
    }

    public Pasture pasture(String settlementId){ return pastures.get(settlementId); }
    public int pastureCount(){ return pastures.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("pastures");
        for(Pasture p : pastures.values())
            w.obj().kv("s", p.settlementId).kv("cap", p.capacity).kv("sh", p.sheep)
              .kv("wo", p.wool).kv("ga", p.growthAcc).kv("wv", p.wolfVisits).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ps = n.list("pastures");
            if(ps == null) return;
            for(Object o : ps){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Pasture p = new Pasture(Json.str(m.get("s"), "?"), (int)Json.num(m.get("sh"), 0),
                    (int)Json.num(m.get("cap"), 1));
                p.wool = (float)Json.num(m.get("wo"), 0f);
                p.growthAcc = Math.max(0f, (float)Json.num(m.get("ga"), 0f));
                p.wolfVisits = (int)Json.num(m.get("wv"), 0);
                pastures.put(p.settlementId, p);
            }
        } catch(Exception ignored){ }
    }
}
