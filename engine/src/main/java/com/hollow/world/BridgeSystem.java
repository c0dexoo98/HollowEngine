package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v12.0 — bridge system (HollowEngine 12.0 'trades & crafts').
 *  Toll bridges between settlements: daily crossings scale with the
 *  bridge's condition and pay the toll into its ledger; a flood damages
 *  the span (halving condition, stopping traffic) until repaired.
 */
public final class BridgeSystem {

    public static final class Bridge {
        public final String id;
        public final String fromId, toId;
        public final int toll;
        public float condition = 1f;                 // 0..1
        public boolean damaged = false;
        public int income = 0;
        public int floods = 0;
        public Bridge(String id, String fromId, String toId, int toll){
            this.id = id; this.fromId = fromId; this.toId = toId;
            this.toll = Math.max(1, toll);
        }
    }

    public interface Listener { void onBridgeEvent(String type, String bridgeId, int value); }
    public Listener listener;

    public final Map<String, Bridge> bridges = new LinkedHashMap<>();
    public long seed = 0xBFD9L;
    public int day = 0;

    /** build a toll bridge; refuses duplicates, same-endpoint spans and double pairs */
    public boolean build(String id, String fromId, String toId, int toll){
        if(id == null || id.isEmpty() || fromId == null || fromId.isEmpty()
           || toId == null || toId.isEmpty() || fromId.equals(toId)
           || bridges.containsKey(id) || pairExists(fromId, toId)) return false;
        bridges.put(id, new Bridge(id, fromId, toId, toll));
        if(listener != null) listener.onBridgeEvent("built", id, toll);
        return true;
    }

    private boolean pairExists(String a, String b){
        for(Bridge br : bridges.values())
            if((br.fromId.equals(a) && br.toId.equals(b)) || (br.fromId.equals(b) && br.toId.equals(a)))
                return true;
        return false;
    }

    /** daily tick: tolls and floods */
    public void tickDay(){
        day++;
        for(Bridge br : bridges.values()){
            if(br.damaged) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 211L) ^ br.id.hashCode());
            if(rng.range(0f, 1f) < 0.02f){
                br.damaged = true;
                br.floods++;
                br.condition = Math.max(0.2f, br.condition * 0.5f);
                if(listener != null) listener.onBridgeEvent("flood", br.id, br.floods);
                continue;
            }
            int crossings = Math.round(8f * br.condition);
            br.income += crossings * br.toll;
            if(listener != null) listener.onBridgeEvent("crossings", br.id, crossings);
        }
    }

    /** repair a damaged span; restores half of the lost condition */
    public boolean repair(String bridgeId){
        Bridge br = bridges.get(bridgeId);
        if(br == null || !br.damaged) return false;
        br.damaged = false;
        br.condition = Math.min(1f, br.condition + 0.5f);
        if(listener != null) listener.onBridgeEvent("repaired", bridgeId, br.floods);
        return true;
    }

    public int incomeOf(String bridgeId){
        Bridge br = bridges.get(bridgeId);
        return br == null ? 0 : br.income;
    }

    public Bridge bridge(String bridgeId){ return bridges.get(bridgeId); }
    public int bridgeCount(){ return bridges.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("bridges");
        for(Bridge br : bridges.values())
            w.obj().kv("id", br.id).kv("f", br.fromId).kv("t", br.toId).kv("toll", br.toll)
              .kv("cond", br.condition).kv("dmg", br.damaged).kv("inc", br.income)
              .kv("fl", br.floods).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> bs = n.list("bridges");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Bridge br = new Bridge(Json.str(m.get("id"), "?"), Json.str(m.get("f"), "?"),
                    Json.str(m.get("t"), "?"), (int)Json.num(m.get("toll"), 1));
                br.condition = MathX.clamp((float)Json.num(m.get("cond"), 1f), 0.2f, 1f);
                br.damaged = Json.bool(m.get("dmg"), false);
                br.income = (int)Json.num(m.get("inc"), 0);
                br.floods = (int)Json.num(m.get("fl"), 0);
                bridges.put(br.id, br);
            }
        } catch(Exception ignored){ }
    }
}
