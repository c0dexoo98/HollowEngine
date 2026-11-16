package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v13.0 — tannery system (HollowEngine 13.0 'ritual & remedy').
 *  Lime pits per settlement: hides soak for five days (a spoil roll can
 *  rot half of the soaking stock), then come out as leather at an 80%
 *  yield until collected.
 */
public final class TannerySystem {

    public static final int PIT_DAYS = 5;
    public static final int PIT_CAPACITY = 20;
    public static final float YIELD = 0.8f;

    public static final class Pit {
        public final String settlementId;
        public int hides = 0;
        public float leather = 0f;
        public int pitDays = 0;
        public int spoils = 0;
        public int batches = 0;
        public Pit(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onTanneryEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Pit> pits = new LinkedHashMap<>();
    public long seed = 0x7A1L;
    public int day = 0;

    /** dig a tannery; one per settlement */
    public boolean build(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || pits.containsKey(settlementId)) return false;
        pits.put(settlementId, new Pit(settlementId));
        if(listener != null) listener.onTanneryEvent("built", settlementId, PIT_CAPACITY);
        return true;
    }

    /** deliver hides into the pit; refuses overflow and unknown pits */
    public boolean deliverHides(String settlementId, int units){
        Pit p = pits.get(settlementId);
        if(p == null || units <= 0 || p.hides + units > PIT_CAPACITY) return false;
        p.hides += units;
        if(listener != null) listener.onTanneryEvent("delivered", settlementId, units);
        return true;
    }

    /** daily tick: soaking, spoilage, tanning */
    public void tickDay(){
        day++;
        for(Pit p : pits.values()){
            if(p.hides > 0){
                MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ p.settlementId.hashCode());
                if(rng.range(0f, 1f) < 0.05f){
                    int spoiled = Math.max(1, Math.round(p.hides * 0.5f));
                    p.hides -= spoiled;
                    p.spoils += spoiled;
                    if(listener != null) listener.onTanneryEvent("spoiled", p.settlementId, spoiled);
                }
                p.pitDays++;
                if(p.pitDays >= PIT_DAYS){
                    p.leather += p.hides * YIELD;
                    p.batches++;
                    p.hides = 0;
                    p.pitDays = 0;
                    if(listener != null) listener.onTanneryEvent("tanned", p.settlementId, p.batches);
                }
            } else {
                p.pitDays = 0;
            }
        }
    }

    /** collect the finished leather; returns what was taken */
    public float takeLeather(String settlementId){
        Pit p = pits.get(settlementId);
        if(p == null || p.leather <= 0f) return 0f;
        float taken = p.leather;
        p.leather = 0f;
        if(listener != null) listener.onTanneryEvent("leather_taken", settlementId, (int)taken);
        return taken;
    }

    public float leatherOf(String settlementId){
        Pit p = pits.get(settlementId);
        return p == null ? 0f : p.leather;
    }

    public Pit pit(String settlementId){ return pits.get(settlementId); }
    public int pitCount(){ return pits.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("pits");
        for(Pit p : pits.values())
            w.obj().kv("s", p.settlementId).kv("h", p.hides).kv("l", p.leather)
              .kv("pd", p.pitDays).kv("sp", p.spoils).kv("bt", p.batches).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ps = n.list("pits");
            if(ps == null) return;
            for(Object o : ps){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Pit p = new Pit(Json.str(m.get("s"), "?"));
                p.hides = MathX.clamp((int)Json.num(m.get("h"), 0), 0, PIT_CAPACITY);
                p.leather = (float)Json.num(m.get("l"), 0f);
                p.pitDays = (int)Json.num(m.get("pd"), 0);
                p.spoils = (int)Json.num(m.get("sp"), 0);
                p.batches = (int)Json.num(m.get("bt"), 0);
                pits.put(p.settlementId, p);
            }
        } catch(Exception ignored){ }
    }
}
