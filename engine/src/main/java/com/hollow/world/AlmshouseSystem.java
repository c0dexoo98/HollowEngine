package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v18.0 — almshouse system (HollowEngine 18.0 'pounds & pages').
 *  One almshouse per settlement: beds are set (up to six); each
 *  night every bed shelters one soul (deterministic — the nights
 *  ledger takes the bed count), and one roll follows while the
 *  house stands occupied — 10% a benefactor leaves five coins in
 *  the alms box.
 */
public final class AlmshouseSystem {

    public static final int MAX_BEDS = 6;
    public static final int BENEFACITOR_GIFT = 5;

    public static final class House {
        public final String settlementId;
        public int beds = 0;
        public int nights = 0;
        public int alms = 0;
        public House(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onHouseEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, House> houses = new LinkedHashMap<>();
    public long seed = 0xA1A5L;
    public int day = 0;

    /** found an almshouse; one per settlement */
    public boolean found(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || houses.containsKey(settlementId)) return false;
        houses.put(settlementId, new House(settlementId));
        if(listener != null) listener.onHouseEvent("founded", settlementId, 0);
        return true;
    }

    /** set the bed count; refuses overflow and unknown houses */
    public boolean setBeds(String settlementId, int beds){
        House h = houses.get(settlementId);
        if(h == null || beds < 0 || beds > MAX_BEDS) return false;
        h.beds = beds;
        if(listener != null) listener.onHouseEvent("beds_set", settlementId, beds);
        return true;
    }

    /** daily tick: the night's shelter is certain, the alms box is not */
    public void tickDay(){
        day++;
        for(House h : houses.values()){
            if(h.beds > 0){
                h.nights += h.beds;
                if(listener != null) listener.onHouseEvent("sheltered", h.settlementId, h.beds);
                MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ h.settlementId.hashCode());
                if(rng.range(0f, 1f) < 0.1f){
                    h.alms += BENEFACITOR_GIFT;
                    if(listener != null) listener.onHouseEvent("gift", h.settlementId, h.alms);
                }
            }
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
            w.obj().kv("s", h.settlementId).kv("b", h.beds).kv("n", h.nights)
              .kv("a", h.alms).endObj();
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
                h.beds = MathX.clamp((int)Json.num(m.get("b"), 0), 0, MAX_BEDS);
                h.nights = (int)Json.num(m.get("n"), 0);
                h.alms = (int)Json.num(m.get("a"), 0);
                houses.put(h.settlementId, h);
            }
        } catch(Exception ignored){ }
    }
}
