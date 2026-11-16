package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.LinkedHashMap;
import java.util.Map;

/** v12.0 — apprentice system (HollowEngine 12.0 'trades & crafts').
 *  Masters take apprentices in a trade; each day of training counts and
 *  after enough of them the apprentice is released as a journeyman.
 *  An apprentice can quit early, and nobody is bound twice.
 */
public final class ApprenticeSystem {

    public static final class Pair {
        public final String master;
        public final String apprentice;
        public final String trade;
        public final int trainingDays;
        public int daysTrained = 0;
        public Pair(String master, String apprentice, String trade, int trainingDays){
            this.master = master; this.apprentice = apprentice; this.trade = trade;
            this.trainingDays = Math.max(1, trainingDays);
        }
    }

    public interface Listener { void onApprenticeEvent(String type, String apprentice, String detail); }
    public Listener listener;

    public final Map<String, Pair> bindings = new LinkedHashMap<>();     // apprentice → pair
    public int day = 0;
    public int journeymen = 0;

    /** bind an apprentice to a master; refuses empty names and double binding */
    public boolean bind(String master, String apprentice, String trade, int trainingDays){
        if(master == null || master.isEmpty() || apprentice == null || apprentice.isEmpty()
           || trade == null || trade.isEmpty() || master.equals(apprentice)
           || bindings.containsKey(apprentice)) return false;
        bindings.put(apprentice, new Pair(master, apprentice, trade, trainingDays));
        if(listener != null) listener.onApprenticeEvent("bound", apprentice, master + ":" + trade);
        return true;
    }

    /** daily tick: training days accumulate; finished apprentices graduate */
    public void tickDay(){
        day++;
        java.util.List<String> done = new java.util.ArrayList<>();
        for(Pair p : bindings.values()){
            p.daysTrained++;
            if(p.daysTrained >= p.trainingDays) done.add(p.apprentice);
        }
        for(String name : done){
            Pair p = bindings.remove(name);
            journeymen++;
            if(listener != null) listener.onApprenticeEvent("journeyman", name,
                p.trade + " under " + p.master);
        }
    }

    /** an apprentice walks out before finishing */
    public boolean quit(String apprentice){
        Pair p = bindings.remove(apprentice);
        if(p == null) return false;
        if(listener != null) listener.onApprenticeEvent("quit", apprentice,
            p.trade + " after " + p.daysTrained);
        return true;
    }

    public Pair bindingOf(String apprentice){ return bindings.get(apprentice); }
    public int bindingCount(){ return bindings.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("jm", journeymen);
        w.arr("bindings");
        for(Pair p : bindings.values())
            w.obj().kv("m", p.master).kv("a", p.apprentice).kv("t", p.trade)
              .kv("td", p.trainingDays).kv("d", p.daysTrained).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            journeymen = (int)n.flt("jm", journeymen);
            java.util.List<Object> bs = n.list("bindings");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Pair p = new Pair(Json.str(m.get("m"), "?"), Json.str(m.get("a"), "?"),
                    Json.str(m.get("t"), "?"), (int)Json.num(m.get("td"), 1));
                p.daysTrained = (int)Json.num(m.get("d"), 0);
                bindings.put(p.apprentice, p);
            }
        } catch(Exception ignored){ }
    }
}
