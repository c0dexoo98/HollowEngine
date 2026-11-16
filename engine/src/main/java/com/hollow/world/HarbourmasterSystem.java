package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v16.0 — harbourmaster system (HollowEngine 16.0 'turns & rounds').
 *  One chartered port per settlement: each day the tide brings one
 *  roll — 5% a sick ship held at the roads (quarantined, no berth
 *  spent), 60% a calling ship that takes a free berth or is turned
 *  away, else empty water. Captains sail their ships out by hand.
 */
public final class HarbourmasterSystem {

    public static final int MIN_BERTHS = 1;
    public static final int MAX_BERTHS = 5;

    public static final class Port {
        public final String settlementId;
        public int berths = 2;
        public int occupied = 0;
        public int arrivals = 0;
        public int quarantined = 0;
        public int turnedAway = 0;
        public Port(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onPortEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Port> ports = new LinkedHashMap<>();
    public long seed = 0x4B2L;
    public int day = 0;

    /** charter a port; one per settlement */
    public boolean charter(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || ports.containsKey(settlementId)) return false;
        ports.put(settlementId, new Port(settlementId));
        if(listener != null) listener.onPortEvent("chartered", settlementId, 0);
        return true;
    }

    /** dredge or fill berths along the quay */
    public boolean setBerths(String settlementId, int n){
        Port p = ports.get(settlementId);
        if(p == null || n < MIN_BERTHS || n > MAX_BERTHS) return false;
        p.berths = n;
        if(listener != null) listener.onPortEvent("berths_set", settlementId, n);
        return true;
    }

    /** a captain sails; frees a berth */
    public boolean sail(String settlementId){
        Port p = ports.get(settlementId);
        if(p == null || p.occupied <= 0) return false;
        p.occupied--;
        if(listener != null) listener.onPortEvent("sailed", settlementId, p.occupied);
        return true;
    }

    /** daily tick: the tide brings one roll per port */
    public void tickDay(){
        day++;
        for(Port p : ports.values()){
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ p.settlementId.hashCode());
            float tide = rng.range(0f, 1f);
            if(tide < 0.05f){
                p.arrivals++;
                p.quarantined++;
                if(listener != null) listener.onPortEvent("quarantined", p.settlementId, p.quarantined);
            } else if(tide < 0.65f){
                p.arrivals++;
                if(p.occupied < p.berths){
                    p.occupied++;
                    if(listener != null) listener.onPortEvent("berthed", p.settlementId, p.occupied);
                } else {
                    p.turnedAway++;
                    if(listener != null) listener.onPortEvent("turned_away", p.settlementId, p.turnedAway);
                }
            }
        }
    }

    public Port port(String settlementId){ return ports.get(settlementId); }
    public int portCount(){ return ports.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("ports");
        for(Port p : ports.values())
            w.obj().kv("s", p.settlementId).kv("b", p.berths).kv("occ", p.occupied)
              .kv("arr", p.arrivals).kv("q", p.quarantined).kv("ta", p.turnedAway).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ps = n.list("ports");
            if(ps == null) return;
            for(Object o : ps){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Port p = new Port(Json.str(m.get("s"), "?"));
                p.berths = MathX.clamp((int)Json.num(m.get("b"), 2), MIN_BERTHS, MAX_BERTHS);
                p.occupied = Math.max(0, (int)Json.num(m.get("occ"), 0));
                p.arrivals = (int)Json.num(m.get("arr"), 0);
                p.quarantined = (int)Json.num(m.get("q"), 0);
                p.turnedAway = (int)Json.num(m.get("ta"), 0);
                ports.put(p.settlementId, p);
            }
        } catch(Exception ignored){ }
    }
}
