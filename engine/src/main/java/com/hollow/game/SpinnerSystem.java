package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v19.0 — spinner system (HollowEngine 19.0 'spindles & shuttles').
 *  The wheel spins fleece into thread (two fleeces paid up front,
 *  two days at the wheel), then the skeining rolls once — 90% a full
 *  draw of five skeins, 10% the thread breaks and only two skeins
 *  survive (counted in the breaks ledger).
 */
public final class SpinnerSystem {

    public static final int FLEECES_PER_SPIN = 2;
    public static final int SPIN_DAYS = 2;
    public static final int SKEINS_CLEAN = 5;
    public static final int SKEINS_BROKEN = 2;
    public static final int FLEECE_CAP = 16;

    public static final class Batch {
        public final int id;
        public final String fleeceGrade;
        public int daysSpun = 0;
        public boolean done = false;
        public boolean broken = false;
        public Batch(int id, String fleeceGrade){
            this.id = id; this.fleeceGrade = fleeceGrade;
        }
    }

    public interface Listener { void onWheelEvent(String type, int batchId, String detail); }
    public Listener listener;

    public final Map<Integer, Batch> batches = new LinkedHashMap<>();
    public int fleeces = 0;
    public int skeins = 0;
    public int breaks = 0;
    public long seed = 0x27L;
    public int day = 0;
    private int nextId = 1;

    /** stock the loft with fleeces */
    public boolean deliverFleeces(int units){
        if(units <= 0 || fleeces + units > FLEECE_CAP) return false;
        fleeces += units;
        return true;
    }

    /** start a spin batch; fleeces are paid up front */
    public Batch spin(String fleeceGrade){
        if(fleeceGrade == null || fleeceGrade.isEmpty() || fleeces < FLEECES_PER_SPIN) return null;
        fleeces -= FLEECES_PER_SPIN;
        Batch b = new Batch(nextId++, fleeceGrade);
        batches.put(b.id, b);
        if(listener != null) listener.onWheelEvent("started", b.id, fleeceGrade);
        return b;
    }

    /** daily tick: wheel days pass; finished batches roll the skeining once */
    public void tickDay(){
        day++;
        for(Batch b : batches.values()){
            if(b.done) continue;
            b.daysSpun++;
            if(b.daysSpun < SPIN_DAYS) continue;
            b.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (b.id * 7919L) ^ (day * 131L));
            b.broken = rng.range(0f, 1f) < 0.1f;
            int drawn = b.broken ? SKEINS_BROKEN : SKEINS_CLEAN;
            skeins += drawn;
            if(b.broken) breaks++;
            if(listener != null) listener.onWheelEvent(b.broken ? "broken" : "drawn", b.id, b.fleeceGrade);
        }
    }

    public Batch batch(int id){ return batches.get(id); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("fleeces", fleeces).kv("skeins", skeins)
          .kv("breaks", breaks).kv("nextId", nextId);
        w.arr("batches");
        for(Batch x : batches.values())
            w.obj().kv("id", x.id).kv("g", x.fleeceGrade).kv("ds", x.daysSpun)
              .kv("d", x.done).kv("br", x.broken).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            fleeces = MathX.clamp((int)n.flt("fleeces", fleeces), 0, FLEECE_CAP);
            skeins = (int)n.flt("skeins", skeins);
            breaks = (int)n.flt("breaks", breaks);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> bs = n.list("batches");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Batch x = new Batch(nextIdFor(m.get("id")), Json.str(m.get("g"), "?"));
                x.daysSpun = (int)Json.num(m.get("ds"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.broken = Json.bool(m.get("br"), false);
                batches.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
