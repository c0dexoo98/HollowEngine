package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v14.0 — dredge system (HollowEngine 14.0 'watches & works').
 *  One harbor per settlement: siltation steals a fathom every day while
 *  each assigned crew wins one back (a single crew only holds the
 *  channel); storms (3% single roll) carve four fathoms off at once.
 *  Depth stays within [5, 20].
 */
public final class DredgeSystem {

    public static final int MIN_DEPTH = 5;
    public static final int MAX_DEPTH = 20;
    public static final int MAX_CREWS = 4;

    public static final class Harbor {
        public final String settlementId;
        public int depth = 10;
        public int crews = 0;
        public int storms = 0;
        public int dredged = 0;
        public Harbor(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onHarborEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Harbor> harbors = new LinkedHashMap<>();
    public long seed = 0x0E1L;
    public int day = 0;

    /** charter a harbor; one per settlement */
    public boolean charter(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || harbors.containsKey(settlementId)) return false;
        harbors.put(settlementId, new Harbor(settlementId));
        if(listener != null) listener.onHarborEvent("chartered", settlementId, 0);
        return true;
    }

    /** assign dredge crews to a harbor */
    public boolean assignCrews(String settlementId, int n){
        Harbor h = harbors.get(settlementId);
        if(h == null || n < 0 || n > MAX_CREWS) return false;
        h.crews = n;
        if(listener != null) listener.onHarborEvent("assigned", settlementId, n);
        return true;
    }

    /** daily tick: siltation, crews, storms */
    public void tickDay(){
        day++;
        for(Harbor h : harbors.values()){
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ h.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.03f){
                int carved = Math.min(4, h.depth - MIN_DEPTH);
                h.depth -= carved;
                h.storms++;
                if(listener != null) listener.onHarborEvent("storm", h.settlementId, carved);
            }
            int net = h.crews - 1;                       // siltation takes one fathom a day
            int before = h.depth;
            h.depth = Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, h.depth + net));
            if(h.depth > before) h.dredged += h.depth - before;
        }
    }

    public Harbor harbor(String settlementId){ return harbors.get(settlementId); }
    public int harborCount(){ return harbors.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("harbors");
        for(Harbor h : harbors.values())
            w.obj().kv("s", h.settlementId).kv("d", h.depth).kv("c", h.crews)
              .kv("st", h.storms).kv("dr", h.dredged).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> hs = n.list("harbors");
            if(hs == null) return;
            for(Object o : hs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Harbor h = new Harbor(Json.str(m.get("s"), "?"));
                h.depth = MathX.clamp((int)Json.num(m.get("d"), 10), MIN_DEPTH, MAX_DEPTH);
                h.crews = MathX.clamp((int)Json.num(m.get("c"), 0), 0, MAX_CREWS);
                h.storms = (int)Json.num(m.get("st"), 0);
                h.dredged = (int)Json.num(m.get("dr"), 0);
                harbors.put(h.settlementId, h);
            }
        } catch(Exception ignored){ }
    }
}
