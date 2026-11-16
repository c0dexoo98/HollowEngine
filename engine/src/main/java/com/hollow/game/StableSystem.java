package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v17.0 — stable system (HollowEngine 17.0 'banks & benches').
 *  The yard trains horses from an oat stock: each untrained horse
 *  drinks one oat ration a day (deterministic) and gains a training
 *  day — a day with an empty manger stalls the horse, no progress,
 *  no roll. Three fed days finish the training and one roll decides
 *  the temper — 85% steady, 15% restive (kept in the ledger
 *  honestly).
 */
public final class StableSystem {

    public static final int OAT_CAP = 12;
    public static final int TRAIN_DAYS = 3;

    public static final class Horse {
        public final int id;
        public final String name;
        public int daysInTraining = 0;
        public int fedDays = 0;
        public boolean trained = false;
        public boolean steady = false;
        public boolean restive = false;
        public Horse(int id, String name){
            this.id = id; this.name = name;
        }
    }

    public interface Listener { void onYardEvent(String type, int horseId, String detail); }
    public Listener listener;

    public final Map<Integer, Horse> horses = new LinkedHashMap<>();
    public int oats = 0;
    public long seed = 0x571L;
    public int day = 0;
    private int nextId = 1;

    /** fill the oat bin */
    public boolean deliverOats(int rations){
        if(rations <= 0 || oats + rations > OAT_CAP) return false;
        oats += rations;
        return true;
    }

    /** take a horse into training */
    public Horse train(String name){
        if(name == null || name.isEmpty()) return null;
        Horse h = new Horse(nextId++, name);
        horses.put(h.id, h);
        if(listener != null) listener.onYardEvent("started", h.id, name);
        return h;
    }

    /** daily tick: feeding order is stable id order; one temper roll per finished horse */
    public void tickDay(){
        day++;
        for(Horse h : horses.values()){
            if(h.trained) continue;
            if(oats <= 0) continue;                       // empty manger: the horse stalls
            oats--;
            h.daysInTraining++;
            h.fedDays++;
            if(h.daysInTraining < TRAIN_DAYS) continue;
            h.trained = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (h.id * 7919L) ^ (day * 131L));
            h.steady = rng.range(0f, 1f) < 0.85f;
            h.restive = !h.steady;
            if(listener != null) listener.onYardEvent(h.steady ? "steady" : "restive", h.id, h.name);
        }
    }

    public Horse horse(int id){ return horses.get(id); }
    public int steadyCount(){
        int n = 0;
        for(Horse h : horses.values()) if(h.trained && h.steady) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("oats", oats).kv("nextId", nextId);
        w.arr("horses");
        for(Horse x : horses.values())
            w.obj().kv("id", x.id).kv("n", x.name).kv("dt", x.daysInTraining)
              .kv("fd", x.fedDays).kv("tr", x.trained).kv("st", x.steady)
              .kv("rs", x.restive).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            oats = MathX.clamp((int)n.flt("oats", oats), 0, OAT_CAP);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> hs = n.list("horses");
            if(hs == null) return;
            for(Object o : hs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Horse x = new Horse(nextIdFor(m.get("id")), Json.str(m.get("n"), "?"));
                x.daysInTraining = (int)Json.num(m.get("dt"), 0);
                x.fedDays = (int)Json.num(m.get("fd"), 0);
                x.trained = Json.bool(m.get("tr"), false);
                x.steady = Json.bool(m.get("st"), false);
                x.restive = Json.bool(m.get("rs"), false);
                horses.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
