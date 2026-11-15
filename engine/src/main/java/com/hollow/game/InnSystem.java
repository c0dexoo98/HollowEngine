package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v11.0 — inn system (HollowEngine 11.0 'hearth & home').
 *  Roadside inns: guests check in up to the room count, pay for their
 *  night (rate scales with taproom quality), and a busy taproom can end
 *  in a brawl that halves the night's take. Deterministic rolls.
 */
public final class InnSystem {

    public static final class Inn {
        public final String name;
        public final int rooms;
        public final float quality;                  // 0..1 taproom quality
        public int occupied = 0;
        public int nights = 0;
        public int income = 0;
        public int brawls = 0;
        public Inn(String name, int rooms, float quality){
            this.name = name; this.rooms = Math.max(1, rooms);
            this.quality = MathX.clamp(quality, 0f, 1f);
        }
    }

    public interface Listener { void onInnEvent(String type, String innName, int value); }
    public Listener listener;

    public final Map<String, Inn> inns = new LinkedHashMap<>();
    public long seed = 0x11775L;
    public int day = 0;

    /** open an inn; refuses duplicates and empty names */
    public boolean build(String name, int rooms, float quality){
        if(name == null || name.isEmpty() || inns.containsKey(name)) return false;
        inns.put(name, new Inn(name, rooms, quality));
        if(listener != null) listener.onInnEvent("built", name, rooms);
        return true;
    }

    /** guests take rooms for the night; refuses overbooking and closed doors */
    public boolean checkIn(String innName, int guests){
        Inn inn = inns.get(innName);
        if(inn == null || guests <= 0 || inn.occupied + guests > inn.rooms) return false;
        inn.occupied += guests;
        if(listener != null) listener.onInnEvent("checked_in", innName, guests);
        return true;
    }

    /** nightly tick: guests pay, then leave; a half-full house may brawl */
    public void tickDay(){
        day++;
        for(Inn inn : inns.values()){
            if(inn.occupied <= 0) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 131L) ^ inn.name.hashCode());
            int take = Math.round(inn.occupied * 5f * (0.5f + inn.quality));
            if(inn.occupied * 2 >= inn.rooms && rng.range(0f, 1f) < 0.1f){
                inn.brawls++;
                take /= 2;
                if(listener != null) listener.onInnEvent("brawl", inn.name, take);
            }
            inn.income += take;
            inn.nights++;
            if(listener != null) listener.onInnEvent("night", inn.name, take);
            inn.occupied = 0;                        // guests move on in the morning
        }
    }

    public Inn inn(String name){ return inns.get(name); }
    public int innCount(){ return inns.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("inns");
        for(Inn i : inns.values())
            w.obj().kv("n", i.name).kv("r", i.rooms).kv("q", i.quality).kv("occ", i.occupied)
              .kv("ni", i.nights).kv("inc", i.income).kv("br", i.brawls).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> is = n.list("inns");
            if(is == null) return;
            for(Object o : is){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Inn i = new Inn(Json.str(m.get("n"), "?"), (int)Json.num(m.get("r"), 1),
                    (float)Json.num(m.get("q"), 0f));
                i.occupied = (int)Json.num(m.get("occ"), 0);
                i.nights = (int)Json.num(m.get("ni"), 0);
                i.income = (int)Json.num(m.get("inc"), 0);
                i.brawls = (int)Json.num(m.get("br"), 0);
                inns.put(i.name, i);
            }
        } catch(Exception ignored){ }
    }
}
