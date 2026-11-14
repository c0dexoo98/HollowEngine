package com.hollow.core;

/** Quaternion spline (MATH-030): smooth rotation paths for cinematic cameras and
 *  turret aiming. Interpolates through keys with squad (spherical quadrangle) or
 *  chained slerp. Keys are (time, rotation) pairs; times strictly increasing. */
public final class QuatSpline {

    private final float[] times;
    private final Quat[] keys;
    private final Quat[] innerA;   // squad inner quadrangle points a_i
    private final Quat[] innerB;   // squad inner quadrangle points b_i

    public QuatSpline(float[] times, Quat[] keys){
        if(times == null || keys == null || times.length != keys.length || keys.length < 2)
            throw new IllegalArgumentException("QuatSpline: need >= 2 matching keys");
        for(int i=1;i<times.length;i++)
            if(times[i] <= times[i-1]) throw new IllegalArgumentException("QuatSpline: times must increase");
        this.times = times.clone();
        this.keys = new Quat[keys.length];
        for(int i=0;i<keys.length;i++) this.keys[i] = keys[i].cpy().norm();
        // hemisphere alignment: consecutive keys must take the short arc
        for(int i=1;i<this.keys.length;i++){
            if(this.keys[i-1].dot(this.keys[i]) < 0f){
                this.keys[i].x = -this.keys[i].x; this.keys[i].y = -this.keys[i].y;
                this.keys[i].z = -this.keys[i].z; this.keys[i].w = -this.keys[i].w;
            }
        }
        this.innerA = new Quat[keys.length];
        this.innerB = new Quat[keys.length];
        computeInner();
    }

    /** squad inner quadrangle tangents (Shoemake):
     *  a_i = b_i = k_i · exp( -( log(k_i⁻¹ k_{i+1}) + log(k_i⁻¹ k_{i-1}) ) / 4 )
     *  NOTE: never mutates the keys — Quat.mul writes into `this`. */
    private void computeInner(){
        int n = keys.length;
        for(int i=0;i<n;i++){
            Quat k = keys[i];
            Quat qPrev = keys[Math.max(0, i-1)];
            Quat qNext = keys[Math.min(n-1, i+1)];
            Quat logNext = logHalf(k.cpy().invert().mul(qNext));
            Quat logPrev = logHalf(k.cpy().invert().mul(qPrev));
            Quat c = scale(add(logNext, logPrev), -0.25f);
            Quat tangent = k.cpy().mul(exp(c));
            innerA[i] = tangent.cpy();
            innerB[i] = tangent.cpy();
        }
    }

    /** sample rotation at time t (clamped) */
    public Quat sample(float t){
        int n = keys.length;
        if(t <= times[0]) return keys[0].cpy();
        if(t >= times[n-1]) return keys[n-1].cpy();
        int i = 1;
        while(i < n - 1 && times[i] < t) i++;
        float t0 = times[i-1], t1 = times[i];
        float u = (t - t0) / Math.max(1e-6f, t1 - t0);
        return squad(keys[i-1], innerA[i-1], innerB[i], keys[i], u);
    }

    /** shortest-path sampling: flips key signs so consecutive arcs stay under 90° */
    public Quat sampleShortest(float t){
        int n = keys.length;
        if(t <= times[0]) return keys[0].cpy();
        if(t >= times[n-1]) return keys[n-1].cpy();
        int i = 1;
        while(i < n - 1 && times[i] < t) i++;
        // simple chained slerp shortest-path fallback
        float u = (t - times[i-1]) / Math.max(1e-6f, times[i] - times[i-1]);
        return keys[i-1].cpy().slerp(keys[i], u);
    }

    // ---------------- squad core ----------------
    /** Squad(q0, q1, a, b, t) = Slerp(Slerp(q0, q1, t), Slerp(a, b, t), 2t(1-t)).
     *  The 2t(1-t) weight hits q0 exactly at t=0 and q1 exactly at t=1. */
    public static Quat squad(Quat p, Quat a, Quat b, Quat q, float t){
        Quat s1 = p.cpy().slerp(q, t);
        Quat s2 = a.cpy().slerp(b, t);
        return s1.slerp(s2, 2f * t * (1f - t)).norm();
    }

    // ---------------- quaternion log/exp (unit quaternions) ----------------
    /** log(q)/2 for unit quaternion -> tangent vector quat (w ~ 0) */
    static Quat logHalf(Quat q){
        q.norm();
        float s = (float)Math.sqrt(q.x*q.x + q.y*q.y + q.z*q.z);
        if(s < 1e-9f) return new Quat(0,0,0,1);
        float theta = (float)Math.atan2(s, q.w);
        float k = theta * 0.5f / s;
        return new Quat(q.x * k, q.y * k, q.z * k, 1f);   // w ignored by exp()
    }
    /** exp of tangent quat (interprets xyz as half-angle vector, w ignored) */
    static Quat exp(Quat t){
        float v = (float)Math.sqrt(t.x*t.x + t.y*t.y + t.z*t.z);
        if(v < 1e-9f) return new Quat(0,0,0,1);
        float sin = (float)Math.sin(v), cos = (float)Math.cos(v);
        float k = sin / v;
        return new Quat(t.x * k, t.y * k, t.z * k, cos).norm();
    }

    // helpers (Quat stays minimal — add/scale live here)
    private static Quat add(Quat a, Quat b){ return new Quat(a.x+b.x, a.y+b.y, a.z+b.z, a.w+b.w); }
    private static Quat scale(Quat a, float s){ return new Quat(a.x*s, a.y*s, a.z*s, a.w*s); }
}
