package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v9.0 — granary system (HollowEngine 9.0 'realm & saga').
 *  A food buffer per settlement: harvest surplus is deposited into the
 *  granary (capacity from granary buildings), grain spoils slowly, and during
 *  famine the granary releases food automatically before people starve.
 */
public final class GranarySystem {

    public static final int CAPACITY_PER_GRANARY = 200;
    public static final float SPOIL_PER_DAY = 0.02f;   // 2% of stores

    public interface Listener { void onGranaryEvent(String type, String settlementId, float amount); }
    public Listener listener;

    public final SettlementSystem world;
    public final Map<String, Float> stores = new LinkedHashMap<>();

    public GranarySystem(SettlementSystem world){ this.world = world; }

    public int capacityOf(String settlementId){
        SettlementSystem.Settlement s = world.settlements.get(settlementId);
        if(s == null) return 0;
        int granaries = 0;
        for(String b : s.buildings) if(b.startsWith("granary")) granaries++;
        return granaries * CAPACITY_PER_GRANARY;
    }

    public float storesOf(String settlementId){ return stores.getOrDefault(settlementId, 0f); }

    /** move food from the settlement into the granary; returns the amount stored */
    public float deposit(String settlementId, float amount){
        SettlementSystem.Settlement s = world.settlements.get(settlementId);
        if(s == null || amount <= 0) return 0f;
        int cap = capacityOf(settlementId);
        float cur = storesOf(settlementId);
        float room = Math.max(0f, cap - cur);
        float put = Math.min(Math.min(amount, room), Math.max(0f, s.food));
        if(put <= 0) return 0f;
        s.food -= put;
        stores.put(settlementId, cur + put);
        if(listener != null) listener.onGranaryEvent("stored", settlementId, put);
        return put;
    }

    /** withdraw grain back into the settlement food pool */
    public float withdraw(String settlementId, float amount){
        SettlementSystem.Settlement s = world.settlements.get(settlementId);
        if(s == null || amount <= 0) return 0f;
        float cur = storesOf(settlementId);
        float take = Math.min(amount, cur);
        if(take <= 0) return 0f;
        stores.put(settlementId, cur - take);
        s.food += take;
        if(listener != null) listener.onGranaryEvent("released", settlementId, take);
        return take;
    }

    /** daily tick: spoilage, then automatic famine relief */
    public void tickDay(){
        for(Map.Entry<String, Float> kv : stores.entrySet()){
            SettlementSystem.Settlement s = world.settlements.get(kv.getKey());
            if(s == null) continue;
            float spoiled = kv.getValue() * SPOIL_PER_DAY;
            if(spoiled > 0f){
                kv.setValue(kv.getValue() - spoiled);
                if(listener != null) listener.onGranaryEvent("spoiled", kv.getKey(), spoiled);
            }
            // famine relief: granary feeds the town before people starve
            if(s.food < s.pop * s.foodPerHead){
                float need = s.pop * s.foodPerHead - s.food;
                float give = Math.min(kv.getValue(), need);
                if(give > 0f){
                    kv.setValue(kv.getValue() - give);
                    s.food += give;
                    if(listener != null) listener.onGranaryEvent("relief", kv.getKey(), give);
                }
            }
        }
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj();
        w.arr("stores");
        for(Map.Entry<String, Float> kv : stores.entrySet())
            w.obj().kv("s", kv.getKey()).kv("v", kv.getValue()).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            stores.clear();
            List<Object> ss = n.list("stores");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m != null) stores.put(Json.str(m.get("s"), ""), (float)Json.num(m.get("v"), 0f));
            }
        } catch(Exception ignored){ }
    }
}
