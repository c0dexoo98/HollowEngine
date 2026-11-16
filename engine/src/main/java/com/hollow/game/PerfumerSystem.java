package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v15.0 — perfumer system (HollowEngine 15.0 'trade & tide').
 *  The still house holds petals; each distillation takes three petals
 *  paid up front and two days in the still, then the decanting rolls
 *  once — 90% a true scent, the rest an off batch (bottled anyway and
 *  marked honestly). Ready essences fade after ten days on the shelf.
 */
public final class PerfumerSystem {

    public static final int PETALS_PER_BATCH = 3;
    public static final int STILL_DAYS = 2;
    public static final int SHELF_DAYS = 10;

    public static final class Essence {
        public final int id;
        public final String scent;
        public int daysInStill = 0;
        public int daysOnShelf = 0;
        public boolean ready = false;
        public boolean faded = false;
        public boolean offBatch = false;
        public Essence(int id, String scent){
            this.id = id; this.scent = scent;
        }
    }

    public interface Listener { void onStillEvent(String type, int essenceId, String detail); }
    public Listener listener;

    public final Map<Integer, Essence> essences = new LinkedHashMap<>();
    public int petals = 0;
    public long seed = 0xF1L;
    public int day = 0;
    private int nextId = 1;

    /** stock the still house with petals */
    public boolean deliverPetals(int units){
        if(units <= 0) return false;
        petals += units;
        return true;
    }

    /** start a distillation; petals are paid up front */
    public Essence distill(String scent){
        if(scent == null || scent.isEmpty() || petals < PETALS_PER_BATCH) return null;
        petals -= PETALS_PER_BATCH;
        Essence e = new Essence(nextId++, scent);
        essences.put(e.id, e);
        if(listener != null) listener.onStillEvent("started", e.id, scent);
        return e;
    }

    /** daily tick: stills run; ready essences age toward fading */
    public void tickDay(){
        day++;
        for(Essence e : essences.values()){
            if(!e.ready){
                e.daysInStill++;
                if(e.daysInStill >= STILL_DAYS){
                    e.ready = true;
                    MathX.Rng rng = new MathX.Rng(seed ^ (e.id * 7919L) ^ (day * 131L));
                    e.offBatch = rng.range(0f, 1f) >= 0.9f;
                    if(listener != null) listener.onStillEvent(e.offBatch ? "off_batch" : "decanted", e.id, e.scent);
                }
            } else if(!e.faded){
                e.daysOnShelf++;
                if(e.daysOnShelf >= SHELF_DAYS){
                    e.faded = true;
                    if(listener != null) listener.onStillEvent("faded", e.id, e.scent);
                }
            }
        }
    }

    public Essence essence(int id){ return essences.get(id); }
    public int batchCount(){
        int n = 0;
        for(Essence e : essences.values()) if(e.ready) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("petals", petals).kv("nextId", nextId);
        w.arr("essences");
        for(Essence e : essences.values())
            w.obj().kv("id", e.id).kv("s", e.scent).kv("ds", e.daysInStill)
              .kv("dsh", e.daysOnShelf).kv("r", e.ready).kv("fd", e.faded)
              .kv("ob", e.offBatch).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            petals = (int)n.flt("petals", petals);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> es = n.list("essences");
            if(es == null) return;
            for(Object o : es){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Essence e = new Essence(nextIdFor(m.get("id")), Json.str(m.get("s"), "?"));
                e.daysInStill = (int)Json.num(m.get("ds"), 0);
                e.daysOnShelf = (int)Json.num(m.get("dsh"), 0);
                e.ready = Json.bool(m.get("r"), false);
                e.faded = Json.bool(m.get("fd"), false);
                e.offBatch = Json.bool(m.get("ob"), false);
                essences.put(e.id, e);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
