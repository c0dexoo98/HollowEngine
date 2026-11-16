package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v20.0 — fiddler system (HollowEngine 20.0 'harps & halls').
 *  One dance night per single-day booking, then the strings roll
 *  once — 90% the floor stays full (eight dancers on the tally),
 *  10% a string snaps and only two keep dancing (counted in the
 *  snaps ledger).
 */
public final class FiddlerSystem {

    public static final int NIGHT_DAYS = 1;
    public static final int DANCERS_FULL = 8;
    public static final int DANCERS_SNAP = 2;

    public static final class Night {
        public final int id;
        public final String tune;
        public int daysWaited = 0;
        public boolean done = false;
        public boolean snapped = false;
        public Night(int id, String tune){
            this.id = id; this.tune = tune;
        }
    }

    public interface Listener { void onFiddleEvent(String type, int nightId, String detail); }
    public Listener listener;

    public final Map<Integer, Night> nights = new LinkedHashMap<>();
    public int dancers = 0;
    public int snaps = 0;
    public long seed = 0xBEAL;
    public int day = 0;
    private int nextId = 1;

    /** book a dance night */
    public Night bookNight(String tune){
        if(tune == null || tune.isEmpty()) return null;
        Night n = new Night(nextId++, tune);
        nights.put(n.id, n);
        if(listener != null) listener.onFiddleEvent("booked", n.id, tune);
        return n;
    }

    /** daily tick: night days pass; finished nights roll the strings once */
    public void tickDay(){
        day++;
        for(Night n : nights.values()){
            if(n.done) continue;
            n.daysWaited++;
            if(n.daysWaited < NIGHT_DAYS) continue;
            n.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (n.id * 7919L) ^ (day * 131L));
            n.snapped = rng.range(0f, 1f) < 0.1f;
            dancers += n.snapped ? DANCERS_SNAP : DANCERS_FULL;
            if(n.snapped) snaps++;
            if(listener != null) listener.onFiddleEvent(n.snapped ? "snap" : "full", n.id, n.tune);
        }
    }

    public Night night(int id){ return nights.get(id); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("dancers", dancers).kv("snaps", snaps).kv("nextId", nextId);
        w.arr("nights");
        for(Night x : nights.values())
            w.obj().kv("id", x.id).kv("t", x.tune).kv("dw", x.daysWaited)
              .kv("d", x.done).kv("s", x.snapped).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            dancers = (int)n.flt("dancers", dancers);
            snaps = (int)n.flt("snaps", snaps);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> ns = n.list("nights");
            if(ns == null) return;
            for(Object o : ns){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Night x = new Night(nextIdFor(m.get("id")), Json.str(m.get("t"), "?"));
                x.daysWaited = (int)Json.num(m.get("dw"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.snapped = Json.bool(m.get("s"), false);
                nights.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
