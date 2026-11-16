package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v18.0 — hatchery system (HollowEngine 18.0 'pounds & pages').
 *  One hatchery tank per settlement: eggs are stocked (up to the
 *  tank's capacity) and each day two eggs are lifted to the hatching
 *  trays; one roll decides the trays — 90% the pair swims free as fry,
 *  10% a cold snap takes the whole pair (counted honestly in the
 *  losses ledger).
 */
public final class HatcherySystem {

    public static final int TANK_CAPACITY = 30;
    public static final int EGGS_PER_DAY = 2;

    public static final class Tank {
        public final String settlementId;
        public int eggs = 0;
        public int fry = 0;
        public int losses = 0;
        public Tank(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onTankEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Tank> tanks = new LinkedHashMap<>();
    public long seed = 0x4E7L;
    public int day = 0;

    /** fill a tank; one per settlement */
    public boolean fill(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || tanks.containsKey(settlementId)) return false;
        tanks.put(settlementId, new Tank(settlementId));
        if(listener != null) listener.onTankEvent("filled", settlementId, 0);
        return true;
    }

    /** stock eggs; refuses overflow and unknown tanks */
    public boolean stock(String settlementId, int eggs){
        Tank t = tanks.get(settlementId);
        if(t == null || eggs <= 0 || t.eggs + eggs > TANK_CAPACITY) return false;
        t.eggs += eggs;
        if(listener != null) listener.onTankEvent("stocked", settlementId, eggs);
        return true;
    }

    /** daily tick: one pair to the trays, one roll for the trays */
    public void tickDay(){
        day++;
        for(Tank t : tanks.values()){
            if(t.eggs <= 0) continue;
            int pair = Math.min(EGGS_PER_DAY, t.eggs);
            t.eggs -= pair;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ t.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.1f){
                t.losses += pair;
                if(listener != null) listener.onTankEvent("cold_snap", t.settlementId, pair);
            } else {
                t.fry += pair;
                if(listener != null) listener.onTankEvent("hatched", t.settlementId, pair);
            }
        }
    }

    public Tank tank(String settlementId){ return tanks.get(settlementId); }
    public int tankCount(){ return tanks.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("tanks");
        for(Tank t : tanks.values())
            w.obj().kv("s", t.settlementId).kv("e", t.eggs).kv("f", t.fry)
              .kv("l", t.losses).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ts = n.list("tanks");
            if(ts == null) return;
            for(Object o : ts){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Tank t = new Tank(Json.str(m.get("s"), "?"));
                t.eggs = MathX.clamp((int)Json.num(m.get("e"), 0), 0, TANK_CAPACITY);
                t.fry = (int)Json.num(m.get("f"), 0);
                t.losses = (int)Json.num(m.get("l"), 0);
                tanks.put(t.settlementId, t);
            }
        } catch(Exception ignored){ }
    }
}
