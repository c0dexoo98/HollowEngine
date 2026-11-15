package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v9.0 — plague system (HollowEngine 9.0 'realm & saga').
 *  Disease burns through settlements: virulence decides the daily sick share,
 *  the sick die at a rate, quarantined towns spread far less, survivors gain
 *  immunity and the outbreak burns out when no one is left to infect.
 */
public final class PlagueSystem {

    public interface Listener { void onPlagueEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final SettlementSystem world;
    public boolean active = false;
    public float virulence = 0.25f;                  // share of healthy that falls sick per day
    public float deathRate = 0.10f;                  // share of sick that dies per day
    public int durationDays = 15;                    // burned out after N days regardless
    public final Map<String, Boolean> quarantine = new LinkedHashMap<>();
    public final Map<String, Boolean> immune = new LinkedHashMap<>();
    public int day = 0;
    public int outbreakDay = -1;
    private final Map<String, Integer> sick = new LinkedHashMap<>();
    private long seed = 0x91A63L;

    public PlagueSystem(SettlementSystem world){ this.world = world; }

    /** the first sick settlers appear in a settlement */
    public boolean outbreak(String settlementId, int initialSick){
        SettlementSystem.Settlement s = world.settlements.get(settlementId);
        if(s == null || immune.getOrDefault(settlementId, false)) return false;
        int n = Math.min(Math.max(1, initialSick), s.pop);
        sick.put(settlementId, n);
        active = true;
        if(outbreakDay < 0) outbreakDay = day;
        if(listener != null) listener.onPlagueEvent("outbreak", settlementId, n);
        return true;
    }

    /** close a settlement: sick share still dies but spread is cut by 80% */
    public void quarantine(String settlementId, boolean on){
        if(on) quarantine.put(settlementId, true);
        else quarantine.remove(settlementId);
    }

    /** daily tick: deaths, spread, burn-out */
    public void tickDay(){
        day++;
        if(!active) return;
        MathX.Rng rng = new MathX.Rng(seed ^ day);
        List<String> deadOutbreaks = new ArrayList<>();

        for(Map.Entry<String, Integer> kv : sick.entrySet()){
            SettlementSystem.Settlement s = world.settlements.get(kv.getKey());
            if(s == null){ deadOutbreaks.add(kv.getKey()); continue; }
            int curSick = kv.getValue();

            // deaths among the sick
            int died = Math.min(curSick, Math.round(curSick * deathRate));
            if(died > 0){
                s.pop -= died;
                curSick -= died;
                if(listener != null) listener.onPlagueEvent("died", kv.getKey(), died);
            }

            // spread within the town: a share of the healthy falls sick
            int healthy = s.pop - curSick;
            if(healthy > 0){
                int newly = Math.round(healthy * virulence);
                curSick += newly;
                if(newly > 0 && listener != null) listener.onPlagueEvent("spread", kv.getKey(), newly);
            }

            // jump to a neighbouring settlement unless quarantined (plain Rng roll, no route wiring)
            boolean gated = quarantine.getOrDefault(kv.getKey(), false);
            if(!gated && rng.range(0f, 1f) < 0.35f){
                SettlementSystem.Settlement target = pickOther(rng, kv.getKey());
                if(target != null && !immune.getOrDefault(target.id, false) && !sick.containsKey(target.id)){
                    int seedSick = Math.max(1, target.pop / 4);
                    sick.put(target.id, seedSick);
                    if(listener != null) listener.onPlagueEvent("jumped", target.id, seedSick);
                }
            }

            if(curSick <= 0){
                sick.remove(kv.getKey());
                immune.put(kv.getKey(), true);          // survivors are immune
                if(listener != null) listener.onPlagueEvent("burned_out", kv.getKey(), s.pop);
            } else {
                kv.setValue(curSick);
            }
        }
        for(String gone : deadOutbreaks) sick.remove(gone);

        if(sick.isEmpty() || day - outbreakDay >= durationDays){
            int remaining = totalSick();
            active = false;
            sick.clear();                              // the epidemic is over — no stale sick counts
            if(listener != null) listener.onPlagueEvent("ended", "", remaining);
        }
    }

    private SettlementSystem.Settlement pickOther(MathX.Rng rng, String excludeId){
        int n = world.settlements.size();
        if(n <= 1) return null;
        int idx = rng.nextInt(n), seen = 0;
        for(SettlementSystem.Settlement s : world.settlements.values()){
            if(seen == idx && !s.id.equals(excludeId)) return s;
            if(!s.id.equals(excludeId)) seen++;
        }
        return null;
    }

    public int sickOf(String settlementId){ return sick.getOrDefault(settlementId, 0); }
    public boolean isQuarantined(String settlementId){ return quarantine.getOrDefault(settlementId, false); }
    public boolean isImmune(String settlementId){ return immune.getOrDefault(settlementId, false); }
    public int totalSick(){
        int sum = 0;
        for(int v : sick.values()) sum += v;
        return sum;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("active", active).kv("vir", virulence).kv("death", deathRate)
          .kv("dur", durationDays).kv("outbreak0", outbreakDay);
        w.arr("sick");
        for(Map.Entry<String, Integer> kv : sick.entrySet())
            w.obj().kv("s", kv.getKey()).kv("n", kv.getValue()).endObj();
        w.endArr();
        w.arr("quar");
        for(String q : quarantine.keySet()) w.obj().kv("s", q).endObj();
        w.endArr();
        w.arr("imm");
        for(String q : immune.keySet()) w.obj().kv("s", q).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            active = n.bool("active", false);
            virulence = (float)n.flt("vir", virulence);
            deathRate = (float)n.flt("death", deathRate);
            durationDays = (int)n.flt("dur", durationDays);
            outbreakDay = (int)n.flt("outbreak0", outbreakDay);
            sick.clear();
            List<Object> ss = n.list("sick");
            if(ss != null) for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m != null) sick.put(Json.str(m.get("s"), ""), (int)Json.num(m.get("n"), 0));
            }
            quarantine.clear();
            List<Object> qs = n.list("quar");
            if(qs != null) for(Object o : qs){
                Map<String,Object> m = Json.obj(o);
                if(m != null) quarantine.put(Json.str(m.get("s"), ""), true);
            }
            immune.clear();
            List<Object> is = n.list("imm");
            if(is != null) for(Object o : is){
                Map<String,Object> m = Json.obj(o);
                if(m != null) immune.put(Json.str(m.get("s"), ""), true);
            }
        } catch(Exception ignored){ }
    }
}
