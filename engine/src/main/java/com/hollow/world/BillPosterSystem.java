package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v20.0 — bill poster system (HollowEngine 20.0 'harps & halls').
 *  One bill wall per town: bills are stacked for posting (up to the
 *  wall's capacity); each day up to four are pasted up, but one roll
 *  decides the weather — 15% a rain shower washes the two oldest
 *  bills off (counted in the faded ledger) and nothing is posted.
 */
public final class BillPosterSystem {

    public static final int BILLS_CAP = 24;
    public static final int POSTS_PER_DAY = 4;
    public static final int FADED_PER_SHOWER = 2;
    public static final float RAIN_CHANCE = 0.15f;

    public static final class Wall {
        public final String townId;
        public int bills = 0;
        public int posted = 0;
        public int faded = 0;
        public Wall(String townId){
            this.townId = townId;
        }
    }

    public interface Listener { void onWallEvent(String type, String townId, int value); }
    public Listener listener;

    public final Map<String, Wall> walls = new LinkedHashMap<>();
    public long seed = 0x3EEL;
    public int day = 0;

    /** raise a bill wall; one per town */
    public boolean raise(String townId){
        if(townId == null || townId.isEmpty() || walls.containsKey(townId)) return false;
        walls.put(townId, new Wall(townId));
        if(listener != null) listener.onWallEvent("raised", townId, 0);
        return true;
    }

    /** stack bills for posting; refuses overflow and unknown walls */
    public boolean stack(String townId, int bills){
        Wall w = walls.get(townId);
        if(w == null || bills <= 0 || w.bills + bills > BILLS_CAP) return false;
        w.bills += bills;
        if(listener != null) listener.onWallEvent("stacked", townId, bills);
        return true;
    }

    /** daily tick: paste up bills, or a shower washes the oldest off */
    public void tickDay(){
        day++;
        for(Wall w : walls.values()){
            if(w.bills <= 0) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ w.townId.hashCode());
            if(rng.range(0f, 1f) < RAIN_CHANCE){
                int lost = Math.min(FADED_PER_SHOWER, w.bills);
                w.bills -= lost;
                w.faded += lost;
                if(listener != null) listener.onWallEvent("shower", w.townId, lost);
            } else {
                int pasted = Math.min(POSTS_PER_DAY, w.bills);
                w.bills -= pasted;
                w.posted += pasted;
                if(listener != null) listener.onWallEvent("posted", w.townId, pasted);
            }
        }
    }

    public Wall wall(String townId){ return walls.get(townId); }
    public int wallCount(){ return walls.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("walls");
        for(Wall x : walls.values())
            w.obj().kv("t", x.townId).kv("b", x.bills).kv("p", x.posted)
              .kv("f", x.faded).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ws = n.list("walls");
            if(ws == null) return;
            for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Wall x = new Wall(Json.str(m.get("t"), "?"));
                x.bills = MathX.clamp((int)Json.num(m.get("b"), 0), 0, BILLS_CAP);
                x.posted = (int)Json.num(m.get("p"), 0);
                x.faded = (int)Json.num(m.get("f"), 0);
                walls.put(x.townId, x);
            }
        } catch(Exception ignored){ }
    }
}
