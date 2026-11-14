package com.hollow.core;

import java.util.List;

/** Parametric 3D spline (MATH): linear, Catmull-Rom (through points) and cubic Bezier
 *  (control points). Powers camera rails, patrol paths, cinematic sequencer tracks. */
public final class Spline {

    public enum Mode { LINEAR, CATMULL_ROM, BEZIER }

    private final Vec3[] points;   // CATMULL_ROM: path points; BEZIER: p0,c0,c1,p1,c0,c1,p2...
    private final Mode mode;

    public Spline(Mode mode, Vec3[] points){
        if(points == null || points.length < 2) throw new IllegalArgumentException("need >= 2 points");
        this.mode = mode;
        this.points = new Vec3[points.length];
        for(int i=0;i<points.length;i++) this.points[i] = points[i].cpy();
    }
    public Spline(Mode mode, List<Vec3> pts){ this(mode, pts.toArray(new Vec3[0])); }

    public static Spline linear(Vec3... pts){ return new Spline(Mode.LINEAR, pts); }
    public static Spline catmullRom(Vec3... pts){ return new Spline(Mode.CATMULL_ROM, pts); }
    /** n control points -> chain of cubic segments (4k+1 points: P C C P C C P ...) */
    public static Spline bezier(Vec3... pts){ return new Spline(Mode.BEZIER, pts); }

    /** sample at t in [0,1] */
    public Vec3 sample(float t){
        t = clamp01(t);
        return switch(mode){
            case LINEAR -> sampleLinear(t);
            case CATMULL_ROM -> sampleCatmull(t);
            case BEZIER -> sampleBezier(t);
        };
    }
    /** finite-difference tangent (direction, not normalized) */
    public Vec3 tangent(float t){
        float e = 0.002f;
        Vec3 a = sample(Math.max(0, t - e));
        Vec3 b = sample(Math.min(1, t + e));
        Vec3 d = b.cpy().sub(a);
        return d.len2() < 1e-12f ? new Vec3(0,0,1) : d.norm();
    }
    /** total polyline length (64 samples) */
    public float length(){
        float len = 0;
        Vec3 prev = sample(0);
        for(int i=1;i<=64;i++){
            Vec3 p = sample(i / 64f);
            len += p.dist(prev);
            prev = p;
        }
        return len;
    }
    /** nearest t on the spline to a point (32-step coarse + 16-step refine) */
    public float nearestT(Vec3 p){
        float bestT = 0, bestD = Float.MAX_VALUE;
        for(int i=0;i<=32;i++){
            float t = i / 32f;
            float d = sample(t).dist2(p);
            if(d < bestD){ bestD = d; bestT = t; }
        }
        float step = 1f/32f;
        for(int i=0;i<16;i++){
            float t0 = Math.max(0, bestT - step), t1 = Math.min(1, bestT + step);
            for(int k=0;k<=4;k++){
                float t = t0 + (t1 - t0) * k / 4f;
                float d = sample(t).dist2(p);
                if(d < bestD){ bestD = d; bestT = t; }
            }
            step /= 4f;
        }
        return bestT;
    }

    public int pointCount(){ return points.length; }
    public Vec3 point(int i){ return points[i].cpy(); }
    public Mode mode(){ return mode; }

    // ---------------- samplers ----------------
    private Vec3 sampleLinear(float t){
        float f = t * (points.length - 1);
        int i = Math.min((int) f, points.length - 2);
        return points[i].cpy().lerp(points[i+1], f - i);
    }
    private Vec3 sampleCatmull(float t){
        int n = points.length;
        float f = t * (n - 1);
        int i = Math.min((int) f, n - 2);
        float u = f - i;
        Vec3 p0 = points[Math.max(0, i-1)];
        Vec3 p1 = points[i];
        Vec3 p2 = points[i+1];
        Vec3 p3 = points[Math.min(n-1, i+2)];
        return catmull(p0, p1, p2, p3, u);
    }
    private Vec3 sampleBezier(float t){
        // chain of segments, each 3 point-steps (P C C -> next P)
        if((points.length - 1) % 3 != 0)
            throw new IllegalStateException("bezier chain needs 3k+1 points, got " + points.length);
        int segs = (points.length - 1) / 3;
        if(segs < 1) return sampleLinear(t);
        float f = t * segs;
        int seg = Math.min((int) f, segs - 1);
        float u = f - seg;
        int b = seg * 3;
        return cubicBezier(points[b], points[b+1], points[b+2], points[b+3], u);
    }

    // ---------------- static math ----------------
    public static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t){
        float t2 = t*t, t3 = t2*t;
        float x = 0.5f * ((2*p1.x) + (-p0.x + p2.x)*t + (2*p0.x - 5*p1.x + 4*p2.x - p3.x)*t2 + (-p0.x + 3*p1.x - 3*p2.x + p3.x)*t3);
        float y = 0.5f * ((2*p1.y) + (-p0.y + p2.y)*t + (2*p0.y - 5*p1.y + 4*p2.y - p3.y)*t2 + (-p0.y + 3*p1.y - 3*p2.y + p3.y)*t3);
        float z = 0.5f * ((2*p1.z) + (-p0.z + p2.z)*t + (2*p0.z - 5*p1.z + 4*p2.z - p3.z)*t2 + (-p0.z + 3*p1.z - 3*p2.z + p3.z)*t3);
        return new Vec3(x, y, z);
    }
    public static Vec3 quadraticBezier(Vec3 p0, Vec3 c, Vec3 p1, float t){
        float u = 1 - t;
        return new Vec3(
            u*u*p0.x + 2*u*t*c.x + t*t*p1.x,
            u*u*p0.y + 2*u*t*c.y + t*t*p1.y,
            u*u*p0.z + 2*u*t*c.z + t*t*p1.z);
    }
    public static Vec3 cubicBezier(Vec3 p0, Vec3 c0, Vec3 c1, Vec3 p1, float t){
        float u = 1 - t;
        float w0 = u*u*u, w1 = 3*u*u*t, w2 = 3*u*t*t, w3 = t*t*t;
        return new Vec3(
            w0*p0.x + w1*c0.x + w2*c1.x + w3*p1.x,
            w0*p0.y + w1*c0.y + w2*c1.y + w3*p1.y,
            w0*p0.z + w1*c0.z + w2*c1.z + w3*p1.z);
    }

    static float clamp01(float t){ return t < 0 ? 0 : (t > 1 ? 1 : t); }
}
