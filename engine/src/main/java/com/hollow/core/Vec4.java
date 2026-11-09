package com.hollow.core;

/** 4-component float vector (MATH). Used by shaders/colors/quaternion math interop. */
public final class Vec4 {
    public float x, y, z, w;

    public Vec4(){ this(0,0,0,0); }
    public Vec4(float x, float y, float z, float w){ this.x=x; this.y=y; this.z=z; this.w=w; }
    public Vec4(Vec4 o){ this(o.x,o.y,o.z,o.w); }
    public Vec4(Vec3 v, float w){ this(v.x, v.y, v.z, w); }

    public Vec4 set(float x, float y, float z, float w){ this.x=x; this.y=y; this.z=z; this.w=w; return this; }
    public Vec4 set(Vec4 o){ return set(o.x,o.y,o.z,o.w); }
    public Vec4 cpy(){ return new Vec4(this); }

    public Vec4 add(Vec4 o){ x+=o.x; y+=o.y; z+=o.z; w+=o.w; return this; }
    public Vec4 sub(Vec4 o){ x-=o.x; y-=o.y; z-=o.z; w-=o.w; return this; }
    public Vec4 scale(float s){ x*=s; y*=s; z*=s; w*=s; return this; }
    public float dot(Vec4 o){ return x*o.x + y*o.y + z*o.z + w*o.w; }
    public float len2(){ return x*x+y*y+z*z+w*w; }
    public float len(){ return (float)Math.sqrt(len2()); }
    public Vec4 norm(){
        float l = len();
        if(l < 1e-9f) return set(0,0,0,0);
        return scale(1f/l);
    }
    public Vec4 lerp(Vec4 o, float t){
        x += (o.x - x) * t; y += (o.y - y) * t; z += (o.z - z) * t; w += (o.w - w) * t;
        return this;
    }
    public Vec3 xyz(){ return new Vec3(x, y, z); }

    @Override public String toString(){ return "(" + x + ", " + y + ", " + z + ", " + w + ")"; }
}
