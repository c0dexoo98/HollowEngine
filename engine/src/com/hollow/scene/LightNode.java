package com.hollow.scene;

import com.hollow.core.MathX.Vec3;

/** HollowEngine 2 — directional light: direction comes from
 *  rotation.x/.y like a sun rig (0,0,0 shines straight down). */
public class LightNode extends Node {
    public String color = "white";
    public float intensity = 1.0f;
    public LightNode(String name){ super(name); }
    @Override public String type(){ return "light"; }
    @Override public boolean hHas(String key){
        return key.equals("color") || key.equals("intensity") || super.hHas(key);
    }
    @Override public Object hGet(String key){
        if(key.equals("color")) return color;
        if(key.equals("intensity")) return (double)intensity;
        return super.hGet(key);
    }
    @Override public void hSet(String key, Object v){
        if(key.equals("color")){ color = String.valueOf(v); return; }
        if(key.equals("intensity")){ intensity = (float)(double)(Double)v; return; }
        super.hSet(key, v);
    }
    public Vec3 lightDir(){
        double ax = Math.toRadians(rotation.x);
        double ay = Math.toRadians(rotation.y);
        return new Vec3((float)(Math.sin(ay) * Math.cos(ax) * -1f),
                        (float)(-Math.cos(ax)),
                        (float)(Math.cos(ay) * Math.cos(ax)));
    }
}
