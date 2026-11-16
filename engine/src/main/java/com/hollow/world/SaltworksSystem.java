package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v16.0 — saltworks system (HollowEngine 16.0 'turns & rounds').
 *  One saltworks per settlement: brine is pumped into the pans (up to
 *  the pan capacity) and the sun boils off two measures a day, giving
 *  one salt per two measures boiled (an odd leftover waits in the
 *  pan); a rainy day (10% single roll) evaporates nothing.
 */
public final class SaltworksSystem {

    public static final int PAN_CAPACITY = 30;

    public static final class Works {
        public final String settlementId;
        public int brine = 0;
        public int salt = 0;
        public int harvested = 0;
        public int rainDelays = 0;
        public Works(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onSaltEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Works> works = new LinkedHashMap<>();
    public long seed = 0x51L;
    public int day = 0;

    /** dig the evaporation pans; one per settlement */
    public boolean dig(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || works.containsKey(settlementId)) return false;
        works.put(settlementId, new Works(settlementId));
        if(listener != null) listener.onSaltEvent("dug", settlementId, 0);
        return true;
    }

    /** pump brine into the pans; refuses overflow and unknown works */
    public boolean pump(String settlementId, int measures){
        Works w = works.get(settlementId);
        if(w == null || measures <= 0 || w.brine + measures > PAN_CAPACITY) return false;
        w.brine += measures;
        if(listener != null) listener.onSaltEvent("pumped", settlementId, measures);
        return true;
    }

    /** daily tick: rain or sun */
    public void tickDay(){
        day++;
        for(Works w : works.values()){
            if(w.brine <= 0) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ w.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.1f){
                w.rainDelays++;
                if(listener != null) listener.onSaltEvent("rain", w.settlementId, w.rainDelays);
                continue;
            }
            int boiled = Math.min(2, w.brine);
            w.brine -= boiled;
            int salt = boiled / 2;
            if(salt > 0){
                w.salt += salt;
                w.harvested += salt;
                if(listener != null) listener.onSaltEvent("harvested", w.settlementId, salt);
            }
        }
    }

    public Works works(String settlementId){ return works.get(settlementId); }
    public int worksCount(){ return works.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("works");
        for(Works x : works.values())
            w.obj().kv("s", x.settlementId).kv("br", x.brine).kv("slt", x.salt)
              .kv("hv", x.harvested).kv("rd", x.rainDelays).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ws = n.list("works");
            if(ws == null) return;
            for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Works x = new Works(Json.str(m.get("s"), "?"));
                x.brine = MathX.clamp((int)Json.num(m.get("br"), 0), 0, PAN_CAPACITY);
                x.salt = (int)Json.num(m.get("slt"), 0);
                x.harvested = (int)Json.num(m.get("hv"), 0);
                x.rainDelays = (int)Json.num(m.get("rd"), 0);
                works.put(x.settlementId, x);
            }
        } catch(Exception ignored){ }
    }
}
