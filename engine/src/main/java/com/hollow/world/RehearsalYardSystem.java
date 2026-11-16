package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v20.0 — rehearsal yard system (HollowEngine 20.0 'harps & halls').
 *  One rehearsal yard per town: troupes reserve a corner (up to the
 *  yard's capacity); each day every reserved troupe rehearses once
 *  and one roll per troupe decides the neighbours — 90% a quiet day
 *  is rehearsed, 10% a noise complaint voids that troupe's day
 *  (counted in the complaints ledger).
 */
public final class RehearsalYardSystem {

    public static final int TROUPES_CAP = 4;
    public static final float COMPLAINT_CHANCE = 0.1f;

    public static final class Yard {
        public final String townId;
        public int troupes = 0;
        public int rehearsed = 0;
        public int complaints = 0;
        public Yard(String townId){
            this.townId = townId;
        }
    }

    public interface Listener { void onYardEvent(String type, String townId, int value); }
    public Listener listener;

    public final Map<String, Yard> yards = new LinkedHashMap<>();
    public long seed = 0xC7L;
    public int day = 0;

    /** open a rehearsal yard; one per town */
    public boolean open(String townId){
        if(townId == null || townId.isEmpty() || yards.containsKey(townId)) return false;
        yards.put(townId, new Yard(townId));
        if(listener != null) listener.onYardEvent("opened", townId, 0);
        return true;
    }

    /** reserve a corner for one troupe; refuses overflow and unknown yards */
    public boolean reserve(String townId){
        Yard y = yards.get(townId);
        if(y == null || y.troupes + 1 > TROUPES_CAP) return false;
        y.troupes++;
        if(listener != null) listener.onYardEvent("reserved", townId, y.troupes);
        return true;
    }

    /** daily tick: every reserved troupe rehearses, one roll each */
    public void tickDay(){
        day++;
        for(Yard y : yards.values()){
            for(int i = 0; i < y.troupes; i++){
                MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ (y.townId.hashCode() * 31 + i));
                if(rng.range(0f, 1f) < COMPLAINT_CHANCE){
                    y.complaints++;
                    if(listener != null) listener.onYardEvent("complaint", y.townId, y.complaints);
                } else {
                    y.rehearsed++;
                    if(listener != null) listener.onYardEvent("rehearsed", y.townId, y.rehearsed);
                }
            }
        }
    }

    public Yard yard(String townId){ return yards.get(townId); }
    public int yardCount(){ return yards.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("yards");
        for(Yard y : yards.values())
            w.obj().kv("t", y.townId).kv("tr", y.troupes).kv("re", y.rehearsed)
              .kv("co", y.complaints).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ys = n.list("yards");
            if(ys == null) return;
            for(Object o : ys){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Yard y = new Yard(Json.str(m.get("t"), "?"));
                y.troupes = MathX.clamp((int)Json.num(m.get("tr"), 0), 0, TROUPES_CAP);
                y.rehearsed = (int)Json.num(m.get("re"), 0);
                y.complaints = (int)Json.num(m.get("co"), 0);
                yards.put(y.townId, y);
            }
        } catch(Exception ignored){ }
    }
}
