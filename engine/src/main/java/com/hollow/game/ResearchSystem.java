package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v8.0 — research system (HollowEngine 8.0 'kingdom').
 *  Civilization tech tree: scholars generate knowledge per day (× library
 *  bonus), technologies cost knowledge and have prerequisites, unlocked techs
 *  contribute aggregate stat effects. JSON-persisted.
 */
public final class ResearchSystem {

    public static final class Tech {
        public final String id;
        public String name;
        public int cost = 10;
        public final List<String> requires = new ArrayList<>();
        public final Map<String, Float> effects = new LinkedHashMap<>();
        public boolean unlocked = false;
        public Tech(String id, String name){ this.id = id; this.name = name; }
        public Tech costs(int c){ cost = c; return this; }
        public Tech requires(String... ids){ for(String r : ids) requires.add(r); return this; }
        public Tech effect(String stat, float v){ effects.put(stat, v); return this; }
    }

    public interface Listener { void onResearchEvent(String type, String techId, float knowledge); }
    public Listener listener;

    public final Map<String, Tech> techs = new LinkedHashMap<>();
    public float knowledge = 0f;
    public int scholars = 0;
    public float knowledgePerScholar = 0.5f;
    public float libraryBonus = 1f;                // multiply from built libraries
    public int day = 0;

    public Tech define(Tech t){ techs.put(t.id, t); return t; }
    public Tech tech(String id){ return techs.get(id); }

    public boolean canUnlock(String id){
        Tech t = techs.get(id);
        if(t == null || t.unlocked || knowledge < t.cost) return false;
        for(String r : t.requires)
            if(techs.get(r) == null || !techs.get(r).unlocked) return false;
        return true;
    }

    public boolean unlock(String id){
        if(!canUnlock(id)) return false;
        Tech t = techs.get(id);
        t.unlocked = true;
        knowledge -= t.cost;
        if(listener != null) listener.onResearchEvent("unlocked", id, knowledge);
        return true;
    }

    /** daily knowledge income from scholars */
    public void tickDay(){
        day++;
        knowledge += scholars * knowledgePerScholar * libraryBonus;
    }

    /** aggregated effect of one stat over all unlocked techs */
    public float bonus(String stat){
        float sum = 0f;
        for(Tech t : techs.values())
            if(t.unlocked && t.effects.containsKey(stat)) sum += t.effects.get(stat);
        return sum;
    }

    public int unlockedCount(){
        int n = 0;
        for(Tech t : techs.values()) if(t.unlocked) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("know", knowledge).kv("scholars", scholars)
          .kv("lib", libraryBonus).kv("per", knowledgePerScholar);
        w.arr("unlocked");
        for(Tech t : techs.values())
            if(t.unlocked) w.obj().kv("id", t.id).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            knowledge = (float)n.flt("know", knowledge);
            scholars = (int)n.flt("scholars", scholars);
            libraryBonus = (float)n.flt("lib", libraryBonus);
            knowledgePerScholar = (float)n.flt("per", knowledgePerScholar);
            List<Object> us = n.list("unlocked");
            if(us == null) return;
            for(Object o : us){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Tech t = techs.get(Json.str(m.get("id"), ""));
                if(t != null) t.unlocked = true;
            }
        } catch(Exception ignored){ }
    }
}
