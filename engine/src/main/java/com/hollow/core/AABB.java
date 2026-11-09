package com.hollow.core;

/** Axis-aligned bounding box. */
public final class AABB {
    public final Vec3 min = new Vec3();
    public final Vec3 max = new Vec3();

    public AABB reset(){
        min.set(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        max.set(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        return this;
    }
    public AABB(){ reset(); }
    public AABB(Vec3 min, Vec3 max){ this.min.set(min); this.max.set(max); }

    public AABB expand(Vec3 p){
        min.x=Math.min(min.x,p.x); min.y=Math.min(min.y,p.y); min.z=Math.min(min.z,p.z);
        max.x=Math.max(max.x,p.x); max.y=Math.max(max.y,p.y); max.z=Math.max(max.z,p.z);
        return this;
    }
    public AABB expand(AABB o){
        expand(o.min); expand(o.max); return this;
    }
    public boolean valid(){ return min.x<=max.x && min.y<=max.y && min.z<=max.z; }

    public boolean overlaps(AABB o){
        return min.x<=o.max.x && max.x>=o.min.x &&
               min.y<=o.max.y && max.y>=o.min.y &&
               min.z<=o.max.z && max.z>=o.min.z;
    }
    public boolean contains(Vec3 p){
        return p.x>=min.x && p.x<=max.x && p.y>=min.y && p.y<=max.y && p.z>=min.z && p.z<=max.z;
    }
    public Vec3 center(){ return min.cpy().add(max).scale(0.5f); }
    public Vec3 size(){ return max.cpy().sub(min); }
    /** half-size */
    public Vec3 extents(){ return size().scale(0.5f); }
    public float radius(){
        Vec3 s = size(); return 0.5f*(float)Math.sqrt(s.len2());
    }
    public static AABB fromCenterExtents(Vec3 c, Vec3 e){
        return new AABB(c.cpy().sub(e), c.cpy().add(e));
    }
    /** from mesh positions (stride 3 floats) */
    public static AABB fromPositions(float[] pos){
        AABB b = new AABB();
        for(int i=0;i<pos.length;i+=3) b.expand(new Vec3(pos[i], pos[i+1], pos[i+2]));
        return b;
    }
}
