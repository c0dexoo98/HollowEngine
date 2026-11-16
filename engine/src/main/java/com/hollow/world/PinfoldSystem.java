package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v18.0 — pinfold system (HollowEngine 18.0 'pounds & pages').
 *  One village pound per settlement: stray beasts are penned (up to
 *  four heads); each day the pinder rolls once — 20% an owner claims
 *  a head and the fine ledger takes two coins, else the pound keeps
 *  feeding the strays and the feed-cost ledger takes one. An empty
 *  pound rolls nothing and costs nothing.
 */
public final class PinfoldSystem {

    public static final int PEN_CAP = 4;
    public static final int FINE_PER_HEAD = 2;
    public static final int FEED_COST_PER_DAY = 1;

    public static final class Pound {
        public final String settlementId;
        public int penned = 0;
        public int fines = 0;
        public int feedCosts = 0;
        public Pound(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onPoundEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Pound> pounds = new LinkedHashMap<>();
    public long seed = 0x701L;
    public int day = 0;

    /** build a pound; one per settlement */
    public boolean build(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || pounds.containsKey(settlementId)) return false;
        pounds.put(settlementId, new Pound(settlementId));
        if(listener != null) listener.onPoundEvent("built", settlementId, 0);
        return true;
    }

    /** pen a stray beast; refuses overflow and unknown pounds */
    public boolean impound(String settlementId, int heads){
        Pound p = pounds.get(settlementId);
        if(p == null || heads <= 0 || p.penned + heads > PEN_CAP) return false;
        p.penned += heads;
        if(listener != null) listener.onPoundEvent("penned", settlementId, heads);
        return true;
    }

    /** daily tick: claim roll while the pen holds beasts */
    public void tickDay(){
        day++;
        for(Pound p : pounds.values()){
            if(p.penned <= 0) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ p.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.2f){
                p.penned--;
                p.fines += FINE_PER_HEAD;
                if(listener != null) listener.onPoundEvent("claimed", p.settlementId, p.fines);
            } else {
                p.feedCosts += FEED_COST_PER_DAY;
                if(listener != null) listener.onPoundEvent("fed", p.settlementId, p.feedCosts);
            }
        }
    }

    public Pound pound(String settlementId){ return pounds.get(settlementId); }
    public int poundCount(){ return pounds.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("pounds");
        for(Pound p : pounds.values())
            w.obj().kv("s", p.settlementId).kv("pn", p.penned).kv("fi", p.fines)
              .kv("fc", p.feedCosts).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ps = n.list("pounds");
            if(ps == null) return;
            for(Object o : ps){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Pound p = new Pound(Json.str(m.get("s"), "?"));
                p.penned = MathX.clamp((int)Json.num(m.get("pn"), 0), 0, PEN_CAP);
                p.fines = (int)Json.num(m.get("fi"), 0);
                p.feedCosts = (int)Json.num(m.get("fc"), 0);
                pounds.put(p.settlementId, p);
            }
        } catch(Exception ignored){ }
    }
}
