package com.hollow.render;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;

import java.util.List;

/** Screen-space projected decal (HollowEngine 2.0, UE Decal Component equivalent).
 *  Projects a texture (with tiling/fade) onto geometry inside an oriented box volume
 *  during the decal pass (after opaque, using scene depth).
 *  JSON: {"type":"Decal","texture":"assets/tex/bullet_hole.hvt","size":[1,1,1],
 *         "fadeScreen":0.1,"intensity":1.0,"tiling":1}
 */
public final class DecalComponent extends Component {
    public String texture = "";
    public final Vec3 size = new Vec3(1, 1, 1);       // decal projection box (full extents)
    public float intensity = 1f;
    public float tiling = 1f;
    public float fadeAngle = 0.6f;                    // skip grazing angles (dot threshold)
    public boolean normalBlend = false;               // also blend normals (requires normal target)

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Decal").kv("texture",texture)
          .kvv("size",size.x,size.y,size.z)
          .kv("intensity",intensity).kv("tiling",tiling).kv("fadeAngle",fadeAngle)
          .kv("normalBlend",normalBlend); w.endObj();
    }

    @Override public void load(JsonV n){
        texture = n.str("texture","");
        intensity = n.flt("intensity",1f);
        tiling = n.flt("tiling",1f);
        fadeAngle = n.flt("fadeAngle",0.6f);
        normalBlend = n.bool("normalBlend",false);
        Object s = n.get("size");
        if(s instanceof List<?> l && l.size() >= 3){
            size.set(f(l.get(0)), f(l.get(1)), f(l.get(2)));
        }
    }

    private static float f(Object o){
        if(o instanceof Number nb) return nb.floatValue();
        try { return Float.parseFloat(String.valueOf(o)); } catch(Exception e){ return 1f; }
    }
}
