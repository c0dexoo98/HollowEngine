package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.List;

/** v6.0 — dynamic difficulty adjustment (HollowEngine 6.0 'living-world').
 *  Tracks player performance events (deaths, kill streaks, damage exchange, pace
 *  of progress) and nudges a 0..1 difficulty scalar with smooth asymmetric ramps:
 *  fast drop on failure streaks, slow rise on dominant streaks. The encounter
 *  budget scales with difficulty; everything persists.
 */
public final class DifficultyDirector {

    public interface Listener { void onDifficultyEvent(String type, float difficulty); }
    public Listener listener;

    public float difficulty = 0.5f;             // 0 casual .. 1 brutal
    public float risePerSec = 0.01f;            // baseline creep toward target while playing
    public float deathPush = -0.12f;            // per player death
    public float killStreakPush = 0.05f;        // per kill streak milestone
    public float beatStreakPush = 0.08f;        // per flawless encounter
    public int streakLen = 5;                   // kills per streak milestone
    public int streakCount = 0;
    public int flawless = 0;                    // encounters without taking damage
    public int deaths = 0;
    public float baseBudget = 10f;              // encounter points at difficulty 0.5

    private float hold = 0f;                    // seconds until difficulty may drift again
    private float target = 0.5f;

    /** world events from the game layer */
    public void onDeath(){ deaths++; push(deathPush); }
    public void onKill(){
        streakCount++;
        if(streakCount % streakLen == 0) push(killStreakPush);
    }
    public void onEncounter(boolean tookDamage){
        if(tookDamage){ flawless = 0; }
        else {
            flawless++;
            if(flawless >= 2) push(beatStreakPush);
        }
    }
    /** big story wins force a rise; crushing losses force a drop */
    public void onMilestone(boolean victory){ push(victory ? beatStreakPush * 1.5f : deathPush * 1.5f); }

    private void push(float delta){
        target = MathX.clamp(target + delta, 0f, 1f);   // accumulate on target, not on unmoved difficulty
        hold = 2.5f;                              // let the ramp settle before drifting again
        if(listener != null) listener.onDifficultyEvent("push", difficulty);
    }

    public void update(float dt){
        if(dt <= 0f) return;
        // the hold window consumes time but lets the rest of the frame ramp
        if(hold > 0f){
            float used = Math.min(hold, dt);
            hold -= used;
            dt -= used;
            if(dt <= 0f) return;
        }
        // once the ramp has settled, creep the target back toward the middle
        if(Math.abs(target - difficulty) < 0.02f)
            target = MathX.approach(target, 0.5f, 0.01f * dt);
        float rate = Math.max(risePerSec, Math.abs(target - difficulty) * risePerSec * 10f);
        difficulty = MathX.approach(difficulty, target, rate * dt);
    }

    /** encounter budget the encounter designer should spend now */
    public float encounterBudget(){
        return baseBudget * (0.45f + difficulty * 1.1f);
    }
    /** multipliers for AI stats */
    public float enemyHealthMul(){ return 0.7f + difficulty * 0.9f; }
    public float enemyDamageMul(){ return 0.6f + difficulty * 1.0f; }
    public float lootBonusMul(){ return 0.85f + difficulty * 0.5f; }   // harder play pays better

    public String describe(){
        return "difficulty=" + Math.round(difficulty * 100f) + "%"
            + " deaths=" + deaths + " streak=" + streakCount
            + " budget=" + Math.round(encounterBudget() * 10f) / 10f;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("diff", difficulty).kv("target", target).kv("deaths", deaths)
          .kv("streak", streakCount).kv("flawless", flawless);
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            difficulty = MathX.clamp(n.flt("diff", difficulty), 0f, 1f);
            target = MathX.clamp(n.flt("target", target), 0f, 1f);
            deaths = (int)n.flt("deaths", deaths);
            streakCount = (int)n.flt("streak", streakCount);
            flawless = (int)n.flt("flawless", flawless);
        } catch(Exception ignored){ }
    }
}
