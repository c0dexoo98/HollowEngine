package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v14.0 — watchtower system (HollowEngine 14.0 'watches & works').
 *  One beacon tower per settlement: a lit watch fire relays a signal
 *  every clear night (single deterministic roll — fog blots 20% of
 *  lit nights), lit by hand and doused the same way.
 */
public final class WatchtowerSystem {

    public static final class Tower {
        public final String settlementId;
        public boolean lit = false;
        public int litNights = 0;
        public int fogNights = 0;
        public int signals = 0;
        public Tower(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onBeaconEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Tower> towers = new LinkedHashMap<>();
    public long seed = 0x3E1L;
    public int day = 0;

    /** raise a watchtower; one per settlement */
    public boolean raise(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || towers.containsKey(settlementId)) return false;
        towers.put(settlementId, new Tower(settlementId));
        if(listener != null) listener.onBeaconEvent("raised", settlementId, 0);
        return true;
    }

    /** light or douse the watch fire */
    public boolean light(String settlementId, boolean on){
        Tower t = towers.get(settlementId);
        if(t == null || t.lit == on) return false;
        t.lit = on;
        if(listener != null) listener.onBeaconEvent(on ? "lit" : "doused", settlementId, 0);
        return true;
    }

    /** nightly tick: lit towers roll one sky (fog 20%, else the signal relays) */
    public void tickDay(){
        day++;
        for(Tower t : towers.values()){
            if(!t.lit) continue;
            t.litNights++;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ t.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.2f){
                t.fogNights++;
                if(listener != null) listener.onBeaconEvent("fogged", t.settlementId, t.litNights);
            } else {
                t.signals++;
                if(listener != null) listener.onBeaconEvent("signalled", t.settlementId, t.signals);
            }
        }
    }

    public Tower tower(String settlementId){ return towers.get(settlementId); }
    public int towerCount(){ return towers.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("towers");
        for(Tower t : towers.values())
            w.obj().kv("s", t.settlementId).kv("lit", t.lit).kv("ln", t.litNights)
              .kv("fg", t.fogNights).kv("sig", t.signals).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ts = n.list("towers");
            if(ts == null) return;
            for(Object o : ts){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Tower t = new Tower(Json.str(m.get("s"), "?"));
                t.lit = Json.bool(m.get("lit"), false);
                t.litNights = (int)Json.num(m.get("ln"), 0);
                t.fogNights = (int)Json.num(m.get("fg"), 0);
                t.signals = (int)Json.num(m.get("sig"), 0);
                towers.put(t.settlementId, t);
            }
        } catch(Exception ignored){ }
    }
}
