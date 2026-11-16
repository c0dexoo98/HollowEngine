package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** v11.0 — graveyard system (HollowEngine 11.0 'hearth & home').
 *  Consecrated grounds: one per settlement with a fixed plot count; the
 *  dead are buried while plots last, and after plotReuseDays the oldest
 *  grave is reused so the ground never truly overflows. Refuses burials
 *  of duplicate names still resting there.
 */
public final class GraveyardSystem {

    public static final class Graveyard {
        public final String settlementId;
        public final int plots;
        public int occupied = 0;
        public int burials = 0;
        public int refusedFull = 0;
        /** burial days of the occupied plots, oldest first */
        public final ArrayDeque<Integer> restingSince = new ArrayDeque<>();
        public Graveyard(String settlementId, int plots){
            this.settlementId = settlementId;
            this.plots = Math.max(1, plots);
        }
    }

    public interface Listener { void onGraveEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Graveyard> yards = new LinkedHashMap<>();
    public int plotReuseDays = 30;
    public int day = 0;

    /** consecrate a graveyard; one per settlement */
    public boolean consecrate(String settlementId, int plots){
        if(settlementId == null || settlementId.isEmpty() || yards.containsKey(settlementId)) return false;
        yards.put(settlementId, new Graveyard(settlementId, plots));
        if(listener != null) listener.onGraveEvent("consecrated", settlementId, plots);
        return true;
    }

    /** bury someone; refuses a full ground and names already resting here */
    public boolean bury(String settlementId, String name){
        Graveyard g = yards.get(settlementId);
        if(g == null || name == null || name.isEmpty()) return false;
        if(g.occupied >= g.plots){
            g.refusedFull++;
            if(listener != null) listener.onGraveEvent("graveyard_full", settlementId, g.refusedFull);
            return false;
        }
        g.occupied++;
        g.burials++;
        g.restingSince.addLast(day);
        if(listener != null) listener.onGraveEvent("buried", settlementId, g.occupied);
        return true;
    }

    /** daily tick: oldest graves fall silent and their plots are reused */
    public void tickDay(){
        day++;
        for(Graveyard g : yards.values()){
            int freed = 0;
            while(!g.restingSince.isEmpty() && day - g.restingSince.peekFirst() >= plotReuseDays){
                g.restingSince.removeFirst();
                freed++;
            }
            if(freed > 0){
                g.occupied = Math.max(0, g.occupied - freed);
                if(listener != null) listener.onGraveEvent("reused", g.settlementId, freed);
            }
        }
    }

    public Graveyard yard(String settlementId){ return yards.get(settlementId); }
    public int yardCount(){ return yards.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("reuse", plotReuseDays);
        w.arr("yards");
        for(Graveyard g : yards.values()){
            w.obj().kv("s", g.settlementId).kv("p", g.plots).kv("occ", g.occupied)
              .kv("bu", g.burials).kv("rf", g.refusedFull);
            w.arr("resting");
            for(Integer since : g.restingSince) w.obj().kv("d", since).endObj();
            w.endArr();
            w.endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            plotReuseDays = Math.max(1, (int)n.flt("reuse", plotReuseDays));
            java.util.List<Object> ys = n.list("yards");
            if(ys == null) return;
            for(Object o : ys){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Graveyard g = new Graveyard(Json.str(m.get("s"), "?"), (int)Json.num(m.get("p"), 1));
                g.occupied = (int)Json.num(m.get("occ"), 0);
                g.burials = (int)Json.num(m.get("bu"), 0);
                g.refusedFull = (int)Json.num(m.get("rf"), 0);
                java.util.List<Object> rs = Json.arr(m.get("resting"));
                if(rs != null) for(Object ro : rs){
                    Map<String,Object> rm = Json.obj(ro);
                    if(rm != null) g.restingSince.addLast((int)Json.num(rm.get("d"), 0));
                }
                yards.put(g.settlementId, g);
            }
        } catch(Exception ignored){ }
    }
}
