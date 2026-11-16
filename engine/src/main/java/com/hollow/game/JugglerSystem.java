package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v20.0 — juggler system (HollowEngine 20.0 'harps & halls').
 *  One routine per single-day street corner: three to seven props
 *  in the air, then the pattern rolls once — 90% every prop lands
 *  (a clean routine), 10% one drops (counted honestly). No materials
 *  are paid; the tally is the trade.
 */
public final class JugglerSystem {

    public static final int ROUTINE_DAYS = 1;
    public static final int PROPS_MIN = 3;
    public static final int PROPS_MAX = 7;

    public static final class Routine {
        public final int id;
        public final int props;
        public int daysWaited = 0;
        public boolean done = false;
        public boolean clean = false;
        public Routine(int id, int props){
            this.id = id; this.props = props;
        }
    }

    public interface Listener { void onJuggleEvent(String type, int routineId, String detail); }
    public Listener listener;

    public final Map<Integer, Routine> routines = new LinkedHashMap<>();
    public int cleanRuns = 0;
    public int drops = 0;
    public long seed = 0xC0DL;
    public int day = 0;
    private int nextId = 1;

    /** take a street corner; three to seven props in the air */
    public Routine perform(int props){
        if(props < PROPS_MIN || props > PROPS_MAX) return null;
        Routine r = new Routine(nextId++, props);
        routines.put(r.id, r);
        if(listener != null) listener.onJuggleEvent("started", r.id, props + " props");
        return r;
    }

    /** daily tick: routine days pass; finished routines roll the pattern once */
    public void tickDay(){
        day++;
        for(Routine r : routines.values()){
            if(r.done) continue;
            r.daysWaited++;
            if(r.daysWaited < ROUTINE_DAYS) continue;
            r.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (r.id * 7919L) ^ (day * 131L));
            r.clean = rng.range(0f, 1f) < 0.9f;
            if(r.clean) cleanRuns++;
            else drops++;
            if(listener != null) listener.onJuggleEvent(r.clean ? "clean" : "drop", r.id, r.props + " props");
        }
    }

    public Routine routine(int id){ return routines.get(id); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("clean", cleanRuns).kv("drops", drops).kv("nextId", nextId);
        w.arr("routines");
        for(Routine x : routines.values())
            w.obj().kv("id", x.id).kv("p", x.props).kv("dw", x.daysWaited)
              .kv("d", x.done).kv("c", x.clean).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            cleanRuns = (int)n.flt("clean", cleanRuns);
            drops = (int)n.flt("drops", drops);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> rs = n.list("routines");
            if(rs == null) return;
            for(Object o : rs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Routine x = new Routine(nextIdFor(m.get("id")), (int)Json.num(m.get("p"), 3));
                x.daysWaited = (int)Json.num(m.get("dw"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.clean = Json.bool(m.get("c"), false);
                routines.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
