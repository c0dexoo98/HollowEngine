package com.hollow.gameplay;

import com.hollow.core.Json;
import com.hollow.core.JsonV;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.0.0 GAMEPLAY: player inventory — grid + weight + equip + crafting.
 *
 * Design: engine-sized (no UI coupling). The game layer reads state and renders
 * however it wants; this class owns the rules: slots, stacking, weight limit,
 * equipment slots, and a small crafting registry (recipe = inputs -> output).
 * Serializable to/from JSON for the existing SaveSystem.
 */
public final class Inventory {

    public static final class Item {
        public String id;              // "medkit", "ammo_rifle"
        public int count = 1;
        public float weightEach = 0f;  // kg
        public boolean stackable = true;
        public String equipSlot;       // null = not equippable; "weapon", "armor", ...

        public Item(){ }
        public Item(String id, int count, float weightEach, boolean stackable, String equipSlot){
            this.id = id; this.count = count; this.weightEach = weightEach;
            this.stackable = stackable; this.equipSlot = equipSlot;
        }
        public float totalWeight(){ return count * weightEach; }
    }

    public final List<Item> slots = new ArrayList<>();   // grid rows = capacity/cols
    public int capacity = 20;                            // max distinct stacks
    public float maxWeight = 60f;                        // 0 = unlimited
    public final Map<String, Item> equipped = new LinkedHashMap<>();  // slotName -> item

    public int gold = 0;

    /** used weight; over limit blocks pickup */
    public float weight(){
        float w = 0f;
        for(Item it : slots) w += it.totalWeight();
        return w;
    }

    /** try to add items; returns the number actually added (0 when full/heavy) */
    public int add(String id, int count, float weightEach, boolean stackable, String equipSlot){
        if(count <= 0) return 0;
        if(maxWeight > 0f){
            float free = maxWeight - weight();
            int byWeight = (int)Math.floor(free / Math.max(1e-6f, weightEach));
            if(byWeight <= 0) return 0;
            count = Math.min(count, byWeight);
        }
        int remaining = count;
        if(stackable){
            for(Item it : slots){
                if(remaining <= 0) break;
                if(it.id.equals(id) && it.stackable){
                    int space = Integer.MAX_VALUE;
                    int take = Math.min(remaining, space);
                    it.count += take; remaining -= take;
                }
            }
        }
        while(remaining > 0 && slots.size() < capacity){
            int take = stackable ? remaining : 1;
            slots.add(new Item(id, take, weightEach, stackable, equipSlot));
            remaining -= take;
            if(!stackable && remaining > 0 && slots.size() >= capacity) break;
        }
        return count - remaining;
    }

    /** remove `count` of `id`; returns how many were actually removed */
    public int remove(String id, int count){
        int remaining = count;
        for(int i = slots.size() - 1; i >= 0 && remaining > 0; i--){
            Item it = slots.get(i);
            if(!it.id.equals(id)) continue;
            int take = Math.min(remaining, it.count);
            it.count -= take; remaining -= take;
            if(it.count <= 0){
                unequipIfEquipped(it);
                slots.remove(i);
            }
        }
        return count - remaining;
    }

    public int count(String id){
        int c = 0;
        for(Item it : slots) if(it.id.equals(id)) c += it.count;
        return c;
    }

    public boolean has(String id, int count){ return count(id) >= count; }

    /** equip the first stack of `id` into its slot; returns false when not equippable */
    public boolean equip(String id){
        for(Item it : slots){
            if(it.id.equals(id) && it.equipSlot != null){
                Item prev = equipped.put(it.equipSlot, it);
                if(prev != null && prev != it){ /* both equipped: prev stays in grid, no-op */ }
                return true;
            }
        }
        return false;
    }

    public boolean unequip(String slotName){
        Item it = equipped.remove(slotName);
        return it != null;
    }

    private void unequipIfEquipped(Item it){
        if(it == null) return;
        equipped.values().removeIf(e -> e == it);
    }

    // ---------------- crafting ----------------

    public static final class Recipe {
        public final String outputId;
        public final int outputCount;
        public final String[] inputIds;
        public final int[] inputCounts;
        public Recipe(String out, int outCount, String[] inIds, int[] inCounts){
            this.outputId = out; this.outputCount = outCount;
            this.inputIds = inIds; this.inputCounts = inCounts;
        }
    }

    public final List<Recipe> recipes = new ArrayList<>();

    public void addRecipe(Recipe r){ recipes.add(r); }

    public boolean canCraft(int recipeIndex){
        if(recipeIndex < 0 || recipeIndex >= recipes.size()) return false;
        Recipe r = recipes.get(recipeIndex);
        for(int i = 0; i < r.inputIds.length; i++)
            if(!has(r.inputIds[i], r.inputCounts[i])) return false;
        return true;
    }

    /** consume inputs and add output; atomic (checks first) */
    public boolean craft(int recipeIndex, float outWeightEach, boolean outStackable, String outEquipSlot){
        if(!canCraft(recipeIndex)) return false;
        Recipe r = recipes.get(recipeIndex);
        for(int i = 0; i < r.inputIds.length; i++) remove(r.inputIds[i], r.inputCounts[i]);
        add(r.outputId, r.outputCount, outWeightEach, outStackable, outEquipSlot);
        return true;
    }

    // ---------------- serialization ----------------

    public String toJson(){
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("capacity", capacity);
        root.put("maxWeight", maxWeight);
        root.put("gold", gold);
        List<Object> items = new ArrayList<>();
        for(Item it : slots){
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", it.id); m.put("count", it.count); m.put("w", it.weightEach);
            m.put("stack", it.stackable); m.put("slot", it.equipSlot);
            items.add(m);
        }
        root.put("items", items);
        List<Object> eq = new ArrayList<>();
        for(Map.Entry<String,Item> e : equipped.entrySet()){
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("slot", e.getKey()); m.put("id", e.getValue().id);
            eq.add(m);
        }
        root.put("equipped", eq);
        return Json.write(root);
    }

    public void load(JsonV v){
        slots.clear(); equipped.clear(); gold = 0;
        capacity = v.int32("capacity", 20);
        maxWeight = (float) v.num("maxWeight", 60f);
        gold = v.int32("gold", 0);
        for(Object io : v.list("items")){ JsonV iv = new JsonV(io);
            Item it = new Item();
            it.id = iv.str("id", "");
            it.count = iv.int32("count", 1);
            it.weightEach = (float) v.num("w", 0f);
            it.stackable = iv.bool("stack", true);
            it.equipSlot = iv.str("slot", null);
            slots.add(it);
        }
        for(Object eo : v.list("equipped")){ JsonV ev = new JsonV(eo);
            String slot = ev.str("slot", null);
            String id = ev.str("id", null);
            if(slot != null && id != null){
                for(Item it : slots){
                    if(it.id.equals(id)){ equipped.put(slot, it); break; }
                }
            }
        }
    }
}
