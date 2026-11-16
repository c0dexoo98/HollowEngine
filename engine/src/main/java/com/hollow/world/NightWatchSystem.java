package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v16.0 — night watch system (HollowEngine 16.0 'turns & rounds').
 *  One watch per settlement: each enlisted watchman walks one round a
 *  night (deterministic), and the watch as a whole rolls once — 5%
 *  trouble on the rounds, else a quiet night counted honestly.
 */
public final class NightWatchSystem {

    public static final int MAX_WATCHMEN = 4;

    public static final class Watch {
        public final String settlementId;
        public int watchmen = 0;
        public int roundsDone = 0;
        public int incidents = 0;
        public int quietNights = 0;
        public Watch(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onWatchEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Watch> watches = new LinkedHashMap<>();
    public long seed = 0x4C1L;
    public int day = 0;

    /** enlist a night watch; one per settlement */
    public boolean enlist(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || watches.containsKey(settlementId)) return false;
        watches.put(settlementId, new Watch(settlementId));
        if(listener != null) listener.onWatchEvent("enlisted", settlementId, 0);
        return true;
    }

    /** assign watchmen to the night rounds */
    public boolean assignWatchmen(String settlementId, int n){
        Watch w = watches.get(settlementId);
        if(w == null || n < 0 || n > MAX_WATCHMEN) return false;
        w.watchmen = n;
        if(listener != null) listener.onWatchEvent("assigned", settlementId, n);
        return true;
    }

    /** nightly tick: the rounds, one roll for trouble */
    public void tickDay(){
        day++;
        for(Watch w : watches.values()){
            if(w.watchmen <= 0) continue;
            w.roundsDone += w.watchmen;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ w.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.05f){
                w.incidents++;
                if(listener != null) listener.onWatchEvent("incident", w.settlementId, w.incidents);
            } else {
                w.quietNights++;
                if(listener != null) listener.onWatchEvent("quiet", w.settlementId, w.quietNights);
            }
        }
    }

    public Watch watch(String settlementId){ return watches.get(settlementId); }
    public int watchCount(){ return watches.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("watches");
        for(Watch x : watches.values())
            w.obj().kv("s", x.settlementId).kv("m", x.watchmen).kv("rd", x.roundsDone)
              .kv("inc", x.incidents).kv("qn", x.quietNights).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ws = n.list("watches");
            if(ws == null) return;
            for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Watch w = new Watch(Json.str(m.get("s"), "?"));
                w.watchmen = MathX.clamp((int)Json.num(m.get("m"), 0), 0, MAX_WATCHMEN);
                w.roundsDone = (int)Json.num(m.get("rd"), 0);
                w.incidents = (int)Json.num(m.get("inc"), 0);
                w.quietNights = (int)Json.num(m.get("qn"), 0);
                watches.put(w.settlementId, w);
            }
        } catch(Exception ignored){ }
    }
}
