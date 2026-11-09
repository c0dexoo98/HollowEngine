package com.hollow.core;

/** Easing functions (MATH) — 25 curves + data-driven lookup by name.
 *  All functions take t in [0,1] and return the eased progress (also [0,1] except back/elastic overshoot). */
public final class Ease {
    private Ease(){}

    // ---------------- basic ----------------
    public static float linear(float t){ return t; }
    public static float quadIn(float t){ return t * t; }
    public static float quadOut(float t){ return 1 - (1 - t) * (1 - t); }
    public static float quadInOut(float t){ return t < 0.5f ? 2*t*t : 1 - 2*(1-t)*(1-t); }
    public static float cubicIn(float t){ return t*t*t; }
    public static float cubicOut(float t){ float u = 1-t; return 1 - u*u*u; }
    public static float cubicInOut(float t){ return t < 0.5f ? 4*t*t*t : 1 - (float)Math.pow(-2*t+2, 3)/2; }
    public static float quartOut(float t){ float u = 1-t; return 1 - u*u*u*u; }
    public static float quintOut(float t){ float u = 1-t; return 1 - u*u*u*u*u; }
    public static float sineIn(float t){ return 1 - (float)Math.cos(t * Math.PI / 2); }
    public static float sineOut(float t){ return (float)Math.sin(t * Math.PI / 2); }
    public static float sineInOut(float t){ return -(float)(Math.cos(Math.PI * t) - 1) / 2; }
    public static float expoIn(float t){ return t == 0 ? 0 : (float)Math.pow(2, 10*t - 10); }
    public static float expoOut(float t){ return t == 1 ? 1 : 1 - (float)Math.pow(2, -10*t); }
    public static float circOut(float t){ float u = t - 1; return (float)Math.sqrt(1 - u*u); }
    public static float circIn(float t){ return 1 - (float)Math.sqrt(1 - t*t); }

    // ---------------- expressive ----------------
    /** overshoot past the target then settle (s controls overshoot amount) */
    public static float backOut(float t, float s){
        float u = t - 1;
        return 1 + (s + 1) * u*u*u + s * u*u;
    }
    public static float backOut(float t){ return backOut(t, 1.70158f); }
    public static float backIn(float t){ float s = 1.70158f; return (s+1)*t*t*t - s*t*t; }
    public static float elasticOut(float t){
        if(t == 0 || t == 1) return t;
        float p = 0.3f;
        return (float)Math.pow(2, -10*t) * (float)Math.sin((t - p/4) * (2*Math.PI)/p) + 1;
    }
    public static float bounceOut(float t){
        float n1 = 7.5625f, d1 = 2.75f;
        if(t < 1/d1) return n1*t*t;
        if(t < 2/d1) return n1*(t -= 1.5f/d1)*t + 0.75f;
        if(t < 2.5/d1) return n1*(t -= 2.25f/d1)*t + 0.9375f;
        return n1*(t -= 2.625f/d1)*t + 0.984375f;
    }

    // ---------------- utility blends ----------------
    /** smoothstep 0..1 with zero end-slopes */
    public static float smooth(float t){ t = clamp01(t); return t*t*(3 - 2*t); }
    /** smootherstep (5th order) */
    public static float smoother(float t){ t = clamp01(t); return t*t*t*(t*(t*6 - 15) + 10); }
    /** frame-rate independent exponential approach toward target */
    public static float damp(float current, float target, float lambda, float dt){
        return target + (current - target) * (float)Math.exp(-lambda * dt);
    }
    /** move toward target by at most maxDelta */
    public static float moveToward(float current, float target, float maxDelta){
        if(Math.abs(target - current) <= maxDelta) return target;
        return current + Math.signum(target - current) * maxDelta;
    }
    /** ping-pong 0..1..0 over t */
    public static float pingPong(float t){
        t = t % 2f;
        return t < 1 ? t : 2 - t;
    }

    // ---------------- data-driven ----------------
    /** lookup by name for tweens/animations defined in JSON ("cubic_out" etc.) */
    public static float byName(String name, float t){
        return switch(name == null ? "" : name){
            case "", "linear" -> linear(t);
            case "quad_in" -> quadIn(t); case "quad_out" -> quadOut(t); case "quad_in_out" -> quadInOut(t);
            case "cubic_in" -> cubicIn(t); case "cubic_out" -> cubicOut(t); case "cubic_in_out" -> cubicInOut(t);
            case "quart_out" -> quartOut(t); case "quint_out" -> quintOut(t);
            case "sine_in" -> sineIn(t); case "sine_out" -> sineOut(t); case "sine_in_out" -> sineInOut(t);
            case "expo_in" -> expoIn(t); case "expo_out" -> expoOut(t);
            case "circ_in" -> circIn(t); case "circ_out" -> circOut(t);
            case "back_in" -> backIn(t); case "back_out" -> backOut(t);
            case "elastic_out" -> elasticOut(t); case "bounce_out" -> bounceOut(t);
            case "smooth" -> smooth(t); case "smoother" -> smoother(t);
            case "ping_pong" -> pingPong(t);
            default -> linear(t);
        };
    }

    static float clamp01(float t){ return t < 0 ? 0 : (t > 1 ? 1 : t); }
}
