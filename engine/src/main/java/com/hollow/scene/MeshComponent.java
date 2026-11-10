package com.hollow.scene;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.List;

/** Renders a mesh asset. Path resolved through AssetDB at render time.
 *  v2.2 (RND-033): explicit LOD list {"lods":[{"mesh":..., "d":0.1}, ...]} — the
 *  renderer picks the last entry whose threshold <= screen-size metric. Legacy
 *  lodDistances (bare thresholds) still work against the single `path`. */
public final class MeshComponent extends Component {
    public String path = "";
    public boolean castShadow = true;
    public boolean visible = true;
    /** material overrides: materialName -> {color:[r,g,b,a], metallic, roughness, emissive, texturePath} */
    public java.util.Map<String,Object> materialOverrides = null;
    /** LOD distances (ascending). Empty = single LOD. */
    public float[] lodDistances = new float[0];

    /** v2.2 LOD entries (ascending d) */
    public static final class LodEntry {
        public final String mesh;
        public final float d;         // screen-metric threshold
        public LodEntry(String mesh, float d){ this.mesh = mesh; this.d = d; }
    }
    public final java.util.List<LodEntry> lods = new java.util.ArrayList<>();

    @Override public void save(JsonW w){
        w.obj();
        w.kv("type","Mesh").kv("path",path).kv("castShadow",castShadow).kv("visible",visible);
        if(materialOverrides != null) w.kv("overrides", com.hollow.core.Json.write(materialOverrides));
        if(!lods.isEmpty()){
            w.arr("lods");
            for(LodEntry l : lods){
                w.obj(); w.kv("mesh", l.mesh).kv("d", l.d); w.endObj();
            }
            w.endArr();
        }
        w.endObj();
    }
    @Override public void load(JsonV n){
        path = n.str("path", "");
        castShadow = n.bool("castShadow", true);
        visible = n.bool("visible", true);
        String ov = n.str("overrides", null);
        if(ov != null){ try { materialOverrides = com.hollow.core.Json.parseObj(ov); } catch(Exception ignored){} }
        lods.clear();
        List<Object> arr = n.list("lods");
        if(arr != null){
            for(Object o : arr){
                java.util.Map<String,Object> m = com.hollow.core.Json.obj(o);
                if(m == null) continue;
                String mesh = com.hollow.core.Json.str(m.get("mesh"), null);
                if(mesh != null) lods.add(new LodEntry(mesh, (float) com.hollow.core.Json.num(m.get("d"), 0f)));
            }
        }
    }
}
