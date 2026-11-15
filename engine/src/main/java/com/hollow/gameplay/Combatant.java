package com.hollow.gameplay;

import com.hollow.core.JsonV;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.0.0 GAMEPLAY: health / damage / hit zones / status effects.
 *
 * A Combatant is a plain data holder (no Entity dependency) so it works for
 * the player, AI agents, destructible props — anything. The game layer wires
 * it: onDeath callbacks, UI readouts, save persistence.
 *
 * Zones: damage multipliers by body part (head 2.0x, torso 1.0x, limb 0.6x...).
 * Status: timed effects (poison/bleed/burn) ticking HP with DOT + optional
 * movement modifiers the gameplay layer can read.
 */
public final class Combatant {

    public float maxHp = 100f;
    public float hp = 100f;
    public boolean dead = false;
    public boolean invulnerable = false;

    /** damage type -> resistance 0..1 (0.5 = half damage) */
    public final Map<String, Float> resistances = new LinkedHashMap<>();

    /** body zone -> multiplier; default zone "default" = 1.0 */
    public final Map<String, Float> zones = new LinkedHashMap<>();

    public interface DeathHook { void onDeath(Combatant c); }
    public final List<DeathHook> deathHooks = new ArrayList<>();
    public final List<DeathHook> reviveHooks = new ArrayList<>();

    public interface DamageHook { /** return true to consume/override the default application */ boolean onDamage(Combatant c, DamageInfo info); }
    public final List<DamageHook> damageHooks = new ArrayList<>();

    public long totalDamageTaken = 0;

    public Combatant(){ this(100f); }
    public Combatant(float maxHp){
        this.maxHp = maxHp;
        this.hp = maxHp;
        zones.put("default", 1f);
        zones.put("head", 2f);
        zones.put("torso", 1f);
        zones.put("limb", 0.6f);
    }

    /** structured damage event */
    public static final class DamageInfo {
        public float amount;
        public String type = "physical";      // physical/fire/energy...
        public String zone = "default";       // head/torso/limb/...
        public Combatant source;              // may be null (environment)
        public boolean critical = false;

        public DamageInfo(float amount){ this.amount = amount; }
        public DamageInfo(float amount, String type, String zone){
            this.amount = amount; this.type = type; this.zone = zone;
        }
    }

    /** apply damage through the full pipeline (hooks -> resist -> zone -> hp) */
    public float damage(DamageInfo info){
        if(dead || invulnerable || info.amount <= 0f) return 0f;
        for(DamageHook h : damageHooks){
            if(h.onDamage(this, info)) return 0f;   // consumed
        }
        float resist = resistances.getOrDefault(info.type, 0f);
        float zoneMul = zones.getOrDefault(info.zone, 1f);
        float finalDmg = info.amount * (1f - clamp01(resist)) * zoneMul;
        hp -= finalDmg;
        totalDamageTaken += (long)finalDmg;
        if(hp <= 0f){
            hp = 0f;
            dead = true;
            for(DeathHook h : deathHooks) h.onDeath(this);
        }
        return finalDmg;
    }

    public float damage(float amount){ return damage(new DamageInfo(amount)); }

    /** heal up to max; returns amount actually restored */
    public float heal(float amount){
        if(dead || amount <= 0f) return 0f;
        float before = hp;
        hp = Math.min(maxHp, hp + amount);
        return hp - before;
    }

    /** revive with hp percent (0..1) */
    public void revive(float hpPercent){
        dead = false;
        hp = maxHp * clamp01(hpPercent);
        for(DeathHook h : reviveHooks) h.onDeath(this);
    }

    public float hpPercent(){ return maxHp > 0f ? clamp01(hp / maxHp) : 0f; }

    // ---------------- status effects (DOT etc.) ----------------

    public static final class Status {
        public final String id;          // "poison", "burn"
        public float remaining;          // seconds
        public float tickInterval;       // seconds between ticks
        public float damagePerTick;
        public float movePenalty = 0f;   // 0..1 movement multiplier reduction
        private float tickTimer = 0f;

        Status(String id, float remaining, float tickInterval, float damagePerTick, float movePenalty){
            this.id = id; this.remaining = remaining; this.tickInterval = tickInterval;
            this.damagePerTick = damagePerTick; this.movePenalty = movePenalty;
        }
    }

    public final List<Status> statuses = new ArrayList<>();

    public void applyStatus(String id, float duration, float tickInterval, float damagePerTick, float movePenalty){
        for(Status s : statuses){
            if(s.id.equals(id)){    // refresh (keep stronger)
                s.remaining = Math.max(s.remaining, duration);
                s.damagePerTick = Math.max(s.damagePerTick, damagePerTick);
                return;
            }
        }
        statuses.add(new Status(id, duration, tickInterval, damagePerTick, movePenalty));
    }

    /** advance DOTs; call from fixed update */
    public void updateStatuses(float dt){
        for(int i = statuses.size() - 1; i >= 0; i--){
            Status s = statuses.get(i);
            s.remaining -= dt;
            s.tickTimer += dt;
            while(s.tickTimer >= s.tickInterval){
                s.tickTimer -= s.tickInterval;
                if(s.damagePerTick > 0f) damage(new DamageInfo(s.damagePerTick, s.id, "default"));
            }
            if(s.remaining <= 0f) statuses.remove(i);
        }
    }

    public boolean hasStatus(String id){
        for(Status s : statuses) if(s.id.equals(id)) return true;
        return false;
    }

    /** combined movement penalty from statuses (0 = free, 1 = rooted) */
    public float movePenalty(){
        float p = 0f;
        for(Status s : statuses) p = Math.max(p, s.movePenalty);
        return p;
    }

    // ---------------- serialization ----------------

    public Map<String,Object> toJson(){
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("maxHp", maxHp); m.put("hp", hp); m.put("dead", dead);
        List<Object> st = new ArrayList<>();
        for(Status s : statuses){
            Map<String,Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id); sm.put("rem", s.remaining); sm.put("dmg", s.damagePerTick);
            st.add(sm);
        }
        m.put("statuses", st);
        return m;
    }

    public void load(JsonV v){
        maxHp = (float) v.num("maxHp", maxHp);
        hp = (float) v.num("hp", hp);
        dead = v.bool("dead", false);
        statuses.clear();
        for(Object so : v.list("statuses")){ JsonV sv = new JsonV(so);
            applyStatus(sv.str("id", "x"), (float) v.num("rem", 0f), 1f, (float) v.num("dmg", 0f), 0f);
        }
    }

    private static float clamp01(float v){ return Math.max(0f, Math.min(1f, v)); }
}
