package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.List;

/** v8.0 — migration system (HollowEngine 8.0 'kingdom').
 *  Settlers move between SettlementSystem settlements: famine and overcrowding
 *  push them out, food surplus with free housing pulls them in. Deterministic
 *  via Rng; operates directly on the shared Settlement objects.
 */
public final class MigrationSystem {

    public interface Listener { void onMigrationEvent(String type, String fromId, String toId, int count); }
    public Listener listener;

    public final SettlementSystem world;
    public long seed = 0x00081L;
    public int day = 0;
    public float pushFoodRatio = 2f;                 // food < pop × this → leave
    public float pullFoodRatio = 3f;                 // food > pop × this → attract
    public int maxMoversPerDay = 2;

    public MigrationSystem(SettlementSystem world){ this.world = world; }

    /** daily migration pass */
    public void tickDay(){
        day++;
        List<SettlementSystem.Settlement> all = new ArrayList<>(world.settlements.values());
        MathX.Rng rng = new MathX.Rng(seed ^ (day * 7717L));
        for(SettlementSystem.Settlement from : all){
            if(from.pop <= 1) continue;
            boolean starving = from.food < from.pop * pushFoodRatio;
            boolean overcrowded = from.pop >= from.housing() && from.food < from.pop * pullFoodRatio;
            if(!starving && !overcrowded) continue;

            int movers = 1 + rng.nextInt(Math.max(1, Math.min(maxMoversPerDay, from.pop - 1)));
            for(SettlementSystem.Settlement to : all){
                if(movers == 0) break;
                if(to == from) continue;
                boolean attractive = to.food > to.pop * pullFoodRatio && to.pop < to.housing();
                if(!attractive) continue;
                int move = Math.min(movers, to.housing() - to.pop);
                if(move <= 0) continue;
                from.pop -= move;
                to.pop += move;
                movers -= move;
                if(listener != null) listener.onMigrationEvent("moved", from.id, to.id, move);
            }
        }
    }

    /** total population across the realm */
    public int realmPopulation(){
        int n = 0;
        for(SettlementSystem.Settlement s : world.settlements.values()) n += s.pop;
        return n;
    }

    // ---------------- persistence (only tuning + clock; pops live in SettlementSystem) ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("push", pushFoodRatio).kv("pull", pullFoodRatio)
          .kv("max", maxMoversPerDay).endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            pushFoodRatio = (float)n.flt("push", pushFoodRatio);
            pullFoodRatio = (float)n.flt("pull", pullFoodRatio);
            maxMoversPerDay = (int)n.flt("max", maxMoversPerDay);
        } catch(Exception ignored){ }
    }
}
