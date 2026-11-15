package com.hollow.gameplay;

import com.hollow.core.Json;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AAA-04 — Gameplay Ability System (GAS-style, HollowEngine 4.0).
 *  Unreal-inspired: hierarchical gameplay tags, an attribute set with modifier stacks
 *  (add / multiply / override), gameplay effects (instant / duration / periodic, with
 *  granted tags) and abilities gated by cost, cooldown and tag rules.
 *
 *  One AbilitySystem instance per entity; fully deterministic and headless-testable.
 */
public final class AbilitySystem {

    // =====================================================================
    // tags — hierarchical "combat.fire.burn"; query "combat.fire" matches it
    // =====================================================================
    public static boolean tagMatches(String tag, String query){
        if(tag == null || query == null) return false;
        if(tag.equals(query)) return true;
        return tag.startsWith(query + ".");
    }

    public static final class TagContainer {
        public final List<String> tags = new ArrayList<>();
        public void add(String tag){ if(tag != null && !tag.isEmpty() && !has(tag)) tags.add(tag); }
        public void remove(String tag){ tags.remove(tag); }
        public boolean has(String query){
            for(String t : tags) if(tagMatches(t, query)) return true;
            return false;
        }
        public boolean hasAll(String... queries){
            for(String q : queries) if(!has(q)) return false;
            return true;
        }
        public boolean hasAny(String... queries){
            for(String q : queries) if(has(q)) return true;
            return false;
        }
        public int count(){ return tags.size(); }
    }

    // =====================================================================
    // attributes — base value + ordered modifier stack
    // =====================================================================
    public static final class Modifier {
        public enum Op { ADD, MUL, OVERRIDE }
        public final Op op;
        public final float value;
        public final String source;           // effect id that granted it
        public float remaining;               // seconds; < 0 = permanent
        public Modifier(Op op, float value, String source, float duration){
            this.op = op; this.value = value; this.source = source; this.remaining = duration;
        }
    }

    public static final class Attribute {
        public final String name;
        public float base;
        public float min = Float.NEGATIVE_INFINITY, max = Float.POSITIVE_INFINITY;
        public final List<Modifier> mods = new ArrayList<>();
        public Attribute(String name, float base){ this.name = name; this.base = base; }
        /** evaluate: start at base, apply ADD sum → MUL product → OVERRIDE (last wins) */
        public float value(){
            float v = base, mul = 1f, over = Float.NaN;
            for(Modifier m : mods){
                if(m.op == Modifier.Op.ADD) v += m.value;
                else if(m.op == Modifier.Op.MUL) mul += m.value;
                else over = m.value;
            }
            v *= mul;
            if(!Float.isNaN(over)) v = over;
            return MathX.clamp(v, min, max);
        }
    }

    // =====================================================================
    // gameplay effects — bundles of modifiers + granted tags
    // =====================================================================
    public static final class Effect {
        public final String id;
        public float duration = 0f;           // 0 = instant (mods apply once via base), < 0 = infinite
        public float period = 0f;             // > 0 = re-apply instantaneous ops every period
        public final List<String> grantTags = new ArrayList<>();
        public final Map<String, Modifier> mods = new LinkedHashMap<>();   // attrName -> modifier
        public int stacks = 1;
        public float tickAccum = 0f;
        public float remaining;

        public Effect(String id){ this.id = id; }
        public Effect mod(String attr, Modifier.Op op, float value){
            mods.put(attr, new Modifier(op, value, id, duration));
            return this;
        }
        public Effect tag(String t){ grantTags.add(t); return this; }
        public Effect forDuration(float sec){ duration = sec; remaining = sec; return this; }
        public Effect every(float sec){ period = sec; return this; }
    }

    // =====================================================================
    // abilities — cost / cooldown / tag-gated actions
    // =====================================================================
    public static final class Ability {
        public final String name;
        public String costAttribute = "stamina";
        public float cost = 10f;
        public float cooldown = 1.5f;
        public final List<String> requiredTags = new ArrayList<>();
        public final List<String> blockedTags = new ArrayList<>();

        public interface Logic {
            /** @return true when the ability actually ran (false = refused inside logic) */
            boolean activate(AbilitySystem owner);
        }
        public Logic logic;

        public Ability(String name){ this.name = name; }
        public Ability requires(String tag){ requiredTags.add(tag); return this; }
        public Ability blockedBy(String tag){ blockedTags.add(tag); return this; }
        public Ability costs(String attr, float amount){ costAttribute = attr; cost = amount; return this; }
        public Ability cooldownSec(float sec){ cooldown = sec; return this; }
        public Ability runs(Logic l){ logic = l; return this; }
    }

    // =====================================================================
    // the component itself
    // =====================================================================
    public final TagContainer tags = new TagContainer();
    public final Map<String, Attribute> attributes = new LinkedHashMap<>();
    public final List<Effect> active = new ArrayList<>();
    public final Map<String, Ability> abilities = new LinkedHashMap<>();
    public final Map<String, Float> cooldowns = new HashMap<>();
    public int activations = 0, refusals = 0;

    public AbilitySystem(){ this("health", 100f, "stamina", 100f, "mana", 50f); }
    public AbilitySystem(Object... attrPairs){
        for(int i = 0; i + 1 < attrPairs.length; i += 2)
            addAttribute((String)attrPairs[i], ((Number)attrPairs[i + 1]).floatValue());
    }

    public void addAttribute(String name, float base){ attributes.put(name, new Attribute(name, base)); }
    public Attribute attribute(String name){ return attributes.get(name); }
    public float attr(String name){
        Attribute a = attributes.get(name);
        return a != null ? a.value() : 0f;
    }
    /** direct base mutation (damage/heal helpers route through effects in real gameplay) */
    public void setBase(String name, float v){ Attribute a = attributes.get(name); if(a != null) a.base = v; }

    // ---------------- effects ----------------
    /** apply an effect: same-id refreshes/stacks the existing one, otherwise adds. */
    public boolean applyEffect(Effect e){
        if(e == null) return false;
        Effect existing = null;
        for(Effect a : active) if(a.id.equals(e.id)){ existing = a; break; }
        if(existing != null){
            existing.stacks++;
            if(e.duration > 0f) existing.remaining = Math.max(existing.remaining, e.duration);
        } else {
            active.add(e);
        }
        for(Map.Entry<String, Modifier> kv : e.mods.entrySet()){
            Attribute a = attributes.get(kv.getKey());
            if(a == null) continue;
            Modifier m = kv.getValue();
            if(e.duration == 0f){
                // instant: bake ADD into base; MUL/OVERRIDE become one-shot permanent mods
                if(m.op == Modifier.Op.ADD) a.base = MathX.clamp(a.base + m.value, a.min, a.max);
                else a.mods.add(new Modifier(m.op, m.value, e.id, -1f));
            } else if(e.period > 0f){
                // periodic: ops re-apply on every tick — NO standing modifier, otherwise
                // the initial application double-counts against the first tick
            } else {
                a.mods.add(new Modifier(m.op, m.value, e.id, e.duration));   // continuous
            }
        }
        for(String t : e.grantTags) tags.add(t);
        return true;
    }

    public void update(float dt){
        if(dt <= 0f) return;
        // tick effect durations + periodic re-application of instant ops
        for(int i = active.size() - 1; i >= 0; i--){
            Effect e = active.get(i);
            if(e.duration > 0f){
                e.remaining -= dt;
                if(e.remaining <= 0f){ removeEffect(e); continue; }
            }
            if(e.period > 0f){
                e.tickAccum += dt;
                while(e.tickAccum >= e.period){
                    e.tickAccum -= e.period;
                    for(Map.Entry<String, Modifier> kv : e.mods.entrySet()){
                        Attribute a = attributes.get(kv.getKey());
                        if(a != null && kv.getValue().op == Modifier.Op.ADD)
                            a.base = MathX.clamp(a.base + kv.getValue().value, a.min, a.max);
                    }
                }
            }
        }
        // expire attribute modifiers
        for(Attribute a : attributes.values()){
            for(int i = a.mods.size() - 1; i >= 0; i--){
                Modifier m = a.mods.get(i);
                if(m.remaining > 0f){
                    m.remaining -= dt;
                    if(m.remaining <= 0f) a.mods.remove(i);
                }
            }
        }
        // cooldowns
        for(java.util.Iterator<Map.Entry<String, Float>> it = cooldowns.entrySet().iterator(); it.hasNext(); ){
            Map.Entry<String, Float> kv = it.next();
            float v = kv.getValue() - dt;
            if(v <= 0f) it.remove(); else kv.setValue(v);
        }
    }

    /** remove an effect and every modifier/tag it granted */
    public void removeEffect(Effect e){
        active.remove(e);
        for(Attribute a : attributes.values()){
            for(int i = a.mods.size() - 1; i >= 0; i--)
                if(e.id.equals(a.mods.get(i).source)) a.mods.remove(i);
        }
        for(String t : e.grantTags) tags.remove(t);
    }
    public Effect effect(String id){
        for(Effect e : active) if(e.id.equals(id)) return e;
        return null;
    }

    // ---------------- abilities ----------------
    public void register(Ability a){ abilities.put(a.name, a); }

    /** full gate chain: exists → tag rules → cooldown → cost → logic */
    public boolean tryActivate(String name){
        Ability a = abilities.get(name);
        if(a == null || a.logic == null){ refusals++; return false; }
        if(!a.requiredTags.isEmpty() && !tags.hasAll(a.requiredTags.toArray(new String[0]))){ refusals++; return false; }
        if(!a.blockedTags.isEmpty() && tags.hasAny(a.blockedTags.toArray(new String[0]))){ refusals++; return false; }
        if(cooldowns.containsKey(name)){ refusals++; return false; }
        Attribute cost = a.costAttribute != null ? attributes.get(a.costAttribute) : null;
        if(cost != null && cost.value() < a.cost){ refusals++; return false; }
        if(!a.logic.activate(this)){ refusals++; return false; }
        if(cost != null && a.cost > 0f) cost.base = Math.max(cost.min, cost.value() - a.cost);
        if(a.cooldown > 0f) cooldowns.put(name, a.cooldown);
        activations++;
        return true;
    }
    public float cooldownLeft(String name){
        Float v = cooldowns.get(name);
        return v != null ? v : 0f;
    }
    public boolean onCooldown(String name){ return cooldowns.containsKey(name); }
}
