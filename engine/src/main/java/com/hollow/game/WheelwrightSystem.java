package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v16.0 — wheelwright system (HollowEngine 16.0 'turns & rounds').
 *  The yard builds cart wheels from a spoke stock (four spokes paid
 *  up front per wheel, two days on the bench), then the road test
 *  rolls once — 90% true round, the rest wobbly (kept in the ledger,
 *  sold as seconds honestly).
 */
public final class WheelwrightSystem {

    public static final int SPOKES_PER_WHEEL = 4;
    public static final int BUILD_DAYS = 2;

    public static final class Wheel {
        public final int id;
        public final String kind;
        public int daysOnBench = 0;
        public boolean done = false;
        public boolean trueRound = false;
        public boolean wobbly = false;
        public Wheel(int id, String kind){
            this.id = id; this.kind = kind;
        }
    }

    public interface Listener { void onYardEvent(String type, int wheelId, String detail); }
    public Listener listener;

    public final Map<Integer, Wheel> wheels = new LinkedHashMap<>();
    public int spokes = 0;
    public long seed = 0x6E2L;
    public int day = 0;
    private int nextId = 1;

    /** stock the yard with spokes */
    public boolean deliverSpokes(int units){
        if(units <= 0) return false;
        spokes += units;
        return true;
    }

    /** start a wheel; spokes are paid up front */
    public Wheel build(String kind){
        if(kind == null || kind.isEmpty() || spokes < SPOKES_PER_WHEEL) return null;
        spokes -= SPOKES_PER_WHEEL;
        Wheel w = new Wheel(nextId++, kind);
        wheels.put(w.id, w);
        if(listener != null) listener.onYardEvent("started", w.id, kind);
        return w;
    }

    /** daily tick: bench days pass; finished wheels roll the road test once */
    public void tickDay(){
        day++;
        for(Wheel w : wheels.values()){
            if(w.done) continue;
            w.daysOnBench++;
            if(w.daysOnBench < BUILD_DAYS) continue;
            w.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (w.id * 7919L) ^ (day * 131L));
            w.trueRound = rng.range(0f, 1f) < 0.9f;
            w.wobbly = !w.trueRound;
            if(listener != null) listener.onYardEvent(w.trueRound ? "true_round" : "wobbly", w.id, w.kind);
        }
    }

    public Wheel wheel(int id){ return wheels.get(id); }
    public int trueCount(){
        int n = 0;
        for(Wheel w : wheels.values()) if(w.done && w.trueRound) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("spokes", spokes).kv("nextId", nextId);
        w.arr("wheels");
        for(Wheel x : wheels.values())
            w.obj().kv("id", x.id).kv("k", x.kind).kv("db", x.daysOnBench)
              .kv("d", x.done).kv("t", x.trueRound).kv("wb", x.wobbly).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            spokes = (int)n.flt("spokes", spokes);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> ws = n.list("wheels");
            if(ws == null) return;
            for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Wheel x = new Wheel(nextIdFor(m.get("id")), Json.str(m.get("k"), "?"));
                x.daysOnBench = (int)Json.num(m.get("db"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.trueRound = Json.bool(m.get("t"), false);
                x.wobbly = Json.bool(m.get("wb"), false);
                wheels.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
