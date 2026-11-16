package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v16.0 — ropemaker system (HollowEngine 16.0 'turns & rounds').
 *  The rope walk twists coils from a hemp stock (two lengths paid up
 *  front, three days on the walk), then the strength test rolls once
 *  — 85% holds fast, the rest frays and is marked honestly.
 */
public final class RopemakerSystem {

    public static final int HEMP_PER_COIL = 2;
    public static final int WALK_DAYS = 3;

    public static final class Coil {
        public final int id;
        public final String ropeName;
        public int daysOnWalk = 0;
        public boolean done = false;
        public boolean holdsFast = false;
        public boolean frayed = false;
        public Coil(int id, String ropeName){
            this.id = id; this.ropeName = ropeName;
        }
    }

    public interface Listener { void onWalkEvent(String type, int coilId, String detail); }
    public Listener listener;

    public final Map<Integer, Coil> coils = new LinkedHashMap<>();
    public int hemp = 0;
    public long seed = 0x11B1L;
    public int day = 0;
    private int nextId = 1;

    /** stock the walk with hemp */
    public boolean deliverHemp(int lengths){
        if(lengths <= 0) return false;
        hemp += lengths;
        return true;
    }

    /** start a coil; hemp is paid up front */
    public Coil lay(String ropeName){
        if(ropeName == null || ropeName.isEmpty() || hemp < HEMP_PER_COIL) return null;
        hemp -= HEMP_PER_COIL;
        Coil c = new Coil(nextId++, ropeName);
        coils.put(c.id, c);
        if(listener != null) listener.onWalkEvent("started", c.id, ropeName);
        return c;
    }

    /** daily tick: walk days pass; finished coils roll the strength test once */
    public void tickDay(){
        day++;
        for(Coil c : coils.values()){
            if(c.done) continue;
            c.daysOnWalk++;
            if(c.daysOnWalk < WALK_DAYS) continue;
            c.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (c.id * 7919L) ^ (day * 131L));
            c.holdsFast = rng.range(0f, 1f) < 0.85f;
            c.frayed = !c.holdsFast;
            if(listener != null) listener.onWalkEvent(c.holdsFast ? "holds_fast" : "frayed", c.id, c.ropeName);
        }
    }

    public Coil coil(int id){ return coils.get(id); }
    public int soundCount(){
        int n = 0;
        for(Coil c : coils.values()) if(c.done && c.holdsFast) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("hemp", hemp).kv("nextId", nextId);
        w.arr("coils");
        for(Coil c : coils.values())
            w.obj().kv("id", c.id).kv("n", c.ropeName).kv("dw", c.daysOnWalk)
              .kv("d", c.done).kv("h", c.holdsFast).kv("f", c.frayed).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            hemp = (int)n.flt("hemp", hemp);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> cs = n.list("coils");
            if(cs == null) return;
            for(Object o : cs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Coil c = new Coil(nextIdFor(m.get("id")), Json.str(m.get("n"), "?"));
                c.daysOnWalk = (int)Json.num(m.get("dw"), 0);
                c.done = Json.bool(m.get("d"), false);
                c.holdsFast = Json.bool(m.get("h"), false);
                c.frayed = Json.bool(m.get("f"), false);
                coils.put(c.id, c);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
