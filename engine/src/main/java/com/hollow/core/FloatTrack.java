package com.hollow.core;

/** Generic keyframe float track (MATH-029): per-segment easing, extrapolation modes
 *  and arbitrary channel semantics (light intensity, camera FOV, material params,
 *  sanity meter...). Complements MathX.Curve (which is hard-wired to smoothstep). */
public final class FloatTrack {

    public enum Ease { LINEAR, SMOOTH, EASE_IN, EASE_OUT, EASE_IN_OUT, STEP, BOUNCE, ELASTIC }

    public enum Extrapolate { CLAMP, LOOP, PINGPONG, CONSTANT }

    private final float[] times;      // strictly increasing
    private final float[] values;
    private final Ease[] ease;        // ease of segment leaving key i (ease.length == times.length)
    private final Extrapolate extrap;

    public FloatTrack(float[] times, float[] values, Ease[] ease, Extrapolate extrap){
        if(times == null || values == null || times.length != values.length || times.length == 0)
            throw new IllegalArgumentException("FloatTrack: bad keys");
        for(int i=1;i<times.length;i++)
            if(times[i] <= times[i-1]) throw new IllegalArgumentException("FloatTrack: times must increase");
        this.times = times.clone();
        this.values = values.clone();
        this.ease = normalizeEase(ease, times.length);
        this.extrap = extrap != null ? extrap : Extrapolate.CLAMP;
    }

    /** accept per-key (n) or per-segment (n-1) ease arrays; pad with SMOOTH */
    private static Ease[] normalizeEase(Ease[] e, int n){
        Ease[] out = new Ease[n];
        java.util.Arrays.fill(out, Ease.SMOOTH);
        if(e != null){
            for(int i=0;i<Math.min(e.length, n);i++){
                if(e[i] != null) out[i] = e[i];
            }
        }
        return out;
    }

    public static FloatTrack linear(float[] times, float[] values){ return new FloatTrack(times, values, null, Extrapolate.CLAMP); }
    public static FloatTrack constant(float v){ return new FloatTrack(new float[]{0,1}, new float[]{v,v}, null, Extrapolate.CLAMP); }

    public float duration(){ return times[times.length - 1] - times[0]; }

    /** sample at time t (honors extrapolation) */
    public float sample(float t){
        float local = wrapTime(t);
        int n = times.length;
        if(local <= times[0]) return values[0];
        if(local >= times[n-1]) return values[n-1];
        int i = 1;
        while(i < n && times[i] < local) i++;
        float t0 = times[i-1], t1 = times[i];
        float f = (local - t0) / Math.max(1e-6f, t1 - t0);
        Ease e = ease[i-1];
        return MathX.lerp(values[i-1], values[i], applyEase(f, e));
    }

    /** sample wrapping into [times0, timesN) — for looping emitters */
    public float sampleLooped(float t){
        float d = Math.max(1e-6f, duration());
        float local = times[0] + ((t - times[0]) % d + d) % d;
        int n = times.length;
        if(local >= times[n-1]) local = times[n-1] - 1e-4f;
        return sample(local);
    }

    private float wrapTime(float t){
        if(extrap == Extrapolate.CLAMP || extrap == Extrapolate.CONSTANT) return t;
        if(extrap == Extrapolate.LOOP){
            float d = Math.max(1e-6f, duration());
            return times[0] + ((t - times[0]) % d + d) % d;
        }
        // PINGPONG
        float d = Math.max(1e-6f, duration());
        float x = ((t - times[0]) % (2 * d) + 2 * d) % (2 * d);
        return x <= d ? times[0] + x : times[0] + 2 * d - x;
    }

    /** normalized progress of the track at t (0..1, clamped) */
    public float progress(float t){
        return MathX.invLerp(times[0], times[times.length - 1], t);
    }

    public boolean isDone(float t){
        return extrap != Extrapolate.LOOP && extrap != Extrapolate.PINGPONG && t >= times[times.length - 1];
    }

    // ---------------- easing ----------------
    public static float applyEase(float f, Ease e){
        f = MathX.clamp(f, 0f, 1f);
        return switch(e){
            case LINEAR -> f;
            case SMOOTH -> MathX.smoothstep(f);
            case EASE_IN -> f * f;
            case EASE_OUT -> 1f - (1f - f) * (1f - f);
            case EASE_IN_OUT -> f < 0.5f ? 2f * f * f : 1f - 2f * (1f - f) * (1f - f);
            case STEP -> 0f;
            case BOUNCE -> bounceOut(f);
            case ELASTIC -> elasticOut(f);
        };
    }

    private static float bounceOut(float f){
        float n1 = 7.5625f, d1 = 2.75f;
        if(f < 1 / d1) return n1 * f * f;
        if(f < 2 / d1) return n1 * (f -= 1.5f / d1) * f + 0.75f;
        if(f < 2.5 / d1) return n1 * (f -= 2.25f / d1) * f + 0.9375f;
        return n1 * (f -= 2.625f / d1) * f + 0.984375f;
    }
    private static float elasticOut(float f){
        if(f == 0 || f == 1) return f;
        float c4 = (float)(2 * Math.PI) / 3f;
        return (float)(Math.pow(2, -10 * f) * Math.sin((f * 10 - 0.75) * c4) + 1);
    }

    // ---------------- JSON round-trip ----------------
    public static FloatTrack fromJson(com.hollow.core.JsonV node){
        if(node == null || !node.has("times") || !node.has("values")) return constant(0);
        java.util.List<Object> tList = node.list("times");
        java.util.List<Object> vList = node.list("values");
        if(tList == null || vList == null || tList.size() != vList.size() || tList.isEmpty()) return constant(0);
        float[] t = new float[tList.size()];
        float[] v = new float[vList.size()];
        for(int i=0;i<t.length;i++){ t[i] = (float) com.hollow.core.Json.num(tList.get(i), 0); v[i] = (float) com.hollow.core.Json.num(vList.get(i), 0); }
        Ease[] e = null;
        java.util.List<Object> es = node.list("ease");
        if(es != null && es.size() == t.length){
            e = new Ease[t.length];
            for(int i=0;i<e.length;i++){
                String s = com.hollow.core.Json.str(es.get(i), null);
                Ease parsed = null;
                if(s != null){ try { parsed = Ease.valueOf(s.toUpperCase()); } catch(IllegalArgumentException ignored){} }
                e[i] = parsed != null ? parsed : Ease.SMOOTH;
            }
        }
        Extrapolate x = Extrapolate.CLAMP;
        String xs = node.str("extrapolate", null);
        if(xs != null){ try { x = Extrapolate.valueOf(xs.toUpperCase()); } catch(IllegalArgumentException ignored){} }
        return new FloatTrack(t, v, e, x);
    }

    public int keyCount(){ return times.length; }
}
