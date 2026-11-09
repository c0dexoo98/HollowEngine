package com.hollow.core;

/** Math utilities and scalar curve (keyframes) for animation-grade interpolation. */
public final class MathX {
    private MathX() {}

    public static final float PI = (float)Math.PI;
    public static final float DEG = (float)(Math.PI/180.0);

    public static float clamp(float v, float a, float b){ return v<a?a:(v>b?b:v); }
    public static int clamp(int v, int a, int b){ return v<a?a:(v>b?b:v); }
    public static float lerp(float a, float b, float t){ return a+(b-a)*t; }
    public static float invLerp(float a, float b, float v){ return a==b?0:clamp((v-a)/(b-a),0,1); }
    public static float smoothstep(float t){ t=clamp(t,0,1); return t*t*(3-2*t); }
    public static float approach(float cur, float target, float maxDelta){
        return cur < target ? Math.min(cur+maxDelta, target) : Math.max(cur-maxDelta, target);
    }
    /** shortest-arc angle difference in degrees */
    public static float deltaAngle(float a, float b){
        float d = (b-a) % 360f;
        if(d > 180) d -= 360;
        if(d < -180) d += 360;
        return d;
    }
    public static float moveAngleTowards(float cur, float target, float maxDeltaDeg){
        float d = deltaAngle(cur, target);
        if(Math.abs(d) <= maxDeltaDeg) return target;
        return cur + Math.signum(d)*maxDeltaDeg;
    }

    /** deterministic xorshift RNG — reproducible simulations & procedural content */
    public static final class Rng {
        private long state;
        public Rng(long seed){ this.state = seed==0 ? 0x9E3779B97F4A7C15L : seed; }
        public long nextLong(){
            long x = state;
            x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
            state = x; return x;
        }
        public int nextInt(int bound){ return (int)(Math.floorMod(nextLong(), bound)); }
        public float nextFloat(){ return (nextLong() & 0xFFFFFF) / (float)(1<<24); }
        public float range(float a, float b){ return a + (b-a)*nextFloat(); }
        public int range(int a, int b){ return a + nextInt(b-a+1); }
        public boolean chance(float p){ return nextFloat() < p; }
        public Vec3 insideUnitSphere(){
            while(true){
                float x=range(-1,1), y=range(-1,1), z=range(-1,1);
                float l2 = x*x+y*y+z*z;
                if(l2 < 1 && l2 > 1e-6f) return new Vec3(x,y,z);
            }
        }
    }

    /** scalar keyframe curve: linear / step / smooth interpolation */
    public static final class Curve {
        public final float[] times;
        public final float[] values;
        public final boolean step;
        public Curve(float[] times, float[] values, boolean step){
            this.times=times; this.values=values; this.step=step;
        }
        public static Curve constant(float v){ return new Curve(new float[]{0,1}, new float[]{v,v}, false); }
        public float sample(float t){
            int n = times.length;
            if(n==0) return 0;
            if(t <= times[0]) return values[0];
            if(t >= times[n-1]) return values[n-1];
            int i=1; while(i<n && times[i] < t) i++;
            float t0=times[i-1], t1=times[i], v0=values[i-1], v1=values[i];
            float f = (t-t0)/Math.max(1e-6f, t1-t0);
            if(step) return v0;
            return lerp(v0, v1, smoothstep(f));
        }
    }
}
