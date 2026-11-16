package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v19.0 — dyer system (HollowEngine 19.0 'spindles & shuttles').
 *  The vat dips one ell of cloth per job (paid up front, two days in
 *  the vat), then the shade test rolls once — 90% the true shade,
 *  10% a muddy dip (kept in the ledger, flagged honestly).
 */
public final class DyerSystem {

    public static final int ELLS_PER_DIP = 1;
    public static final int DYE_DAYS = 2;
    public static final int ELLS_CAP = 10;

    public static final class Vat {
        public final int id;
        public final String shade;
        public int daysDipped = 0;
        public boolean done = false;
        public boolean trueShade = false;
        public boolean muddy = false;
        public Vat(int id, String shade){
            this.id = id; this.shade = shade;
        }
    }

    public interface Listener { void onVatEvent(String type, int vatId, String detail); }
    public Listener listener;

    public final Map<Integer, Vat> vats = new LinkedHashMap<>();
    public int ells = 0;
    public long seed = 0xD3L;
    public int day = 0;
    private int nextId = 1;

    /** stock the drying rack with cloth ells */
    public boolean deliverElls(int units){
        if(units <= 0 || ells + units > ELLS_CAP) return false;
        ells += units;
        return true;
    }

    /** start a dip; the ell is paid up front */
    public Vat dip(String shade){
        if(shade == null || shade.isEmpty() || ells < ELLS_PER_DIP) return null;
        ells -= ELLS_PER_DIP;
        Vat v = new Vat(nextId++, shade);
        vats.put(v.id, v);
        if(listener != null) listener.onVatEvent("started", v.id, shade);
        return v;
    }

    /** daily tick: vat days pass; finished dips roll the shade test once */
    public void tickDay(){
        day++;
        for(Vat v : vats.values()){
            if(v.done) continue;
            v.daysDipped++;
            if(v.daysDipped < DYE_DAYS) continue;
            v.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (v.id * 7919L) ^ (day * 131L));
            v.trueShade = rng.range(0f, 1f) < 0.9f;
            v.muddy = !v.trueShade;
            if(listener != null) listener.onVatEvent(v.trueShade ? "true" : "muddy", v.id, v.shade);
        }
    }

    public Vat vat(int id){ return vats.get(id); }
    public int trueCount(){
        int n = 0;
        for(Vat v : vats.values()) if(v.done && v.trueShade) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("ells", ells).kv("nextId", nextId);
        w.arr("vats");
        for(Vat x : vats.values())
            w.obj().kv("id", x.id).kv("s", x.shade).kv("dd", x.daysDipped)
              .kv("d", x.done).kv("t", x.trueShade).kv("m", x.muddy).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            ells = MathX.clamp((int)n.flt("ells", ells), 0, ELLS_CAP);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> vs = n.list("vats");
            if(vs == null) return;
            for(Object o : vs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Vat x = new Vat(nextIdFor(m.get("id")), Json.str(m.get("s"), "?"));
                x.daysDipped = (int)Json.num(m.get("dd"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.trueShade = Json.bool(m.get("t"), false);
                x.muddy = Json.bool(m.get("m"), false);
                vats.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
