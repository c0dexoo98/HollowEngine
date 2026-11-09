package com.hollow.scene;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

/** Renders a mesh asset. Path resolved through AssetDB at render time. */
public final class MeshComponent extends Component {
    public String path = "";
    public boolean castShadow = true;
    public boolean visible = true;
    /** material overrides: materialName -> {color:[r,g,b,a], metallic, roughness, emissive, texturePath} */
    public java.util.Map<String,Object> materialOverrides = null;
    /** LOD distances (ascending). Empty = single LOD. */
    public float[] lodDistances = new float[0];

    @Override public void save(JsonW w){
        w.obj();
        w.kv("type","Mesh").kv("path",path).kv("castShadow",castShadow).kv("visible",visible);
        if(materialOverrides != null) w.kv("overrides", com.hollow.core.Json.write(materialOverrides));
        w.endObj();
    }
    @Override public void load(JsonV n){
        path = n.str("path", "");
        castShadow = n.bool("castShadow", true);
        visible = n.bool("visible", true);
        String ov = n.str("overrides", null);
        if(ov != null){ try { materialOverrides = com.hollow.core.Json.parseObj(ov); } catch(Exception ignored){} }
    }
}
