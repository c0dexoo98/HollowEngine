package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v7.0 — NPC daily scheduler (HollowEngine 7.0 'civilization').
 *  Each NPC has a home / workplace / leisure anchor and a routine: SLEEP at
 *  night, WORK during shift hours, LEISURE otherwise. update(hour) resolves the
 *  activity and the anchor the NPC should walk to (movement itself plugs in).
 */
public final class NpcScheduler {

    public enum Activity { SLEEP, WORK, LEISURE }

    public static final class Profile {
        public final String name;
        public Vec3 home = new Vec3();
        public Vec3 work = new Vec3();
        public Vec3 leisure = new Vec3();            // defaults to home if unset
        public int workStart = 8, workEnd = 17;      // inclusive-exclusive hours
        public int sleepStart = 22, sleepEnd = 6;
        public Profile(String name){ this.name = name; }
        public Profile homeAt(float x, float y, float z){ home.set(x, y, z); return this; }
        public Profile workAt(float x, float y, float z){ work.set(x, y, z); return this; }
        public Profile leisureAt(float x, float y, float z){ leisure.set(x, y, z); return this; }
        public Profile shift(int start, int end){ workStart = start; workEnd = end; return this; }
    }

    public interface Listener { void onScheduleEvent(String npc, Activity from, Activity to, int hour); }
    public Listener listener;

    public final Map<String, Profile> npcs = new LinkedHashMap<>();
    public final Map<String, Activity> current = new LinkedHashMap<>();
    public final Map<String, Vec3> anchors = new LinkedHashMap<>();
    public int lastHour = -1;

    public Profile register(Profile p){
        npcs.put(p.name, p);
        current.put(p.name, Activity.LEISURE);
        anchors.put(p.name, new Vec3(p.home));
        return p;
    }
    public Profile profile(String name){ return npcs.get(name); }

    public Activity activityAt(Profile p, int hour){
        boolean night = hour >= p.sleepStart || hour < p.sleepEnd;
        if(night) return Activity.SLEEP;
        if(hour >= p.workStart && hour < p.workEnd) return Activity.WORK;
        return Activity.LEISURE;
    }

    /** resolve the routine for an hour of day (0..23); fires transition events */
    public void update(int hour){
        if(hour < 0 || hour > 23) return;
        lastHour = hour;
        for(Profile p : npcs.values()){
            Activity act = activityAt(p, hour);
            Activity prev = current.get(p.name);
            if(act != prev){
                current.put(p.name, act);
                if(listener != null) listener.onScheduleEvent(p.name, prev, act, hour);
            }
            Vec3 anchor;
            switch(act){
                case SLEEP: anchor = p.home; break;
                case WORK: anchor = p.work; break;
                default: {
                    // use the leisure anchor only if it has actually been placed
                    boolean set = p.leisure.x != 0f || p.leisure.y != 0f || p.leisure.z != 0f;
                    anchor = set ? p.leisure : p.home;
                    break;
                }
            }
            anchors.get(p.name).set(anchor);
        }
    }

    /** NPCs currently at work */
    public List<String> working(){
        List<String> out = new ArrayList<>();
        for(Map.Entry<String, Activity> kv : current.entrySet())
            if(kv.getValue() == Activity.WORK) out.add(kv.getKey());
        return out;
    }

    public Vec3 anchorOf(String name){ return anchors.get(name); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj();
        w.arr("npcs");
        for(Profile p : npcs.values()){
            w.obj().kv("name", p.name)
              .kvv("home", p.home.x, p.home.y, p.home.z)
              .kvv("work", p.work.x, p.work.y, p.work.z)
              .kvv("leisure", p.leisure.x, p.leisure.y, p.leisure.z)
              .kv("ws", p.workStart).kv("we", p.workEnd)
              .kv("ss", p.sleepStart).kv("se", p.sleepEnd).endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            List<Object> ns = n.list("npcs");
            if(ns == null) return;
            for(Object o : ns){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String name = Json.str(m.get("name"), null);
                if(name == null) continue;
                Profile p = npcs.get(name);
                if(p == null) p = register(new Profile(name));
                List<Object> h = Json.arr(m.get("home"));
                if(h != null && h.size() >= 3) p.home.set((float)Json.num(h.get(0), 0), (float)Json.num(h.get(1), 0), (float)Json.num(h.get(2), 0));
                List<Object> wk = Json.arr(m.get("work"));
                if(wk != null && wk.size() >= 3) p.work.set((float)Json.num(wk.get(0), 0), (float)Json.num(wk.get(1), 0), (float)Json.num(wk.get(2), 0));
                List<Object> le = Json.arr(m.get("leisure"));
                if(le != null && le.size() >= 3) p.leisure.set((float)Json.num(le.get(0), 0), (float)Json.num(le.get(1), 0), (float)Json.num(le.get(2), 0));
                p.workStart = (int)Json.num(m.get("ws"), p.workStart);
                p.workEnd = (int)Json.num(m.get("we"), p.workEnd);
                p.sleepStart = (int)Json.num(m.get("ss"), p.sleepStart);
                p.sleepEnd = (int)Json.num(m.get("se"), p.sleepEnd);
            }
        } catch(Exception ignored){ }
    }
}
