package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.LinkedHashMap;
import java.util.Map;

/** v11.0 — vital statistics system (HollowEngine 11.0 'hearth & home').
 *  Natural population movement for every settlement in the world: daily
 *  births and deaths at configurable rates, applied to SettlementSystem
 *  populations and booked into per-settlement ledgers. The game layer
 *  raises the death rate during plagues or famines.
 */
public final class VitalSystem {

    public static final class Stats {
        public int born = 0;
        public int died = 0;
        public int net(){ return born - died; }
    }

    public interface Listener { void onVitalEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final SettlementSystem world;
    public float birthRate = 0.010f;                 // share of pop born per day
    public float deathRate = 0.008f;                 // share of pop dying per day (raise in crisis)
    public final Map<String, Stats> ledgers = new LinkedHashMap<>();
    public int day = 0;

    public VitalSystem(SettlementSystem world){ this.world = world; }

    /** daily tick: births and deaths for every settlement; pop never goes negative */
    public void tickDay(){
        day++;
        for(SettlementSystem.Settlement s : world.settlements.values()){
            int born = Math.round(s.pop * birthRate);
            int died = Math.min(s.pop, Math.round(s.pop * deathRate));
            if(born <= 0 && died <= 0) continue;
            s.pop += born - died;
            Stats st = ledgers.get(s.id);
            if(st == null){ st = new Stats(); ledgers.put(s.id, st); }
            st.born += born;
            st.died += died;
            if(born > 0 && listener != null) listener.onVitalEvent("born", s.id, born);
            if(died > 0 && listener != null) listener.onVitalEvent("died", s.id, died);
        }
    }

    /** recorded vital statistics for a settlement (zeros when none yet) */
    public Stats stats(String settlementId){
        Stats st = ledgers.get(settlementId);
        return st != null ? st : new Stats();
    }

    public int totalBorn(){
        int sum = 0;
        for(Stats st : ledgers.values()) sum += st.born;
        return sum;
    }

    public int totalDied(){
        int sum = 0;
        for(Stats st : ledgers.values()) sum += st.died;
        return sum;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("br", birthRate).kv("dr", deathRate);
        w.arr("ledgers");
        for(Map.Entry<String, Stats> kv : ledgers.entrySet())
            w.obj().kv("s", kv.getKey()).kv("b", kv.getValue().born)
              .kv("d", kv.getValue().died).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            birthRate = (float)n.flt("br", birthRate);
            deathRate = (float)n.flt("dr", deathRate);
            java.util.List<Object> ls = n.list("ledgers");
            if(ls == null) return;
            for(Object o : ls){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Stats st = new Stats();
                st.born = (int)Json.num(m.get("b"), 0);
                st.died = (int)Json.num(m.get("d"), 0);
                ledgers.put(Json.str(m.get("s"), "?"), st);
            }
        } catch(Exception ignored){ }
    }
}
