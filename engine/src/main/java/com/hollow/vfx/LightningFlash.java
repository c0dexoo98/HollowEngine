package com.hollow.vfx;

import com.hollow.core.MathX;

/** Event lightning flash (LGT-020, VFX-013): drives a global ambient/directional
 *  intensity spike with a 2-3 flicker envelope and thunder delay. Fired from
 *  FlowScript/Sequencer via {@link #strike()}; the renderer samples
 *  {@link #ambientBoost()} and {@link #directionalBoost()} each frame. */
public final class LightningFlash {
    private float t = -1f;               // seconds since strike, -1 = idle
    private float duration = 0.9f;
    private int seed = 1;
    private float strength = 1f;
    private float thunderDelay = 1.4f;
    private boolean thunderQueued = false;

    /** fire a flash (returns thunder delay for audio scheduling) */
    public float strike(){
        return strike(1f, 0.9f, (int)(Math.random() * 1e9));
    }
    public float strike(float strength, float duration, int seed){
        this.strength = MathX.clamp(strength, 0.1f, 3f);
        this.duration = Math.max(0.2f, duration);
        this.seed = seed;
        this.t = 0f;
        this.thunderQueued = true;
        return thunderDelay;
    }

    /** per-frame advance; returns true on the frame the thunder should play */
    public boolean update(float dt){
        if(t >= 0f){
            t += dt;
            if(thunderQueued && t >= thunderDelay){
                thunderQueued = false;
                return true;
            }
            if(t > duration + thunderDelay) t = -1f;
        }
        return false;
    }

    /** 0..~2 multiplier for ambient light this frame (2-3 flicker bumps) */
    public float ambientBoost(){
        if(t < 0f || t > duration) return 0f;
        float u = t / duration;
        // three decaying flicker peaks (deterministic per seed)
        float p1 = peak(u, 0.02f, 0.10f);
        float p2 = peak(u, 0.18f, 0.10f) * 0.75f;
        float p3 = peak(u, 0.40f, 0.16f) * 0.5f;
        return strength * Math.max(p1, Math.max(p2, p3));
    }

    /** directional light multiplier (slightly tighter envelope) */
    public float directionalBoost(){
        return ambientBoost() * 0.85f;
    }

    public boolean active(){ return t >= 0f && t <= duration; }

    private static float peak(float u, float center, float width){
        float d = (u - center) / width;
        return (float)Math.exp(-d * d);
    }

    // ---------------- test helpers ----------------
    public boolean thunderFired(){ return !thunderQueued && t > thunderDelay * 0.5f; }
    public float time(){ return t; }
}
