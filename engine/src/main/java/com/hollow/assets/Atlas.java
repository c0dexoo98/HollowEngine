package com.hollow.assets;

import java.util.LinkedHashMap;
import java.util.Map;

/** Sprite atlas: packed HVT texture + normalized regions. */
public final class Atlas {
    public static final class Region {
        public float u0, v0, u1, v1;
        public int px, py, pw, ph;
    }
    public String texturePath;             // .hvt
    public int width, height;
    public final Map<String, Region> sprites = new LinkedHashMap<>();

    public Region get(String name){
        if(sprites.isEmpty()) return null;
        // direct or frame-suffix lookup (name_3)
        Region r = sprites.get(name);
        if(r != null) return r;
        int us = name.lastIndexOf('_');
        if(us > 0){
            r = sprites.get(name.substring(0, us));
            if(r != null) return r;
        }
        return null;
    }
    public static Atlas fromJson(Map<String,Object> root){
        Atlas a = new Atlas();
        a.texturePath = com.hollow.core.Json.str(root.get("texture"), null);
        a.width = com.hollow.core.Json.int32(root.get("width"), 0);
        a.height = com.hollow.core.Json.int32(root.get("height"), 0);
        Map<String,Object> sp = com.hollow.core.Json.obj(root.get("sprites"));
        if(sp != null){
            for(Map.Entry<String,Object> e : sp.entrySet()){
                java.util.List<Object> r = com.hollow.core.Json.arr(e.getValue());
                if(r == null || r.size() < 4) continue;
                int x = com.hollow.core.Json.int32(r.get(0),0), y = com.hollow.core.Json.int32(r.get(1),0);
                int w = com.hollow.core.Json.int32(r.get(2),0), h = com.hollow.core.Json.int32(r.get(3),0);
                Region rg = new Region();
                rg.px=x; rg.py=y; rg.pw=w; rg.ph=h;
                rg.u0 = x/(float)a.width; rg.v0 = y/(float)a.height;
                rg.u1 = (x+w)/(float)a.width; rg.v1 = (y+h)/(float)a.height;
                a.sprites.put(e.getKey(), rg);
            }
        }
        return a;
    }
}
