package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v16.0 — lapidary system (HollowEngine 16.0 'turns & rounds').
 *  The workshop cuts rough stones from a stock (one stone paid up
 *  front, two days on the wheel), then the cleave rolls once — 80% a
 *  brilliant, the rest flawed (both enter the ledger, marked
 *  honestly).
 */
public final class LapidarySystem {

    public static final int CUT_DAYS = 2;

    public static final class Gem {
        public final int id;
        public final String stoneName;
        public int daysOnWheel = 0;
        public boolean done = false;
        public boolean brilliant = false;
        public boolean flawed = false;
        public Gem(int id, String stoneName){
            this.id = id; this.stoneName = stoneName;
        }
    }

    public interface Listener { void onWheelEvent(String type, int gemId, String detail); }
    public Listener listener;

    public final Map<Integer, Gem> gems = new LinkedHashMap<>();
    public int rough = 0;
    public long seed = 0x1A2B3L;
    public int day = 0;
    private int nextId = 1;

    /** stock the tray with rough stones */
    public boolean deliverRough(int stones){
        if(stones <= 0) return false;
        rough += stones;
        return true;
    }

    /** start a cut; the stone is paid up front */
    public Gem cut(String stoneName){
        if(stoneName == null || stoneName.isEmpty() || rough < 1) return null;
        rough--;
        Gem g = new Gem(nextId++, stoneName);
        gems.put(g.id, g);
        if(listener != null) listener.onWheelEvent("started", g.id, stoneName);
        return g;
    }

    /** daily tick: wheel days pass; finished cuts roll the cleave once */
    public void tickDay(){
        day++;
        for(Gem g : gems.values()){
            if(g.done) continue;
            g.daysOnWheel++;
            if(g.daysOnWheel < CUT_DAYS) continue;
            g.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (g.id * 7919L) ^ (day * 131L));
            g.brilliant = rng.range(0f, 1f) < 0.8f;
            g.flawed = !g.brilliant;
            if(listener != null) listener.onWheelEvent(g.brilliant ? "brilliant" : "flawed", g.id, g.stoneName);
        }
    }

    public Gem gem(int id){ return gems.get(id); }
    public int brilliantCount(){
        int n = 0;
        for(Gem g : gems.values()) if(g.done && g.brilliant) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("rough", rough).kv("nextId", nextId);
        w.arr("gems");
        for(Gem g : gems.values())
            w.obj().kv("id", g.id).kv("n", g.stoneName).kv("dw", g.daysOnWheel)
              .kv("d", g.done).kv("br", g.brilliant).kv("fl", g.flawed).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            rough = (int)n.flt("rough", rough);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> gs = n.list("gems");
            if(gs == null) return;
            for(Object o : gs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Gem g = new Gem(nextIdFor(m.get("id")), Json.str(m.get("n"), "?"));
                g.daysOnWheel = (int)Json.num(m.get("dw"), 0);
                g.done = Json.bool(m.get("d"), false);
                g.brilliant = Json.bool(m.get("br"), false);
                g.flawed = Json.bool(m.get("fl"), false);
                gems.put(g.id, g);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
