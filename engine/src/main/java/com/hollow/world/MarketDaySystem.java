package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.LinkedHashMap;
import java.util.Map;

/** v10.0 — market day system (HollowEngine 10.0 'crown & coin').
 *  Each settlement can hold a market every N days: on its day the town
 *  draws a crowd (half the population), trade prices swell by a boost and
 *  a "market_day" event fires. Deterministic, JSON-persisted.
 */
public final class MarketDaySystem {

    public static final float PRICE_BOOST = 1.25f;

    public interface Listener { void onMarketEvent(String type, String settlementId, int crowd); }
    public Listener listener;

    public final SettlementSystem world;
    /** settlementId → interval in days */
    public final Map<String, Integer> schedule = new LinkedHashMap<>();
    public int day = 0;

    public MarketDaySystem(SettlementSystem world){ this.world = world; }

    /** register a settlement's market cadence (interval >= 1); replaces an existing one */
    public boolean register(String settlementId, int intervalDays){
        if(!world.settlements.containsKey(settlementId) || intervalDays < 1) return false;
        schedule.put(settlementId, intervalDays);
        return true;
    }

    public void unregister(String settlementId){ schedule.remove(settlementId); }

    /** is today a market day in this settlement? */
    public boolean isMarketDay(String settlementId){
        Integer interval = schedule.get(settlementId);
        return interval != null && day % interval == 0;
    }

    /** trade price multiplier today */
    public float priceBoost(String settlementId){
        return isMarketDay(settlementId) ? PRICE_BOOST : 1f;
    }

    /** crowd drawn today (half the population on market days) */
    public int crowdOf(String settlementId){
        if(!isMarketDay(settlementId)) return 0;
        SettlementSystem.Settlement s = world.settlements.get(settlementId);
        return s == null ? 0 : s.pop / 2;
    }

    /** daily tick: fires market_day events for the settlements whose day it is */
    public void tickDay(){
        day++;
        for(Map.Entry<String, Integer> kv : schedule.entrySet()){
            if(day % kv.getValue() != 0) continue;
            SettlementSystem.Settlement s = world.settlements.get(kv.getKey());
            if(s == null) continue;
            if(listener != null) listener.onMarketEvent("market_day", kv.getKey(), s.pop / 2);
        }
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("schedule");
        for(Map.Entry<String, Integer> kv : schedule.entrySet())
            w.obj().kv("s", kv.getKey()).kv("iv", kv.getValue()).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ss = n.list("schedule");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m != null) schedule.put(Json.str(m.get("s"), ""), Math.max(1, (int)Json.num(m.get("iv"), 1)));
            }
        } catch(Exception ignored){ }
    }
}
