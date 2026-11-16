package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.LinkedHashMap;
import java.util.Map;

/** v12.0 — bakery system (HollowEngine 12.0 'trades & crafts').
 *  One bakery per settlement: ovens bake bread from delivered grain at
 *  a fixed recipe, a fixed daily demand buys what it can, and bread
 *  beyond two days of oven output goes stale and is written off.
 */
public final class BakerySystem {

    public static final int BREAD_PER_GRAIN = 2;
    public static final int DEMAND_PER_DAY = 5;

    public static final class Bakery {
        public final String settlementId;
        public final int ovens;
        public int grain = 0;
        public int bread = 0;
        public int sold = 0;
        public int rotted = 0;
        public Bakery(String settlementId, int ovens){
            this.settlementId = settlementId;
            this.ovens = Math.max(1, ovens);
        }
        public int bakeCapacity(){ return ovens * 4; }       // grain units per day
        public int breadCap(){ return ovens * 8; }           // stale beyond two days of output
    }

    public interface Listener { void onBakeEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Bakery> bakeries = new LinkedHashMap<>();
    public int day = 0;

    /** open a bakery; one per settlement */
    public boolean open(String settlementId, int ovens){
        if(settlementId == null || settlementId.isEmpty() || bakeries.containsKey(settlementId)) return false;
        bakeries.put(settlementId, new Bakery(settlementId, ovens));
        if(listener != null) listener.onBakeEvent("opened", settlementId, ovens);
        return true;
    }

    /** deliver grain for baking */
    public boolean deliverGrain(String settlementId, int units){
        Bakery b = bakeries.get(settlementId);
        if(b == null || units <= 0) return false;
        b.grain += units;
        if(listener != null) listener.onBakeEvent("delivered", settlementId, units);
        return true;
    }

    /** daily tick: bake, sell to demand, write off stale bread */
    public void tickDay(){
        day++;
        for(Bakery b : bakeries.values()){
            int bake = Math.min(b.grain, b.bakeCapacity());
            if(bake > 0){
                b.grain -= bake;
                b.bread += bake * BREAD_PER_GRAIN;
                if(listener != null) listener.onBakeEvent("baked", b.settlementId, bake * BREAD_PER_GRAIN);
            }
            int sold = Math.min(b.bread, DEMAND_PER_DAY);
            if(sold > 0){
                b.bread -= sold;
                b.sold += sold;
                if(listener != null) listener.onBakeEvent("sold", b.settlementId, sold);
            }
            if(b.bread > b.breadCap()){
                b.rotted += b.bread - b.breadCap();
                b.bread = b.breadCap();
                if(listener != null) listener.onBakeEvent("stale", b.settlementId, b.rotted);
            }
        }
    }

    public Bakery bakery(String settlementId){ return bakeries.get(settlementId); }
    public int bakeryCount(){ return bakeries.size(); }

    public int totalSold(){
        int sum = 0;
        for(Bakery b : bakeries.values()) sum += b.sold;
        return sum;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("bakeries");
        for(Bakery b : bakeries.values())
            w.obj().kv("s", b.settlementId).kv("ov", b.ovens).kv("g", b.grain)
              .kv("br", b.bread).kv("so", b.sold).kv("ro", b.rotted).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> bs = n.list("bakeries");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Bakery b = new Bakery(Json.str(m.get("s"), "?"), (int)Json.num(m.get("ov"), 1));
                b.grain = (int)Json.num(m.get("g"), 0);
                b.bread = (int)Json.num(m.get("br"), 0);
                b.sold = (int)Json.num(m.get("so"), 0);
                b.rotted = (int)Json.num(m.get("ro"), 0);
                bakeries.put(b.settlementId, b);
            }
        } catch(Exception ignored){ }
    }
}
