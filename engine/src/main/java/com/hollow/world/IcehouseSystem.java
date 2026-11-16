package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.LinkedHashMap;
import java.util.Map;

/** v14.0 — icehouse system (HollowEngine 14.0 'watches & works').
 *  One icehouse per settlement, purely arithmetic: winter cut blocks
 *  are stored up to the pit capacity and every day a tenth of the
 *  stock melts (rounded up — ice never survives untouched), counted
 *  in the melt ledger. No rolls; the caller decides when it is winter.
 */
public final class IcehouseSystem {

    public static final int PIT_CAPACITY = 50;

    public static final class House {
        public final String settlementId;
        public int ice = 0;
        public int harvested = 0;
        public int melted = 0;
        public House(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onIceEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, House> houses = new LinkedHashMap<>();
    public int day = 0;

    /** dig an icehouse; one per settlement */
    public boolean build(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || houses.containsKey(settlementId)) return false;
        houses.put(settlementId, new House(settlementId));
        if(listener != null) listener.onIceEvent("built", settlementId, 0);
        return true;
    }

    /** store winter-cut ice; refuses overflow and unknown houses */
    public boolean cutIce(String settlementId, int blocks){
        House h = houses.get(settlementId);
        if(h == null || blocks <= 0 || h.ice + blocks > PIT_CAPACITY) return false;
        h.ice += blocks;
        h.harvested += blocks;
        if(listener != null) listener.onIceEvent("cut", settlementId, blocks);
        return true;
    }

    /** daily tick: a tenth melts, rounded up */
    public void tickDay(){
        day++;
        for(House h : houses.values()){
            if(h.ice <= 0) continue;
            int melt = (int)Math.ceil(h.ice * 0.1f);
            h.ice -= melt;
            h.melted += melt;
            if(listener != null) listener.onIceEvent("melted", h.settlementId, melt);
        }
    }

    public House house(String settlementId){ return houses.get(settlementId); }
    public int houseCount(){ return houses.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("houses");
        for(House h : houses.values())
            w.obj().kv("s", h.settlementId).kv("ice", h.ice)
              .kv("hv", h.harvested).kv("ml", h.melted).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> hs = n.list("houses");
            if(hs == null) return;
            for(Object o : hs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                House h = new House(Json.str(m.get("s"), "?"));
                h.ice = Math.max(0, Math.min(PIT_CAPACITY, (int)Json.num(m.get("ice"), 0)));
                h.harvested = (int)Json.num(m.get("hv"), 0);
                h.melted = (int)Json.num(m.get("ml"), 0);
                houses.put(h.settlementId, h);
            }
        } catch(Exception ignored){ }
    }
}
