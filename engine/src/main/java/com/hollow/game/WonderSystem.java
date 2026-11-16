package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.LinkedHashMap;
import java.util.Map;

/** v10.0 — wonder system (HollowEngine 10.0 'crown & coin').
 *  Monumental projects: a wonder is started (the game layer pays its cost),
 *  grows one stage per day, and once finished grants a permanent realm-wide
 *  bonus of its kind. Multiple wonders of the same kind stack.
 */
public final class WonderSystem {

    public enum Kind { MORALE, RESEARCH, TRADE }

    public static final class Wonder {
        public final String id;
        public final String name;
        public final Kind kind;
        public final float bonusValue;
        public final int buildDays;
        public float progress = 0f;
        public boolean done = false;
        public Wonder(String id, String name, Kind kind, float bonusValue, int buildDays){
            this.id = id; this.name = name; this.kind = kind;
            this.bonusValue = bonusValue; this.buildDays = Math.max(1, buildDays);
        }
    }

    public interface Listener { void onWonderEvent(String type, String wonderId, String detail); }
    public Listener listener;

    public final Map<String, Wonder> wonders = new LinkedHashMap<>();
    public int day = 0;

    public Wonder define(Wonder w){
        if(w == null || w.id == null || w.id.isEmpty()) return null;
        wonders.put(w.id, w);
        return w;
    }

    public Wonder wonder(String id){ return wonders.get(id); }

    /** break ground on a wonder; refuses unknown/already-started ones */
    public boolean start(String wonderId){
        Wonder w = wonders.get(wonderId);
        if(w == null || w.progress > 0f || w.done) return false;
        w.progress = 0.001f;                        // marks it as started
        if(listener != null) listener.onWonderEvent("started", wonderId, w.name);
        return true;
    }

    /** daily tick: started wonders advance one stage */
    public void tickDay(){
        day++;
        for(Wonder w : wonders.values()){
            if(w.done || w.progress <= 0f) continue;
            w.progress += 1f;
            if(w.progress >= w.buildDays){
                w.done = true;
                if(listener != null) listener.onWonderEvent("completed", w.id, w.kind.name());
            }
        }
    }

    /** permanent realm bonus of a kind: sum over completed wonders */
    public float realmBonus(Kind kind){
        float sum = 0f;
        for(Wonder w : wonders.values())
            if(w.done && w.kind == kind) sum += w.bonusValue;
        return sum;
    }

    public int completedCount(){
        int n = 0;
        for(Wonder w : wonders.values()) if(w.done) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("wonders");
        for(Wonder x : wonders.values())
            w.obj().kv("id", x.id).kv("name", x.name).kv("kind", x.kind.name())
              .kv("bonus", x.bonusValue).kv("days", x.buildDays)
              .kv("prog", x.progress).kv("done", x.done).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ws = n.list("wonders");
            if(ws == null) return;
            for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Wonder x;
                try {
                    x = new Wonder(Json.str(m.get("id"), "?"), Json.str(m.get("name"), "?"),
                        WonderSystem.Kind.valueOf(Json.str(m.get("kind"), "MORALE")),
                        (float)Json.num(m.get("bonus"), 0f), (int)Json.num(m.get("days"), 1));
                } catch(Exception e){ continue; }
                x.progress = (float)Json.num(m.get("prog"), 0f);
                x.done = Json.bool(m.get("done"), false);
                wonders.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }
}
