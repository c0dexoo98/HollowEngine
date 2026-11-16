package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v13.0 — healer system (HollowEngine 13.0 'ritual & remedy').
 *  A hospice with a few beds: patients are admitted with an injury and a
 *  recovery window; each day under care counts, and at the window's end
 *  a deterministic roll decides recovery or death. Leaving early counts
 *  as neither.
 */
public final class HealerSystem {

    public static final int BEDS = 3;
    public static final float RECOVERY_CHANCE = 0.85f;

    public static final class Patient {
        public final String name;
        public final String injury;
        public final int recoveryDays;
        public int daysUnderCare = 0;
        public Patient(String name, String injury, int recoveryDays){
            this.name = name; this.injury = injury;
            this.recoveryDays = Math.max(1, recoveryDays);
        }
    }

    public interface Listener { void onHealerEvent(String type, String patient, String detail); }
    public Listener listener;

    public final Map<String, Patient> patients = new LinkedHashMap<>();     // name → patient
    public int recoveredCount = 0;
    public int lostCount = 0;
    public long seed = 0xE4L;
    public int day = 0;

    /** admit a patient; refuses garbage, duplicates and a full ward */
    public boolean admit(String name, String injury, int recoveryDays){
        if(name == null || name.isEmpty() || injury == null || injury.isEmpty()
           || patients.containsKey(name) || patients.size() >= BEDS) return false;
        patients.put(name, new Patient(name, injury, recoveryDays));
        if(listener != null) listener.onHealerEvent("admitted", name, injury);
        return true;
    }

    /** daily tick: care days accumulate; the window's end decides */
    public void tickDay(){
        day++;
        List<String> done = new ArrayList<>();
        for(Patient p : patients.values()){
            p.daysUnderCare++;
            if(p.daysUnderCare >= p.recoveryDays) done.add(p.name);
        }
        for(String name : done){
            Patient p = patients.remove(name);
            MathX.Rng rng = new MathX.Rng(seed ^ (p.name.hashCode() * 31L) ^ (day * 101L));
            if(rng.range(0f, 1f) < RECOVERY_CHANCE){
                recoveredCount++;
                if(listener != null) listener.onHealerEvent("recovered", name, p.injury);
            } else {
                lostCount++;
                if(listener != null) listener.onHealerEvent("died", name, p.injury);
            }
        }
    }

    /** a patient walks out before the window closes */
    public boolean discharge(String name){
        Patient p = patients.remove(name);
        if(p == null) return false;
        if(listener != null) listener.onHealerEvent("discharged", name,
            p.injury + " after " + p.daysUnderCare);
        return true;
    }

    public Patient patient(String name){ return patients.get(name); }
    public int bedCount(){ return patients.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("rec", recoveredCount).kv("lost", lostCount);
        w.arr("patients");
        for(Patient p : patients.values())
            w.obj().kv("n", p.name).kv("inj", p.injury).kv("rd", p.recoveryDays)
              .kv("d", p.daysUnderCare).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            recoveredCount = (int)n.flt("rec", recoveredCount);
            lostCount = (int)n.flt("lost", lostCount);
            java.util.List<Object> ps = n.list("patients");
            if(ps == null) return;
            for(Object o : ps){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Patient p = new Patient(Json.str(m.get("n"), "?"),
                    Json.str(m.get("inj"), "?"), (int)Json.num(m.get("rd"), 1));
                p.daysUnderCare = (int)Json.num(m.get("d"), 0);
                patients.put(p.name, p);
            }
        } catch(Exception ignored){ }
    }
}
