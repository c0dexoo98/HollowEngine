package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v19.0 — mill pond system (HollowEngine 19.0 'spindles & shuttles').
 *  One mill pond per settlement: filling raises the head by eight
 *  inches (pond capped at forty-eight); each day with water in the
 *  pond the wheel turns three times and the head drops twelve inches,
 *  then one roll decides the bank — 15% a leak takes six more inches
 *  (counted in the drains ledger). An empty pond idles: no wheel, no
 *  roll.
 */
public final class MillPondSystem {

    public static final int DEPTH_CAP = 48;
    public static final int FILL_INCHES = 8;
    public static final int DRAIN_INCHES = 12;
    public static final int LEAK_INCHES = 6;
    public static final int TURNS_PER_DAY = 3;

    public static final class Pond {
        public final String settlementId;
        public int depthInches = 0;
        public int wheelTurns = 0;
        public int drains = 0;
        public Pond(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onPondEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Pond> ponds = new LinkedHashMap<>();
    public long seed = 0x6D2L;
    public int day = 0;

    /** dig a mill pond; one per settlement */
    public boolean dig(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || ponds.containsKey(settlementId)) return false;
        ponds.put(settlementId, new Pond(settlementId));
        if(listener != null) listener.onPondEvent("dug", settlementId, 0);
        return true;
    }

    /** raise the head by one fill (capped); refuses unknown ponds */
    public boolean fill(String settlementId){
        Pond p = ponds.get(settlementId);
        if(p == null || p.depthInches >= DEPTH_CAP) return false;
        p.depthInches = Math.min(DEPTH_CAP, p.depthInches + FILL_INCHES);
        if(listener != null) listener.onPondEvent("filled", settlementId, p.depthInches);
        return true;
    }

    /** daily tick: the wheel takes its water, then the bank may leak */
    public void tickDay(){
        day++;
        for(Pond p : ponds.values()){
            if(p.depthInches <= 0) continue;
            p.wheelTurns += TURNS_PER_DAY;
            p.depthInches = Math.max(0, p.depthInches - DRAIN_INCHES);
            if(listener != null) listener.onPondEvent("turned", p.settlementId, p.wheelTurns);
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ p.settlementId.hashCode());
            if(p.depthInches > 0 && rng.range(0f, 1f) < 0.15f){
                p.drains++;
                p.depthInches = Math.max(0, p.depthInches - LEAK_INCHES);
                if(listener != null) listener.onPondEvent("leak", p.settlementId, p.drains);
            }
        }
    }

    public Pond pond(String settlementId){ return ponds.get(settlementId); }
    public int pondCount(){ return ponds.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("ponds");
        for(Pond p : ponds.values())
            w.obj().kv("s", p.settlementId).kv("d", p.depthInches).kv("t", p.wheelTurns)
              .kv("dr", p.drains).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ps = n.list("ponds");
            if(ps == null) return;
            for(Object o : ps){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Pond p = new Pond(Json.str(m.get("s"), "?"));
                p.depthInches = MathX.clamp((int)Json.num(m.get("d"), 0), 0, DEPTH_CAP);
                p.wheelTurns = (int)Json.num(m.get("t"), 0);
                p.drains = (int)Json.num(m.get("dr"), 0);
                ponds.put(p.settlementId, p);
            }
        } catch(Exception ignored){ }
    }
}
