package com.hollow.scene;

/** HollowEngine 2 — the eye: renders the scene; exactly one camera is
 *  active at a time. rotation (0,0,0) looks down -Z. */
public class CameraNode extends Node {
    public float fov = 60f;
    public float near = 0.1f;
    public float far = 500f;
    public boolean active = true;
    public CameraNode(String name){ super(name); }
    @Override public String type(){ return "camera"; }
    @Override public boolean hHas(String key){
        return key.equals("fov") || key.equals("active") || super.hHas(key);
    }
    @Override public Object hGet(String key){
        if(key.equals("fov")) return (double)fov;
        if(key.equals("active")) return active;
        return super.hGet(key);
    }
    @Override public void hSet(String key, Object v){
        if(key.equals("fov")){ fov = (float)(double)(Double)v; return; }
        if(key.equals("active")){ active = com.hollow.script.Interp.strOf(v).equals("true"); return; }
        super.hSet(key, v);
    }
}
