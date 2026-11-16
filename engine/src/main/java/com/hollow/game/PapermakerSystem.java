package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v18.0 — papermaker system (HollowEngine 18.0 'pounds & pages').
 *  The vat pulps rag batches into quires (three rags paid up front,
 *  two days in the vat), then the drying loft rolls once — 90% a
 *  clean pull of four quires, 10% mold spots on the sheets and only
 *  one saleable quire (both ledgers kept honestly).
 */
public final class PapermakerSystem {

    public static final int RAGS_PER_BATCH = 3;
    public static final int PULP_DAYS = 2;
    public static final int QUIRES_CLEAN = 4;
    public static final int QUIRES_MOLDY = 1;
    public static final int RAGS_CAP = 20;

    public static final class Batch {
        public final int id;
        public final String ragKind;
        public int daysPulped = 0;
        public boolean done = false;
        public boolean moldy = false;
        public Batch(int id, String ragKind){
            this.id = id; this.ragKind = ragKind;
        }
    }

    public interface Listener { void onVatEvent(String type, int batchId, String detail); }
    public Listener listener;

    public final Map<Integer, Batch> batches = new LinkedHashMap<>();
    public int rags = 0;
    public int quires = 0;
    public int molds = 0;
    public long seed = 0x8A0L;
    public int day = 0;
    private int nextId = 1;

    /** stock the loft with rags */
    public boolean deliverRags(int units){
        if(units <= 0 || rags + units > RAGS_CAP) return false;
        rags += units;
        return true;
    }

    /** start a pulp batch; rags are paid up front */
    public Batch make(String ragKind){
        if(ragKind == null || ragKind.isEmpty() || rags < RAGS_PER_BATCH) return null;
        rags -= RAGS_PER_BATCH;
        Batch b = new Batch(nextId++, ragKind);
        batches.put(b.id, b);
        if(listener != null) listener.onVatEvent("started", b.id, ragKind);
        return b;
    }

    /** daily tick: vat days pass; finished batches roll the loft once */
    public void tickDay(){
        day++;
        for(Batch b : batches.values()){
            if(b.done) continue;
            b.daysPulped++;
            if(b.daysPulped < PULP_DAYS) continue;
            b.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (b.id * 7919L) ^ (day * 131L));
            b.moldy = rng.range(0f, 1f) < 0.1f;
            int pulled = b.moldy ? QUIRES_MOLDY : QUIRES_CLEAN;
            quires += pulled;
            if(b.moldy) molds++;
            if(listener != null) listener.onVatEvent(b.moldy ? "mold" : "pulled", b.id, b.ragKind);
        }
    }

    public Batch batch(int id){ return batches.get(id); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("rags", rags).kv("quires", quires)
          .kv("molds", molds).kv("nextId", nextId);
        w.arr("batches");
        for(Batch x : batches.values())
            w.obj().kv("id", x.id).kv("k", x.ragKind).kv("dp", x.daysPulped)
              .kv("d", x.done).kv("mo", x.moldy).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            rags = MathX.clamp((int)n.flt("rags", rags), 0, RAGS_CAP);
            quires = (int)n.flt("quires", quires);
            molds = (int)n.flt("molds", molds);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> bs = n.list("batches");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Batch x = new Batch(nextIdFor(m.get("id")), Json.str(m.get("k"), "?"));
                x.daysPulped = (int)Json.num(m.get("dp"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.moldy = Json.bool(m.get("mo"), false);
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
