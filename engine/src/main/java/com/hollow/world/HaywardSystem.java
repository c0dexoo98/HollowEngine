package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v17.0 — hayward system (HollowEngine 17.0 'banks & benches').
 *  One hayward's beat per settlement: two fixed rounds a day (morning
 *  and evening, deterministic); while the hedge lies open (strength
 *  0) a single roll may let a beast stray (30%), and the mend rounds
 *  always raise the hedge one notch a day up to three. A tight hedge
 *  keeps the strays out for good.
 */
public final class HaywardSystem {

    public static final int MAX_HEDGE = 3;
    public static final int ROUNDS_PER_DAY = 2;

    public static final class Beat {
        public final String settlementId;
        public int hedges = 0;
        public int strays = 0;
        public int roundsDone = 0;
        public Beat(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onBeatEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Beat> beats = new LinkedHashMap<>();
    public long seed = 0x6E1L;
    public int day = 0;

    /** appoint a hayward; one per settlement */
    public boolean appoint(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || beats.containsKey(settlementId)) return false;
        beats.put(settlementId, new Beat(settlementId));
        if(listener != null) listener.onBeatEvent("appointed", settlementId, 0);
        return true;
    }

    /** daily tick: the two rounds, a stray roll only while the hedge lies open */
    public void tickDay(){
        day++;
        for(Beat b : beats.values()){
            b.roundsDone += ROUNDS_PER_DAY;
            if(b.hedges == 0){
                MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ b.settlementId.hashCode());
                if(rng.range(0f, 1f) < 0.3f){
                    b.strays++;
                    if(listener != null) listener.onBeatEvent("stray", b.settlementId, b.strays);
                }
            }
            if(b.hedges < MAX_HEDGE){
                b.hedges++;
                if(listener != null) listener.onBeatEvent("mended", b.settlementId, b.hedges);
            }
        }
    }

    public Beat beat(String settlementId){ return beats.get(settlementId); }
    public int beatCount(){ return beats.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("beats");
        for(Beat b : beats.values())
            w.obj().kv("s", b.settlementId).kv("h", b.hedges).kv("st", b.strays)
              .kv("rd", b.roundsDone).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> bs = n.list("beats");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Beat b = new Beat(Json.str(m.get("s"), "?"));
                b.hedges = MathX.clamp((int)Json.num(m.get("h"), 0), 0, MAX_HEDGE);
                b.strays = (int)Json.num(m.get("st"), 0);
                b.roundsDone = (int)Json.num(m.get("rd"), 0);
                beats.put(b.settlementId, b);
            }
        } catch(Exception ignored){ }
    }
}
