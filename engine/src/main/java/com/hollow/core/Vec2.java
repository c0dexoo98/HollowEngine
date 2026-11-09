package com.hollow.core;

/** 2-component float vector. */
public final class Vec2 {
    public float x, y;
    public Vec2(){ this(0,0); }
    public Vec2(float x, float y){ this.x=x; this.y=y; }
    public Vec2 set(float x, float y){ this.x=x; this.y=y; return this; }
    public Vec2 set(Vec2 o){ x=o.x; y=o.y; return this; }
    public Vec2 add(Vec2 o){ x+=o.x; y+=o.y; return this; }
    public Vec2 sub(Vec2 o){ x-=o.x; y-=o.y; return this; }
    public Vec2 scale(float s){ x*=s; y*=s; return this; }
    public float dot(Vec2 o){ return x*o.x + y*o.y; }
    public float len(){ return (float)Math.sqrt(x*x+y*y); }
    public Vec2 norm(){ float l=len(); return l>1e-8f ? set(x/l, y/l) : this; }
    public float dist(Vec2 o){ float dx=x-o.x, dy=y-o.y; return (float)Math.sqrt(dx*dx+dy*dy); }
    public Vec2 lerp(Vec2 o, float t){ x+=(o.x-x)*t; y+=(o.y-y)*t; return this; }
    public Vec2 cpy(){ return new Vec2(x,y); }
    @Override public String toString(){ return "("+x+", "+y+")"; }
}
