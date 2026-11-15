package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v6.0 — wildlife ecosystem (HollowEngine 6.0 'living-world').
 *  Species populations (plant / prey / predator) with a predation matrix and
 *  fertility driven by grass cover and season. Each simulated day applies
 *  Lotka-Volterra-inspired steps: plants regrow, prey graze, predators hunt.
 *  Populations clamp to carrying capacity; a species below 1 goes extinct and
 *  fires an event. Deterministic with a per-day Rng for hunt luck.
 */
public final class EcosystemSystem {

    public enum Role { PLANT, PREY, PREDATOR }

    public static final class Species {
        public final String id;
        public String name;
        public Role role;
        public float pop = 10f;
        public float capacity = 100f;           // carrying capacity
        public float growthRate = 0.15f;        // per day, plants only
        public float huntSuccess = 0.25f;       // predators: chance-weighted per prey eaten
        public float grazeRate = 0.02f;         // prey: fraction of plant pop eaten per head
        public float metabolism = 0.03f;        // starvation per day per head
        public final List<String> preyOf = new ArrayList<>();   // what this predator eats
        public Species(String id, String name, Role role){ this.id = id; this.name = name; this.role = role; }
        public Species eats(String preyId){ preyOf.add(preyId); return this; }
    }

    public interface Listener { void onEcoEvent(String type, String speciesId, float pop); }
    public Listener listener;

    public final Map<String, Species> species = new LinkedHashMap<>();
    public int day = 0;
    public long seed = 0xEC051L;
    public float fertility = 0.6f;              // soil richness 0..1
    public SeasonsSystem.Season season = SeasonsSystem.Season.SPRING;
    /** season modifiers: winter starves predators/prey, spring blooms plants */
    private float seasonPlantMul(){
        switch(season){ case SPRING: return 1.6f; case SUMMER: return 1.2f;
                        case AUTUMN: return 0.8f; case WINTER: return 0.25f; default: return 1f; }
    }
    private float seasonMetabolismMul(){
        switch(season){ case WINTER: return 1.8f; case AUTUMN: return 1.1f; default: return 1f; }
    }

    public Species define(Species s){ species.put(s.id, s); return s; }
    public Species species(String id){ return species.get(id); }

    /** advance one simulated day */
    public void tickDay(){
        day++;
        MathX.Rng rng = new MathX.Rng(seed ^ day * 0x9E37L);
        float plantMul = seasonPlantMul();
        float metabMul = seasonMetabolismMul();

        // 1) plants regrow toward capacity (logistic)
        for(Species sp : species.values()){
            if(sp.role != Role.PLANT || extinct(sp)) continue;
            float growth = sp.pop * sp.growthRate * plantMul * fertility * (1f - sp.pop / Math.max(1f, sp.capacity));
            sp.pop = MathX.clamp(sp.pop + growth, 0f, sp.capacity);
        }

        // 2) prey graze; shortage starves them
        for(Species prey : species.values()){
            if(prey.role != Role.PREY || extinct(prey)) continue;
            float food = plantCover(prey);
            float eaten = prey.pop * prey.grazeRate * Math.min(1f, food / Math.max(1f, prey.pop));
            consumePlants(prey, eaten);
            float starve = prey.pop * prey.metabolism * metabMul * (food < prey.pop ? 1.6f : 0.7f);
            prey.pop = Math.max(0f, prey.pop - starve);
            // birth bonus when well fed
            if(food > prey.pop * 1.5f) prey.pop = Math.min(prey.capacity, prey.pop + prey.pop * 0.08f);
        }

        // 3) predators hunt their prey
        for(Species pred : species.values()){
            if(pred.role != Role.PREDATOR || extinct(pred)) continue;
            float totalFood = 0f;
            for(String preyId : pred.preyOf){
                Species prey = species.get(preyId);
                if(prey == null || extinct(prey)) continue;
                // hunt yield scales with prey density — predators starve only when prey is truly scarce
                float density = Math.min(2f, prey.pop / 10f);
                float kills = Math.min(prey.pop,
                    pred.pop * pred.huntSuccess * density * (0.6f + rng.range(0f, 0.8f)));
                prey.pop = Math.max(0f, prey.pop - kills);
                totalFood += kills;
            }
            float feed = totalFood / Math.max(0.5f, pred.pop);
            float starve = pred.pop * pred.metabolism * metabMul * (feed < 0.35f ? 2.2f : 0.6f);
            pred.pop = Math.max(0f, pred.pop - starve);
            if(feed >= 0.5f) pred.pop = Math.min(pred.capacity, pred.pop + pred.pop * 0.06f);
        }

        // 4) extinctions
        for(Species sp : species.values()){
            if(!extinct(sp) && sp.pop < 1f){
                sp.pop = 0f;
                if(listener != null) listener.onEcoEvent("extinct", sp.id, 0f);
            }
        }
    }

    /** relative plant abundance a grazer feeds on (scaled by its grazing weight) */
    private float plantCover(Species grazer){
        float total = 0f;
        for(Species sp : species.values())
            if(sp.role == Role.PLANT && !extinct(sp)) total += sp.pop;
        return total * grazer.grazeRate * 50f;   // scale into "supportable heads"
    }
    private void consumePlants(Species grazer, float headsWorth){
        float need = headsWorth / Math.max(1f, grazer.grazeRate * 50f);
        for(Species sp : species.values()){
            if(sp.role != Role.PLANT || extinct(sp) || need <= 0f) continue;
            float take = Math.min(sp.pop, need);
            sp.pop -= take;
            need -= take;
        }
    }
    private static boolean extinct(Species s){ return s.pop <= 0f; }

    public float totalPopulation(Role role){
        float sum = 0f;
        for(Species sp : species.values()) if(sp.role == role) sum += sp.pop;
        return sum;
    }
    public int livingSpecies(){
        int n = 0;
        for(Species sp : species.values()) if(!extinct(sp)) n++;
        return n;
    }

    public String describe(){
        StringBuilder sb = new StringBuilder("eco[day=" + day);
        for(Species sp : species.values())
            sb.append(' ').append(sp.id).append('=').append(Math.round(sp.pop));
        return sb.append(']').toString();
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("fert", fertility).kv("season", season.name());
        w.arr("species");
        for(Species sp : species.values())
            w.obj().kv("id", sp.id).kv("pop", sp.pop).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            fertility = MathX.clamp(n.flt("fert", fertility), 0f, 1f);
            try { season = SeasonsSystem.Season.valueOf(n.str("season", season.name())); } catch(Exception ignored){ }
            List<Object> ss = n.list("species");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Species sp = species.get(Json.str(m.get("id"), ""));
                if(sp != null) sp.pop = Math.max(0f, (float)Json.num(m.get("pop"), sp.pop));
            }
        } catch(Exception ignored){ }
    }
}
