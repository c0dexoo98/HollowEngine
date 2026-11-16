package com.hollow.scene;

/** HollowEngine 2 — runs a HollowScript file with the engine hooks
 *  on_start(node, scene) / on_update(node, scene, delta) /
 *  on_input(node, scene, key). */
public class ScriptNode extends Node {
    public String path = "";
    public ScriptNode(String name){ super(name); }
    @Override public String type(){ return "script"; }
    @Override public boolean hHas(String key){ return key.equals("path") || super.hHas(key); }
    @Override public Object hGet(String key){
        if(key.equals("path")) return path;
        return super.hGet(key);
    }
    @Override public void hSet(String key, Object v){
        if(key.equals("path")){ path = String.valueOf(v); return; }
        super.hSet(key, v);
    }
}
