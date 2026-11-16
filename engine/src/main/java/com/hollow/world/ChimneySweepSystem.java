package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v15.0 — chimney sweep system (HollowEngine 15.0 'trade & tide').
 *  One registered hearth per settlement: burning lays two layers of
 *  soot a day, each sweep crew scrapes three; when ten layers pile up
 *  a single roll (10%) sets the chimney alight — the fire counts and
 *  the flue starts clean. One crew holds a hearth; three clean it fast.
 */
public final class ChimneySweepSystem {

    public static final int FIRE_SOOT = 10;
    public static final int MAX_CREWS = 3;

    public static final class Hearth {
        public final String settlementId;
        public int soot = 0;
        public int crews = 0;
        public int fires = 0;
        public int crewDays = 0;
        public Hearth(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onSweepEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Hearth> hearths = new LinkedHashMap<>();
    public long seed = 0x2C1L;
    public int day = 0;

    /** register a hearth; one per settlement */
    public boolean register(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || hearths.containsKey(settlementId)) return false;
        hearths.put(settlementId, new Hearth(settlementId));
        if(listener != null) listener.onSweepEvent("registered", settlementId, 0);
        return true;
    }

    /** assign sweep crews to a hearth */
    public boolean assignCrews(String settlementId, int n){
        Hearth h = hearths.get(settlementId);
        if(h == null || n < 0 || n > MAX_CREWS) return false;
        h.crews = n;
        if(listener != null) listener.onSweepEvent("assigned", settlementId, n);
        return true;
    }

    /** daily tick: burning, sweeping, and the fire roll on a choked flue */
    public void tickDay(){
        day++;
        for(Hearth h : hearths.values()){
            h.soot += 2;                                  // a day of burning
            if(h.crews > 0){
                h.crewDays += h.crews;
                h.soot = Math.max(0, h.soot - h.crews * 3);
            }
            if(h.soot >= FIRE_SOOT){
                MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ h.settlementId.hashCode());
                if(rng.range(0f, 1f) < 0.1f){
                    h.fires++;
                    h.soot = 0;                           // burnt clean, darkly
                    if(listener != null) listener.onSweepEvent("fire", h.settlementId, h.fires);
                }
            }
        }
    }

    public Hearth hearth(String settlementId){ return hearths.get(settlementId); }
    public int hearthCount(){ return hearths.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("hearths");
        for(Hearth h : hearths.values())
            w.obj().kv("s", h.settlementId).kv("soot", h.soot).kv("c", h.crews)
              .kv("f", h.fires).kv("cd", h.crewDays).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> hs = n.list("hearths");
            if(hs == null) return;
            for(Object o : hs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Hearth h = new Hearth(Json.str(m.get("s"), "?"));
                h.soot = Math.max(0, (int)Json.num(m.get("soot"), 0));
                h.crews = MathX.clamp((int)Json.num(m.get("c"), 0), 0, MAX_CREWS);
                h.fires = (int)Json.num(m.get("f"), 0);
                h.crewDays = (int)Json.num(m.get("cd"), 0);
                hearths.put(h.settlementId, h);
            }
        } catch(Exception ignored){ }
    }
}
