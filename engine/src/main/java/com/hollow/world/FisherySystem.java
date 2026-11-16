package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v12.0 — fishery system (HollowEngine 12.0 'trades & crafts').
 *  Fishing grounds off each settlement: boats haul a daily catch that
 *  scales with how rich the grounds still are, the stock regrows a
 *  little every day, and a storm keeps the fleet ashore.
 */
public final class FisherySystem {

    public static final int MAX_BOATS = 10;

    public static final class Ground {
        public final String settlementId;
        public float stock = 100f;                   // 0..100 richness of the grounds
        public int boats = 0;
        public float catchAcc = 0f;                  // uncollected catch
        public int storms = 0;
        public Ground(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onFishEvent(String type, String settlementId, float value); }
    public Listener listener;

    public final Map<String, Ground> grounds = new LinkedHashMap<>();
    public long seed = 0xF155L;
    public int day = 0;

    /** open a fishing ground; one per settlement */
    public boolean open(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || grounds.containsKey(settlementId)) return false;
        grounds.put(settlementId, new Ground(settlementId));
        if(listener != null) listener.onFishEvent("opened", settlementId, 100f);
        return true;
    }

    /** assign boats to a ground */
    public boolean launch(String settlementId, int boats){
        Ground g = grounds.get(settlementId);
        if(g == null || boats < 0 || boats > MAX_BOATS) return false;
        g.boats = boats;
        if(listener != null) listener.onFishEvent("launched", settlementId, boats);
        return true;
    }

    /** daily tick: hauls, regrowth, storms */
    public void tickDay(){
        day++;
        for(Ground g : grounds.values()){
            if(g.boats <= 0) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 61L) ^ g.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.04f){           // fleet stays ashore
                g.storms++;
                if(listener != null) listener.onFishEvent("storm", g.settlementId, g.boats);
                continue;
            }
            float catched = g.boats * 2f * (g.stock / 100f);
            g.catchAcc += catched;
            g.stock = MathX.clamp(g.stock - catched * 0.5f + 1f, 0f, 100f);
            if(listener != null) listener.onFishEvent("catch", g.settlementId, catched);
        }
    }

    /** collect the accumulated catch; returns what was taken */
    public float haul(String settlementId){
        Ground g = grounds.get(settlementId);
        if(g == null || g.catchAcc <= 0f) return 0f;
        float taken = g.catchAcc;
        g.catchAcc = 0f;
        if(listener != null) listener.onFishEvent("hauled", settlementId, taken);
        return taken;
    }

    public float catchOf(String settlementId){
        Ground g = grounds.get(settlementId);
        return g == null ? 0f : g.catchAcc;
    }

    public Ground ground(String settlementId){ return grounds.get(settlementId); }
    public int groundCount(){ return grounds.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("grounds");
        for(Ground g : grounds.values())
            w.obj().kv("s", g.settlementId).kv("st", g.stock).kv("b", g.boats)
              .kv("c", g.catchAcc).kv("stm", g.storms).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> gs = n.list("grounds");
            if(gs == null) return;
            for(Object o : gs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Ground g = new Ground(Json.str(m.get("s"), "?"));
                g.stock = MathX.clamp((float)Json.num(m.get("st"), 100f), 0f, 100f);
                g.boats = MathX.clamp((int)Json.num(m.get("b"), 0), 0, MAX_BOATS);
                g.catchAcc = (float)Json.num(m.get("c"), 0f);
                g.storms = (int)Json.num(m.get("stm"), 0);
                grounds.put(g.settlementId, g);
            }
        } catch(Exception ignored){ }
    }
}
