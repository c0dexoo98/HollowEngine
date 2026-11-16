package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v15.0 — buoy system (HollowEngine 15.0 'trade & tide').
 *  One channel buoy per settlement: the current drags it a fathom a
 *  day (a squall — 5% single roll — drags it three more); past eight
 *  fathoms of drift it is visibly off its station. Repositioning crews
 *  reset the mooring. Visibility is deterministic from the drift.
 */
public final class BuoySystem {

    public static final int DRIFT_LIMIT = 8;

    public static final class Buoy {
        public final String settlementId;
        public int drift = 0;
        public int repositions = 0;
        public int squalls = 0;
        public Buoy(String settlementId){
            this.settlementId = settlementId;
        }
        public boolean onStation(){ return drift < DRIFT_LIMIT; }
    }

    public interface Listener { void onBuoyEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Buoy> buoys = new LinkedHashMap<>();
    public long seed = 0xB0L;
    public int day = 0;

    /** moor a channel buoy; one per settlement */
    public boolean moor(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || buoys.containsKey(settlementId)) return false;
        buoys.put(settlementId, new Buoy(settlementId));
        if(listener != null) listener.onBuoyEvent("moored", settlementId, 0);
        return true;
    }

    /** send a crew to reset the mooring */
    public boolean reposition(String settlementId){
        Buoy b = buoys.get(settlementId);
        if(b == null) return false;
        b.drift = 0;
        b.repositions++;
        if(listener != null) listener.onBuoyEvent("repositioned", settlementId, b.repositions);
        return true;
    }

    /** daily tick: the current drags, a squall may drag more */
    public void tickDay(){
        day++;
        for(Buoy b : buoys.values()){
            b.drift += 1;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ b.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.05f){
                b.drift += 3;
                b.squalls++;
                if(listener != null) listener.onBuoyEvent("squall", b.settlementId, b.drift);
            }
            if(!b.onStation() && listener != null)
                listener.onBuoyEvent("off_station", b.settlementId, b.drift);
        }
    }

    public Buoy buoy(String settlementId){ return buoys.get(settlementId); }
    public int buoyCount(){ return buoys.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("buoys");
        for(Buoy b : buoys.values())
            w.obj().kv("s", b.settlementId).kv("d", b.drift)
              .kv("rp", b.repositions).kv("sq", b.squalls).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> bs = n.list("buoys");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Buoy b = new Buoy(Json.str(m.get("s"), "?"));
                b.drift = Math.max(0, (int)Json.num(m.get("d"), 0));
                b.repositions = (int)Json.num(m.get("rp"), 0);
                b.squalls = (int)Json.num(m.get("sq"), 0);
                buoys.put(b.settlementId, b);
            }
        } catch(Exception ignored){ }
    }
}
