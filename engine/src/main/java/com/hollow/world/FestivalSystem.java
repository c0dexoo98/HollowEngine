package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v8.0 — festival system (HollowEngine 8.0 'kingdom').
 *  Settlements schedule festivals: on their start day they become ACTIVE
 *  (cost is exposed so the game layer can pay from the treasury), boost
 *  morale for the duration, then end. Stacking boosts are summed.
 */
public final class FestivalSystem {

    public enum State { SCHEDULED, ACTIVE, ENDED }

    public static final class Festival {
        public final int id;
        public final String name;
        public final String settlementId;
        public final int startDay;
        public final int durationDays;
        public final int cost;
        public float moraleBoost = 15f;
        public State state = State.SCHEDULED;
        public Festival(int id, String name, String settlementId, int startDay, int durationDays, int cost){
            this.id = id; this.name = name; this.settlementId = settlementId;
            this.startDay = startDay; this.durationDays = durationDays; this.cost = cost;
        }
    }

    public interface Listener { void onFestivalEvent(String type, int festivalId, String detail); }
    public Listener listener;

    public final Map<Integer, Festival> festivals = new LinkedHashMap<>();
    public int day = 0;
    private int nextId = 1;

    /** schedule a festival; cost is charged by the game layer (e.g. TreasurySystem) */
    public Festival schedule(String name, String settlementId, int startDay, int durationDays, int cost){
        if(name == null || name.isEmpty() || settlementId == null || durationDays <= 0) return null;
        Festival f = new Festival(nextId++, name, settlementId, Math.max(day, startDay), durationDays, Math.max(0, cost));
        festivals.put(f.id, f);
        if(listener != null) listener.onFestivalEvent("scheduled", f.id, settlementId);
        return f;
    }

    public Festival festival(int id){ return festivals.get(id); }

    /** daily tick: start and end festivals by the clock */
    public void tickDay(){
        day++;
        for(Festival f : festivals.values()){
            if(f.state == State.SCHEDULED && day >= f.startDay){
                f.state = State.ACTIVE;
                if(listener != null) listener.onFestivalEvent("started", f.id, f.settlementId);
            } else if(f.state == State.ACTIVE && day >= f.startDay + f.durationDays){
                f.state = State.ENDED;
                if(listener != null) listener.onFestivalEvent("ended", f.id, f.settlementId);
            }
        }
    }

    /** is any festival live in the settlement right now? */
    public boolean isActive(String settlementId){
        for(Festival f : festivals.values())
            if(f.state == State.ACTIVE && f.settlementId.equals(settlementId)) return true;
        return false;
    }

    /** summed morale boost of active festivals in a settlement */
    public float moraleOf(String settlementId){
        float sum = 0f;
        for(Festival f : festivals.values())
            if(f.state == State.ACTIVE && f.settlementId.equals(settlementId)) sum += f.moraleBoost;
        return sum;
    }

    public List<Festival> active(){
        List<Festival> out = new ArrayList<>();
        for(Festival f : festivals.values()) if(f.state == State.ACTIVE) out.add(f);
        return out;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId);
        w.arr("festivals");
        for(Festival f : festivals.values()){
            w.obj().kv("id", f.id).kv("name", f.name).kv("settle", f.settlementId)
              .kv("start", f.startDay).kv("dur", f.durationDays).kv("cost", f.cost)
              .kv("boost", f.moraleBoost).kv("state", f.state.name()).endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> fs = n.list("festivals");
            if(fs == null) return;
            for(Object o : fs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Festival f = new Festival((int)Json.num(m.get("id"), nextId++), Json.str(m.get("name"), "?"),
                    Json.str(m.get("settle"), ""), (int)Json.num(m.get("start"), day),
                    (int)Json.num(m.get("dur"), 1), (int)Json.num(m.get("cost"), 0));
                f.moraleBoost = (float)Json.num(m.get("boost"), f.moraleBoost);
                try { f.state = State.valueOf(Json.str(m.get("state"), "SCHEDULED")); } catch(Exception ignored){ }
                festivals.put(f.id, f);
            }
        } catch(Exception ignored){ }
    }
}
