package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v13.0 — ratcatcher system (HollowEngine 13.0 'ritual & remedy').
 *  Each ward hosts a rat population that multiplies when ignored; traps
 *  catch a fixed haul per day while rats remain, and a heavy infestation
 *  rings the ward bell every tick until the catchers win it back.
 */
public final class RatcatcherSystem {

    public static final int MAX_TRAPS = 8;
    public static final int INFESTATION_AT = 60;

    public static final class Ward {
        public final String settlementId;
        public int rats = 0;
        public int traps = 0;
        public int catches = 0;
        public int infestations = 0;
        public Ward(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onRatEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Ward> wards = new LinkedHashMap<>();
    public int day = 0;

    /** open a ward; one per settlement */
    public boolean open(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || wards.containsKey(settlementId)) return false;
        wards.put(settlementId, new Ward(settlementId));
        if(listener != null) listener.onRatEvent("opened", settlementId, 0);
        return true;
    }

    /** deploy traps in a ward */
    public boolean deployTraps(String settlementId, int traps){
        Ward w = wards.get(settlementId);
        if(w == null || traps < 0 || traps > MAX_TRAPS) return false;
        w.traps = traps;
        if(listener != null) listener.onRatEvent("deployed", settlementId, traps);
        return true;
    }

    /** daily tick: rats breed, traps haul, bells ring */
    public void tickDay(){
        day++;
        for(Ward w : wards.values()){
            if(w.rats > 0) w.rats = Math.min(100, w.rats + (int)(w.rats * 0.15f));
            if(w.traps > 0 && w.rats > 0){
                int caught = Math.min(w.rats, w.traps * 2);
                w.rats -= caught;
                w.catches += caught;
                if(listener != null) listener.onRatEvent("caught", w.settlementId, caught);
            }
            if(w.rats > INFESTATION_AT){
                w.infestations++;
                if(listener != null) listener.onRatEvent("infested", w.settlementId, w.rats);
            }
        }
    }

    public Ward ward(String settlementId){ return wards.get(settlementId); }
    public int wardCount(){ return wards.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("wards");
        for(Ward x : wards.values())
            w.obj().kv("s", x.settlementId).kv("rats", x.rats).kv("traps", x.traps)
              .kv("c", x.catches).kv("inf", x.infestations).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ws = n.list("wards");
            if(ws == null) return;
            for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Ward x = new Ward(Json.str(m.get("s"), "?"));
                x.rats = MathX.clamp((int)Json.num(m.get("rats"), 0), 0, 100);
                x.traps = MathX.clamp((int)Json.num(m.get("traps"), 0), 0, MAX_TRAPS);
                x.catches = (int)Json.num(m.get("c"), 0);
                x.infestations = (int)Json.num(m.get("inf"), 0);
                wards.put(x.settlementId, x);
            }
        } catch(Exception ignored){ }
    }
}
