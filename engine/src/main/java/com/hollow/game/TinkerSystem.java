package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v18.0 — tinker system (HollowEngine 18.0 'pounds & pages').
 *  The bench mends broken goods from a spare-parts stock (one part
 *  paid up front, two days at the tins), then the mending rolls once
 *  — 95% mended as good as new, 5% beyond saving (kept in the
 *  ledger, marked honestly).
 */
public final class TinkerSystem {

    public static final int PARTS_PER_MEND = 1;
    public static final int MEND_DAYS = 2;
    public static final int PARTS_CAP = 8;

    public static final class Repair {
        public final int id;
        public final String item;
        public int daysMending = 0;
        public boolean done = false;
        public boolean mended = false;
        public boolean beyond = false;
        public Repair(int id, String item){
            this.id = id; this.item = item;
        }
    }

    public interface Listener { void onBenchEvent(String type, int repairId, String detail); }
    public Listener listener;

    public final Map<Integer, Repair> repairs = new LinkedHashMap<>();
    public int parts = 0;
    public long seed = 0x51L;
    public int day = 0;
    private int nextId = 1;

    /** stock the tin with spare parts */
    public boolean deliverParts(int units){
        if(units <= 0 || parts + units > PARTS_CAP) return false;
        parts += units;
        return true;
    }

    /** take a broken item in; the part is paid up front */
    public Repair mend(String item){
        if(item == null || item.isEmpty() || parts < PARTS_PER_MEND) return null;
        parts -= PARTS_PER_MEND;
        Repair r = new Repair(nextId++, item);
        repairs.put(r.id, r);
        if(listener != null) listener.onBenchEvent("started", r.id, item);
        return r;
    }

    /** daily tick: bench days pass; finished repairs roll once */
    public void tickDay(){
        day++;
        for(Repair r : repairs.values()){
            if(r.done) continue;
            r.daysMending++;
            if(r.daysMending < MEND_DAYS) continue;
            r.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (r.id * 7919L) ^ (day * 131L));
            r.mended = rng.range(0f, 1f) < 0.95f;
            r.beyond = !r.mended;
            if(listener != null) listener.onBenchEvent(r.mended ? "mended" : "beyond", r.id, r.item);
        }
    }

    public Repair repair(int id){ return repairs.get(id); }
    public int mendedCount(){
        int n = 0;
        for(Repair r : repairs.values()) if(r.done && r.mended) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("parts", parts).kv("nextId", nextId);
        w.arr("repairs");
        for(Repair x : repairs.values())
            w.obj().kv("id", x.id).kv("i", x.item).kv("dm", x.daysMending)
              .kv("d", x.done).kv("m", x.mended).kv("b", x.beyond).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            parts = MathX.clamp((int)n.flt("parts", parts), 0, PARTS_CAP);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> rs = n.list("repairs");
            if(rs == null) return;
            for(Object o : rs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Repair x = new Repair(nextIdFor(m.get("id")), Json.str(m.get("i"), "?"));
                x.daysMending = (int)Json.num(m.get("dm"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.mended = Json.bool(m.get("m"), false);
                x.beyond = Json.bool(m.get("b"), false);
                repairs.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
