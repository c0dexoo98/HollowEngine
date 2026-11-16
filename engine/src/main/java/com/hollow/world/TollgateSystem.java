package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v15.0 — tollgate system (HollowEngine 15.0 'trade & tide').
 *  One tollgate per settlement takes a penny toll (1–5, set by hand);
 *  each day one traveler passes and a single deterministic roll
 *  decides whether they pay (80%) or slip past the bar (20%).
 */
public final class TollgateSystem {

    public static final int MIN_TOLL = 1;
    public static final int MAX_TOLL = 5;

    public static final class Gate {
        public final String settlementId;
        public int tollPennies = 1;
        public int passages = 0;
        public int evasions = 0;
        public int revenue = 0;
        public Gate(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onGateEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Gate> gates = new LinkedHashMap<>();
    public long seed = 0x71L;
    public int day = 0;

    /** erect a tollgate; one per settlement */
    public boolean erect(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || gates.containsKey(settlementId)) return false;
        gates.put(settlementId, new Gate(settlementId));
        if(listener != null) listener.onGateEvent("erected", settlementId, 0);
        return true;
    }

    /** set the penny toll; refuses unknown gates and out-of-band rates */
    public boolean setToll(String settlementId, int pennies){
        Gate g = gates.get(settlementId);
        if(g == null || pennies < MIN_TOLL || pennies > MAX_TOLL) return false;
        g.tollPennies = pennies;
        if(listener != null) listener.onGateEvent("toll_set", settlementId, pennies);
        return true;
    }

    /** daily tick: one traveler, one roll — pay or slip past the bar */
    public void tickDay(){
        day++;
        for(Gate g : gates.values()){
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ g.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.2f){
                g.evasions++;
                if(listener != null) listener.onGateEvent("evaded", g.settlementId, g.evasions);
            } else {
                g.passages++;
                g.revenue += g.tollPennies;
                if(listener != null) listener.onGateEvent("paid", g.settlementId, g.revenue);
            }
        }
    }

    public Gate gate(String settlementId){ return gates.get(settlementId); }
    public int gateCount(){ return gates.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("gates");
        for(Gate g : gates.values())
            w.obj().kv("s", g.settlementId).kv("t", g.tollPennies).kv("p", g.passages)
              .kv("ev", g.evasions).kv("rev", g.revenue).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> gs = n.list("gates");
            if(gs == null) return;
            for(Object o : gs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Gate g = new Gate(Json.str(m.get("s"), "?"));
                g.tollPennies = MathX.clamp((int)Json.num(m.get("t"), 1), MIN_TOLL, MAX_TOLL);
                g.passages = (int)Json.num(m.get("p"), 0);
                g.evasions = (int)Json.num(m.get("ev"), 0);
                g.revenue = (int)Json.num(m.get("rev"), 0);
                gates.put(g.settlementId, g);
            }
        } catch(Exception ignored){ }
    }
}
