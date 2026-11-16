package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v17.0 — thatcher system (HollowEngine 17.0 'banks & benches').
 *  The crew thatches roofs from a straw stock (three sheaves paid up
 *  front, two days on the ladder), then the weather test rolls once
 *  — 90% watertight, 10% leaks (kept in the ledger, flagged
 *  honestly).
 */
public final class ThatcherSystem {

    public static final int SHEAVES_PER_ROOF = 3;
    public static final int THATCH_DAYS = 2;

    public static final class Roof {
        public final int id;
        public final String house;
        public int daysThatched = 0;
        public boolean done = false;
        public boolean watertight = false;
        public boolean leaks = false;
        public Roof(int id, String house){
            this.id = id; this.house = house;
        }
    }

    public interface Listener { void onRoofEvent(String type, int roofId, String detail); }
    public Listener listener;

    public final Map<Integer, Roof> roofs = new LinkedHashMap<>();
    public int straw = 0;
    public long seed = 0x7EA1L;
    public int day = 0;
    private int nextId = 1;

    /** stack the rick with sheaves */
    public boolean deliverStraw(int sheaves){
        if(sheaves <= 0) return false;
        straw += sheaves;
        return true;
    }

    /** start a roof; sheaves are paid up front */
    public Roof thatch(String house){
        if(house == null || house.isEmpty() || straw < SHEAVES_PER_ROOF) return null;
        straw -= SHEAVES_PER_ROOF;
        Roof r = new Roof(nextId++, house);
        roofs.put(r.id, r);
        if(listener != null) listener.onRoofEvent("started", r.id, house);
        return r;
    }

    /** daily tick: ladder days pass; finished roofs roll the weather test once */
    public void tickDay(){
        day++;
        for(Roof r : roofs.values()){
            if(r.done) continue;
            r.daysThatched++;
            if(r.daysThatched < THATCH_DAYS) continue;
            r.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (r.id * 7919L) ^ (day * 131L));
            r.watertight = rng.range(0f, 1f) < 0.9f;
            r.leaks = !r.watertight;
            if(listener != null) listener.onRoofEvent(r.watertight ? "watertight" : "leaks", r.id, r.house);
        }
    }

    public Roof roof(int id){ return roofs.get(id); }
    public int tightCount(){
        int n = 0;
        for(Roof r : roofs.values()) if(r.done && r.watertight) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("straw", straw).kv("nextId", nextId);
        w.arr("roofs");
        for(Roof x : roofs.values())
            w.obj().kv("id", x.id).kv("h", x.house).kv("dt", x.daysThatched)
              .kv("d", x.done).kv("wt", x.watertight).kv("lk", x.leaks).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            straw = (int)n.flt("straw", straw);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> rs = n.list("roofs");
            if(rs == null) return;
            for(Object o : rs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Roof x = new Roof(nextIdFor(m.get("id")), Json.str(m.get("h"), "?"));
                x.daysThatched = (int)Json.num(m.get("dt"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.watertight = Json.bool(m.get("wt"), false);
                x.leaks = Json.bool(m.get("lk"), false);
                roofs.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
