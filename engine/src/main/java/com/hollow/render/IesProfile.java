package com.hollow.render;

/** IES-style light falloff profiles (LGT-014): intensity as a function of the angle
 *  from the spot axis. Four built-in curves model common real fixtures; the value
 *  is fed to the shader as a per-light scalar multiplier sampled CPU-side at light
 *  setup (the cone covers ~60°, 12 taps are plenty).
 *  0 none | 1 bulb (omni-ish) | 2 spot (tight) | 3 flood (wide) | 4 larson (work light) */
public final class IesProfile {
    private IesProfile(){}

    public static final int NONE = 0, BULB = 1, SPOT = 2, FLOOD = 3, LARSON = 4;

    /** intensity multiplier at cone angle deg (0..90), profile p */
    public static float evaluate(int p, float angleDeg){
        float a = Math.max(0f, Math.min(90f, angleDeg));
        return switch(p){
            case BULB -> bulb(a);
            case SPOT -> spot(a, 22f);
            case FLOOD -> spot(a, 48f);
            case LARSON -> larson(a);
            default -> 1f;
        };
    }

    /** sampled 12-tap table over the cone for the shader uniform array */
    public static float[] table(int p, float outerAngleDeg){
        float[] out = new float[12];
        for(int i=0;i<12;i++){
            float a = outerAngleDeg * i / 11f;
            out[i] = evaluate(p, a);
        }
        return out;
    }

    private static float bulb(float a){
        // soft bulb: mild darkening toward the equator
        float c = (float)Math.cos(Math.toRadians(a));
        return 0.35f + 0.65f * c * c;
    }
    private static float spot(float a, float centerDeg){
        // gaussian hotspot with spill tail
        float d = (a - centerDeg * 0.35f) / (centerDeg * 0.8f);
        float core = (float)Math.exp(-d * d);
        float tail = 0.12f / (1f + a * 0.06f);
        return core + tail;
    }
    private static float larson(float a){
        // wide industrial: flat center, cosine shoulder
        float flat = a < 30f ? 1f : (float)Math.cos(Math.toRadians((a - 30f) * 1.4f));
        return Math.max(0f, flat) * 0.85f + 0.15f;
    }
}
