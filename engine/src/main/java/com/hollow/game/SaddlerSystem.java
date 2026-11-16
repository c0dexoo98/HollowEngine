package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v17.0 — saddler system (HollowEngine 17.0 'banks & benches').
 *  The bench stitches saddles from a leather stock (two hides paid
 *  up front, two days at the awl), then the fitting rolls once —
 *  90% fits the rider, 10% pinches (kept in the ledger, marked
 *  honestly).
 */
public final class SaddlerSystem {

    public static final int HIDES_PER_SADDLE = 2;
    public static final int STITCH_DAYS = 2;

    public static final class Saddle {
        public final int id;
        public final String kind;
        public int daysStitched = 0;
        public boolean done = false;
        public boolean fits = false;
        public boolean pinches = false;
        public Saddle(int id, String kind){
            this.id = id; this.kind = kind;
        }
    }

    public interface Listener { void onBenchEvent(String type, int saddleId, String detail); }
    public Listener listener;

    public final Map<Integer, Saddle> saddles = new LinkedHashMap<>();
    public int leather = 0;
    public long seed = 0x5A11L;
    public int day = 0;
    private int nextId = 1;

    /** stock the bench with hides */
    public boolean deliverLeather(int hides){
        if(hides <= 0) return false;
        leather += hides;
        return true;
    }

    /** start a saddle; hides are paid up front */
    public Saddle build(String kind){
        if(kind == null || kind.isEmpty() || leather < HIDES_PER_SADDLE) return null;
        leather -= HIDES_PER_SADDLE;
        Saddle s = new Saddle(nextId++, kind);
        saddles.put(s.id, s);
        if(listener != null) listener.onBenchEvent("started", s.id, kind);
        return s;
    }

    /** daily tick: bench days pass; finished saddles roll the fitting once */
    public void tickDay(){
        day++;
        for(Saddle s : saddles.values()){
            if(s.done) continue;
            s.daysStitched++;
            if(s.daysStitched < STITCH_DAYS) continue;
            s.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (s.id * 7919L) ^ (day * 131L));
            s.fits = rng.range(0f, 1f) < 0.9f;
            s.pinches = !s.fits;
            if(listener != null) listener.onBenchEvent(s.fits ? "fits" : "pinches", s.id, s.kind);
        }
    }

    public Saddle saddle(int id){ return saddles.get(id); }
    public int fitCount(){
        int n = 0;
        for(Saddle s : saddles.values()) if(s.done && s.fits) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("leather", leather).kv("nextId", nextId);
        w.arr("saddles");
        for(Saddle x : saddles.values())
            w.obj().kv("id", x.id).kv("k", x.kind).kv("ds", x.daysStitched)
              .kv("d", x.done).kv("f", x.fits).kv("p", x.pinches).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            leather = (int)n.flt("leather", leather);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> ss = n.list("saddles");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Saddle x = new Saddle(nextIdFor(m.get("id")), Json.str(m.get("k"), "?"));
                x.daysStitched = (int)Json.num(m.get("ds"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.fits = Json.bool(m.get("f"), false);
                x.pinches = Json.bool(m.get("p"), false);
                saddles.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
