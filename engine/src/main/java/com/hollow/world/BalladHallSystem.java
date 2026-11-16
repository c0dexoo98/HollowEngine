package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v20.0 — ballad hall system (HollowEngine 20.0 'harps & halls').
 *  One ballad hall per town: nights are booked ahead (up to the
 *  hall's ledger depth); each booked night performs once and one
 *  roll decides the crowd — 90% a full house of thirty silver,
 *  10% a half house of twelve. Performed, full, half and gross
 *  ledgers are kept honestly.
 */
public final class BalladHallSystem {

    public static final int BOOKINGS_CAP = 6;
    public static final int FULL_HOUSE_GROSS = 30;
    public static final int HALF_HOUSE_GROSS = 12;
    public static final float FULL_CHANCE = 0.9f;

    public static final class Hall {
        public final String townId;
        public int bookings = 0;
        public int performed = 0;
        public int full = 0;
        public int half = 0;
        public int gross = 0;
        public Hall(String townId){
            this.townId = townId;
        }
    }

    public interface Listener { void onHallEvent(String type, String townId, int value); }
    public Listener listener;

    public final Map<String, Hall> halls = new LinkedHashMap<>();
    public long seed = 0xA11L;
    public int day = 0;

    /** raise a ballad hall; one per town */
    public boolean build(String townId){
        if(townId == null || townId.isEmpty() || halls.containsKey(townId)) return false;
        halls.put(townId, new Hall(townId));
        if(listener != null) listener.onHallEvent("built", townId, 0);
        return true;
    }

    /** book a performance night; refuses overflow and unknown halls */
    public boolean book(String townId){
        Hall h = halls.get(townId);
        if(h == null || h.bookings + 1 > BOOKINGS_CAP) return false;
        h.bookings++;
        if(listener != null) listener.onHallEvent("booked", townId, h.bookings);
        return true;
    }

    /** daily tick: one booked night performs, one roll for the crowd */
    public void tickDay(){
        day++;
        for(Hall h : halls.values()){
            if(h.bookings <= 0) continue;
            h.bookings--;
            h.performed++;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ h.townId.hashCode());
            if(rng.range(0f, 1f) < FULL_CHANCE){
                h.full++;
                h.gross += FULL_HOUSE_GROSS;
                if(listener != null) listener.onHallEvent("full", h.townId, h.gross);
            } else {
                h.half++;
                h.gross += HALF_HOUSE_GROSS;
                if(listener != null) listener.onHallEvent("half", h.townId, h.gross);
            }
        }
    }

    public Hall hall(String townId){ return halls.get(townId); }
    public int hallCount(){ return halls.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("halls");
        for(Hall h : halls.values())
            w.obj().kv("t", h.townId).kv("b", h.bookings).kv("p", h.performed)
              .kv("f", h.full).kv("h", h.half).kv("g", h.gross).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> hs = n.list("halls");
            if(hs == null) return;
            for(Object o : hs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Hall h = new Hall(Json.str(m.get("t"), "?"));
                h.bookings = MathX.clamp((int)Json.num(m.get("b"), 0), 0, BOOKINGS_CAP);
                h.performed = (int)Json.num(m.get("p"), 0);
                h.full = (int)Json.num(m.get("f"), 0);
                h.half = (int)Json.num(m.get("h"), 0);
                h.gross = (int)Json.num(m.get("g"), 0);
                halls.put(h.townId, h);
            }
        } catch(Exception ignored){ }
    }
}
