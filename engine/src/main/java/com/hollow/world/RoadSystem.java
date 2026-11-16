package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v9.0 — road network (HollowEngine 9.0 'realm & saga').
 *  Roads between settlements (symmetric, level 0..3). Higher levels shorten
 *  caravan travel and ease migration; upgrade costs are exposed so the game
 *  layer can pay from the treasury. Deterministic, JSON-persisted.
 */
public final class RoadSystem {

    public static final int MAX_LEVEL = 3;

    public static final class Road {
        public final String fromId;
        public final String toId;
        public int level = 1;
        public Road(String fromId, String toId, int level){
            this.fromId = fromId; this.toId = toId; this.level = MathX.clamp(level, 0, MAX_LEVEL);
        }
    }

    public interface Listener { void onRoadEvent(String type, String fromId, String toId, int level); }
    public Listener listener;

    /** key "a|b" with a <= b — symmetric */
    public final Map<String, Road> roads = new LinkedHashMap<>();
    public int upgradeCostPerLevel = 40;

    private static String key(String a, String b){
        return a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)
            ? null : (a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a);
    }

    /** build a road at level 1; no-op when one already links the pair */
    public boolean build(String fromId, String toId){
        String k = key(fromId, toId);
        if(k == null || roads.containsKey(k)) return false;
        roads.put(k, new Road(fromId, toId, 1));
        if(listener != null) listener.onRoadEvent("built", fromId, toId, 1);
        return true;
    }

    /** upgrade one level up to MAX_LEVEL; returns the new level or -1 */
    public int upgrade(String fromId, String toId){
        Road r = roads.get(key(fromId, toId));
        if(r == null || r.level >= MAX_LEVEL) return -1;
        r.level++;
        if(listener != null) listener.onRoadEvent("upgraded", fromId, toId, r.level);
        return r.level;
    }

    public Road road(String fromId, String toId){ return roads.get(key(fromId, toId)); }
    public int levelOf(String fromId, String toId){
        Road r = roads.get(key(fromId, toId));
        return r == null ? 0 : r.level;
    }

    /** caravan travel days after the road discount; floored at 1 */
    public int travelDays(int baseDays, String fromId, String toId){
        int lvl = levelOf(fromId, toId);
        int discounted = Math.round(baseDays * (1f - lvl * 0.15f));
        return Math.max(1, discounted);
    }

    /** migration pull bonus: road level × 0.1 */
    public float migrationBonus(String fromId, String toId){
        return levelOf(fromId, toId) * 0.1f;
    }

    public int roadCount(){ return roads.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("cost", upgradeCostPerLevel);
        w.arr("roads");
        for(Road r : roads.values())
            w.obj().kv("f", r.fromId).kv("t", r.toId).kv("lvl", r.level).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            upgradeCostPerLevel = (int)n.flt("cost", upgradeCostPerLevel);
            java.util.List<Object> rs = n.list("roads");
            if(rs == null) return;
            for(Object o : rs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String f = Json.str(m.get("f"), null), t = Json.str(m.get("t"), null);
                if(f == null || t == null) continue;
                String k = key(f, t);
                if(k == null) continue;
                Road r = roads.get(k);
                if(r == null){ r = new Road(f, t, 1); roads.put(k, r); }
                r.level = MathX.clamp((int)Json.num(m.get("lvl"), r.level), 0, MAX_LEVEL);
            }
        } catch(Exception ignored){ }
    }
}
