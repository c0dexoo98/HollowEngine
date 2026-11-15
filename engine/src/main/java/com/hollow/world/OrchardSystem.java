package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v11.0 — orchard system (HollowEngine 11.0 'hearth & home').
 *  One orchard per settlement on an 8-day rhythm: blossom on day 3 of the
 *  cycle (a frost can spoil it), fruit ripens by day 7 and accumulates in
 *  the orchard until harvested. Young orchards yield proportionally less.
 */
public final class OrchardSystem {

    public static final int CYCLE = 8;
    public static final int BLOSSOM_DAY = 3;
    public static final int RIPE_DAY = 7;

    public static final class Orchard {
        public final String settlementId;
        public final int trees;
        public int age = 0;
        public float fruit = 0f;
        public int harvests = 0;
        public int frosts = 0;
        public Orchard(String settlementId, int trees){
            this.settlementId = settlementId;
            this.trees = Math.max(1, trees);
        }
    }

    public interface Listener { void onOrchardEvent(String type, String settlementId, float value); }
    public Listener listener;

    public final Map<String, Orchard> orchards = new LinkedHashMap<>();
    public long seed = 0xF0F1L;
    public int day = 0;

    /** plant an orchard; one per settlement */
    public boolean plant(String settlementId, int trees){
        if(settlementId == null || settlementId.isEmpty() || orchards.containsKey(settlementId)) return false;
        orchards.put(settlementId, new Orchard(settlementId, trees));
        if(listener != null) listener.onOrchardEvent("planted", settlementId, trees);
        return true;
    }

    /** daily tick: aging, blossom (with frost risk), ripening */
    public void tickDay(){
        day++;
        for(Orchard o : orchards.values()){
            o.age++;
            int phase = o.age % CYCLE;
            if(phase == BLOSSOM_DAY){
                MathX.Rng rng = new MathX.Rng(seed ^ (day * 61L) ^ o.settlementId.hashCode());
                if(rng.range(0f, 1f) < 0.05f){
                    o.frosts++;
                    if(listener != null) listener.onOrchardEvent("frost", o.settlementId, o.trees);
                } else if(listener != null){
                    listener.onOrchardEvent("blossom", o.settlementId, o.trees);
                }
            }
            if(phase == RIPE_DAY){
                float maturity = Math.min(1f, o.age / 10f);
                o.fruit += o.trees * maturity;
            }
        }
    }

    /** pick the ripe fruit; returns what was gathered */
    public float harvest(String settlementId){
        Orchard o = orchards.get(settlementId);
        if(o == null || o.fruit <= 0f) return 0f;
        float taken = o.fruit;
        o.fruit = 0f;
        o.harvests++;
        if(listener != null) listener.onOrchardEvent("harvested", settlementId, taken);
        return taken;
    }

    public float fruitOf(String settlementId){
        Orchard o = orchards.get(settlementId);
        return o == null ? 0f : o.fruit;
    }

    public Orchard orchard(String settlementId){ return orchards.get(settlementId); }
    public int orchardCount(){ return orchards.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("orchards");
        for(Orchard o : orchards.values())
            w.obj().kv("s", o.settlementId).kv("t", o.trees).kv("age", o.age)
              .kv("f", o.fruit).kv("hv", o.harvests).kv("fr", o.frosts).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> os = n.list("orchards");
            if(os == null) return;
            for(Object o : os){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Orchard x = new Orchard(Json.str(m.get("s"), "?"), (int)Json.num(m.get("t"), 1));
                x.age = (int)Json.num(m.get("age"), 0);
                x.fruit = (float)Json.num(m.get("f"), 0f);
                x.harvests = (int)Json.num(m.get("hv"), 0);
                x.frosts = (int)Json.num(m.get("fr"), 0);
                orchards.put(x.settlementId, x);
            }
        } catch(Exception ignored){ }
    }
}
