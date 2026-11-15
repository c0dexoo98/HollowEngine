package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v8.0 — justice system (HollowEngine 8.0 'kingdom').
 *  Crimes raise a criminal's wanted level; guards arrest anyone at or above the
 *  arrest threshold into a jail sentence scaled to their crimes; fines reduce
 *  wanted levels; sentences tick down daily and release automatically.
 */
public final class JusticeSystem {

    public static final class CrimeRecord {
        public final String criminal;
        public final String type;
        public final int severity;
        public final int day;
        public CrimeRecord(String criminal, String type, int severity, int day){
            this.criminal = criminal; this.type = type; this.severity = severity; this.day = day;
        }
    }

    public interface Listener { void onJusticeEvent(String type, String criminal, int value); }
    public Listener listener;

    public final List<CrimeRecord> crimes = new ArrayList<>();
    public final Map<String, Integer> wanted = new LinkedHashMap<>();
    public final Map<String, Integer> sentences = new LinkedHashMap<>();   // criminal → days left
    public int day = 0;
    public int arrestThreshold = 10;
    public int wantedDecayPerDay = 1;
    public int daysPerSeverity = 2;                  // sentence length scale

    /** a crime is reported; raises wanted level */
    public void recordCrime(String criminal, String type, int severity){
        if(criminal == null || criminal.isEmpty() || severity <= 0) return;
        crimes.add(new CrimeRecord(criminal, type, severity, day));
        wanted.put(criminal, wanted.getOrDefault(criminal, 0) + severity);
        if(listener != null) listener.onJusticeEvent("crime", criminal, wanted.get(criminal));
    }

    public boolean isWanted(String criminal){
        return wanted.getOrDefault(criminal, 0) >= arrestThreshold;
    }

    public int wantedLevel(String criminal){ return wanted.getOrDefault(criminal, 0); }

    /** guards take the criminal in: jail time scales with wanted level */
    public boolean arrest(String criminal){
        if(!isWanted(criminal) || sentences.containsKey(criminal)) return false;
        int level = wanted.get(criminal);
        int days = Math.max(1, (level / arrestThreshold) * daysPerSeverity);
        sentences.put(criminal, days);
        wanted.put(criminal, 0);
        if(listener != null) listener.onJusticeEvent("arrested", criminal, days);
        return true;
    }

    /** pay a fine to reduce wanted level; returns true if fully cleared */
    public boolean payFine(String criminal, int amount){
        if(amount <= 0 || sentences.containsKey(criminal)) return false;
        int level = wanted.getOrDefault(criminal, 0);
        int paid = Math.min(level, amount);
        wanted.put(criminal, level - paid);
        if(listener != null) listener.onJusticeEvent("fine", criminal, paid);
        return wanted.get(criminal) == 0;
    }

    /** daily tick: sentences count down, free wanted levels decay */
    public void tickDay(){
        day++;
        List<String> released = new ArrayList<>();
        for(Map.Entry<String, Integer> kv : sentences.entrySet()){
            int left = kv.getValue() - 1;
            if(left <= 0) released.add(kv.getKey());
            else kv.setValue(left);
        }
        for(String name : released){
            sentences.remove(name);
            if(listener != null) listener.onJusticeEvent("released", name, 0);
        }
        for(Map.Entry<String, Integer> kv : wanted.entrySet())
            if(kv.getValue() > 0 && !sentences.containsKey(kv.getKey()))
                kv.setValue(Math.max(0, kv.getValue() - wantedDecayPerDay));
    }

    public boolean isImprisoned(String criminal){ return sentences.containsKey(criminal); }
    public int sentenceOf(String criminal){ return sentences.getOrDefault(criminal, 0); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("threshold", arrestThreshold);
        w.arr("wanted");
        for(Map.Entry<String, Integer> kv : wanted.entrySet())
            w.obj().kv("c", kv.getKey()).kv("w", kv.getValue()).endObj();
        w.endArr();
        w.arr("jail");
        for(Map.Entry<String, Integer> kv : sentences.entrySet())
            w.obj().kv("c", kv.getKey()).kv("d", kv.getValue()).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            arrestThreshold = (int)n.flt("threshold", arrestThreshold);
            List<Object> ws = n.list("wanted");
            if(ws != null) for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m != null) wanted.put(Json.str(m.get("c"), ""), (int)Json.num(m.get("w"), 0));
            }
            List<Object> js = n.list("jail");
            if(js != null) for(Object o : js){
                Map<String,Object> m = Json.obj(o);
                if(m != null) sentences.put(Json.str(m.get("c"), ""), (int)Json.num(m.get("d"), 0));
            }
        } catch(Exception ignored){ }
    }
}
