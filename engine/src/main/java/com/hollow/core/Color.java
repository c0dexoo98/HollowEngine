package com.hollow.core;

/** RGBA float color. */
public final class Color {
    public float r, g, b, a;
    public Color(){ this(1,1,1,1); }
    public Color(float r, float g, float b, float a){ this.r=r; this.g=g; this.b=b; this.a=a; }
    public Color(float r, float g, float b){ this(r,g,b,1); }
    public Color set(float r, float g, float b, float a){ this.r=r; this.g=g; this.b=b; this.a=a; return this; }
    public Color set(Color o){ return set(o.r,o.g,o.b,o.a); }
    public Color cpy(){ return new Color(r,g,b,a); }
    public Color scale(float s){ r*=s; g*=s; b*=s; return this; }
    public Color lerp(Color o, float t){
        r+=(o.r-r)*t; g+=(o.g-g)*t; b+=(o.b-b)*t; a+=(o.a-a)*t; return this;
    }
    public int rgba8888(){
        int R=(int)Math.min(255,Math.max(0,r*255f)), G=(int)Math.min(255,Math.max(0,g*255f)),
            B=(int)Math.min(255,Math.max(0,b*255f)), A=(int)Math.min(255,Math.max(0,a*255f));
        return (A<<24)|(R<<16)|(G<<8)|B;
    }
    public static Color parse(String s, Color def){
        try {
            s = s.trim();
            if(s.startsWith("#")) s = s.substring(1);
            if(s.length() != 6 && s.length() != 8) return def;   // #F00 etc. is not a supported format
            long v = Long.parseLong(s, 16);
            if(s.length()<=6) return new Color(((v>>16)&255)/255f, ((v>>8)&255)/255f, (v&255)/255f, 1f);
            return new Color(((v>>16)&255)/255f, ((v>>8)&255)/255f, (v&255)/255f, ((v>>24)&255)/255f);
        } catch(Exception e){ return def; }
    }
    @Override public String toString(){ return String.format("#%08x", rgba8888()); }
}
