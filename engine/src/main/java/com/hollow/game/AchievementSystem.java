package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.LinkedHashMap;
import java.util.Map;

/** v6.0 — achievement system (HollowEngine 6.0 'living-world').
 *  Stat-tracked achievements: the game layer feeds stat deltas (kills, crafted,
 *  distance, ...), thresholds unlock achievements, grant points and fire events.
 *  Progress is queryable per achievement for the UI. JSON-persisted.
 */
public final class AchievementSystem {

    public static final class Achievement {
        public final String id;
        public String name;
        public String stat;                     // stat key being watched
        public int threshold;
        public int points = 10;
        public boolean secret = false;
        public boolean unlocked = false;
        public Achievement(String id, String stat, int threshold){
            this.id = id; this.stat = stat; this.threshold = threshold;
        }
        public Achievement named(String n){ name = n; return this; }
        public Achievement worth(int p){ points = p; return this; }
        public Achievement hidden(){ secret = true; return this; }
    }

    public interface Listener { void onAchievementEvent(String type, String achievementId, int points); }
    public Listener listener;

    public final Map<String, Achievement> achievements = new LinkedHashMap<>();
    public final Map<String, Double> stats = new LinkedHashMap<>();
    public int points = 0;
    public int unlockedCount = 0;

    public Achievement define(Achievement a){ achievements.put(a.id, a); return a; }
    public Achievement achievement(String id){ return achievements.get(id); }

    public double stat(String key){ return stats.getOrDefault(key, 0.0); }

    /** add to a stat and check achievements watching it */
    public boolean record(String stat, double delta){
        double v = stats.getOrDefault(stat, 0.0) + delta;
        stats.put(stat, v);
        boolean any = false;
        for(Achievement a : achievements.values()){
            if(a.unlocked || !a.stat.equals(stat) || v < a.threshold) continue;
            a.unlocked = true;
            unlockedCount++;
            points += a.points;
            any = true;
            if(listener != null) listener.onAchievementEvent("unlocked", a.id, a.points);
        }
        return any;
    }

    /** progress toward one achievement (0..1; 1 once unlocked) */
    public float progress(String id){
        Achievement a = achievements.get(id);
        if(a == null) return 0f;
        if(a.unlocked) return 1f;
        return (float)Math.min(1.0, stat(a.stat) / a.threshold);
    }

    public float completionPercent(){
        if(achievements.isEmpty()) return 0f;
        return unlockedCount * 100f / achievements.size();
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("points", points).kv("unlocked", unlockedCount);
        w.obj("stats");
        for(Map.Entry<String, Double> kv : stats.entrySet()) w.kv(kv.getKey(), kv.getValue().floatValue());
        w.endObj();
        w.arr("done");
        for(Achievement a : achievements.values())
            if(a.unlocked) w.obj().kv("id", a.id).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            points = (int)n.flt("points", points);
            unlockedCount = (int)n.flt("unlocked", unlockedCount);
            JsonV st = n.node("stats");
            if(st.map != null)
                for(String k : st.map.keySet()) stats.put(k, (double)Json.num(st.map.get(k), 0.0));
            java.util.List<Object> done = n.list("done");
            if(done != null) for(Object o : done){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Achievement a = achievements.get(Json.str(m.get("id"), ""));
                if(a != null && !a.unlocked){ a.unlocked = true; }
            }
        } catch(Exception ignored){ }
    }
}
