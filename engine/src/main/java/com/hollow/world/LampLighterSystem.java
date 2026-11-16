package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v16.0 — lamplighter system (HollowEngine 16.0 'turns & rounds').
 *  One dusk round per settlement: the lighter lights as many street
 *  lamps as the oil stock feeds (each burning lamp drinks one flask a
 *  night), then a single roll may gutter one lamp at dusk (10%) — a
 *  guttered lamp burns no oil that night. Dark lamps are counted
 *  honestly every night.
 */
public final class LampLighterSystem {

    public static final int LAMP_CAP = 6;
    public static final int OIL_CAP = 20;

    public static final class Round {
        public final String settlementId;
        public int lamps = 0;
        public int oil = 0;
        public int litNights = 0;
        public int darkNights = 0;
        public int gutters = 0;
        public Round(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onLampEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Round> rounds = new LinkedHashMap<>();
    public long seed = 0x1A11L;
    public int day = 0;

    /** open a lighting round; one per settlement */
    public boolean open(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || rounds.containsKey(settlementId)) return false;
        rounds.put(settlementId, new Round(settlementId));
        if(listener != null) listener.onLampEvent("opened", settlementId, 0);
        return true;
    }

    /** hang or take down street lamps */
    public boolean hang(String settlementId, int n){
        Round r = rounds.get(settlementId);
        if(r == null || n < 0 || n > LAMP_CAP) return false;
        r.lamps = n;
        if(listener != null) listener.onLampEvent("hung", settlementId, n);
        return true;
    }

    /** stock the oil crate */
    public boolean deliverOil(String settlementId, int flasks){
        Round r = rounds.get(settlementId);
        if(r == null || flasks <= 0 || r.oil + flasks > OIL_CAP) return false;
        r.oil += flasks;
        if(listener != null) listener.onLampEvent("oiled", settlementId, flasks);
        return true;
    }

    /** nightly tick: the dusk round — light what the oil feeds, one gutter roll */
    public void tickDay(){
        day++;
        for(Round r : rounds.values()){
            if(r.lamps <= 0) continue;
            int lit = Math.min(r.lamps, r.oil);
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ r.settlementId.hashCode());
            if(lit > 0 && rng.range(0f, 1f) < 0.1f){
                lit--;
                r.gutters++;
                if(listener != null) listener.onLampEvent("guttered", r.settlementId, r.gutters);
            }
            r.oil -= lit;
            r.litNights += lit;
            r.darkNights += r.lamps - lit;
            if(listener != null) listener.onLampEvent("lit", r.settlementId, lit);
        }
    }

    public Round round(String settlementId){ return rounds.get(settlementId); }
    public int roundCount(){ return rounds.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("rounds");
        for(Round r : rounds.values())
            w.obj().kv("s", r.settlementId).kv("l", r.lamps).kv("o", r.oil)
              .kv("ln", r.litNights).kv("dn", r.darkNights).kv("g", r.gutters).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> rs = n.list("rounds");
            if(rs == null) return;
            for(Object o : rs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Round r = new Round(Json.str(m.get("s"), "?"));
                r.lamps = MathX.clamp((int)Json.num(m.get("l"), 0), 0, LAMP_CAP);
                r.oil = MathX.clamp((int)Json.num(m.get("o"), 0), 0, OIL_CAP);
                r.litNights = (int)Json.num(m.get("ln"), 0);
                r.darkNights = (int)Json.num(m.get("dn"), 0);
                r.gutters = (int)Json.num(m.get("g"), 0);
                rounds.put(r.settlementId, r);
            }
        } catch(Exception ignored){ }
    }
}
