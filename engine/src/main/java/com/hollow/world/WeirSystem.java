package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v17.0 — weir system (HollowEngine 17.0 'banks & benches').
 *  One river weir per settlement: each trap set holds one fish a
 *  day (deterministic), and the weir rolls once — 5% a flood washes
 *  one trap's catch downstream, else the full catch is landed and
 *  counted honestly.
 */
public final class WeirSystem {

    public static final int MAX_TRAPS = 5;

    public static final class Weir {
        public final String settlementId;
        public int traps = 0;
        public int totalCatch = 0;
        public int floods = 0;
        public Weir(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onWeirEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Weir> weirs = new LinkedHashMap<>();
    public long seed = 0x7D1L;
    public int day = 0;

    /** stake out a weir; one per settlement */
    public boolean stake(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || weirs.containsKey(settlementId)) return false;
        weirs.put(settlementId, new Weir(settlementId));
        if(listener != null) listener.onWeirEvent("staked", settlementId, 0);
        return true;
    }

    /** set or pull traps in the water */
    public boolean setTraps(String settlementId, int n){
        Weir w = weirs.get(settlementId);
        if(w == null || n < 0 || n > MAX_TRAPS) return false;
        w.traps = n;
        if(listener != null) listener.onWeirEvent("traps_set", settlementId, n);
        return true;
    }

    /** daily tick: the catch, one roll for the flood */
    public void tickDay(){
        day++;
        for(Weir w : weirs.values()){
            if(w.traps <= 0) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ w.settlementId.hashCode());
            int catchToday = w.traps;
            if(rng.range(0f, 1f) < 0.05f){
                catchToday = Math.max(0, w.traps - 1);
                w.floods++;
                if(listener != null) listener.onWeirEvent("flood", w.settlementId, w.floods);
            }
            w.totalCatch += catchToday;
            if(listener != null) listener.onWeirEvent("caught", w.settlementId, catchToday);
        }
    }

    public Weir weir(String settlementId){ return weirs.get(settlementId); }
    public int weirCount(){ return weirs.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("weirs");
        for(Weir x : weirs.values())
            w.obj().kv("s", x.settlementId).kv("t", x.traps).kv("tc", x.totalCatch)
              .kv("fl", x.floods).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ws = n.list("weirs");
            if(ws == null) return;
            for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Weir x = new Weir(Json.str(m.get("s"), "?"));
                x.traps = MathX.clamp((int)Json.num(m.get("t"), 0), 0, MAX_TRAPS);
                x.totalCatch = (int)Json.num(m.get("tc"), 0);
                x.floods = (int)Json.num(m.get("fl"), 0);
                weirs.put(x.settlementId, x);
            }
        } catch(Exception ignored){ }
    }
}
