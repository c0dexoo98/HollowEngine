package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v14.0 — glassblower system (HollowEngine 14.0 'watches & works').
 *  The workshop blows batches from a sand stock (one gather per batch,
 *  paid up front); each batch anneals its stated days, then rolls the
 *  kiln once — 85% come out intact, the rest shatter.
 */
public final class GlassblowerSystem {

    public static final class Batch {
        public final int id;
        public final String kind;
        public final int annealDays;
        public int daysInKiln = 0;
        public boolean done = false;
        public boolean shattered = false;
        public Batch(int id, String kind, int annealDays){
            this.id = id; this.kind = kind;
            this.annealDays = Math.max(1, annealDays);
        }
    }

    public interface Listener { void onKilnEvent(String type, int batchId, String detail); }
    public Listener listener;

    public final Map<Integer, Batch> batches = new LinkedHashMap<>();
    public int sand = 0;
    public int pieces = 0;
    public long seed = 0x61A5L;
    public int day = 0;
    private int nextId = 1;

    /** stock the gather pit with sand */
    public boolean deliverSand(int units){
        if(units <= 0) return false;
        sand += units;
        return true;
    }

    /** blow a batch; sand is paid up front (default one gather) */
    public Batch blow(String kind, int annealDays){
        int cost = 1;
        if(kind == null || kind.isEmpty() || annealDays < 1 || sand < cost) return null;
        sand -= cost;
        Batch b = new Batch(nextId++, kind, annealDays);
        batches.put(b.id, b);
        if(listener != null) listener.onKilnEvent("blown", b.id, kind);
        return b;
    }

    /** daily tick: batches anneal; finished ones roll the kiln once */
    public void tickDay(){
        day++;
        for(Batch b : batches.values()){
            if(b.done) continue;
            b.daysInKiln++;
            if(b.daysInKiln < b.annealDays) continue;
            b.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (b.id * 7919L) ^ (day * 131L));
            b.shattered = rng.range(0f, 1f) >= 0.85f;
            if(b.shattered){
                if(listener != null) listener.onKilnEvent("shattered", b.id, b.kind);
            } else {
                pieces++;
                if(listener != null) listener.onKilnEvent("intact", b.id, b.kind);
            }
        }
    }

    public Batch batch(int id){ return batches.get(id); }
    public int intactCount(){ return pieces; }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("sand", sand).kv("pieces", pieces).kv("nextId", nextId);
        w.arr("batches");
        for(Batch b : batches.values())
            w.obj().kv("id", b.id).kv("k", b.kind).kv("ad", b.annealDays)
              .kv("dk", b.daysInKiln).kv("d", b.done).kv("sh", b.shattered).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            sand = (int)n.flt("sand", sand);
            pieces = (int)n.flt("pieces", pieces);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> bs = n.list("batches");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Batch b = new Batch(nextIdFor(m.get("id")),
                    Json.str(m.get("k"), "?"), (int)Json.num(m.get("ad"), 1));
                b.daysInKiln = (int)Json.num(m.get("dk"), 0);
                b.done = Json.bool(m.get("d"), false);
                b.shattered = Json.bool(m.get("sh"), false);
                batches.put(b.id, b);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
