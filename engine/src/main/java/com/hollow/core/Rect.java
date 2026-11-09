package com.hollow.core;

/** Axis-aligned 2D rectangle (MATH): UI layout, sprite culling, region math. */
public final class Rect {
    public float x, y, w, h;

    public Rect(){}
    public Rect(float x, float y, float w, float h){ set(x,y,w,h); }
    public Rect(Rect o){ set(o.x,o.y,o.w,o.h); }

    public Rect set(float x, float y, float w, float h){ this.x=x; this.y=y; this.w=w; this.h=h; return this; }
    public Rect set(Rect o){ return set(o.x,o.y,o.w,o.h); }
    public Rect cpy(){ return new Rect(this); }

    public float left(){ return x; }
    public float right(){ return x + w; }
    public float top(){ return y; }
    public float bottom(){ return y + h; }
    public float centerX(){ return x + w * 0.5f; }
    public float centerY(){ return y + h * 0.5f; }
    public float area(){ return w * h; }

    public boolean contains(float px, float py){ return px >= x && px <= right() && py >= y && py <= bottom(); }
    public boolean contains(Rect o){ return contains(o.x, o.y) && contains(o.right(), o.bottom()); }
    public boolean intersects(Rect o){
        return x < o.right() && right() > o.x && y < o.bottom() && bottom() > o.y;
    }
    public Rect intersection(Rect o){
        float nx = Math.max(x, o.x), ny = Math.max(y, o.y);
        float nr = Math.min(right(), o.right()), nb = Math.min(bottom(), o.bottom());
        if(nr <= nx || nb <= ny) return new Rect(0,0,0,0);
        return new Rect(nx, ny, nr - nx, nb - ny);
    }
    public Rect union(Rect o){
        float nx = Math.min(x, o.x), ny = Math.min(y, o.y);
        float nr = Math.max(right(), o.right()), nb = Math.max(bottom(), o.bottom());
        return new Rect(nx, ny, nr - nx, nb - ny);
    }
    public Rect expand(float pad){ return new Rect(x - pad, y - pad, w + pad*2, h + pad*2); }
    public Rect translate(float dx, float dy){ return new Rect(x + dx, y + dy, w, h); }
    /** grow in place */
    public Rect inflate(float pad){ x -= pad; y -= pad; w += pad*2; h += pad*2; return this; }

    /** pixel snapping for UI (floor position, ceil size) */
    public Rect snap(){
        float x2 = (float)Math.ceil(right()), y2 = (float)Math.ceil(bottom());
        x = (float)Math.floor(x); y = (float)Math.floor(y);
        w = x2 - x; h = y2 - y;
        return this;
    }
    @Override public String toString(){ return "Rect(" + x + ", " + y + ", " + w + "x" + h + ")"; }
}
