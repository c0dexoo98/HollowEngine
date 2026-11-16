package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v14.0 — cartographer system (HollowEngine 14.0 'watches & works').
 *  Survey parties are commissioned per region; each takes its stated
 *  days in the field, then the draft rolls the quill once — 80% a true
 *  map, else a rough one (both enter the archive, marked honestly).
 */
public final class CartographerSystem {

    public static final class Survey {
        public final int id;
        public final String region;
        public final int surveyDays;
        public int daysDone = 0;
        public boolean drawn = false;
        public boolean accurate = false;
        public Survey(int id, String region, int surveyDays){
            this.id = id; this.region = region;
            this.surveyDays = Math.max(1, surveyDays);
        }
    }

    public interface Listener { void onMapEvent(String type, int surveyId, String detail); }
    public Listener listener;

    public final Map<Integer, Survey> surveys = new LinkedHashMap<>();
    public int maps = 0;
    public long seed = 0x2A2L;
    public int day = 0;
    private int nextId = 1;

    /** commission a survey; region must be fresh and days at least one */
    public Survey commission(String region, int surveyDays){
        if(region == null || region.isEmpty() || surveyDays < 1) return null;
        for(Survey s : surveys.values()) if(s.region.equals(region)) return null;
        Survey s = new Survey(nextId++, region, surveyDays);
        surveys.put(s.id, s);
        if(listener != null) listener.onMapEvent("commissioned", s.id, region);
        return s;
    }

    /** daily tick: parties in the field count a day; finished ones draft */
    public void tickDay(){
        day++;
        for(Survey s : surveys.values()){
            if(s.drawn) continue;
            s.daysDone++;
            if(s.daysDone < s.surveyDays) continue;
            s.drawn = true;
            maps++;
            MathX.Rng rng = new MathX.Rng(seed ^ (s.id * 7919L) ^ (day * 131L));
            s.accurate = rng.range(0f, 1f) < 0.8f;
            if(listener != null) listener.onMapEvent(s.accurate ? "accurate" : "rough", s.id, s.region);
        }
    }

    public Survey survey(int id){ return surveys.get(id); }
    public int mapCount(){ return maps; }
    public int accurateCount(){
        int n = 0;
        for(Survey s : surveys.values()) if(s.drawn && s.accurate) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("maps", maps).kv("nextId", nextId);
        w.arr("surveys");
        for(Survey s : surveys.values())
            w.obj().kv("id", s.id).kv("r", s.region).kv("sd", s.surveyDays)
              .kv("dd", s.daysDone).kv("dw", s.drawn).kv("ac", s.accurate).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            maps = (int)n.flt("maps", maps);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> ss = n.list("surveys");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Survey s = new Survey(nextIdFor(m.get("id")),
                    Json.str(m.get("r"), "?"), (int)Json.num(m.get("sd"), 1));
                s.daysDone = (int)Json.num(m.get("dd"), 0);
                s.drawn = Json.bool(m.get("dw"), false);
                s.accurate = Json.bool(m.get("ac"), false);
                surveys.put(s.id, s);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
