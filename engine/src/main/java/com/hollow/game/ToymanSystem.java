package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v15.0 — toyman system (HollowEngine 15.0 'trade & tide').
 *  The stall carves toys from a wood-scrap stock (one scrap per toy),
 *  paints them by hand, and sells painted stock on fair days — each
 *  painted, unsold toy rolls the crowd once (50% a sale at two coins).
 */
public final class ToymanSystem {

    public static final int TOY_PRICE = 2;

    public static final class Toy {
        public final int id;
        public final String toyName;
        public boolean painted = false;
        public boolean sold = false;
        public Toy(int id, String toyName){
            this.id = id; this.toyName = toyName;
        }
    }

    public interface Listener { void onStallEvent(String type, int toyId, String detail); }
    public Listener listener;

    public final Map<Integer, Toy> toys = new LinkedHashMap<>();
    public int wood = 0;
    public int takings = 0;
    public long seed = 0x70L;
    public int day = 0;
    private int nextId = 1;

    /** stock the workshop with wood scraps */
    public boolean deliverWood(int scraps){
        if(scraps <= 0) return false;
        wood += scraps;
        return true;
    }

    /** carve a toy; one scrap paid up front */
    public Toy carve(String toyName){
        if(toyName == null || toyName.isEmpty() || wood < 1) return null;
        wood--;
        Toy t = new Toy(nextId++, toyName);
        toys.put(t.id, t);
        if(listener != null) listener.onStallEvent("carved", t.id, toyName);
        return t;
    }

    /** paint a toy by hand; deterministic */
    public boolean paint(int toyId){
        Toy t = toys.get(toyId);
        if(t == null || t.painted) return false;
        t.painted = true;
        if(listener != null) listener.onStallEvent("painted", t.id, t.toyName);
        return true;
    }

    /** run a fair day: one crowd roll per painted, unsold toy */
    public int fairDay(){
        day++;
        int sold = 0;
        for(Toy t : toys.values()){
            if(!t.painted || t.sold) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (t.id * 7919L) ^ (day * 131L));
            if(rng.range(0f, 1f) < 0.5f){
                t.sold = true;
                sold++;
                takings += TOY_PRICE;
                if(listener != null) listener.onStallEvent("sold", t.id, t.toyName);
            }
        }
        return sold;
    }

    public Toy toy(int id){ return toys.get(id); }
    public int stockCount(){
        int n = 0;
        for(Toy t : toys.values()) if(!t.sold) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("wood", wood).kv("takings", takings).kv("nextId", nextId);
        w.arr("toys");
        for(Toy t : toys.values())
            w.obj().kv("id", t.id).kv("n", t.toyName)
              .kv("p", t.painted).kv("s", t.sold).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            wood = (int)n.flt("wood", wood);
            takings = (int)n.flt("takings", takings);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> ts = n.list("toys");
            if(ts == null) return;
            for(Object o : ts){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Toy t = new Toy(nextIdFor(m.get("id")), Json.str(m.get("n"), "?"));
                t.painted = Json.bool(m.get("p"), false);
                t.sold = Json.bool(m.get("s"), false);
                toys.put(t.id, t);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
