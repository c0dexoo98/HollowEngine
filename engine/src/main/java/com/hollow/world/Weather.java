package com.hollow.world;

import com.hollow.core.MathX;

/** AAA-03 — dynamic weather system (HollowEngine 4.0).
 *  Six weather presets, smooth parameter blending between them, a wind vector exposed
 *  to foliage/particles/audio, ground wetness accumulation (drives reflections + slip),
 *  and stochastic thunder strikes during storms (deterministic via engine RNG).
 *
 *  Headless-safe: no renderer dependency — the render layer reads `current.*`.
 */
public final class Weather {
    public enum Preset { CLEAR, CLOUDY, RAIN, STORM, SNOW, FOG }

    public static final class Profile {
        public float cloud, rain, snow, fog, windSpeed, thunder;   // all 0..1 (thunder = strikes/sec scale)
        public Profile(float c, float r, float sn, float f, float w, float th){
            cloud = c; rain = r; snow = sn; fog = f; windSpeed = w; thunder = th;
        }
    }

    public static Profile profile(Preset p){
        switch(p){
            case CLEAR:  return new Profile(0.12f, 0f,   0f, 0.05f, 0.15f, 0f);
            case CLOUDY: return new Profile(0.65f, 0f,   0f, 0.12f, 0.35f, 0f);
            case RAIN:   return new Profile(0.85f, 0.7f, 0f, 0.25f, 0.55f, 0.1f);
            case STORM:  return new Profile(1.0f,  1.0f, 0f, 0.35f, 0.95f, 0.8f);
            case SNOW:   return new Profile(0.8f,  0f,   0.8f, 0.30f, 0.25f, 0f);
            case FOG:    return new Profile(0.5f,  0f,   0f, 0.9f,  0.10f, 0f);
            default:     return new Profile(0.12f, 0f,   0f, 0.05f, 0.15f, 0f);
        }
    }

    /** live blended state (renderer/particles read this every frame) */
    public final Profile current = new Profile(0.12f, 0f, 0f, 0.05f, 0.15f, 0f);
    public Preset preset = Preset.CLEAR;
    public Preset target = Preset.CLEAR;
    public float blendTime = 6f;               // seconds to fully reach the target
    public float windDirDeg = 45f;
    /** ground wetness 0..1 — rises under rain/snow melt, dries in clear weather */
    public float wetness = 0f;
    public long seed = 0x51EC0A1L;             // thunder scheduling RNG (deterministic)

    public interface Listener { void onWeatherEvent(String type, float intensity); }
    public Listener listener;

    private final Profile from = profile(Preset.CLEAR);
    private float blend = 1f;                  // 0 = just switched .. 1 = arrived
    private float thunderTimer = 0f;

    /** request a weather change; parameters blend over blendTime seconds */
    public void set(Preset p){
        from.cloud = current.cloud; from.rain = current.rain; from.snow = current.snow;
        from.fog = current.fog; from.windSpeed = current.windSpeed; from.thunder = current.thunder;
        target = p;
        blend = 0f;
    }

    public void update(float dt){
        if(dt <= 0f) return;
        if(blend < 1f){
            blend = Math.min(1f, blend + dt / Math.max(0.01f, blendTime));
            float k = blend * blend * (3f - 2f * blend);       // smoothstep
            Profile to = profile(target);
            current.cloud = MathX.lerp(from.cloud, to.cloud, k);
            current.rain = MathX.lerp(from.rain, to.rain, k);
            current.snow = MathX.lerp(from.snow, to.snow, k);
            current.fog = MathX.lerp(from.fog, to.fog, k);
            current.windSpeed = MathX.lerp(from.windSpeed, to.windSpeed, k);
            current.thunder = MathX.lerp(from.thunder, to.thunder, k);
            if(blend >= 1f) preset = target;
        }
        // wetness: accumulate under precipitation, dry in the clear
        float precip = Math.max(current.rain, current.snow * 0.6f);
        if(precip > 0.05f) wetness = Math.min(1f, wetness + precip * dt * 0.12f);
        else wetness = Math.max(0f, wetness - dt * 0.03f);
        // thunder strikes (deterministic RNG)
        if(current.thunder > 0.02f){
            thunderTimer -= dt;
            if(thunderTimer <= 0f){
                com.hollow.core.MathX.Rng rng = new com.hollow.core.MathX.Rng(seed ^ (long)(current.thunder * 1000) ^ System.identityHashCode(this) * 0);
                float gap = MathX.lerp(14f, 2.5f, current.thunder) * rng.range(0.6f, 1.5f);
                thunderTimer = gap;
                if(listener != null) listener.onWeatherEvent("thunder", MathX.clamp(current.thunder * rng.range(0.7f, 1.2f), 0f, 1f));
            }
        } else thunderTimer = 0f;
    }

    /** wind as a world-space vector (m/s) — foliage sway, particle advection, audio occlusion */
    public float[] windVector(){
        float rad = (float)Math.toRadians(windDirDeg);
        return new float[]{ (float)Math.cos(rad) * current.windSpeed, (float)Math.sin(rad) * current.windSpeed };
    }
    /** slip factor 0..1 — wet ground reduces tire grip / foot traction */
    public float slipFactor(){ return wetness * 0.5f; }
    public String describe(){
        return "weather[" + preset + "→" + target + " cloud=" + f(current.cloud) + " rain=" + f(current.rain)
            + " fog=" + f(current.fog) + " wind=" + f(current.windSpeed) + " wet=" + f(wetness) + "]";
    }
    private static String f(float v){ return String.valueOf(Math.round(v * 100f) / 100f); }
}
