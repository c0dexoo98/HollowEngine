package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v19.0 — wash house system (HollowEngine 19.0 'spindles & shuttles').
 *  One wash house per village: fleeces are brought to the troughs (up
 *  to the house's capacity); each day up to three fleeces are washed
 *  and one roll decides the water — 90% clean wash (each clean day
 *  also renders one pot of lanolin), 10% the trough scalds and the
 *  day's fleeces spoil (counted honestly).
 */
public final class WashHouseSystem {

    public static final int FLEECES_CAP = 18;
    public static final int FLEECES_PER_DAY = 3;

    public static final class Trough {
        public final String villageId;
        public int fleeces = 0;
        public int washed = 0;
        public int lanolin = 0;
        public int spoil = 0;
        public Trough(String villageId){
            this.villageId = villageId;
        }
    }

    public interface Listener { void onWashEvent(String type, String villageId, int value); }
    public Listener listener;

    public final Map<String, Trough> troughs = new LinkedHashMap<>();
    public long seed = 0x4A5L;
    public int day = 0;

    /** build a wash house; one per village */
    public boolean build(String villageId){
        if(villageId == null || villageId.isEmpty() || troughs.containsKey(villageId)) return false;
        troughs.put(villageId, new Trough(villageId));
        if(listener != null) listener.onWashEvent("built", villageId, 0);
        return true;
    }

    /** bring fleeces; refuses overflow and unknown houses */
    public boolean bring(String villageId, int fleeces){
        Trough t = troughs.get(villageId);
        if(t == null || fleeces <= 0 || t.fleeces + fleeces > FLEECES_CAP) return false;
        t.fleeces += fleeces;
        if(listener != null) listener.onWashEvent("brought", villageId, fleeces);
        return true;
    }

    /** daily tick: up to three fleeces meet the water, one roll for the trough */
    public void tickDay(){
        day++;
        for(Trough t : troughs.values()){
            if(t.fleeces <= 0) continue;
            int batch = Math.min(FLEECES_PER_DAY, t.fleeces);
            t.fleeces -= batch;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ t.villageId.hashCode());
            if(rng.range(0f, 1f) < 0.1f){
                t.spoil += batch;
                if(listener != null) listener.onWashEvent("scalded", t.villageId, batch);
            } else {
                t.washed += batch;
                t.lanolin += 1;
                if(listener != null) listener.onWashEvent("washed", t.villageId, batch);
            }
        }
    }

    public Trough trough(String villageId){ return troughs.get(villageId); }
    public int troughCount(){ return troughs.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("troughs");
        for(Trough t : troughs.values())
            w.obj().kv("v", t.villageId).kv("f", t.fleeces).kv("w", t.washed)
              .kv("l", t.lanolin).kv("s", t.spoil).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ts = n.list("troughs");
            if(ts == null) return;
            for(Object o : ts){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Trough t = new Trough(Json.str(m.get("v"), "?"));
                t.fleeces = MathX.clamp((int)Json.num(m.get("f"), 0), 0, FLEECES_CAP);
                t.washed = (int)Json.num(m.get("w"), 0);
                t.lanolin = (int)Json.num(m.get("l"), 0);
                t.spoil = (int)Json.num(m.get("s"), 0);
                troughs.put(t.villageId, t);
            }
        } catch(Exception ignored){ }
    }
}
