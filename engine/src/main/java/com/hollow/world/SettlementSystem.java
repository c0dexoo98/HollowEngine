package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v7.0 — settlement system (HollowEngine 7.0 'civilization').
 *  Villages with population, stockpiles (food/wood/stone), worker assignments
 *  (farmers / woodcutters / miners), housing capacity and a construction queue
 *  for buildings. Each simulated day: production scales with workers and the
 *  season, population eats, surplus grows the village, famine shrinks it.
 */
public final class SettlementSystem {

    public static final class Settlement {
        public final String id;
        public String name;
        public int pop = 4;
        public float food = 20f, wood = 10f, stone = 0f;
        public int farmers = 2, woodcutters = 1, miners = 0;
        public int houses = 1;                       // housing = houses * 6
        public float foodPerFarmer = 2.2f;           // per day
        public float woodPerCutter = 1.5f;
        public float stonePerMiner = 1.0f;
        public float foodPerHead = 0.2f;             // daily consumption
        public int constructionDays = 3;             // days per queued building
        public final List<String> buildings = new ArrayList<>();
        public final List<String> queue = new ArrayList<>();   // blueprint ids under construction
        public int queueProgress = 0;
        public Settlement(String id, String name){ this.id = id; this.name = name; }
        public int housing(){ return houses * 6; }
        public int workers(){ return farmers + woodcutters + miners; }
    }

    public interface Listener { void onSettlementEvent(String type, String settlementId, String detail); }
    public Listener listener;

    public final Map<String, Settlement> settlements = new LinkedHashMap<>();
    public SeasonsSystem.Season season = SeasonsSystem.Season.SPRING;
    public int day = 0;
    private float seasonFoodMul(){
        switch(season){ case SPRING: return 1.2f; case SUMMER: return 1.35f;
                        case AUTUMN: return 1.0f; case WINTER: return 0.3f; default: return 1f; }
    }

    public Settlement found(String id, String name){
        Settlement s = new Settlement(id, name);
        settlements.put(id, s);
        if(listener != null) listener.onSettlementEvent("founded", id, name);
        return s;
    }
    public Settlement settlement(String id){ return settlements.get(id); }

    /** queue a building: costs are paid up front in wood/stone */
    public boolean queueBuilding(String id, String buildingName, int woodCost, int stoneCost){
        Settlement s = settlements.get(id);
        if(s == null || s.queue.size() >= 3) return false;
        if(s.wood < woodCost || s.stone < stoneCost) return false;
        s.wood -= woodCost;
        s.stone -= stoneCost;
        // reset progress only when nothing is under construction — never restart work in flight
        if(s.queue.isEmpty()) s.queueProgress = 0;
        s.queue.add(buildingName);
        if(listener != null) listener.onSettlementEvent("queued", id, buildingName);
        return true;
    }

    /** advance one in-game day */
    public void tickDay(){
        day++;
        for(Settlement s : settlements.values()){
            // production (season affects farming only)
            s.food += s.farmers * s.foodPerFarmer * seasonFoodMul();
            s.wood += s.woodcutters * s.woodPerCutter;
            s.stone += s.miners * s.stonePerMiner;

            // consumption + famine / growth
            s.food -= s.pop * s.foodPerHead;
            if(s.food < 0f){
                s.food = 0f;
                if(s.pop > 1){
                    s.pop--;
                    if(listener != null) listener.onSettlementEvent("famine", s.id, "pop=" + s.pop);
                }
            } else if(s.food > s.pop * 2f && s.pop < s.housing()){
                s.pop++;
                if(listener != null) listener.onSettlementEvent("grown", s.id, "pop=" + s.pop);
            }

            // construction queue
            if(!s.queue.isEmpty()){
                s.queueProgress++;
                if(s.queueProgress >= s.constructionDays){
                    String built = s.queue.remove(0);
                    s.queueProgress = 0;
                    s.buildings.add(built);
                    if(built.startsWith("house")){
                        s.houses++;
                        s.farmers++;                          // new settlers take up work
                    }
                    if(listener != null) listener.onSettlementEvent("built", s.id, built);
                }
            }
        }
    }

    public String describe(String id){
        Settlement s = settlements.get(id);
        if(s == null) return "unknown";
        return s.name + "[pop=" + s.pop + "/" + s.housing() + " food=" + Math.round(s.food)
            + " wood=" + Math.round(s.wood) + " stone=" + Math.round(s.stone)
            + " buildings=" + s.buildings.size() + "]";
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("season", season.name());
        w.arr("settlements");
        for(Settlement s : settlements.values()){
            w.obj().kv("id", s.id).kv("name", s.name).kv("pop", s.pop)
              .kv("food", s.food).kv("wood", s.wood).kv("stone", s.stone)
              .kv("farmers", s.farmers).kv("cutters", s.woodcutters).kv("miners", s.miners)
              .kv("houses", s.houses);
            w.arr("buildings");
            for(String b : s.buildings) w.val(b);
            w.endArr();
            w.arr("queue");
            for(String b : s.queue) w.val(b);
            w.endArr();
            w.kv("qprog", s.queueProgress).endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            try { season = SeasonsSystem.Season.valueOf(n.str("season", season.name())); } catch(Exception ignored){ }
            List<Object> ss = n.list("settlements");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String id = Json.str(m.get("id"), null);
                if(id == null) continue;
                Settlement s = settlements.get(id);
                if(s == null) s = found(id, Json.str(m.get("name"), id));
                s.pop = (int)Json.num(m.get("pop"), s.pop);
                s.food = (float)Json.num(m.get("food"), s.food);
                s.wood = (float)Json.num(m.get("wood"), s.wood);
                s.stone = (float)Json.num(m.get("stone"), s.stone);
                s.farmers = (int)Json.num(m.get("farmers"), s.farmers);
                s.woodcutters = (int)Json.num(m.get("cutters"), s.woodcutters);
                s.miners = (int)Json.num(m.get("miners"), s.miners);
                s.houses = (int)Json.num(m.get("houses"), s.houses);
                List<Object> bs = Json.arr(m.get("buildings"));
                if(bs != null) for(Object b : bs) s.buildings.add(String.valueOf(b));
                List<Object> qs = Json.arr(m.get("queue"));
                if(qs != null) for(Object b : qs) s.queue.add(String.valueOf(b));
                s.queueProgress = (int)Json.num(m.get("qprog"), 0);
            }
        } catch(Exception ignored){ }
    }
}
