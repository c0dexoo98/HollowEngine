package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v13.0 — brewery system (HollowEngine 13.0 'ritual & remedy').
 *  Grain goes into fermenting batches; each batch needs its fermenting
 *  days before tapping, and a bad roll skunks the whole cask (nothing
 *  bottled, event fired). Grain is paid up front.
 */
public final class BrewerySystem {

    public static final int CASK_SIZE = 10;          // ales per good cask

    public static final class Cask {
        public final int id;
        public final String ale;
        public final int fermentDays;
        public int daysLeft;
        public boolean tapped = false;
        public boolean skunked = false;
        public Cask(int id, String ale, int fermentDays){
            this.id = id; this.ale = ale;
            this.fermentDays = Math.max(1, fermentDays);
            this.daysLeft = this.fermentDays;
        }
    }

    public interface Listener { void onBrewEvent(String type, int caskId, String detail); }
    public Listener listener;

    public final Map<Integer, Cask> casks = new LinkedHashMap<>();
    public int grain = 0;
    public int ales = 0;
    public long seed = 0xB27EL;
    public int day = 0;
    private int nextId = 1;

    /** stock the granary-fed mash tun */
    public boolean deliverGrain(int units){
        if(units <= 0) return false;
        grain += units;
        return true;
    }

    /** pitch a batch; grain is paid up front */
    public Cask brew(String ale, int grainCost){
        int cost = grainCost <= 0 ? 3 : grainCost;
        if(ale == null || ale.isEmpty() || grain < cost) return null;
        grain -= cost;
        Cask c = new Cask(nextId++, ale, 4);
        casks.put(c.id, c);
        if(listener != null) listener.onBrewEvent("pitched", c.id, ale);
        return c;
    }

    /** daily tick: fermentation, tapping */
    public void tickDay(){
        day++;
        List<Integer> ready = new ArrayList<>();
        for(Cask c : casks.values()){
            if(c.tapped) continue;
            c.daysLeft--;
            if(c.daysLeft <= 0) ready.add(c.id);
        }
        for(Integer id : ready){
            Cask c = casks.get(id);
            c.tapped = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (c.id * 7919L) ^ (day * 131L));
            c.skunked = rng.range(0f, 1f) >= 0.9f;
            if(c.skunked){
                if(listener != null) listener.onBrewEvent("skunked", c.id, c.ale);
            } else {
                ales += CASK_SIZE;
                if(listener != null) listener.onBrewEvent("tapped", c.id, c.ale);
            }
        }
    }

    public Cask cask(int id){ return casks.get(id); }
    public int caskCount(){ return casks.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("grain", grain).kv("ales", ales).kv("nextId", nextId);
        w.arr("casks");
        for(Cask c : casks.values())
            w.obj().kv("id", c.id).kv("a", c.ale).kv("fd", c.fermentDays)
              .kv("left", c.daysLeft).kv("t", c.tapped).kv("k", c.skunked).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            grain = (int)n.flt("grain", grain);
            ales = (int)n.flt("ales", ales);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> cs = n.list("casks");
            if(cs == null) return;
            for(Object o : cs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Cask c = new Cask(nextIdFor(m.get("id")),
                    Json.str(m.get("a"), "?"), (int)Json.num(m.get("fd"), 1));
                c.daysLeft = (int)Json.num(m.get("left"), c.fermentDays);
                c.tapped = Json.bool(m.get("t"), false);
                c.skunked = Json.bool(m.get("k"), false);
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
