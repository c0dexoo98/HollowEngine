package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v10.0 — herald system (HollowEngine 10.0 'crown & coin').
 *  Proclamations: the crown publishes edicts with a realm-wide effect and a
 *  duration in days; each daily tick wears them down until they lapse.
 *  effectOf(type) sums the magnitudes of all currently active edicts.
 */
public final class HeraldSystem {

    public static final class Edict {
        public final int id;
        public final String name;
        public final String effectType;              // "tax", "curfew", "conscription"...
        public final float magnitude;
        public int remainingDays;
        public Edict(int id, String name, String effectType, float magnitude, int durationDays){
            this.id = id; this.name = name; this.effectType = effectType;
            this.magnitude = magnitude; this.remainingDays = Math.max(1, durationDays);
        }
    }

    public interface Listener { void onHeraldEvent(String type, int edictId, String detail); }
    public Listener listener;

    public final Map<Integer, Edict> edicts = new LinkedHashMap<>();
    public int day = 0;
    private int nextId = 1;

    /** proclaim a new edict; returns it or null on garbage input */
    public Edict proclaim(String name, String effectType, float magnitude, int durationDays){
        if(name == null || name.isEmpty() || effectType == null || effectType.isEmpty()) return null;
        Edict e = new Edict(nextId++, name, effectType, magnitude, durationDays);
        edicts.put(e.id, e);
        if(listener != null) listener.onHeraldEvent("proclaimed", e.id, effectType);
        return e;
    }

    /** daily tick: active edicts wear down; lapsed ones fire an event and are kept for the record */
    public void tickDay(){
        day++;
        for(Edict e : edicts.values()){
            if(e.remainingDays <= 0) continue;
            e.remainingDays--;
            if(e.remainingDays == 0 && listener != null)
                listener.onHeraldEvent("lapsed", e.id, e.effectType);
        }
    }

    /** is the edict still in force? */
    public boolean inForce(int edictId){
        Edict e = edicts.get(edictId);
        return e != null && e.remainingDays > 0;
    }

    /** sum of magnitudes of active edicts of one effect type */
    public float effectOf(String effectType){
        float sum = 0f;
        for(Edict e : edicts.values())
            if(e.remainingDays > 0 && e.effectType.equals(effectType)) sum += e.magnitude;
        return sum;
    }

    /** ids of edicts currently in force */
    public List<Integer> activeIds(){
        List<Integer> out = new ArrayList<>();
        for(Edict e : edicts.values()) if(e.remainingDays > 0) out.add(e.id);
        return out;
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId);
        w.arr("edicts");
        for(Edict e : edicts.values())
            w.obj().kv("id", e.id).kv("name", e.name).kv("fx", e.effectType)
              .kv("mag", e.magnitude).kv("rem", e.remainingDays).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> es = n.list("edicts");
            if(es == null) return;
            for(Object o : es){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Edict e = new Edict(nextIdFor(m.get("id")), Json.str(m.get("name"), "?"),
                    Json.str(m.get("fx"), "?"), (float)Json.num(m.get("mag"), 0f),
                    Math.max(1, (int)Json.num(m.get("rem"), 1)));
                e.remainingDays = (int)Json.num(m.get("rem"), e.remainingDays);   // exact remaining, even 0 — no resurrection
                edicts.put(e.id, e);
            }
        } catch(Exception ignored){ }
    }
}
