package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v14.0 — candlemaker system (HollowEngine 14.0 'watches & works').
 *  The chandler's shop holds wax per settlement; each day it dips what
 *  the stock allows (two candles per wax, capped), then one roll may
 *  smoke the rack — a sooty batch loses half its candles.
 */
public final class CandlemakerSystem {

    public static final int DIP_CAP = 40;

    public interface Listener { void onChandlerEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Integer> wax = new LinkedHashMap<>();
    public final Map<String, Integer> candles = new LinkedHashMap<>();
    public final Map<String, Integer> sootStrikes = new LinkedHashMap<>();
    public long seed = 0xC4L;
    public int day = 0;

    /** deliver wax to a shop */
    public boolean deliverWax(String settlementId, int units){
        if(settlementId == null || settlementId.isEmpty() || units <= 0) return false;
        wax.merge(settlementId, units, Integer::sum);
        if(!candles.containsKey(settlementId)) candles.put(settlementId, 0);
        if(!sootStrikes.containsKey(settlementId)) sootStrikes.put(settlementId, 0);
        return true;
    }

    /** daily tick: dipping, the soot roll */
    public void tickDay(){
        day++;
        for(Map.Entry<String, Integer> e : wax.entrySet()){
            String s = e.getKey();
            int stock = e.getValue();
            int made = Math.min(stock * 2, DIP_CAP);
            if(made <= 0) continue;
            wax.put(s, stock - made / 2);
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ s.hashCode());
            if(rng.range(0f, 1f) < 0.05f){
                made -= Math.max(1, made / 2);
                sootStrikes.merge(s, 1, Integer::sum);
                if(listener != null) listener.onChandlerEvent("sooty", s, made);
            }
            candles.merge(s, made, Integer::sum);
            if(listener != null) listener.onChandlerEvent("dipped", s, made);
        }
    }

    public int candlesOf(String settlementId){ return candles.getOrDefault(settlementId, 0); }
    public int waxOf(String settlementId){ return wax.getOrDefault(settlementId, 0); }
    public int sootOf(String settlementId){ return sootStrikes.getOrDefault(settlementId, 0); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("shops");
        for(Map.Entry<String, Integer> e : wax.entrySet())
            w.obj().kv("s", e.getKey()).kv("wax", e.getValue())
              .kv("cd", candles.getOrDefault(e.getKey(), 0))
              .kv("soot", sootStrikes.getOrDefault(e.getKey(), 0)).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ss = n.list("shops");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String s = Json.str(m.get("s"), "?");
                wax.put(s, Math.max(0, (int)Json.num(m.get("wax"), 0)));
                candles.put(s, Math.max(0, (int)Json.num(m.get("cd"), 0)));
                sootStrikes.put(s, Math.max(0, (int)Json.num(m.get("soot"), 0)));
            }
        } catch(Exception ignored){ }
    }
}
