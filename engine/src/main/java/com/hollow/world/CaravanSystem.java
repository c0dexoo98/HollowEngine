package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v7.0 — caravan trade system (HollowEngine 7.0 'civilization').
 *  Caravans run goods between settlements. Sale price scales with how scarce the
 *  cargo is at the destination (stockpile below its comfort level pays more),
 *  dangerous routes can lose the whole cargo to bandits. Deterministic via Rng.
 */
public final class CaravanSystem {

    public enum State { TRAVELING, ARRIVED, ROBBED }

    public static final class Route {
        public final String id;
        public final String fromId, toId;
        public float travelDays = 4f;
        public float danger = 0.05f;                 // robbery chance per trip
        public Route(String id, String fromId, String toId){
            this.id = id; this.fromId = fromId; this.toId = toId;
        }
        public Route travelDays(float d){ travelDays = Math.max(1f, d); return this; }
        public Route danger(float d){ danger = MathX.clamp(d, 0f, 1f); return this; }
    }

    public static final class Caravan {
        public final int id;
        public final String routeId;
        public Map<String, Integer> cargo;           // item → units
        public int cargoCost = 0;                    // gold spent buying the cargo
        public float progress = 0f;
        public State state = State.TRAVELING;
        public int profit = 0;
        public Caravan(int id, String routeId, Map<String, Integer> cargo, int cargoCost){
            this.id = id; this.routeId = routeId; this.cargo = cargo; this.cargoCost = cargoCost;
        }
    }

    /** settlement stockpile + gold wallet provider (wire to SettlementSystem / player gold) */
    public interface Market {
        int gold(String settlementId);
        void spend(String settlementId, int amount);
        void earn(String settlementId, int amount);
        /** comfort stockpile of an item at a settlement (units); scarcity drives prices */
        float stockpile(String settlementId, String itemId);
    }
    public Market market;
    public interface Listener { void onCaravanEvent(String type, int caravanId, String detail); }
    public Listener listener;

    public final Map<String, Route> routes = new LinkedHashMap<>();
    public final Map<Integer, Caravan> caravans = new LinkedHashMap<>();
    public int gold = 100;                            // player gold investing in caravans
    public long seed = 0xCA7A1L;
    public int day = 0;
    private int nextId = 1;

    public Route define(Route r){ routes.put(r.id, r); return r; }
    public Route route(String id){ return routes.get(id); }
    public Caravan caravan(int id){ return caravans.get(id); }

    /** send a caravan: buys the cargo at the player's cost, debits player gold */
    public Caravan send(String routeId, Map<String, Integer> cargo, int cargoCost){
        Route r = routes.get(routeId);
        if(r == null || cargo == null || cargo.isEmpty() || cargoCost > gold) return null;
        gold -= cargoCost;
        Caravan c = new Caravan(nextId++, routeId, cargo, cargoCost);
        caravans.put(c.id, c);
        if(listener != null) listener.onCaravanEvent("sent", c.id, routeId);
        return c;
    }

    /** price an item would fetch at a settlement: scarce stockpile → better price */
    public float priceAt(String settlementId, String itemId, int units){
        if(market == null) return units * 2f;
        float stock = market.stockpile(settlementId, itemId);
        float scarcity = MathX.clamp(1f - stock / 50f, 0.2f, 1.6f);   // rich market 0.2×, starved 1.6×
        return units * 2f * scarcity;
    }

    /** advance one in-game day */
    public void tickDay(){
        day++;
        for(Caravan c : caravans.values()){
            if(c.state != State.TRAVELING) continue;
            Route r = routes.get(c.routeId);
            if(r == null) continue;
            c.progress += 1f;
            if(c.progress < r.travelDays) continue;

            // arrival: robbery roll first
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 31L + c.id));
            if(rng.range(0f, 1f) < r.danger){
                c.state = State.ROBBED;
                if(listener != null) listener.onCaravanEvent("robbed", c.id, r.id);
                continue;
            }
            int sale = 0;
            for(Map.Entry<String, Integer> kv : c.cargo.entrySet())
                sale += priceAt(r.toId, kv.getKey(), kv.getValue());
            c.profit = sale - c.cargoCost;
            gold += sale;
            if(market != null) market.spend(r.toId, sale);            // destination pays from its treasury
            c.state = State.ARRIVED;
            if(listener != null) listener.onCaravanEvent("arrived", c.id, r.id + " profit=" + c.profit);
        }
    }

    public int arrivedCount(){
        int n = 0;
        for(Caravan c : caravans.values()) if(c.state == State.ARRIVED) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("gold", gold).kv("nextId", nextId);
        w.arr("caravans");
        for(Caravan c : caravans.values()){
            w.obj().kv("id", c.id).kv("route", c.routeId).kv("prog", c.progress)
              .kv("state", c.state.name()).kv("cost", c.cargoCost).kv("profit", c.profit);
            w.arr("cargo");
            for(Map.Entry<String, Integer> kv : c.cargo.entrySet())
                w.obj().kv("item", kv.getKey()).kv("qty", kv.getValue()).endObj();
            w.endArr();
            w.endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            gold = (int)n.flt("gold", gold);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> cs = n.list("caravans");
            if(cs == null) return;
            for(Object o : cs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Map<String, Integer> cargo = new LinkedHashMap<>();
                List<Object> items = Json.arr(m.get("cargo"));
                if(items != null) for(Object io : items){
                    Map<String,Object> im = Json.obj(io);
                    if(im != null) cargo.put(Json.str(im.get("item"), ""), (int)Json.num(im.get("qty"), 0));
                }
                Caravan c = new Caravan((int)Json.num(m.get("id"), nextId++), Json.str(m.get("route"), ""),
                    cargo, (int)Json.num(m.get("cost"), 0));
                c.progress = (float)Json.num(m.get("prog"), 0f);
                try { c.state = State.valueOf(Json.str(m.get("state"), "TRAVELING")); } catch(Exception ignored){ }
                c.profit = (int)Json.num(m.get("profit"), 0);
                caravans.put(c.id, c);
            }
        } catch(Exception ignored){ }
    }
}
