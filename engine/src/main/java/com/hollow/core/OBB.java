package com.hollow.core;

/** Oriented bounding box (MATH-022): center + half-extents + rotation quaternion.
 *  Supports construction from an entity transform, AABB conversion, SAT overlap
 *  tests (OBB/OBB, OBB/AABB), containment and closest-point queries. */
public final class OBB {
    public final Vec3 center = new Vec3();
    public final Vec3 halfExtents = new Vec3(0.5f, 0.5f, 0.5f);
    public final Quat rotation = new Quat();

    public OBB(){}
    public OBB(Vec3 center, Vec3 halfExtents, Quat rotation){
        this.center.set(center);
        this.halfExtents.set(halfExtents);
        this.rotation.set(rotation);
    }

    // ---------------- construction ----------------

    /** build from world matrix (position + rotation + scale/2 = half extents) */
    public static OBB fromWorldMatrix(Mat4 m){
        OBB b = new OBB();
        b.center.set(m.translation());
        // column vectors = rotated axes scaled by extents
        Vec3 ax = new Vec3(m.m[0], m.m[1], m.m[2]);
        Vec3 ay = new Vec3(m.m[4], m.m[5], m.m[6]);
        Vec3 az = new Vec3(m.m[8], m.m[9], m.m[10]);
        b.halfExtents.set(ax.len() * 0.5f, ay.len() * 0.5f, az.len() * 0.5f);
        // extract rotation by normalizing axes
        Vec3 x = ax.norm(), y = ay.norm(), z = x.cpy().cross(y).norm();
        y.set(z.cpy().cross(x));
        b.rotation.set(fromAxes(x, y, z));
        return b;
    }

    /** rotation quaternion from orthonormal axis vectors */
    public static Quat fromAxes(Vec3 x, Vec3 y, Vec3 z){
        // rotation matrix -> quaternion (column-major)
        float m00=x.x, m01=y.x, m02=z.x;
        float m10=x.y, m11=y.y, m12=z.y;
        float m20=x.z, m21=y.z, m22=z.z;
        float tr = m00 + m11 + m22;
        float qx, qy, qz, qw;
        if(tr > 0){
            float s = (float)Math.sqrt(tr + 1f) * 2f;
            qw = 0.25f * s;
            qx = (m21 - m12) / s; qy = (m02 - m20) / s; qz = (m10 - m01) / s;
        } else if(m00 > m11 && m00 > m22){
            float s = (float)Math.sqrt(1f + m00 - m11 - m22) * 2f;
            qw = (m21 - m12) / s; qx = 0.25f * s;
            qy = (m01 + m10) / s; qz = (m02 + m20) / s;
        } else if(m11 > m22){
            float s = (float)Math.sqrt(1f + m11 - m00 - m22) * 2f;
            qw = (m02 - m20) / s; qx = (m01 + m10) / s;
            qy = 0.25f * s; qz = (m12 + m21) / s;
        } else {
            float s = (float)Math.sqrt(1f + m22 - m00 - m11) * 2f;
            qw = (m10 - m01) / s; qx = (m02 + m20) / s;
            qy = (m12 + m21) / s; qz = 0.25f * s;
        }
        return new Quat(qx, qy, qz, qw).norm();
    }

    /** enclosing OBB of an axis-aligned box (identity rotation) */
    public static OBB fromAABB(AABB b){
        return new OBB(b.center(), b.extents(), new Quat());
    }

    // ---------------- axes ----------------

    public Vec3 axisX(){ return rotation.right().scale(halfExtents.x); }
    public Vec3 axisY(){ return rotation.up().scale(halfExtents.y); }
    public Vec3 axisZ(){ return rotation.forward().scale(-halfExtents.z); }  // forward = -Z

    // ---------------- tests ----------------

    /** SAT overlap OBB vs OBB — 15 axes (6 face normals + 9 edge cross products) */
    public boolean overlaps(OBB o){
        Vec3[] A = { rotation.right(), rotation.up(), rotation.forward().scale(-1f) };
        Vec3[] B = { o.rotation.right(), o.rotation.up(), o.rotation.forward().scale(-1f) };
        float[] a = { halfExtents.x, halfExtents.y, halfExtents.z };
        float[] b = { o.halfExtents.x, o.halfExtents.y, o.halfExtents.z };
        Vec3 d = o.center.cpy().sub(center);

        float ra, rb;
        // 6 face axes
        for(int i=0;i<3;i++){
            ra = a[i];
            rb = Math.abs(b[0] * A[i].dot(B[0])) + Math.abs(b[1] * A[i].dot(B[1])) + Math.abs(b[2] * A[i].dot(B[2]));
            if(Math.abs(d.dot(A[i])) > ra + rb + 1e-6f) return false;
        }
        for(int j=0;j<3;j++){
            ra = Math.abs(a[0] * B[j].dot(A[0])) + Math.abs(a[1] * B[j].dot(A[1])) + Math.abs(a[2] * B[j].dot(A[2]));
            rb = b[j];
            if(Math.abs(d.dot(B[j])) > ra + rb + 1e-6f) return false;
        }
        // 9 edge-edge cross axes
        for(int i=0;i<3;i++){
            for(int j=0;j<3;j++){
                Vec3 L = A[i].cpy().cross(B[j]);
                float l2 = L.len2();
                if(l2 < 1e-10f) continue;               // parallel edges — covered by face axes
                L.scale(1f / (float)Math.sqrt(l2));
                ra = Math.abs(a[0] * A[(i+1)%3].dot(L)) + Math.abs(a[1] * A[(i+2)%3].dot(L));
                rb = Math.abs(b[0] * B[(j+1)%3].dot(L)) + Math.abs(b[1] * B[(j+2)%3].dot(L));
                if(Math.abs(d.dot(L)) > ra + rb + 1e-6f) return false;
            }
        }
        return true;
    }

    /** overlap with an axis-aligned box */
    public boolean overlapsAABB(AABB box){ return overlaps(OBB.fromAABB(box)); }

    public boolean contains(Vec3 p){
        Vec3 d = p.cpy().sub(center);
        Vec3 x = rotation.right(), y = rotation.up(), z = rotation.forward().scale(-1f);
        return Math.abs(d.dot(x)) <= halfExtents.x + 1e-6f
            && Math.abs(d.dot(y)) <= halfExtents.y + 1e-6f
            && Math.abs(d.dot(z)) <= halfExtents.z + 1e-6f;
    }

    /** closest point on the box surface (interior points project to themselves) */
    public Vec3 closestPoint(Vec3 p){
        Vec3 d = p.cpy().sub(center);
        Vec3 x = rotation.right(), y = rotation.up(), z = rotation.forward().scale(-1f);
        float tx = clampAbs(d.dot(x), halfExtents.x);
        float ty = clampAbs(d.dot(y), halfExtents.y);
        float tz = clampAbs(d.dot(z), halfExtents.z);
        return center.cpy()
            .addScaled(x, tx)
            .addScaled(y, ty)
            .addScaled(z, tz);
    }
    private static float clampAbs(float v, float lim){ return v < -lim ? -lim : (v > lim ? lim : v); }

    /** world-space AABB (conservative, 8 corners) */
    public AABB worldAABB(){
        AABB out = new AABB().reset();
        Vec3 x = axisX(), y = axisY(), z = axisZ();
        for(int i=0;i<8;i++){
            Vec3 c = center.cpy()
                .addScaled(x, (i & 1) != 0 ? 1 : -1)
                .addScaled(y, (i & 2) != 0 ? 1 : -1)
                .addScaled(z, (i & 4) != 0 ? 1 : -1);
            out.expand(c);
        }
        return out;
    }

    @Override public String toString(){
        return "OBB[c=" + center + " h=" + halfExtents + "]";
    }
}
