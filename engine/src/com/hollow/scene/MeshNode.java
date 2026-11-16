package com.hollow.scene;

/** HollowEngine 2 — renders a mesh (loaded from .obj, or the built-in
 *  cube when empty) with a material color. */
public class MeshNode extends Node {
    public String meshPath = "";
    public String color = "silver";                     // material color name or #rrggbb
    public MeshNode(String name){ super(name); }
    @Override public String type(){ return "mesh"; }
    @Override public boolean hHas(String key){
        return key.equals("mesh") || key.equals("color") || super.hHas(key);
    }
    @Override public Object hGet(String key){
        if(key.equals("mesh")) return meshPath;
        if(key.equals("color")) return color;
        return super.hGet(key);
    }
    @Override public void hSet(String key, Object v){
        if(key.equals("mesh")){ meshPath = String.valueOf(v); return; }
        if(key.equals("color")){ color = String.valueOf(v); return; }
        super.hSet(key, v);
    }
}
