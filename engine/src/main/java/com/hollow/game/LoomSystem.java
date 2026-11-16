package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v19.0 — loom system (HollowEngine 19.0 'spindles & shuttles').
 *  The loom weaves skeins into cloth (five skeins paid up front,
 *  three days at the loom), then the selvedge check rolls once —
 *  90% two sound ells, 10% a mispick and only one flawed ell
 *  (counted in the mispicks ledger).
 */
public final class LoomSystem {

    public static final int SKEINS_PER_CLOTH = 5;
    public static final int WEAVE_DAYS = 3;
    public static final int ELLS_SOUND = 2;
    public static final int ELLS_FLAWED = 1;
    public static final int SKEIN_CAP = 20;

    public static final class Run {
        public final int id;
        public final String color;
        public int daysWoven = 0;
        public boolean done = false;
        public boolean mispicked = false;
        public Run(int id, String color){
            this.id = id; this.color = color;
        }
    }

    public interface Listener { void onLoomEvent(String type, int runId, String detail); }
    public Listener listener;

    public final Map<Integer, Run> runs = new LinkedHashMap<>();
    public int skeins = 0;
    public int ells = 0;
    public int mispicks = 0;
    public long seed = 0x0B0L;
    public int day = 0;
    private int nextId = 1;

    /** stock the rack with skeins */
    public boolean deliverSkeins(int units){
        if(units <= 0 || skeins + units > SKEIN_CAP) return false;
        skeins += units;
        return true;
    }

    /** start a weave run; skeins are paid up front */
    public Run weave(String color){
        if(color == null || color.isEmpty() || skeins < SKEINS_PER_CLOTH) return null;
        skeins -= SKEINS_PER_CLOTH;
        Run r = new Run(nextId++, color);
        runs.put(r.id, r);
        if(listener != null) listener.onLoomEvent("started", r.id, color);
        return r;
    }

    /** daily tick: loom days pass; finished runs roll the selvedge check once */
    public void tickDay(){
        day++;
        for(Run r : runs.values()){
            if(r.done) continue;
            r.daysWoven++;
            if(r.daysWoven < WEAVE_DAYS) continue;
            r.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (r.id * 7919L) ^ (day * 131L));
            r.mispicked = rng.range(0f, 1f) < 0.1f;
            int cut = r.mispicked ? ELLS_FLAWED : ELLS_SOUND;
            ells += cut;
            if(r.mispicked) mispicks++;
            if(listener != null) listener.onLoomEvent(r.mispicked ? "mispick" : "cut", r.id, r.color);
        }
    }

    public Run run(int id){ return runs.get(id); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("skeins", skeins).kv("ells", ells)
          .kv("mispicks", mispicks).kv("nextId", nextId);
        w.arr("runs");
        for(Run x : runs.values())
            w.obj().kv("id", x.id).kv("c", x.color).kv("dw", x.daysWoven)
              .kv("d", x.done).kv("m", x.mispicked).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            skeins = MathX.clamp((int)n.flt("skeins", skeins), 0, SKEIN_CAP);
            ells = (int)n.flt("ells", ells);
            mispicks = (int)n.flt("mispicks", mispicks);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> rs = n.list("runs");
            if(rs == null) return;
            for(Object o : rs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Run x = new Run(nextIdFor(m.get("id")), Json.str(m.get("c"), "?"));
                x.daysWoven = (int)Json.num(m.get("dw"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.mispicked = Json.bool(m.get("m"), false);
                runs.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
