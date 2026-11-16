package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v15.0 — bellfoundry system (HollowEngine 15.0 'trade & tide').
 *  The foundry casts bells from a bronze stock: a bell of W tons costs
 *  W bronze paid up front and spends W days in the mould, then the
 *  pour rolls once — 85% a true tone, the rest cracked. Only true
 *  bells may ring; every ring is counted.
 */
public final class BellfoundrySystem {

    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 4;

    public static final class Bell {
        public final int id;
        public final int weight;
        public final int mouldDays;
        public int daysInMould = 0;
        public boolean done = false;
        public boolean cracked = false;
        public int rings = 0;
        public Bell(int id, int weight){
            this.id = id;
            this.weight = weight;
            this.mouldDays = weight;
        }
    }

    public interface Listener { void onFoundryEvent(String type, int bellId, int value); }
    public Listener listener;

    public final Map<Integer, Bell> bells = new LinkedHashMap<>();
    public int bronze = 0;
    public long seed = 0x2E1L;
    public int day = 0;
    private int nextId = 1;

    /** stock the smelter with bronze */
    public boolean deliverBronze(int tons){
        if(tons <= 0) return false;
        bronze += tons;
        return true;
    }

    /** pour a bell of the given weight; bronze is paid up front */
    public Bell cast(int weight){
        if(weight < MIN_WEIGHT || weight > MAX_WEIGHT || bronze < weight) return null;
        bronze -= weight;
        Bell b = new Bell(nextId++, weight);
        bells.put(b.id, b);
        if(listener != null) listener.onFoundryEvent("poured", b.id, weight);
        return b;
    }

    /** daily tick: moulds cure; finished pours roll the tone once */
    public void tickDay(){
        day++;
        for(Bell b : bells.values()){
            if(b.done) continue;
            b.daysInMould++;
            if(b.daysInMould < b.mouldDays) continue;
            b.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (b.id * 7919L) ^ (day * 131L));
            b.cracked = rng.range(0f, 1f) >= 0.85f;
            if(listener != null) listener.onFoundryEvent(b.cracked ? "cracked" : "true_tone", b.id, b.weight);
        }
    }

    /** ring a finished, true bell */
    public boolean ring(int bellId){
        Bell b = bells.get(bellId);
        if(b == null || !b.done || b.cracked) return false;
        b.rings++;
        if(listener != null) listener.onFoundryEvent("rung", b.id, b.rings);
        return true;
    }

    public Bell bell(int id){ return bells.get(id); }
    public int trueBellCount(){
        int n = 0;
        for(Bell b : bells.values()) if(b.done && !b.cracked) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("bronze", bronze).kv("nextId", nextId);
        w.arr("bells");
        for(Bell b : bells.values())
            w.obj().kv("id", b.id).kv("w", b.weight).kv("md", b.mouldDays)
              .kv("dm", b.daysInMould).kv("d", b.done).kv("c", b.cracked)
              .kv("r", b.rings).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            bronze = (int)n.flt("bronze", bronze);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> bs = n.list("bells");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Bell b = new Bell(nextIdFor(m.get("id")), (int)Json.num(m.get("w"), 1));
                b.daysInMould = (int)Json.num(m.get("dm"), 0);
                b.done = Json.bool(m.get("d"), false);
                b.cracked = Json.bool(m.get("c"), false);
                b.rings = (int)Json.num(m.get("r"), 0);
                bells.put(b.id, b);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
