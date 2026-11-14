package com.hollow.core;

/** 3D plane (MATH): normal + distance. Powers frustum culling, decal projection, mirror math. */
public final class Plane {
    public float nx, ny, nz, d; // n·p + d = 0

    public Plane(){}
    public Plane(float nx, float ny, float nz, float d){ set(nx, ny, nz, d); }

    public Plane set(float nx, float ny, float nz, float d){ this.nx=nx; this.ny=ny; this.nz=nz; this.d=d; return this; }
    public Plane cpy(){ return new Plane(nx, ny, nz, d); }

    /** build from three points (counter-clockwise = normal up) */
    public static Plane fromPoints(Vec3 a, Vec3 b, Vec3 c){
        Vec3 n = b.cpy().sub(a).cross(c.cpy().sub(a));
        if(n.len2() < 1e-12f) throw new IllegalArgumentException("Plane.fromPoints: collinear points");
        n.norm();
        return new Plane(n.x, n.y, n.z, -n.dot(a));
    }
    /** normal + a point on the plane */
    public static Plane fromNormalPoint(Vec3 n, Vec3 p){
        Vec3 nn = n.cpy().norm();
        return new Plane(nn.x, nn.y, nn.z, -nn.dot(p));
    }

    /** signed distance (positive = front/normal side) */
    public float distance(Vec3 p){ return nx*p.x + ny*p.y + nz*p.z + d; }

    /** ray intersection; t in [tmin, tmax] -> hit distance, else -1 */
    public float raycast(Vec3 origin, Vec3 dir, float tmin, float tmax){
        float denom = nx*dir.x + ny*dir.y + nz*dir.z;
        if(Math.abs(denom) < 1e-8f) return -1;
        float t = -(nx*origin.x + ny*origin.y + nz*origin.z + d) / denom;
        return (t >= tmin && t <= tmax) ? t : -1;
    }

    /** classify AABB: 1 front, -1 back, 0 straddling */
    public int boxSide(AABB box){
        Vec3 c = box.center();
        Vec3 e = box.extents();
        float r = Math.abs(nx)*e.x + Math.abs(ny)*e.y + Math.abs(nz)*e.z;
        float s = distance(c);
        if(s > r) return 1;
        if(s < -r) return -1;
        return 0;
    }

    public Vec3 normal(){ return new Vec3(nx, ny, nz); }
    @Override public String toString(){ return "Plane(n=" + nx + "," + ny + "," + nz + " d=" + d + ")"; }
}
