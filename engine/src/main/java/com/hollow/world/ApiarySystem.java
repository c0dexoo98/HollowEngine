package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v11.0 — apiary system (HollowEngine 11.0 'hearth & home').
 *  One apiary per settlement: strong hives make honey every day into a
 *  local pool, harvests collect it, and occasionally a hive swarms and
 *  loses half its strength. Deterministic rolls.
 */
public final class ApiarySystem {

    public static final class Hive {
        public final String settlementId;
        public float strength;                       // 0..1 colony strength
        public float honey = 0f;                     // unharvested pool
        public int harvests = 0;
        public int swarms = 0;
        public Hive(String settlementId, float strength){
            this.settlementId = settlementId;
            this.strength = MathX.clamp(strength, 0f, 1f);
        }
    }

    public interface Listener { void onApiaryEvent(String type, String settlementId, float value); }
    public Listener listener;

    public final Map<String, Hive> hives = new LinkedHashMap<>();
    public long seed = 0xBEE5L;
    public int day = 0;

    /** place an apiary; one per settlement */
    public boolean place(String settlementId, float strength){
        if(settlementId == null || settlementId.isEmpty() || hives.containsKey(settlementId)) return false;
        hives.put(settlementId, new Hive(settlementId, strength));
        if(listener != null) listener.onApiaryEvent("placed", settlementId, strength);
        return true;
    }

    /** daily tick: honey accumulates; a small deterministic chance of swarming */
    public void tickDay(){
        day++;
        for(Hive h : hives.values()){
            h.honey += h.strength * 2f;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 31L) ^ h.settlementId.hashCode());
            if(h.strength > 0.2f && rng.range(0f, 1f) < 0.02f){
                h.strength /= 2f;
                h.swarms++;
                if(listener != null) listener.onApiaryEvent("swarmed", h.settlementId, h.strength);
            }
        }
    }

    /** collect the accumulated honey; returns what was taken */
    public float harvest(String settlementId){
        Hive h = hives.get(settlementId);
        if(h == null || h.honey <= 0f) return 0f;
        float taken = h.honey;
        h.honey = 0f;
        h.harvests++;
        if(listener != null) listener.onApiaryEvent("harvested", settlementId, taken);
        return taken;
    }

    public float poolOf(String settlementId){
        Hive h = hives.get(settlementId);
        return h == null ? 0f : h.honey;
    }

    public Hive hive(String settlementId){ return hives.get(settlementId); }
    public int hiveCount(){ return hives.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("hives");
        for(Hive h : hives.values())
            w.obj().kv("s", h.settlementId).kv("st", h.strength).kv("h", h.honey)
              .kv("hv", h.harvests).kv("sw", h.swarms).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> hs = n.list("hives");
            if(hs == null) return;
            for(Object o : hs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Hive h = new Hive(Json.str(m.get("s"), "?"), (float)Json.num(m.get("st"), 0f));
                h.honey = (float)Json.num(m.get("h"), 0f);
                h.harvests = (int)Json.num(m.get("hv"), 0);
                h.swarms = (int)Json.num(m.get("sw"), 0);
                hives.put(h.settlementId, h);
            }
        } catch(Exception ignored){ }
    }
}
