package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v11.0 — well system (HollowEngine 11.0 'hearth & home').
 *  Village wells: one per settlement, a deep clean well grants water
 *  access, and contamination can strike any well (the game layer may tie
 *  that into PlagueSystem). Purification restores the water.
 */
public final class WellSystem {

    public static final int ACCESS_DEPTH = 3;

    public static final class Well {
        public final String settlementId;
        public final int depth;
        public boolean clean = true;
        public int contaminations = 0;
        public Well(String settlementId, int depth){
            this.settlementId = settlementId;
            this.depth = Math.max(1, depth);
        }
    }

    public interface Listener { void onWellEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Well> wells = new LinkedHashMap<>();
    public long seed = 0x2E1L;
    public int day = 0;

    /** dig a well; one per settlement, depth >= 1 */
    public boolean dig(String settlementId, int depth){
        if(settlementId == null || settlementId.isEmpty() || wells.containsKey(settlementId)) return false;
        wells.put(settlementId, new Well(settlementId, depth));
        if(listener != null) listener.onWellEvent("dug", settlementId, depth);
        return true;
    }

    /** daily tick: a small deterministic chance of contamination */
    public void tickDay(){
        day++;
        for(Well w : wells.values()){
            if(!w.clean) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 101L) ^ w.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.03f){
                w.clean = false;
                w.contaminations++;
                if(listener != null) listener.onWellEvent("contaminated", w.settlementId, w.contaminations);
            }
        }
    }

    /** clean the well again */
    public boolean purify(String settlementId){
        Well w = wells.get(settlementId);
        if(w == null || w.clean) return false;
        w.clean = true;
        if(listener != null) listener.onWellEvent("purified", settlementId, w.contaminations);
        return true;
    }

    /** does the settlement draw safe water today? */
    public boolean waterAccess(String settlementId){
        Well w = wells.get(settlementId);
        return w != null && w.clean && w.depth >= ACCESS_DEPTH;
    }

    public Well well(String settlementId){ return wells.get(settlementId); }
    public int wellCount(){ return wells.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("wells");
        for(Well x : wells.values())
            w.obj().kv("s", x.settlementId).kv("d", x.depth).kv("cl", x.clean)
              .kv("ct", x.contaminations).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ws = n.list("wells");
            if(ws == null) return;
            for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Well x = new Well(Json.str(m.get("s"), "?"), (int)Json.num(m.get("d"), 1));
                x.clean = Json.bool(m.get("cl"), true);
                x.contaminations = (int)Json.num(m.get("ct"), 0);
                wells.put(x.settlementId, x);
            }
        } catch(Exception ignored){ }
    }
}
