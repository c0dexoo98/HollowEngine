package com.hollow.gameplay;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.List;

/** v6.0 — survival stats system (HollowEngine 6.0 'living-world').
 *  Hunger / thirst / warmth / rest drain over time, modulated by the world context
 *  (ambient temperature from SeasonsSystem, rain from Weather, activity level).
 *  Crossing low thresholds raises status effects that drain health or cripple the
 *  player; being fed and rested regenerates health. Deterministic, JSON-persisted.
 */
public final class SurvivalSystem {

    public enum Effect { NONE, STARVING, DEHYDRATED, FREEZING, EXHAUSTED }

    public interface Listener { void onSurvivalEvent(String type, String stat, float value); }
    public Listener listener;

    // tuning (per simulated second, at activity 1.0 and neutral conditions)
    public float hungerPerSec = 0.30f;
    public float thirstPerSec = 0.45f;
    public float restPerSec   = 0.22f;
    public float warmthPerSec = 0.25f;          // at 0 °C; scales with cold, recovers when warm
    public float healthDrainPerSec = 0.8f;      // while an effect is active
    public float healthRegenPerSec = 0.5f;      // while all stats above 50

    // state (0..100)
    public float hunger = 100f, thirst = 100f, warmth = 100f, rest = 100f, health = 100f;
    public boolean dead = false;

    /** world context supplied by the game layer each frame */
    public float ambientTemp = 12f;             // °C — feed from SeasonsSystem.currentTemp()
    public boolean raining = false;             // feed from Weather
    public float activity = 0.4f;               // 0 resting .. 1 sprinting
    public boolean byFire = false;              // fire proximity keeps warmth up

    private Effect lastEffect = Effect.NONE;    // effect seen on the previous frame

    public SurvivalSystem(){ }
    public SurvivalSystem(float hungerPerSec, float thirstPerSec){
        this.hungerPerSec = hungerPerSec; this.thirstPerSec = thirstPerSec;
    }

    public Effect currentEffect(){
        if(hunger < 15f) return Effect.STARVING;
        if(thirst < 15f) return Effect.DEHYDRATED;
        if(warmth < 15f) return Effect.FREEZING;
        if(rest < 15f) return Effect.EXHAUSTED;
        return Effect.NONE;
    }

    public void update(float dt){
        if(dt <= 0f || dead) return;
        float act = MathX.clamp(activity, 0f, 1f);

        hunger = Math.max(0f, hunger - hungerPerSec * (0.5f + act) * dt);
        thirst = Math.max(0f, thirst - thirstPerSec * (0.5f + act) * dt);
        rest   = Math.max(0f, rest   - restPerSec * (0.6f + act) * dt);

        // warmth: ambient cold drains it; heat above ~20 °C or a fire restores it; rain steals more
        float tempK = MathX.clamp((20f - ambientTemp) / 30f, -0.6f, 1.6f);   // -0.6 (hot) .. 1.6 (bitter cold)
        float warmthDelta = byFire
            ? -warmthPerSec * 1.8f                                  // a good fire wins over the weather
            : warmthPerSec * tempK + (raining ? 0.35f : 0f);
        warmth = MathX.clamp(warmth - warmthDelta * dt, 0f, 100f);

        Effect now = currentEffect();
        if(now != lastEffect && listener != null)
            listener.onSurvivalEvent(now == Effect.NONE ? "recovered" : "effect", now.name(), worstStat());
        lastEffect = now;

        if(now != Effect.NONE){
            health -= healthDrainPerSec * dt;
            if(health <= 0f){
                health = 0f;
                dead = true;
                if(listener != null) listener.onSurvivalEvent("death", now.name(), 0f);
            }
        } else if(hunger > 50f && thirst > 50f && warmth > 50f && rest > 50f && health < 100f){
            health = Math.min(100f, health + healthRegenPerSec * dt);
        }
    }

    private float worstStat(){
        return Math.min(Math.min(hunger, thirst), Math.min(warmth, rest));
    }

    public void eat(float amount){ hunger = MathX.clamp(hunger + amount, 0f, 100f); }
    public void drink(float amount){ thirst = MathX.clamp(thirst + amount, 0f, 100f); }
    /** sleep / sit by the fire: restores rest, kills the activity drain */
    public void sleep(float amount){ rest = MathX.clamp(rest + amount, 0f, 100f); activity = 0f; }
    public void warmUp(float amount){ warmth = MathX.clamp(warmth + amount, 0f, 100f); }
    public void heal(float amount){
        if(dead) return;
        health = MathX.clamp(health + amount, 0f, 100f);
    }
    /** respawn after death: stats reset, health partial */
    public void respawn(){
        dead = false;
        hunger = thirst = 70f; warmth = rest = 70f; health = 50f;
        if(listener != null) listener.onSurvivalEvent("respawn", "", health);
    }

    public List<Effect> activeEffects(){
        List<Effect> out = new ArrayList<>();
        if(hunger < 15f) out.add(Effect.STARVING);
        if(thirst < 15f) out.add(Effect.DEHYDRATED);
        if(warmth < 15f) out.add(Effect.FREEZING);
        if(rest < 15f) out.add(Effect.EXHAUSTED);
        return out;
    }

    public String describe(){
        return "survival[hunger=" + Math.round(hunger) + " thirst=" + Math.round(thirst)
            + " warmth=" + Math.round(warmth) + " rest=" + Math.round(rest)
            + " hp=" + Math.round(health) + (dead ? " DEAD" : "") + "]";
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("hunger", hunger).kv("thirst", thirst).kv("warmth", warmth)
          .kv("rest", rest).kv("health", health).kv("dead", dead);
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            hunger = n.flt("hunger", hunger);
            thirst = n.flt("thirst", thirst);
            warmth = n.flt("warmth", warmth);
            rest = n.flt("rest", rest);
            health = n.flt("health", health);
            dead = n.bool("dead", dead);
        } catch(Exception ignored){ }
    }
}
