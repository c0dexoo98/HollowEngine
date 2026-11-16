package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v10.0 — lighthouse system (HollowEngine 10.0 'crown & coin').
 *  Coastal beacons: one per settlement, three levels, lit or dark. A lit
 *  light cuts shipwreck odds on the rocks below it; upkeep scales with
 *  level and is paid by the game layer.
 */
public final class LighthouseSystem {

    public static final int MAX_LEVEL = 3;

    public static final class Lighthouse {
        public final String settlementId;
        public int level = 1;
        public boolean lit = true;
        public int shipsGuided = 0;
        public Lighthouse(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onLightEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Lighthouse> lights = new LinkedHashMap<>();
    public int day = 0;

    public boolean build(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || lights.containsKey(settlementId)) return false;
        lights.put(settlementId, new Lighthouse(settlementId));
        if(listener != null) listener.onLightEvent("built", settlementId, 1);
        return true;
    }

    /** raise one level up to MAX_LEVEL; returns the new level or -1 */
    public int upgrade(String settlementId){
        Lighthouse l = lights.get(settlementId);
        if(l == null || l.level >= MAX_LEVEL) return -1;
        l.level++;
        if(listener != null) listener.onLightEvent("upgraded", settlementId, l.level);
        return l.level;
    }

    public void setLit(String settlementId, boolean lit){
        Lighthouse l = lights.get(settlementId);
        if(l != null) l.lit = lit;
    }

    /** daily upkeep the game layer owes for a light (level x 5) */
    public int upkeepOf(String settlementId){
        Lighthouse l = lights.get(settlementId);
        return l == null ? 0 : l.level * 5;
    }

    /** wreck odds for a ship passing this coast: dark 0.10, lit 0.06 - level x 0.015 */
    public float wreckChance(String settlementId){
        Lighthouse l = lights.get(settlementId);
        if(l == null || !l.lit) return 0.10f;
        return Math.max(0f, 0.06f - l.level * 0.015f);
    }

    /** a ship passes: deterministic wreck/safe roll, counted */
    public boolean shipPasses(String settlementId, long seedRoll){
        MathX.Rng rng = new MathX.Rng(seedRoll ^ settlementId.hashCode() ^ day);
        boolean wrecked = rng.range(0f, 1f) < wreckChance(settlementId);
        Lighthouse l = lights.get(settlementId);
        if(l != null && !wrecked) l.shipsGuided++;
        if(listener != null)
            listener.onLightEvent(wrecked ? "wrecked" : "safe", settlementId, wrecked ? 1 : 0);
        return wrecked;
    }

    public Lighthouse light(String settlementId){ return lights.get(settlementId); }
    public int lightCount(){ return lights.size(); }

    /** daily tick: only the calendar moves; upkeep collection stays with the game layer */
    public void tickDay(){
        day++;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("lights");
        for(Lighthouse l : lights.values())
            w.obj().kv("s", l.settlementId).kv("lvl", l.level).kv("lit", l.lit)
              .kv("guided", l.shipsGuided).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ls = n.list("lights");
            if(ls == null) return;
            for(Object o : ls){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Lighthouse l = new Lighthouse(Json.str(m.get("s"), "?"));
                l.level = MathX.clamp((int)Json.num(m.get("lvl"), 1), 1, MAX_LEVEL);
                l.lit = Json.bool(m.get("lit"), true);
                l.shipsGuided = (int)Json.num(m.get("guided"), 0);
                lights.put(l.settlementId, l);
            }
        } catch(Exception ignored){ }
    }
}
