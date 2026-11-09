package com.hollow.core;

/** 3-component float vector with chaining ops (mutable, engine-wide). */
public final class Vec3 {
    public float x, y, z;

    public Vec3(){ this(0,0,0); }
    public Vec3(float x, float y, float z){ this.x=x; this.y=y; this.z=z; }
    public Vec3(Vec3 o){ this(o.x,o.y,o.z); }

    public Vec3 set(float x, float y, float z){ this.x=x; this.y=y; this.z=z; return this; }
    public Vec3 set(Vec3 o){ return set(o.x,o.y,o.z); }
    public Vec3 cpy(){ return new Vec3(this); }

    public Vec3 add(float x, float y, float z){ this.x+=x; this.y+=y; this.z+=z; return this; }
    public Vec3 add(Vec3 o){ return add(o.x,o.y,o.z); }
    public Vec3 sub(Vec3 o){ x-=o.x; y-=o.y; z-=o.z; return this; }
    public Vec3 scale(float s){ x*=s; y*=s; z*=s; return this; }
    public Vec3 mul(Vec3 o){ x*=o.x; y*=o.y; z*=o.z; return this; }

    public float dot(Vec3 o){ return x*o.x + y*o.y + z*o.z; }
    public Vec3 cross(Vec3 o){
        return set(y*o.z - z*o.y, z*o.x - x*o.z, x*o.y - y*o.x);
    }
    public float len2(){ return x*x+y*y+z*z; }
    public float len(){ return (float)Math.sqrt(len2()); }
    public Vec3 norm(){
        float l = len();
        if(l > 1e-9f){ x/=l; y/=l; z/=l; }
        return this;
    }
    public float dist(Vec3 o){ float dx=x-o.x,dy=y-o.y,dz=z-o.z; return (float)Math.sqrt(dx*dx+dy*dy+dz*dz); }
    public float dist2(Vec3 o){ float dx=x-o.x,dy=y-o.y,dz=z-o.z; return dx*dx+dy*dy+dz*dz; }

    public Vec3 lerp(Vec3 o, float t){
        x += (o.x-x)*t; y += (o.y-y)*t; z += (o.z-z)*t; return this;
    }

    public Vec3 addScaled(Vec3 o, float s){ x+=o.x*s; y+=o.y*s; z+=o.z*s; return this; }

    @Override public String toString(){ return "("+x+", "+y+", "+z+")"; }

    // ---- static helpers ----
    public static Vec3[] axisVectors(){
        return new Vec3[]{ new Vec3(1,0,0), new Vec3(0,1,0), new Vec3(0,0,1) };
    }
    public static float dot(Vec3 a, Vec3 b){ return a.dot(b); }
}
