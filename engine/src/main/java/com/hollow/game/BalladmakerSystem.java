package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v20.0 — balladmaker system (HollowEngine 20.0 'harps & halls').
 *  A ballad takes one quire of paper paid up front and three days at
 *  the desk, then the market hall rolls once — 90% a crowd-pleaser
 *  is added to the songbook, 10% a flop (kept in the ledger, flagged
 *  honestly). Quires are stocked from the papermaker's shelf.
 */
public final class BalladmakerSystem {

    public static final int QUIRES_PER_BALLAD = 1;
    public static final int COMPOSE_DAYS = 3;
    public static final int QUIRE_CAP = 12;

    public static final class Ballad {
        public final int id;
        public final String title;
        public int daysWritten = 0;
        public boolean done = false;
        public boolean crowdPleaser = false;
        public boolean flop = false;
        public Ballad(int id, String title){
            this.id = id; this.title = title;
        }
    }

    public interface Listener { void onDeskEvent(String type, int balladId, String detail); }
    public Listener listener;

    public final Map<Integer, Ballad> ballads = new LinkedHashMap<>();
    public int quires = 0;
    public long seed = 0x5EEDL;
    public int day = 0;
    private int nextId = 1;

    /** stock the desk with quires */
    public boolean deliverQuires(int units){
        if(units <= 0 || quires + units > QUIRE_CAP) return false;
        quires += units;
        return true;
    }

    /** start a ballad; the quire is paid up front */
    public Ballad compose(String title){
        if(title == null || title.isEmpty() || quires < QUIRES_PER_BALLAD) return null;
        quires -= QUIRES_PER_BALLAD;
        Ballad b = new Ballad(nextId++, title);
        ballads.put(b.id, b);
        if(listener != null) listener.onDeskEvent("started", b.id, title);
        return b;
    }

    /** daily tick: desk days pass; finished ballads roll the market once */
    public void tickDay(){
        day++;
        for(Ballad b : ballads.values()){
            if(b.done) continue;
            b.daysWritten++;
            if(b.daysWritten < COMPOSE_DAYS) continue;
            b.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (b.id * 7919L) ^ (day * 131L));
            b.crowdPleaser = rng.range(0f, 1f) < 0.9f;
            b.flop = !b.crowdPleaser;
            if(listener != null) listener.onDeskEvent(b.crowdPleaser ? "pleaser" : "flop", b.id, b.title);
        }
    }

    public Ballad ballad(int id){ return ballads.get(id); }
    public int pleaserCount(){
        int n = 0;
        for(Ballad b : ballads.values()) if(b.done && b.crowdPleaser) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("quires", quires).kv("nextId", nextId);
        w.arr("ballads");
        for(Ballad x : ballads.values())
            w.obj().kv("id", x.id).kv("t", x.title).kv("dw", x.daysWritten)
              .kv("d", x.done).kv("p", x.crowdPleaser).kv("f", x.flop).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            quires = MathX.clamp((int)n.flt("quires", quires), 0, QUIRE_CAP);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> bs = n.list("ballads");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Ballad x = new Ballad(nextIdFor(m.get("id")), Json.str(m.get("t"), "?"));
                x.daysWritten = (int)Json.num(m.get("dw"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.crowdPleaser = Json.bool(m.get("p"), false);
                x.flop = Json.bool(m.get("f"), false);
                ballads.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
