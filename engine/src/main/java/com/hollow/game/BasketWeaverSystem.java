package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v18.0 — basket weaver system (HollowEngine 18.0 'pounds & pages').
 *  The bench weaves baskets from an osier stock (two rods paid up
 *  front, a single day's weaving — a fast craft), then the rattle
 *  test rolls once — 90% tight weave, 10% gappy (kept in the
 *  ledger, flagged honestly).
 */
public final class BasketWeaverSystem {

    public static final int RODS_PER_BASKET = 2;
    public static final int WEAVE_DAYS = 1;
    public static final int OSIERS_CAP = 15;

    public static final class Basket {
        public final int id;
        public final String kind;
        public int daysWoven = 0;
        public boolean done = false;
        public boolean tight = false;
        public boolean gappy = false;
        public Basket(int id, String kind){
            this.id = id; this.kind = kind;
        }
    }

    public interface Listener { void onBenchEvent(String type, int basketId, String detail); }
    public Listener listener;

    public final Map<Integer, Basket> baskets = new LinkedHashMap<>();
    public int osiers = 0;
    public long seed = 0x351L;
    public int day = 0;
    private int nextId = 1;

    /** stock the bench with osier rods */
    public boolean deliverOsiers(int rods){
        if(rods <= 0 || osiers + rods > OSIERS_CAP) return false;
        osiers += rods;
        return true;
    }

    /** start a basket; rods are paid up front */
    public Basket weave(String kind){
        if(kind == null || kind.isEmpty() || osiers < RODS_PER_BASKET) return null;
        osiers -= RODS_PER_BASKET;
        Basket b = new Basket(nextId++, kind);
        baskets.put(b.id, b);
        if(listener != null) listener.onBenchEvent("started", b.id, kind);
        return b;
    }

    /** daily tick: a weaving day passes; finished baskets roll the rattle test once */
    public void tickDay(){
        day++;
        for(Basket b : baskets.values()){
            if(b.done) continue;
            b.daysWoven++;
            if(b.daysWoven < WEAVE_DAYS) continue;
            b.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (b.id * 7919L) ^ (day * 131L));
            b.tight = rng.range(0f, 1f) < 0.9f;
            b.gappy = !b.tight;
            if(listener != null) listener.onBenchEvent(b.tight ? "tight" : "gappy", b.id, b.kind);
        }
    }

    public Basket basket(int id){ return baskets.get(id); }
    public int tightCount(){
        int n = 0;
        for(Basket b : baskets.values()) if(b.done && b.tight) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("osiers", osiers).kv("nextId", nextId);
        w.arr("baskets");
        for(Basket x : baskets.values())
            w.obj().kv("id", x.id).kv("k", x.kind).kv("dw", x.daysWoven)
              .kv("d", x.done).kv("t", x.tight).kv("g", x.gappy).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            osiers = MathX.clamp((int)n.flt("osiers", osiers), 0, OSIERS_CAP);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> bs = n.list("baskets");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Basket x = new Basket(nextIdFor(m.get("id")), Json.str(m.get("k"), "?"));
                x.daysWoven = (int)Json.num(m.get("dw"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.tight = Json.bool(m.get("t"), false);
                x.gappy = Json.bool(m.get("g"), false);
                baskets.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
