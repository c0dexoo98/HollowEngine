package com.hollow.gameplay;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v7.0 — cooking system (HollowEngine 7.0 'civilization').
 *  Recipes combine ingredients into dishes; a deterministic quality roll
 *  (ingredients freshness × cook skill) grades the dish POOR..EXCELLENT and
 *  scales its feed values, which the game layer applies to SurvivalSystem.
 */
public final class CookingSystem {

    public enum Quality { POOR, NORMAL, GOOD, EXCELLENT }

    public static final class Recipe {
        public final String id;
        public String name;
        public final Map<String, Integer> ingredients = new LinkedHashMap<>();
        public float feedHunger = 20f, feedThirst = 0f, feedRest = 0f;
        public float freshnessRequired = 0.5f;       // ingredients below this hurt quality
        public Recipe(String id, String name){ this.id = id; this.name = name; }
        public Recipe needs(String item, int qty){ ingredients.put(item, qty); return this; }
        public Recipe feeds(float hunger, float thirst, float rest){
            feedHunger = hunger; feedThirst = thirst; feedRest = rest; return this;
        }
    }

    public static final class Dish {
        public final String recipeId;
        public final Quality quality;
        public final float hunger, thirst, rest;     // after the quality multiplier
        public Dish(String recipeId, Quality q, float h, float t, float r){
            this.recipeId = recipeId; this.quality = q; this.hunger = h; this.thirst = t; this.rest = r;
        }
    }

    /** pantry hook: check + consume raw ingredients */
    public interface Pantry {
        boolean has(Map<String, Integer> ingredients);
        void consume(Map<String, Integer> ingredients);
    }
    public Pantry pantry;
    public interface Listener { void onCookEvent(String type, String recipeId, String quality); }
    public Listener listener;

    public final Map<String, Recipe> recipes = new LinkedHashMap<>();
    public final List<String> known = new ArrayList<>();
    public long seed = 0xC00B1L;

    public Recipe define(Recipe r){ recipes.put(r.id, r); return r; }
    public Recipe recipe(String id){ return recipes.get(id); }

    public boolean learn(String recipeId){
        if(recipes.get(recipeId) == null || known.contains(recipeId)) return false;
        known.add(recipeId);
        if(listener != null) listener.onCookEvent("learned", recipeId, "");
        return true;
    }

    /** cook a known recipe: consumes ingredients, returns the graded dish */
    public Dish cook(String recipeId, float cookSkill, float ingredientsFreshness){
        Recipe r = recipes.get(recipeId);
        if(r == null || !known.contains(recipeId)) return null;
        if(pantry != null){
            if(!pantry.has(r.ingredients)){
                if(listener != null) listener.onCookEvent("missing", recipeId, "");
                return null;
            }
            pantry.consume(r.ingredients);
        }
        // quality roll: skill + freshness, deterministic
        MathX.Rng rng = new MathX.Rng(seed ^ recipeId.hashCode() * 2654435761L ^ (long)(cookSkill * 1000));
        float roll = MathX.clamp(cookSkill * 0.6f + ingredientsFreshness * 0.4f + rng.range(-0.15f, 0.15f), 0f, 1f);
        Quality q;
        float mul;
        if(roll >= 0.85f){ q = Quality.EXCELLENT; mul = 1.5f; }
        else if(roll >= 0.6f){ q = Quality.GOOD; mul = 1.2f; }
        else if(roll >= 0.3f){ q = Quality.NORMAL; mul = 1f; }
        else { q = Quality.POOR; mul = 0.6f; }
        Dish d = new Dish(recipeId, q, r.feedHunger * mul, r.feedThirst * mul, r.feedRest * mul);
        if(listener != null) listener.onCookEvent("cooked", recipeId, q.name());
        return d;
    }

    /** apply a dish to survival stats (game layer wires the systems) */
    public boolean serve(Dish dish, SurvivalSystem target){
        if(dish == null || target == null || target.dead) return false;
        target.eat(dish.hunger);
        if(dish.thirst > 0f) target.drink(dish.thirst);
        if(dish.rest > 0f) target.sleep(dish.rest);
        return true;
    }

    public boolean knows(String recipeId){ return known.contains(recipeId); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj();
        w.arr("known");
        for(String k : known) w.val(k);
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            List<Object> ks = n.list("known");
            if(ks == null) return;
            for(Object k : ks){
                String id = String.valueOf(k);
                if(recipes.containsKey(id) && !known.contains(id)) known.add(id);
            }
        } catch(Exception ignored){ }
    }
}
