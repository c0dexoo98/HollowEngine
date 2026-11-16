package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v16.0 — cooper system (HollowEngine 16.0 'turns & rounds').
 *  The shop raises casks from a stave stock (three staves paid up
 *  front, two days at the bench), then the leak test rolls once —
 *  90% tight, the rest leak (hooped anyway, marked honestly).
 */
public final class CooperSystem {

    public static final int STAVES_PER_CASK = 3;
    public static final int RAISE_DAYS = 2;

    public static final class Cask {
        public final int id;
        public final String kind;
        public int daysRaised = 0;
        public boolean done = false;
        public boolean tight = false;
        public boolean leaks = false;
        public Cask(int id, String kind){
            this.id = id; this.kind = kind;
        }
    }

    public interface Listener { void onShopEvent(String type, int caskId, String detail); }
    public Listener listener;

    public final Map<Integer, Cask> casks = new LinkedHashMap<>();
    public int staves = 0;
    public long seed = 0xC01E1L;
    public int day = 0;
    private int nextId = 1;

    /** stock the shop with staves */
    public boolean deliverStaves(int units){
        if(units <= 0) return false;
        staves += units;
        return true;
    }

    /** raise a cask; staves are paid up front */
    public Cask raise(String kind){
        if(kind == null || kind.isEmpty() || staves < STAVES_PER_CASK) return null;
        staves -= STAVES_PER_CASK;
        Cask c = new Cask(nextId++, kind);
        casks.put(c.id, c);
        if(listener != null) listener.onShopEvent("raised", c.id, kind);
        return c;
    }

    /** daily tick: bench days pass; raised casks roll the leak test once */
    public void tickDay(){
        day++;
        for(Cask c : casks.values()){
            if(c.done) continue;
            c.daysRaised++;
            if(c.daysRaised < RAISE_DAYS) continue;
            c.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (c.id * 7919L) ^ (day * 131L));
            c.tight = rng.range(0f, 1f) < 0.9f;
            c.leaks = !c.tight;
            if(listener != null) listener.onShopEvent(c.tight ? "tight" : "leaks", c.id, c.kind);
        }
    }

    public Cask cask(int id){ return casks.get(id); }
    public int tightCount(){
        int n = 0;
        for(Cask c : casks.values()) if(c.done && c.tight) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("staves", staves).kv("nextId", nextId);
        w.arr("casks");
        for(Cask c : casks.values())
            w.obj().kv("id", c.id).kv("k", c.kind).kv("dr", c.daysRaised)
              .kv("d", c.done).kv("t", c.tight).kv("l", c.leaks).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            staves = (int)n.flt("staves", staves);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> cs = n.list("casks");
            if(cs == null) return;
            for(Object o : cs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Cask c = new Cask(nextIdFor(m.get("id")), Json.str(m.get("k"), "?"));
                c.daysRaised = (int)Json.num(m.get("dr"), 0);
                c.done = Json.bool(m.get("d"), false);
                c.tight = Json.bool(m.get("t"), false);
                c.leaks = Json.bool(m.get("l"), false);
                casks.put(c.id, c);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
