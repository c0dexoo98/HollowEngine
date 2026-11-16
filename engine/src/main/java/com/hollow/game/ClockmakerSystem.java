package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v17.0 — clockmaker system (HollowEngine 17.0 'banks & benches').
 *  The workshop assembles clocks from a gear stock (four gears paid
 *  up front, three days at the bench), then the regulator rolls once
 *  — 90% keeps true time, 10% drifts (cased anyway, marked
 *  honestly).
 */
public final class ClockmakerSystem {

    public static final int GEARS_PER_CLOCK = 4;
    public static final int ASSEMBLY_DAYS = 3;
    public static final int GEAR_CAP = 20;

    public static final class Clock {
        public final int id;
        public final String name;
        public int daysAssembled = 0;
        public boolean done = false;
        public boolean keepsTrue = false;
        public boolean drifts = false;
        public Clock(int id, String name){
            this.id = id; this.name = name;
        }
    }

    public interface Listener { void onBenchEvent(String type, int clockId, String detail); }
    public Listener listener;

    public final Map<Integer, Clock> clocks = new LinkedHashMap<>();
    public int gears = 0;
    public long seed = 0x10A1L;
    public int day = 0;
    private int nextId = 1;

    /** stock the drawer with gears */
    public boolean deliverGears(int units){
        if(units <= 0 || gears + units > GEAR_CAP) return false;
        gears += units;
        return true;
    }

    /** start a clock; gears are paid up front */
    public Clock assemble(String name){
        if(name == null || name.isEmpty() || gears < GEARS_PER_CLOCK) return null;
        gears -= GEARS_PER_CLOCK;
        Clock c = new Clock(nextId++, name);
        clocks.put(c.id, c);
        if(listener != null) listener.onBenchEvent("started", c.id, name);
        return c;
    }

    /** daily tick: bench days pass; finished clocks roll the regulator once */
    public void tickDay(){
        day++;
        for(Clock c : clocks.values()){
            if(c.done) continue;
            c.daysAssembled++;
            if(c.daysAssembled < ASSEMBLY_DAYS) continue;
            c.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (c.id * 7919L) ^ (day * 131L));
            c.keepsTrue = rng.range(0f, 1f) < 0.9f;
            c.drifts = !c.keepsTrue;
            if(listener != null) listener.onBenchEvent(c.keepsTrue ? "true_time" : "drifts", c.id, c.name);
        }
    }

    public Clock clock(int id){ return clocks.get(id); }
    public int trueCount(){
        int n = 0;
        for(Clock c : clocks.values()) if(c.done && c.keepsTrue) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("gears", gears).kv("nextId", nextId);
        w.arr("clocks");
        for(Clock x : clocks.values())
            w.obj().kv("id", x.id).kv("n", x.name).kv("da", x.daysAssembled)
              .kv("d", x.done).kv("t", x.keepsTrue).kv("dr", x.drifts).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            gears = MathX.clamp((int)n.flt("gears", gears), 0, GEAR_CAP);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> cs = n.list("clocks");
            if(cs == null) return;
            for(Object o : cs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Clock x = new Clock(nextIdFor(m.get("id")), Json.str(m.get("n"), "?"));
                x.daysAssembled = (int)Json.num(m.get("da"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.keepsTrue = Json.bool(m.get("t"), false);
                x.drifts = Json.bool(m.get("dr"), false);
                clocks.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
