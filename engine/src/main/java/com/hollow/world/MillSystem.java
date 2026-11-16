package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v12.0 — mill system (HollowEngine 12.0 'trades & crafts').
 *  One mill per settlement: grain is delivered into a bounded hopper and
 *  ground into flour at a fixed daily rate; the mechanism can break down
 *  (deterministic roll) and grind nothing until repaired.
 */
public final class MillSystem {

    public static final int GRIND_PER_DAY = 5;

    public static final class Mill {
        public final String settlementId;
        public final int capacity;
        public int grain = 0;
        public float flour = 0f;
        public boolean broken = false;
        public int breakdowns = 0;
        public Mill(String settlementId, int capacity){
            this.settlementId = settlementId;
            this.capacity = Math.max(1, capacity);
        }
    }

    public interface Listener { void onMillEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Mill> mills = new LinkedHashMap<>();
    public long seed = 0x31DFL;
    public int day = 0;

    /** build a mill; one per settlement */
    public boolean build(String settlementId, int capacity){
        if(settlementId == null || settlementId.isEmpty() || mills.containsKey(settlementId)) return false;
        mills.put(settlementId, new Mill(settlementId, capacity));
        if(listener != null) listener.onMillEvent("built", settlementId, capacity);
        return true;
    }

    /** deliver grain into the hopper; refuses overflow and unknown mills */
    public boolean deliverGrain(String settlementId, int units){
        Mill m = mills.get(settlementId);
        if(m == null || units <= 0 || m.grain + units > m.capacity) return false;
        m.grain += units;
        if(listener != null) listener.onMillEvent("delivered", settlementId, units);
        return true;
    }

    /** daily tick: grinding, breakdowns */
    public void tickDay(){
        day++;
        for(Mill m : mills.values()){
            if(m.broken) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 101L) ^ m.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.03f){
                m.broken = true;
                m.breakdowns++;
                if(listener != null) listener.onMillEvent("breakdown", m.settlementId, m.grain);
                continue;
            }
            int ground = Math.min(m.grain, GRIND_PER_DAY);
            if(ground > 0){
                m.grain -= ground;
                m.flour += ground;
                if(listener != null) listener.onMillEvent("ground", m.settlementId, ground);
            }
        }
    }

    /** repair the mechanism */
    public boolean repair(String settlementId){
        Mill m = mills.get(settlementId);
        if(m == null || !m.broken) return false;
        m.broken = false;
        if(listener != null) listener.onMillEvent("repaired", settlementId, m.breakdowns);
        return true;
    }

    /** collect the milled flour; returns what was taken */
    public float takeFlour(String settlementId){
        Mill m = mills.get(settlementId);
        if(m == null || m.flour <= 0f) return 0f;
        float taken = m.flour;
        m.flour = 0f;
        if(listener != null) listener.onMillEvent("flour_taken", settlementId, (int)taken);
        return taken;
    }

    public float flourOf(String settlementId){
        Mill m = mills.get(settlementId);
        return m == null ? 0f : m.flour;
    }

    public Mill mill(String settlementId){ return mills.get(settlementId); }
    public int millCount(){ return mills.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("mills");
        for(Mill m : mills.values())
            w.obj().kv("s", m.settlementId).kv("cap", m.capacity).kv("g", m.grain)
              .kv("f", m.flour).kv("br", m.broken).kv("bd", m.breakdowns).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ms = n.list("mills");
            if(ms == null) return;
            for(Object o : ms){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Mill x = new Mill(Json.str(m.get("s"), "?"), (int)Json.num(m.get("cap"), 1));
                x.grain = (int)Json.num(m.get("g"), 0);
                x.flour = (float)Json.num(m.get("f"), 0f);
                x.broken = Json.bool(m.get("br"), false);
                x.breakdowns = (int)Json.num(m.get("bd"), 0);
                mills.put(x.settlementId, x);
            }
        } catch(Exception ignored){ }
    }
}
