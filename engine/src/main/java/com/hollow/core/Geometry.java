package com.hollow.core;

/** Geometric queries (MATH-023/024/026): barycentric coordinates, point-in-triangle,
 *  closest point on triangle, spherical/cylindrical coordinate conversions and the
 *  Welzl minimum bounding sphere. All allocation-light, static, engine-wide. */
public final class Geometry {
    private Geometry(){}

    // ---------------- MATH-023: barycentric / triangle ----------------

    /** barycentric coordinates of p in triangle (a,b,c); result = a*u + b*v + c*w.
     *  Returns null if the triangle is degenerate. */
    public static float[] barycentric(Vec3 p, Vec3 a, Vec3 b, Vec3 c){
        Vec3 v0 = b.cpy().sub(a), v1 = c.cpy().sub(a), v2 = p.cpy().sub(a);
        float d00 = v0.dot(v0), d01 = v0.dot(v1), d11 = v1.dot(v1);
        float d20 = v2.dot(v0), d21 = v2.dot(v1);
        float den = d00 * d11 - d01 * d01;
        if(Math.abs(den) < 1e-12f) return null;
        float v = (d11 * d20 - d01 * d21) / den;
        float w = (d00 * d21 - d01 * d20) / den;
        return new float[]{ 1f - v - w, v, w };       // (u, v, w)
    }

    /** true if p lies inside (or on the edge of) triangle (a,b,c) within epsilon */
    public static boolean pointInTriangle(Vec3 p, Vec3 a, Vec3 b, Vec3 c, float eps){
        float[] bary = barycentric(p, a, b, c);
        if(bary == null) return false;
        return bary[0] >= -eps && bary[1] >= -eps && bary[2] >= -eps;
    }

    /** closest point on triangle (a,b,c) to p (Ericson, Real-Time Collision Detection) */
    public static Vec3 closestPointOnTriangle(Vec3 p, Vec3 a, Vec3 b, Vec3 c){
        Vec3 ab = b.cpy().sub(a), ac = c.cpy().sub(a), ap = p.cpy().sub(a);
        float d1 = ab.dot(ap), d2 = ac.dot(ap);
        if(d1 <= 0 && d2 <= 0) return a.cpy();

        Vec3 bp = p.cpy().sub(b);
        float d3 = ab.dot(bp), d4 = ac.dot(bp);
        if(d3 >= 0 && d4 <= d3) return b.cpy();

        float vc = d1 * d4 - d3 * d2;
        if(vc <= 0 && d1 >= 0 && d3 <= 0){
            float t = d1 / (d1 - d3);
            return a.cpy().addScaled(ab, t);
        }

        Vec3 cp = p.cpy().sub(c);
        float d5 = ab.dot(cp), d6 = ac.dot(cp);
        if(d6 >= 0 && d5 <= d6) return c.cpy();

        float vb = d5 * d2 - d1 * d6;
        if(vb <= 0 && d2 >= 0 && d6 <= 0){
            float t = d2 / (d2 - d6);
            return a.cpy().addScaled(ac, t);
        }

        float va = d3 * d6 - d5 * d4;
        if(va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0){
            float t = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return b.cpy().addScaled(c.cpy().sub(b), t);
        }

        float den = 1f / (va + vb + vc);
        float v = vb * den, w = vc * den;
        return a.cpy().addScaled(ab, v).addScaled(ac, w);
    }

    /** triangle normal (counter-clockwise winding); zero vector if degenerate */
    public static Vec3 triangleNormal(Vec3 a, Vec3 b, Vec3 c){
        return b.cpy().sub(a).cross(c.cpy().sub(a)).norm();
    }

    // ---------------- MATH-024: spherical / cylindrical ----------------

    /** cartesian -> spherical (radius, theta=azimuth deg [0,360), phi=polar deg [0,180]) */
    public static float[] toSpherical(Vec3 v){
        float r = v.len();
        if(r < 1e-9f) return new float[]{ 0, 0, 90 };
        float theta = (float)Math.toDegrees(Math.atan2(v.z, v.x));
        if(theta < 0) theta += 360f;
        float phi = (float)Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, v.y / r))));
        return new float[]{ r, theta, phi };
    }

    /** spherical (radius, theta deg, phi deg) -> cartesian */
    public static Vec3 fromSpherical(float r, float thetaDeg, float phiDeg){
        float th = (float)Math.toRadians(thetaDeg), ph = (float)Math.toRadians(phiDeg);
        float sp_r = r * (float)Math.sin(ph);
        return new Vec3(sp_r * (float)Math.cos(th), r * (float)Math.cos(ph), sp_r * (float)Math.sin(th));
    }

    /** cartesian -> cylindrical (radius, theta deg [0,360), height) */
    public static float[] toCylindrical(Vec3 v){
        float r = (float)Math.sqrt(v.x * v.x + v.z * v.z);
        float theta = (float)Math.toDegrees(Math.atan2(v.z, v.x));
        if(theta < 0) theta += 360f;
        return new float[]{ r, theta, v.y };
    }

    /** cylindrical (radius, theta deg, height) -> cartesian */
    public static Vec3 fromCylindrical(float r, float thetaDeg, float height){
        float th = (float)Math.toRadians(thetaDeg);
        return new Vec3(r * (float)Math.cos(th), height, r * (float)Math.sin(th));
    }

    // ---------------- MATH-026: Welzl minimum bounding sphere ----------------

    /** minimal enclosing sphere of a point set (Welzl's move-to-front, iterative core).
     *  Degenerate supports (collinear/coplanar) fall back to lower-dimensional subsets
     *  so the result always encloses every point. */
    public static BoundingSphere minSphere(Vec3[] points){
        if(points == null || points.length == 0) return new BoundingSphere(new Vec3(), 0);
        Vec3[] shuffled = points.clone();
        RandomStream rng = new RandomStream(0x5DEECE66DL);
        for(int i = shuffled.length - 1; i > 0; i--){
            int j = rng.nextInt(i + 1);
            Vec3 t = shuffled[i]; shuffled[i] = shuffled[j]; shuffled[j] = t;
        }
        BoundingSphere s = new BoundingSphere(shuffled[0].cpy(), 0);
        for(int i=1;i<shuffled.length;i++){
            if(!s.contains(shuffled[i])){
                s = new BoundingSphere(shuffled[i].cpy(), 0);
                for(int j=0;j<i;j++){
                    if(!s.contains(shuffled[j])){
                        s = sphere2(shuffled[i], shuffled[j]);
                        for(int k=0;k<j;k++){
                            if(!s.contains(shuffled[k])){
                                s = sphere3Enclosing(shuffled[i], shuffled[j], shuffled[k]);
                                for(int m=0;m<k;m++){
                                    if(!s.contains(shuffled[m])){
                                        s = sphere4Enclosing(shuffled[i], shuffled[j], shuffled[k], shuffled[m]);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return s;
    }

    public static final class BoundingSphere {
        public final Vec3 center;
        public final float radius;
        public BoundingSphere(Vec3 c, float r){ center = c; radius = r; }
        public boolean intersects(BoundingSphere o){
            float d = center.dist(o.center);
            return d <= radius + o.radius;
        }
        public boolean contains(Vec3 p){ return center.dist2(p) <= radius * radius + 1e-4f; }
        @Override public String toString(){ return "Sphere[r=" + radius + " c=" + center + "]"; }
    }

    private static boolean inside(Vec3 p, Vec3 c, float r){
        return c.dist2(p) <= r * r + 1e-4f;
    }
    private static BoundingSphere sphere2(Vec3 a, Vec3 b){
        return new BoundingSphere(a.cpy().lerp(b, 0.5f), a.dist(b) * 0.5f);
    }
    /** circumcircle; falls back to the best enclosing 2-subset when collinear */
    private static BoundingSphere sphere3Enclosing(Vec3 a, Vec3 b, Vec3 c){
        Vec3 cc = circumcenter3(a, b, c);
        if(cc != null){
            float r = Math.max(cc.dist(a), Math.max(cc.dist(b), cc.dist(c)));
            BoundingSphere s = new BoundingSphere(cc, r);
            if(s.contains(a) && s.contains(b) && s.contains(c)) return s;
        }
        // degenerate: diameter of the two extreme points encloses the third
        BoundingSphere ab = sphere2(a, b);
        if(ab.contains(c)) return ab;
        BoundingSphere ac = sphere2(a, c);
        if(ac.contains(b)) return ac;
        BoundingSphere bc = sphere2(b, c);
        if(bc.contains(a)) return bc;
        // numerically pathological: conservative bound
        float rMax = Math.max(ab.radius, Math.max(ac.radius, bc.radius));
        return new BoundingSphere(a.cpy(), rMax + a.dist(b) + a.dist(c));
    }
    /** circumsphere; falls back to the best enclosing 3-subset (then 2-subset) */
    private static BoundingSphere sphere4Enclosing(Vec3 a, Vec3 b, Vec3 c, Vec3 d){
        Vec3 cc = circumcenter4(a, b, c, d);
        if(cc != null){
            float r = Math.max(Math.max(cc.dist(a), cc.dist(b)), Math.max(cc.dist(c), cc.dist(d)));
            BoundingSphere s = new BoundingSphere(cc, r);
            if(s.contains(a) && s.contains(b) && s.contains(c) && s.contains(d)) return s;
        }
        BoundingSphere best = null;
        Vec3[][] triples = { {a,b,c}, {a,b,d}, {a,c,d}, {b,c,d} };
        Vec3[][] singles = { {d}, {c}, {b}, {a} };
        for(int t = 0; t < 4; t++){
            BoundingSphere s = sphere3Enclosing(triples[t][0], triples[t][1], triples[t][2]);
            if(s.contains(singles[t][0])) return s;
            if(best == null || s.radius > best.radius) best = s;   // keep largest as safe fallback
        }
        // last resort: largest 2-subset sphere (must enclose by triangle inequality)
        BoundingSphere s2 = sphere2(a, b);
        float rMax = Math.max(s2.center.dist(c), s2.center.dist(d));
        return new BoundingSphere(s2.center, Math.max(s2.radius, rMax));
    }

    /** circumcenter of triangle; null if collinear */
    private static Vec3 circumcenter3(Vec3 a, Vec3 b, Vec3 c){
        Vec3 ab = b.cpy().sub(a), ac = c.cpy().sub(a);
        Vec3 n = ab.cpy().cross(ac);
        if(n.len2() < 1e-10f) return null;
        float[][] M = {
            { ab.x, ab.y, ab.z },
            { ac.x, ac.y, ac.z },
            { n.x,  n.y,  n.z  }
        };
        float[] rhs = {
            0.5f * (b.len2() - a.len2()),
            0.5f * (c.len2() - a.len2()),
            a.x * n.x + a.y * n.y + a.z * n.z
        };
        float det = det3(M);
        if(Math.abs(det) < 1e-10f) return null;
        return new Vec3(cramer(M, rhs, 0, det), cramer(M, rhs, 1, det), cramer(M, rhs, 2, det));
    }
    /** circumcenter of tetrahedron; null if coplanar/degenerate */
    private static Vec3 circumcenter4(Vec3 a, Vec3 b, Vec3 c, Vec3 d){
        Vec3 ba = b.cpy().sub(a), ca = c.cpy().sub(a), da = d.cpy().sub(a);
        float[][] M = {
            { ba.x, ba.y, ba.z },
            { ca.x, ca.y, ca.z },
            { da.x, da.y, da.z }
        };
        float[] rhs = {
            0.5f * (b.len2() - a.len2()),
            0.5f * (c.len2() - a.len2()),
            0.5f * (d.len2() - a.len2())
        };
        float det = det3(M);
        if(Math.abs(det) < 1e-10f) return null;
        return new Vec3(cramer(M, rhs, 0, det), cramer(M, rhs, 1, det), cramer(M, rhs, 2, det));
    }
    private static float det3(float[][] m){
        return m[0][0]*(m[1][1]*m[2][2]-m[1][2]*m[2][1])
             - m[0][1]*(m[1][0]*m[2][2]-m[1][2]*m[2][0])
             + m[0][2]*(m[1][0]*m[2][1]-m[1][1]*m[2][0]);
    }
    private static float cramer(float[][] M, float[] rhs, int col, float det){
        float[][] t = { M[0].clone(), M[1].clone(), M[2].clone() };
        for(int i=0;i<3;i++) t[i][col] = rhs[i];
        return det3(t) / det;
    }

    // ---------------- plane helpers ----------------

    /** ray vs plane intersection point; null when parallel or behind origin */
    public static Vec3 rayPlane(Vec3 origin, Vec3 dir, Plane plane){
        float t = plane.raycast(origin, dir, 0f, Float.MAX_VALUE);
        return t >= 0 ? origin.cpy().addScaled(dir, t) : null;
    }
}
