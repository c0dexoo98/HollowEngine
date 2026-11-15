package com.hollow.collision;

import com.hollow.core.AABB;
import com.hollow.core.MathX;
import com.hollow.core.OBB;
import com.hollow.core.Quat;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.List;

/** v2.3 Batch 3 — collision library (COL-011..COL-025).
 *  Pure static math + small stateful helpers; no engine dependencies beyond core.
 *  <pre>
 *  COL-011 obbOBB(OBB,OBB)          full 15-axis SAT with MTV (normal + penetration)
 *  COL-012 obbCapsule(OBB,a,b,r)    OBB vs capsule via OBB-space segment clamp
 *  COL-013 ConvexHull               monotone-chain hull (<=32 verts) + overlap test
 *  COL-014 meshCapsule              triangle-accurate capsule vs triangle soup
 *  COL-015 Heightfield              heightmap raycast + sphere pushout (gradient normal)
 *  COL-016 sweepCapsule             capsule cast-and-collect (character mid-move)
 *  COL-017 DynamicBVH               dynamic AABB tree (insert/remove/update/query)
 *  COL-018 ContactManifold          multi-point contact generation (incident-face corners)
 *  COL-019 CollisionMatrix          32×32 group matrix (project-persisted JSON)
 *  COL-020 CollisionStats           counters + report string for the profiler
 *  COL-021 batchRay                 N rays vs AABB set in one call
 *  COL-022 cameraPick               camera ray through NDC (editor pick)
 *  COL-023 trajectoryPreview        projectile arc + bounce plan
 *  COL-024 surfaceSound             bridge: PhysicsMaterials.pairSurfaceSound (see physics pkg)
 *  COL-025 WaterSurface             animated waterline height/normal for floaters
 *  </pre>
 */
public final class CollisionLib {
    private CollisionLib() {}

    // ==================================================================
    // COL-011 — OBB vs OBB, full SAT (15 axes), returns MTV
    // ==================================================================
    public static final class MTV {
        public boolean hit;
        public final Vec3 normal = new Vec3(0, 1, 0);   // pushes A away from B
        public float penetration;
    }

    public static MTV obbOBB(OBB a, OBB b, MTV reuse){
        MTV out = reuse != null ? reuse : new MTV();
        Vec3 ax = a.rotation.right(), ay = a.rotation.up(), az = a.rotation.forward().scale(-1f);
        Vec3 bx = b.rotation.right(), by = b.rotation.up(), bz = b.rotation.forward().scale(-1f);
        Vec3[] aAxes = {ax, ay, az}, bAxes = {bx, by, bz};
        float bestPen = Float.MAX_VALUE;
        Vec3 bestAxis = new Vec3();
        Vec3 d = b.center.cpy().sub(a.center);
        // 3 + 3 face axes
        for(int i = 0; i < 6; i++){
            Vec3 axis = i < 3 ? aAxes[i] : bAxes[i - 3];
            if(!satAxis(axis, aAxes, a.halfExtents, bAxes, b.halfExtents, d, out)) return out;
            if(out.penetration < bestPen){ bestPen = out.penetration; bestAxis.set(out.normal); }
        }
        // 9 cross axes
        for(Vec3 u : aAxes){
            for(Vec3 v : bAxes){
                Vec3 axis = u.cpy().cross(v);               // cpy: Vec3.cross mutates this
                if(axis.len2() < 1e-12f) continue;
                if(!satAxis(axis, aAxes, a.halfExtents, bAxes, b.halfExtents, d, out)) return out;
                if(out.penetration < bestPen){ bestPen = out.penetration; bestAxis.set(out.normal); }
            }
        }
        out.hit = true;
        out.penetration = bestPen;
        out.normal.set(bestAxis);
        if(d.dot(bestAxis) > 0f) out.normal.scale(-1f);   // ensure normal pushes A away from B (B->A)
        return out;
    }

    private static boolean satAxis(Vec3 axis, Vec3[] aA, Vec3 aE, Vec3[] bA, Vec3 bE, Vec3 d, MTV out){
        float len = axis.len();
        if(len < 1e-6f){ out.hit = true; out.penetration = Float.MAX_VALUE; return true; }
        axis = axis.scale(1f / len);
        float projA = Math.abs(aA[0].dot(axis)) * aE.x + Math.abs(aA[1].dot(axis)) * aE.y + Math.abs(aA[2].dot(axis)) * aE.z;
        float projB = Math.abs(bA[0].dot(axis)) * bE.x + Math.abs(bA[1].dot(axis)) * bE.y + Math.abs(bA[2].dot(axis)) * bE.z;
        float dist = Math.abs(d.dot(axis));
        float pen = projA + projB - dist;
        if(pen <= 0f){ out.hit = false; return false; }
        out.hit = true;
        out.penetration = pen;
        out.normal.set(axis);
        return true;
    }

    // ==================================================================
    // COL-012 — OBB vs capsule (segment a-b, radius r); returns penetration (or -1)
    // ==================================================================
    public static float obbCapsule(OBB box, Vec3 a, Vec3 b, float r, Vec3 outNormal){
        Quat inv = box.rotation.invert();
        Vec3 la = inv.rotate(a.cpy().sub(box.center));
        Vec3 lb = inv.rotate(b.cpy().sub(box.center));
        Vec3 he = box.halfExtents;
        // alternating segment<->box projection until converged (true closest pair,
        // a single center-based sample misses diagonal contacts)
        Vec3 p = la.cpy().add(lb).scale(0.5f);
        for(int it = 0; it < 8; it++){
            Vec3 clamped = new Vec3(
                MathX.clamp(p.x, -he.x, he.x),
                MathX.clamp(p.y, -he.y, he.y),
                MathX.clamp(p.z, -he.z, he.z));
            Vec3 ab = lb.cpy().sub(la);
            float t = MathX.clamp(clamped.cpy().sub(la).dot(ab) / Math.max(1e-9f, ab.len2()), 0f, 1f);
            Vec3 np = la.cpy().addScaled(ab, t);
            if(np.dist2(p) < 1e-10f){ p = np; break; }
            p = np;
        }
        Vec3 clamped = new Vec3(
            MathX.clamp(p.x, -he.x, he.x),
            MathX.clamp(p.y, -he.y, he.y),
            MathX.clamp(p.z, -he.z, he.z));
        float dist = p.dist(clamped);
        if(dist >= r) return -1f;
        Vec3 nLocal = dist < 1e-6f
            ? new Vec3(0, 1, 0)
            : p.cpy().sub(clamped).scale(1f / dist);
        outNormal.set(box.rotation.rotate(nLocal));       // world normal out of the box
        return r - dist;
    }

    // ==================================================================
    // COL-013 — convex hull (monotone chain, <=32 verts) + SAT overlap
    // ==================================================================
    public static final class ConvexHull {
        public final List<Vec3> verts = new ArrayList<>();
        public final Vec3 normal = new Vec3(0, 0, 1);               // hull plane normal
        public float planeD;

        public boolean build(List<Vec3> points, int maxVerts){
            verts.clear();
            if(points.size() < 3) return false;
            // plane fit: Newell's method
            Vec3 n = new Vec3();
            float cx = 0, cy = 0, cz = 0;
            for(int i = 0; i < points.size(); i++){
                Vec3 cur = points.get(i), nxt = points.get((i + 1) % points.size());
                n.x += (cur.y - nxt.y) * (cur.z + nxt.z);
                n.y += (cur.z - nxt.z) * (cur.x + nxt.x);
                n.z += (cur.x - nxt.x) * (cur.y + nxt.y);
                cx += cur.x; cy += cur.y; cz += cur.z;
            }
            float nl = n.len();
            if(nl < 1e-9f) return false;
            normal.set(n.scale(1f / nl));
            planeD = -(normal.x * cx + normal.y * cy + normal.z * cz) / points.size();
            // drop the dominant normal axis for 2D projection
            int drop = Math.abs(normal.x) > Math.abs(normal.y)
                ? (Math.abs(normal.x) > Math.abs(normal.z) ? 0 : 2)
                : (Math.abs(normal.y) > Math.abs(normal.z) ? 1 : 2);
            List<float[]> pts2 = new ArrayList<>();
            List<Vec3> src = new ArrayList<>();
            for(Vec3 p : points){
                pts2.add(project2(p, drop));
                src.add(p);
            }
            // Andrew monotone chain
            pts2.sort((p, q) -> p[0] != q[0] ? Float.compare(p[0], q[0]) : Float.compare(p[1], q[1]));
            int m = pts2.size(), k = 0;
            float[][] hull = new float[2 * m][];
            for(int i = 0; i < m; i++){ while(k >= 2 && cross2(hull[k-2], hull[k-1], pts2.get(i)) <= 0) k--; hull[k++] = pts2.get(i); }
            for(int i = m - 2, t = k + 1; i >= 0; i--){ while(k >= t && cross2(hull[k-2], hull[k-1], pts2.get(i)) <= 0) k--; hull[k++] = pts2.get(i); }
            int count = Math.min(k - 1, maxVerts);
            for(int i = 0; i < count; i++){
                Vec3 best = nearestBy2D(src, hull[i], drop);
                if(best != null && !verts.contains(best)) verts.add(best);
            }
            return verts.size() >= 3;
        }

        /** SAT overlap in the shared projection plane (conservative: different planes → pass) */
        public boolean overlaps(ConvexHull o){
            if(verts.size() < 3 || o.verts.size() < 3) return false;
            // plane separation: A entirely on one side of B's plane (and far from it) → miss;
            // straddling (0) or coplanar (2) → keep. Symmetric check against A's plane.
            if(sameSideAll(verts, o.normal, o.planeD) != 0 && coplanarDist(o.normal, o.planeD, verts) > 1e-3f) return false;
            if(sameSideAll(o.verts, normal, planeD) != 0 && coplanarDist(normal, planeD, o.verts) > 1e-3f) return false;
            int dropA = dominantDrop(normal), dropB = dominantDrop(o.normal);
            if(dropA != dropB) return true;
            List<float[]> A = new ArrayList<>(), B = new ArrayList<>();
            for(Vec3 p : verts) A.add(project2(p, dropA));
            for(Vec3 p : o.verts) B.add(project2(p, dropA));
            return polyOverlap(A, B);
        }

        private int sameSideAll(List<Vec3> v, Vec3 n, float d){
            Boolean first = null;
            for(Vec3 p : v){
                float s = n.dot(p) + d;
                if(Math.abs(s) < 1e-5f) continue;
                boolean pos = s > 0f;
                if(first == null) first = pos;
                else if(first != pos) return 0;
            }
            return first == null ? 2 : (first ? 1 : -1);
        }

        private float coplanarDist(Vec3 n, float d, List<Vec3> v){
            float max = 0f;
            for(Vec3 p : v) max = Math.max(max, Math.abs(n.dot(p) + d));
            return max;
        }

        private static int dominantDrop(Vec3 n){
            return Math.abs(n.x) > Math.abs(n.y)
                ? (Math.abs(n.x) > Math.abs(n.z) ? 0 : 2)
                : (Math.abs(n.y) > Math.abs(n.z) ? 1 : 2);
        }

        private static float[] project2(Vec3 p, int drop){
            return drop == 0 ? new float[]{ p.z, p.y }
                 : drop == 1 ? new float[]{ p.x, p.z }
                 : new float[]{ p.x, p.y };
        }

        private static Vec3 nearestBy2D(List<Vec3> src, float[] h, int drop){
            Vec3 best = null;
            float bestD = Float.MAX_VALUE;
            for(Vec3 p : src){
                float[] q = project2(p, drop);
                float dx = q[0] - h[0], dy = q[1] - h[1];
                float d2 = dx * dx + dy * dy;
                if(d2 < bestD){ bestD = d2; best = p; }
            }
            return best;
        }

        private static float cross2(float[] o, float[] a, float[] b){
            return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
        }

        private static boolean polyOverlap(List<float[]> a, List<float[]> b){
            for(int i = 0; i < a.size(); i++){
                int j = (i + 1) % a.size();
                float ex = a.get(j)[0] - a.get(i)[0], ey = a.get(j)[1] - a.get(i)[1];
                float minA = Float.MAX_VALUE, maxA = -Float.MAX_VALUE;
                for(float[] p : a){
                    float s = ex * (p[1] - a.get(i)[1]) - ey * (p[0] - a.get(i)[0]);
                    minA = Math.min(minA, s); maxA = Math.max(maxA, s);
                }
                float minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
                for(float[] p : b){
                    float s = ex * (p[1] - a.get(i)[1]) - ey * (p[0] - a.get(i)[0]);
                    minB = Math.min(minB, s); maxB = Math.max(maxB, s);
                }
                if(maxA < minB || maxB < minA) return false;
            }
            // and B's edges against A
            for(int i = 0; i < b.size(); i++){
                int j = (i + 1) % b.size();
                float ex = b.get(j)[0] - b.get(i)[0], ey = b.get(j)[1] - b.get(i)[1];
                float minA = Float.MAX_VALUE, maxA = -Float.MAX_VALUE;
                for(float[] p : a){
                    float s = ex * (p[1] - b.get(i)[1]) - ey * (p[0] - b.get(i)[0]);
                    minA = Math.min(minA, s); maxA = Math.max(maxA, s);
                }
                float minB = Float.MAX_VALUE, maxB = -Float.MAX_VALUE;
                for(float[] p : b){
                    float s = ex * (p[1] - b.get(i)[1]) - ey * (p[0] - b.get(i)[0]);
                    minB = Math.min(minB, s); maxB = Math.max(maxB, s);
                }
                if(maxA < minB || maxB < minA) return false;
            }
            return true;
        }
    }

    // ==================================================================
    // COL-014 — mesh vs capsule (triangle-accurate)
    // ==================================================================
    public static final class MeshHit {
        public boolean hit;
        public float penetration;
        public final Vec3 normal = new Vec3(0, 1, 0);
        public int triangleIndex = -1;
    }

    /** capsule (segment ab, radius r) vs triangle list (x9 floats per tri); AABB broadphase per triangle */
    public static MeshHit meshCapsule(Vec3 a, Vec3 b, float r, float[] tris, MeshHit reuse){
        MeshHit out = reuse != null ? reuse : new MeshHit();
        out.hit = false;
        out.penetration = 0f;
        out.triangleIndex = -1;
        int triCount = tris.length / 9;
        float bestPen = 0f;
        Vec3 bestN = new Vec3(0, 1, 0);
        int bestTri = -1;
        float minX = Math.min(a.x, b.x) - r, maxX = Math.max(a.x, b.x) + r;
        float minY = Math.min(a.y, b.y) - r, maxY = Math.max(a.y, b.y) + r;
        float minZ = Math.min(a.z, b.z) - r, maxZ = Math.max(a.z, b.z) + r;
        for(int t = 0; t < triCount; t++){
            int o = t * 9;
            float v0x = tris[o], v0y = tris[o+1], v0z = tris[o+2];
            float v1x = tris[o+3], v1y = tris[o+4], v1z = tris[o+5];
            float v2x = tris[o+6], v2y = tris[o+7], v2z = tris[o+8];
            if(Math.max(v0x, Math.max(v1x, v2x)) < minX || Math.min(v0x, Math.min(v1x, v2x)) > maxX) continue;
            if(Math.max(v0y, Math.max(v1y, v2y)) < minY || Math.min(v0y, Math.min(v1y, v2y)) > maxY) continue;
            if(Math.max(v0z, Math.max(v1z, v2z)) < minZ || Math.min(v0z, Math.min(v1z, v2z)) > maxZ) continue;
            Vec3 v0 = new Vec3(v0x, v0y, v0z), v1 = new Vec3(v1x, v1y, v1z), v2 = new Vec3(v2x, v2y, v2z);
            Vec3 cpTri = new Vec3(), cpSeg = new Vec3();
            closestTriSeg(v0, v1, v2, a, b, cpTri, cpSeg);
            Vec3 d = cpSeg.cpy().sub(cpTri);            // surface -> object: push direction
            float dist = d.len();
            if(dist < r && r - dist > bestPen){
                bestPen = r - dist;
                bestN.set(dist < 1e-6f ? 0 : d.x / Math.max(1e-9f, dist), dist < 1e-6f ? 1 : d.y / Math.max(1e-9f, dist), dist < 1e-6f ? 0 : d.z / Math.max(1e-9f, dist));
                bestTri = t;
                out.hit = true;
            }
        }
        if(out.hit){
            out.penetration = bestPen;
            out.normal.set(bestN);
            out.triangleIndex = bestTri;
        }
        return out;
    }

    /** closest points between triangle and segment (segment sampled — robust at gameplay scale) */
    private static void closestTriSeg(Vec3 v0, Vec3 v1, Vec3 v2, Vec3 a, Vec3 b, Vec3 outTri, Vec3 outSeg){
        float bestD = Float.MAX_VALUE;
        final int SAMPLES = 7;
        for(int i = 0; i <= SAMPLES; i++){
            Vec3 sp = a.cpy().lerp(b, i / (float)SAMPLES);
            Vec3 cp = closestPointTriangle(sp, v0, v1, v2);
            float d = sp.dist(cp);
            if(d < bestD){ bestD = d; outTri.set(cp); outSeg.set(sp); }
        }
    }

    /** Ericson closest point on triangle */
    public static Vec3 closestPointTriangle(Vec3 p, Vec3 a, Vec3 b, Vec3 c){
        Vec3 ab = b.cpy().sub(a), ac = c.cpy().sub(a), ap = p.cpy().sub(a);
        float d1 = ab.dot(ap), d2 = ac.dot(ap);
        if(d1 <= 0f && d2 <= 0f) return a.cpy();
        Vec3 bp = p.cpy().sub(b);
        float d3 = ab.dot(bp), d4 = ac.dot(bp);
        if(d3 >= 0f && d4 <= d3) return b.cpy();
        float vc = d1 * d4 - d3 * d2;
        if(vc <= 0f && d1 >= 0f && d3 <= 0f){
            float v = d1 / (d1 - d3);
            return a.cpy().addScaled(ab, v);
        }
        Vec3 cp = p.cpy().sub(c);
        float d5 = ab.dot(cp), d6 = ac.dot(cp);
        if(d6 >= 0f && d5 <= d6) return c.cpy();
        float vb = d5 * d2 - d1 * d6;
        if(vb <= 0f && d2 >= 0f && d6 <= 0f){
            float w = d2 / (d2 - d6);
            return a.cpy().addScaled(ac, w);
        }
        float va = d3 * d6 - d5 * d4;
        if(va <= 0f && (d4 - d3) >= 0f && (d5 - d6) >= 0f){
            float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return b.cpy().addScaled(c.cpy().sub(b), w);
        }
        float denom = 1f / (va + vb + vc);
        float v = vb * denom, w = vc * denom;
        return a.cpy().addScaled(ab, v).addScaled(ac, w);
    }

    // ==================================================================
    // COL-015 — heightfield (heightmap) collision
    // ==================================================================
    public static final class Heightfield {
        public final float[] heights;      // row-major [rows*cols]
        public final int cols, rows;
        public final Vec3 origin = new Vec3();
        public float cellSize = 1f;

        public Heightfield(float[] heights, int cols, int rows, Vec3 origin, float cellSize){
            this.heights = heights; this.cols = cols; this.rows = rows;
            this.origin.set(origin); this.cellSize = cellSize;
        }

        /** bilinear height at world xz (outside terrain → -inf) */
        public float heightAt(float wx, float wz){
            float fx = (wx - origin.x) / cellSize;
            float fz = (wz - origin.z) / cellSize;
            int x0 = (int)Math.floor(fx), z0 = (int)Math.floor(fz);
            if(x0 < 0 || z0 < 0 || x0 >= cols - 1 || z0 >= rows - 1) return Float.NEGATIVE_INFINITY;
            float tx = fx - x0, tz = fz - z0;
            float h00 = heights[z0 * cols + x0], h10 = heights[z0 * cols + x0 + 1];
            float h01 = heights[(z0 + 1) * cols + x0], h11 = heights[(z0 + 1) * cols + x0 + 1];
            return MathX.lerp(MathX.lerp(h00, h10, tx), MathX.lerp(h01, h11, tx), tz);
        }

        /** straight down-ray vs terrain (ground snap, drops) */
        public boolean rayDown(Vec3 from, float maxDist, Vec3 outPoint){
            float h = heightAt(from.x, from.z);
            if(h == Float.NEGATIVE_INFINITY) return false;
            float dy = from.y - h;
            if(dy < 0f || dy > maxDist) return false;
            outPoint.set(from.x, h, from.z);
            return true;
        }

        /** sphere pushout with gradient-based normal */
        public float spherePush(Vec3 p, float r, Vec3 outNormal){
            float h = heightAt(p.x, p.z);
            if(h == Float.NEGATIVE_INFINITY) return -1f;
            float pen = (h + r) - p.y;
            if(pen <= 0f) return -1f;
            float eps = cellSize * 0.5f;
            float hc = heightAt(p.x, p.z);
            float hxp = heightAt(p.x + eps, p.z), hxm = heightAt(p.x - eps, p.z);
            float hzp = heightAt(p.x, p.z + eps), hzm = heightAt(p.x, p.z - eps);
            // off-grid samples: treat as flat (reuse center height) instead of -inf → NaN
            if(hxp == Float.NEGATIVE_INFINITY) hxp = hc;
            if(hxm == Float.NEGATIVE_INFINITY) hxm = hc;
            if(hzp == Float.NEGATIVE_INFINITY) hzp = hc;
            if(hzm == Float.NEGATIVE_INFINITY) hzm = hc;
            float hx = hxp - hxm;
            float hz = hzp - hzm;
            outNormal.set(-hx / (2f * eps), 1f, -hz / (2f * eps)).norm();
            return pen;
        }
    }

    // ==================================================================
    // COL-016 — capsule sweep (cast-and-collect, earliest blocking hit)
    // ==================================================================
    public static final class SweepHit {
        public boolean hit;
        public float fraction = 1f;
        public final Vec3 position = new Vec3();
        public final Vec3 normal = new Vec3(0, 1, 0);
        public Object source;                       // Heightfield / OBB that blocked
    }

    public static SweepHit sweepCapsule(Vec3 basePos, Vec3 aOff, Vec3 bOff, float r, Vec3 delta,
                                        List<OBB> obstacles, Heightfield terrain, SweepHit reuse){
        SweepHit out = reuse != null ? reuse : new SweepHit();
        out.hit = false;
        out.fraction = 1f;
        float len = delta.len();
        if(len < 1e-9f) return out;
        Vec3 end = basePos.cpy().add(delta);
        int steps = Math.max(2, (int)(len / Math.max(0.05f, r * 0.5f)) + 1);
        Vec3 n = new Vec3();
        for(int i = 1; i <= steps; i++){
            float t = i / (float)steps;
            Vec3 p = basePos.cpy().lerp(end, t);
            Vec3 sa = p.cpy().add(aOff), sb = p.cpy().add(bOff);
            for(OBB ob : obstacles){
                if(obbCapsule(ob, sa, sb, r, n) > 0f){
                    out.hit = true;
                    out.fraction = Math.max(0f, t - 1f / steps);
                    out.normal.set(n);
                    out.position.set(p);
                    out.source = ob;
                    return out;
                }
            }
            if(terrain != null){
                Vec3 mid = new Vec3((sa.x + sb.x) * 0.5f, sa.y, (sa.z + sb.z) * 0.5f);
                if(terrain.spherePush(mid, r, n) > 0f){
                    out.hit = true;
                    out.fraction = Math.max(0f, t - 1f / steps);
                    out.normal.set(n);
                    out.position.set(p);
                    out.source = terrain;
                    return out;
                }
            }
        }
        out.position.set(end);
        return out;
    }

    // ==================================================================
    // COL-017 — dynamic AABB tree (BVH, b2DynamicTree-style)
    // ==================================================================
    public static final class DynamicBVH {
        private static final int NIL = -1;
        private static final class Node {
            int parent = NIL, left = NIL, right = NIL;
            final AABB box = new AABB(new Vec3(), new Vec3());
            int height = 0;
            int objectId = -1;       // >=0 on leaves, -2 = freed slot
        }
        private final List<Node> nodes = new ArrayList<>();
        private int root = NIL;
        private float fatMargin = 0.1f;

        public void setMargin(float m){ fatMargin = Math.max(0.01f, m); }

        public int insert(AABB box, int objectId){
            int leaf = allocate();
            Node n = nodes.get(leaf);
            n.box.min.set(box.min.x - fatMargin, box.min.y - fatMargin, box.min.z - fatMargin);
            n.box.max.set(box.max.x + fatMargin, box.max.y + fatMargin, box.max.z + fatMargin);
            n.objectId = objectId;
            n.height = 0;
            insertLeaf(leaf);
            return leaf;
        }

        public void remove(int leaf){
            if(leaf < 0 || leaf >= nodes.size() || nodes.get(leaf).objectId < 0) return;
            removeLeaf(leaf);
            nodes.get(leaf).objectId = -2;
            nodes.get(leaf).height = -1;
            nodes.get(leaf).left = NIL;
            nodes.get(leaf).right = NIL;
            nodes.get(leaf).parent = NIL;
        }

        /** update after movement; returns true when the leaf outgrew its fat box and was reinserted */
        public boolean update(int leaf, AABB box){
            if(leaf < 0 || leaf >= nodes.size()) return false;
            Node n = nodes.get(leaf);
            if(n.objectId < 0) return false;
            if(n.box.contains(box.min) && n.box.contains(box.max)) return false;
            removeLeaf(leaf);
            n.box.min.set(box.min.x - fatMargin, box.min.y - fatMargin, box.min.z - fatMargin);
            n.box.max.set(box.max.x + fatMargin, box.max.y + fatMargin, box.max.z + fatMargin);
            insertLeaf(leaf);
            return true;
        }

        /** collect object ids of leaves whose fat box overlaps the query */
        public void query(AABB box, List<Integer> outIds){
            outIds.clear();
            if(root == NIL) return;
            java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
            stack.push(root);
            while(!stack.isEmpty()){
                int idx = stack.pop();
                if(idx < 0) continue;
                Node n = nodes.get(idx);
                if(!n.box.overlaps(box)) continue;
                if(n.objectId >= 0) outIds.add(n.objectId);
                else { stack.push(n.left); stack.push(n.right); }
            }
        }

        public int nodeCount(){ return nodes.size(); }
        public int height(){ return root == NIL ? 0 : nodes.get(root).height; }
        public int rootIndex(){ return root; }

        private int allocate(){
            for(int i = 0; i < nodes.size(); i++)
                if(nodes.get(i).objectId == -2) return i;
            nodes.add(new Node());
            return nodes.size() - 1;
        }

        private void insertLeaf(int leaf){
            if(root == NIL){ root = leaf; nodes.get(leaf).parent = NIL; return; }
            AABB leafBox = nodes.get(leaf).box;
            int sibling = root;
            while(nodes.get(sibling).objectId < 0){
                Node s = nodes.get(sibling);
                float volL = unionVolume(nodes.get(s.left).box, leafBox);
                float volR = unionVolume(nodes.get(s.right).box, leafBox);
                sibling = volL < volR ? s.left : s.right;
            }
            int oldParent = nodes.get(sibling).parent;
            int newParent = allocate();
            Node np = nodes.get(newParent);
            Node sib = nodes.get(sibling);
            np.parent = oldParent;
            np.left = sibling; np.right = leaf;
            np.box.min.set(Math.min(sib.box.min.x, leafBox.min.x), Math.min(sib.box.min.y, leafBox.min.y), Math.min(sib.box.min.z, leafBox.min.z));
            np.box.max.set(Math.max(sib.box.max.x, leafBox.max.x), Math.max(sib.box.max.y, leafBox.max.y), Math.max(sib.box.max.z, leafBox.max.z));
            np.height = sib.height + 1;
            sib.parent = newParent;
            nodes.get(leaf).parent = newParent;
            if(oldParent == NIL){
                root = newParent;
            } else {
                Node op = nodes.get(oldParent);
                if(op.left == sibling) op.left = newParent; else op.right = newParent;
            }
            for(int i = nodes.get(leaf).parent; i != NIL; i = nodes.get(i).parent)
                refit(i);
        }

        private void removeLeaf(int leaf){
            if(leaf == root){ root = NIL; return; }
            int parent = nodes.get(leaf).parent;
            int grandParent = nodes.get(parent).parent;
            int sibling = nodes.get(parent).left == leaf ? nodes.get(parent).right : nodes.get(parent).left;
            if(grandParent == NIL){
                root = sibling;
                nodes.get(sibling).parent = NIL;
            } else {
                Node gp = nodes.get(grandParent);
                if(gp.left == parent) gp.left = sibling; else gp.right = sibling;
                nodes.get(sibling).parent = grandParent;
                for(int i = grandParent; i != NIL; i = nodes.get(i).parent)
                    refit(i);
            }
            // recycle the parent slot
            nodes.get(parent).objectId = -2;
            nodes.get(parent).height = -1;
            nodes.get(parent).left = NIL;
            nodes.get(parent).right = NIL;
            nodes.get(parent).parent = NIL;
        }

        private void refit(int idx){
            Node n = nodes.get(idx);
            if(n.left < 0 || n.right < 0) return;
            Node l = nodes.get(n.left), r = nodes.get(n.right);
            n.box.min.set(Math.min(l.box.min.x, r.box.min.x), Math.min(l.box.min.y, r.box.min.y), Math.min(l.box.min.z, r.box.min.z));
            n.box.max.set(Math.max(l.box.max.x, r.box.max.x), Math.max(l.box.max.y, r.box.max.y), Math.max(l.box.max.z, r.box.max.z));
            n.height = 1 + Math.max(l.height, r.height);
        }

        private float unionVolume(AABB a, AABB b){
            float dx = Math.max(a.max.x, b.max.x) - Math.min(a.min.x, b.min.x);
            float dy = Math.max(a.max.y, b.max.y) - Math.min(a.min.y, b.min.y);
            float dz = Math.max(a.max.z, b.max.z) - Math.min(a.min.z, b.min.z);
            return Math.max(0f, dx) * Math.max(0f, dy) * Math.max(0f, dz);
        }
    }

    // ==================================================================
    // COL-018 — contact manifold (multi-point)
    // ==================================================================
    public static final class ContactPoint {
        public final Vec3 point = new Vec3();
        public final Vec3 normal = new Vec3();
        public float penetration;
        public float accumulatedImpulse;
    }

    public static final class ContactManifold {
        public final List<ContactPoint> points = new ArrayList<>(4);
        public final Vec3 normal = new Vec3(0, 1, 0);

        public void clear(){ points.clear(); }

        /** incident-face corner sampling against the reference OBB footprint (up to 4 points) */
        public void buildBoxBox(OBB a, OBB b, MTV m){
            clear();
            normal.set(m.normal);
            Vec3 refCenter = a.center;
            Vec3 ax = a.rotation.right(), ay = a.rotation.up(), az = a.rotation.forward().scale(-1f);
            Vec3[] bAxes = { b.rotation.right(), b.rotation.up(), b.rotation.forward().scale(-1f) };
            Vec3 incAxis = bAxes[0];
            int incIdx = 0;                                    // P12: track WHICH axis won
            float bestDot = Float.MAX_VALUE;
            for(int bi = 0; bi < 3; bi++){
                float d = bAxes[bi].dot(m.normal);
                if(d < bestDot){ bestDot = d; incAxis = bAxes[bi]; incIdx = bi; }
            }
            float sign = Math.signum(incAxis.dot(m.normal));
            // P12: was always b.halfExtents.y — for X/Z incident faces the sampled contact
            // patch floated inside/off the actual face and the manifold points got rejected
            float incExt = incIdx == 0 ? b.halfExtents.x : (incIdx == 1 ? b.halfExtents.y : b.halfExtents.z);
            Vec3 centerB = b.center.cpy().addScaled(incAxis, sign * incExt);
            Vec3 t1 = incAxis.cross(m.normal);
            if(t1.len2() < 1e-8f) t1.set(1, 0, 0);
            t1.norm();
            Vec3 t2 = incAxis.cross(t1).norm();
            float e = Math.min(b.halfExtents.x, Math.min(b.halfExtents.y, b.halfExtents.z)) * 0.9f;
            Vec3[] corners = new Vec3[]{
                centerB.cpy().addScaled(t1,  e).addScaled(t2,  e),
                centerB.cpy().addScaled(t1, -e).addScaled(t2,  e),
                centerB.cpy().addScaled(t1,  e).addScaled(t2, -e),
                centerB.cpy().addScaled(t1, -e).addScaled(t2, -e)};
            for(Vec3 c : corners){
                Vec3 local = c.cpy().sub(refCenter);
                float fx = local.dot(ax), fy = local.dot(ay), fz = local.dot(az);
                if(Math.abs(fx) <= a.halfExtents.x + 0.01f && Math.abs(fy) <= a.halfExtents.y + 0.01f
                   && Math.abs(fz) <= a.halfExtents.z + 0.01f){
                    ContactPoint cp = new ContactPoint();
                    cp.point.set(c);
                    cp.normal.set(m.normal);
                    cp.penetration = m.penetration;
                    points.add(cp);
                }
            }
            if(points.isEmpty()){
                ContactPoint cp = new ContactPoint();       // fallback: single mid point
                cp.point.set(a.center.cpy().addScaled(m.normal, -m.penetration * 0.5f));
                cp.normal.set(m.normal);
                cp.penetration = m.penetration;
                points.add(cp);
            }
        }
    }

    // ==================================================================
    // COL-019 — 32×32 collision group matrix
    // ==================================================================
    public static final class CollisionMatrix {
        private final boolean[][] table = new boolean[32][32];
        public CollisionMatrix(){
            for(int i = 0; i < 32; i++)
                for(int j = 0; j < 32; j++)
                    table[i][j] = true;
        }
        public void set(int a, int b, boolean collides){
            table[a][b] = collides; table[b][a] = collides;
        }
        public boolean get(int a, int b){ return table[a][b]; }
        public void disableAll(){ for(int i = 0; i < 32; i++) java.util.Arrays.fill(table[i], false); }

        /** compact rows-major JSON {"groups":[[0/1 ×32]×32]} */
        public String toJson(){
            StringBuilder sb = new StringBuilder(2048);
            sb.append("{\"groups\":[");
            for(int i = 0; i < 32; i++){
                if(i > 0) sb.append(',');
                sb.append('[');
                for(int j = 0; j < 32; j++){
                    if(j > 0) sb.append(',');
                    sb.append(table[i][j] ? '1' : '0');
                }
                sb.append(']');
            }
            sb.append("]}");
            return sb.toString();
        }

        public static CollisionMatrix fromJson(String json){
            CollisionMatrix m = new CollisionMatrix();
            Object root = com.hollow.core.Json.parse(json);
            if(!(root instanceof java.util.Map<?,?> map)) return m;
            Object groups = map.get("groups");
            if(groups instanceof List<?> rows){
                int i = 0;
                for(Object rowObj : rows){
                    if(rowObj instanceof List<?> row){
                        int j = 0;
                        for(Object cell : row){
                            if(i < 32 && j < 32){
                                boolean on = cell instanceof Number num ? num.intValue() != 0
                                    : Integer.parseInt(String.valueOf(cell).trim()) != 0;
                                m.table[i][j] = on;
                            }
                            j++;
                        }
                    }
                    i++;
                }
            }
            return m;
        }
    }

    // ==================================================================
    // COL-020 — collision statistics
    // ==================================================================
    public static final class CollisionStats {
        public long broadphasePairs, narrowTests, narrowHits, raysCast, raysHit, manifoldPoints;
        public double broadphaseMs, narrowMs;
        public void reset(){
            broadphasePairs = narrowTests = narrowHits = raysCast = raysHit = manifoldPoints = 0;
            broadphaseMs = narrowMs = 0;
        }
        public String report(){
            return String.format(
                "pairs=%d narrow=%d hits=%d rays=%d rayHits=%d manifoldPts=%d bp=%.2fms narrow=%.2fms",
                broadphasePairs, narrowTests, narrowHits, raysCast, raysHit, manifoldPoints, broadphaseMs, narrowMs);
        }
    }

    // ==================================================================
    // COL-021 — batch raycast
    // ==================================================================
    public static final class RayResult {
        public boolean hit;
        public float distance;
        public final Vec3 point = new Vec3();
        public final Vec3 normal = new Vec3();
    }

    /** N rays vs an AABB set in one call (world statics batch case) */
    public static void batchRay(Vec3 origin, Vec3[] dirs, float maxDist, AABB[] boxes, RayResult[] out){
        for(int i = 0; i < dirs.length && i < out.length; i++){
            Vec3 d = dirs[i];
            float L = d.len();
            RayResult r = out[i];
            if(r == null){ r = new RayResult(); out[i] = r; }
            if(L < 1e-9f){ r.hit = false; r.distance = maxDist; continue; }
            Vec3 dn = d.cpy().scale(1f / L);
            float best = Float.MAX_VALUE;
            Vec3 bestN = new Vec3();
            Vec3 n = new Vec3();
            for(AABB b : boxes){
                float t = rayAABB(origin, dn, b, n);
                if(t >= 0f && t < best && t <= maxDist){ best = t; bestN.set(n); }
            }
            r.hit = best < Float.MAX_VALUE;
            r.distance = r.hit ? best : maxDist;
            if(r.hit){
                r.point.set(origin).addScaled(dn, best);
                r.normal.set(bestN);
            }
        }
    }

    static float rayAABB(Vec3 o, Vec3 d, AABB b, Vec3 outNormal){
        float tmin = 0f, tmax = Float.MAX_VALUE;
        int axis = 1; float sign = -1f;
        float[][] ax = {
            {o.x, d.x, b.min.x, b.max.x},
            {o.y, d.y, b.min.y, b.max.y},
            {o.z, d.z, b.min.z, b.max.z}};
        for(int i = 0; i < 3; i++){
            if(Math.abs(ax[i][1]) < 1e-9f){
                if(ax[i][0] < ax[i][2] || ax[i][0] > ax[i][3]) return -1f;
            } else {
                float inv = 1f / ax[i][1];
                float t1 = (ax[i][2] - ax[i][0]) * inv, t2 = (ax[i][3] - ax[i][0]) * inv;
                float s = -1f;
                if(t1 > t2){ float tt = t1; t1 = t2; t2 = tt; s = 1f; }
                if(t1 > tmin){ tmin = t1; axis = i; sign = s; }
                if(t2 < tmax) tmax = t2;
                if(tmin > tmax) return -1f;
            }
        }
        if(tmin < 0f) return -1f;
        switch(axis){
            case 0: outNormal.set(sign, 0, 0); break;
            case 1: outNormal.set(0, sign, 0); break;
            default: outNormal.set(0, 0, sign); break;
        }
        return tmin;
    }

    // ==================================================================
    // COL-022 — camera pick (NDC → world ray)
    // ==================================================================
    /** minimal 4×4 (row-major storage, column-vector convention) */
    public static final class Mat4Like {
        public final float[] m = new float[16];
        public static Mat4Like identity(){
            Mat4Like r = new Mat4Like();
            r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1f;
            return r;
        }
        public Mat4Like copy(){ Mat4Like r = new Mat4Like(); System.arraycopy(m, 0, r.m, 0, 16); return r; }
        public Mat4Like mul(Mat4Like o){
            Mat4Like r = new Mat4Like();
            for(int c = 0; c < 4; c++)
                for(int rw = 0; rw < 4; rw++){
                    float s = 0f;
                    for(int k = 0; k < 4; k++) s += m[rw * 4 + k] * o.m[k * 4 + c];
                    r.m[rw * 4 + c] = s;
                }
            return r;
        }
        public Mat4Like invert(){
            Mat4Like out = new Mat4Like();
            float a00=m[0],a01=m[1],a02=m[2],a03=m[3],a10=m[4],a11=m[5],a12=m[6],a13=m[7],
                  a20=m[8],a21=m[9],a22=m[10],a23=m[11],a30=m[12],a31=m[13],a32=m[14],a33=m[15];
            float b00=a00*a11-a01*a10, b01=a00*a12-a02*a10, b02=a00*a13-a03*a10,
                  b03=a01*a12-a02*a11, b04=a01*a13-a03*a11, b05=a02*a13-a03*a12,
                  b06=a20*a31-a21*a30, b07=a20*a32-a22*a30, b08=a20*a33-a23*a30,
                  b09=a21*a32-a22*a31, b10=a21*a33-a23*a31, b11=a22*a33-a23*a32;
            float det = b00*b11 - b01*b10 + b02*b09 + b03*b08 - b04*b07 + b05*b06;
            if(Math.abs(det) < 1e-12f) return identity();
            float inv = 1f / det;
            out.m[0]=(a11*b11-a12*b10+a13*b09)*inv;  out.m[1]=(a02*b10-a01*b11-a03*b09)*inv;
            out.m[2]=(a31*b05-a32*b04+a33*b03)*inv;  out.m[3]=(a22*b04-a21*b05-a23*b03)*inv;
            out.m[4]=(a12*b08-a10*b11-a13*b07)*inv;  out.m[5]=(a00*b11-a02*b08+a03*b07)*inv;
            out.m[6]=(a32*b02-a30*b05-a33*b01)*inv;  out.m[7]=(a20*b05-a22*b02+a23*b01)*inv;
            out.m[8]=(a10*b10-a11*b08+a13*b06)*inv;  out.m[9]=(a01*b08-a00*b10-a03*b06)*inv;
            out.m[10]=(a30*b04-a31*b02+a33*b00)*inv; out.m[11]=(a21*b02-a20*b04-a23*b00)*inv;
            out.m[12]=(a11*b07-a10*b09-a12*b06)*inv; out.m[13]=(a00*b09-a01*b07+a02*b06)*inv;
            out.m[14]=(a31*b01-a30*b03-a32*b00)*inv; out.m[15]=(a20*b03-a21*b01+a22*b00)*inv;
            return out;
        }
    }

    public static final class Vec4 { public float x, y, z, w; }

    /** unproject an NDC point through view & proj matrices into a world-space direction */
    public static Vec3 cameraPickRay(Vec3 ndc, Mat4Like view, Mat4Like proj, Vec3 outDir){
        Mat4Like inv = proj.copy().invert().mul(view.copy().invert());
        Vec4 p0 = transform(inv, ndc.x, ndc.y, -1f, 1f);
        Vec4 p1 = transform(inv, ndc.x, ndc.y,  1f, 1f);
        if(Math.abs(p0.w) < 1e-9f || Math.abs(p1.w) < 1e-9f){ outDir.set(0, 0, -1); return outDir; }
        Vec3 a = new Vec3(p0.x / p0.w, p0.y / p0.w, p0.z / p0.w);   // near plane point
        Vec3 b = new Vec3(p1.x / p1.w, p1.y / p1.w, p1.z / p1.w);   // far plane point
        Vec3 dir = a.cpy().sub(b);                                  // near <- far = view forward
        float l = dir.len();
        outDir.set(l < 1e-9f ? new Vec3(0, 0, -1) : dir.scale(1f / l));
        return outDir;
    }

    private static Vec4 transform(Mat4Like m, float x, float y, float z, float w){
        Vec4 r = new Vec4();
        float[] a = m.m;
        r.x = a[0]*x + a[1]*y + a[2]*z + a[3]*w;
        r.y = a[4]*x + a[5]*y + a[6]*z + a[7]*w;
        r.z = a[8]*x + a[9]*y + a[10]*z + a[11]*w;
        r.w = a[12]*x + a[13]*y + a[14]*z + a[15]*w;
        return r;
    }

    // ==================================================================
    // COL-023 — projectile trajectory preview
    // ==================================================================
    /** ballistic arc with bounce plan; returns polyline points (world space) */
    public static List<Vec3> trajectoryPreview(Vec3 start, Vec3 velocity, float gravity,
                                                float maxTime, float dt, int maxBounces,
                                                AABB[] world, float restitution){
        List<Vec3> out = new ArrayList<>(64);
        Vec3 p = start.cpy(), v = velocity.cpy();
        int bounces = 0;
        float t = 0f;
        out.add(p.cpy());
        Vec3 n = new Vec3();
        while(t < maxTime){
            Vec3 prev = p.cpy();
            v.y -= gravity * dt;
            p.addScaled(v, dt);
            boolean bounced = false;
            if(world != null){
                for(AABB box : world){
                    if(box.contains(p)){
                        float vl = v.len();
                        if(vl > 1e-9f){
                            float tHit = rayAABB(prev, v.cpy().scale(1f / vl), box, n);
                            if(tHit >= 0f){
                                Vec3 hitP = prev.cpy().addScaled(v.cpy().scale(1f / vl), tHit);
                                out.add(hitP);
                                float vn = v.dot(n);
                                v.addScaled(n, -(1f + restitution) * vn);
                                p.set(hitP).addScaled(n, 0.01f);
                                bounced = true;
                                break;
                            }
                        }
                    }
                }
            }
            if(!bounced) out.add(p.cpy());
            if(bounced && ++bounces >= maxBounces) break;
            t += dt;
        }
        return out;
    }

    // ==================================================================
    // COL-025 — surface water sampling for floaters
    // ==================================================================
    public static final class WaterSurface {
        public float amplitude = 0.08f;
        public float speed = 1.6f;
        public float wavelength = 3.2f;

        /** crossed-sine wave height at world xz */
        public float height(float wx, float wz, float time){
            float k = (float)(2.0 * Math.PI) / wavelength;
            return amplitude * (float)Math.sin(k * wx + time * speed)
                 + amplitude * 0.6f * (float)Math.sin(k * wz * 1.3f + time * speed * 1.7f);
        }

        /** surface normal via finite difference (floaters orient with the swell) */
        public Vec3 normal(float wx, float wz, float time, Vec3 out){
            float e = 0.12f;
            float hx = height(wx + e, wz, time) - height(wx - e, wz, time);
            float hz = height(wx, wz + e, time) - height(wx, wz - e, time);
            out.set(-hx / (2f * e), 1f, -hz / (2f * e)).norm();
            return out;
        }
    }
}
