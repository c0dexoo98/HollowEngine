package com.hollow.assets;

import java.util.LinkedHashMap;
import java.util.Map;

/** Material definition (PBR metallic-roughness, glTF-compatible). Pure data. */
public final class MaterialDef {
    public enum AlphaMode { OPAQUE, MASK, BLEND }

    public String name = "default";
    public float[] baseColorFactor = {1,1,1,1};
    public String baseColorTexture = null;      // asset path
    public float metallicFactor = 0f;
    public float roughnessFactor = 1f;
    public String normalTexture = null;
    public float normalScale = 1f;
    public String emissiveTexture = null;
    public float[] emissiveFactor = {0,0,0};
    public AlphaMode alphaMode = AlphaMode.OPAQUE;
    public float alphaCutoff = 0.5f;
    public boolean doubleSided = false;
    /** optional: unlit shader flag (UI/2D/sky) */
    public boolean unlit = false;
    /** uv tiling */
    public float uvScale = 1f;

    public Map<String,Object> toJson(){
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("baseColorFactor", baseColorFactor);
        if(baseColorTexture != null) m.put("baseColorTexture", baseColorTexture);
        m.put("metallicFactor", metallicFactor);
        m.put("roughnessFactor", roughnessFactor);
        if(normalTexture != null) m.put("normalTexture", normalTexture);
        m.put("normalScale", normalScale);
        if(emissiveTexture != null) m.put("emissiveTexture", emissiveTexture);
        m.put("emissiveFactor", emissiveFactor);
        m.put("alphaMode", alphaMode.name());
        m.put("alphaCutoff", alphaCutoff);
        m.put("doubleSided", doubleSided);
        m.put("unlit", unlit);
        m.put("uvScale", uvScale);
        return m;
    }
    public static MaterialDef fromJson(Map<String,Object> m){
        MaterialDef d = new MaterialDef();
        d.name = com.hollow.core.Json.str(m.get("name"), d.name);
        Object bcf = m.get("baseColorFactor");
        if(bcf instanceof java.util.List<?> l && l.size()>=3){
            for(int i=0;i<4;i++) d.baseColorFactor[i] = (float) com.hollow.core.Json.num(l.get(Math.min(i,l.size()-1)), 1);
        }
        d.baseColorTexture = com.hollow.core.Json.str(m.get("baseColorTexture"), null);
        d.metallicFactor = (float) com.hollow.core.Json.num(m.get("metallicFactor"), 0);
        d.roughnessFactor = (float) com.hollow.core.Json.num(m.get("roughnessFactor"), 1);
        d.normalTexture = com.hollow.core.Json.str(m.get("normalTexture"), null);
        d.normalScale = (float) com.hollow.core.Json.num(m.get("normalScale"), 1);
        d.emissiveTexture = com.hollow.core.Json.str(m.get("emissiveTexture"), null);
        Object ef = m.get("emissiveFactor");
        if(ef instanceof java.util.List<?> l && l.size()>=3){
            for(int i=0;i<3;i++) d.emissiveFactor[i] = (float) com.hollow.core.Json.num(l.get(i), 0);
        }
        String am = com.hollow.core.Json.str(m.get("alphaMode"), "OPAQUE");
        try { d.alphaMode = AlphaMode.valueOf(am.toUpperCase()); } catch(Exception ignored){}
        d.alphaCutoff = (float) com.hollow.core.Json.num(m.get("alphaCutoff"), 0.5);
        d.doubleSided = com.hollow.core.Json.bool(m.get("doubleSided"), false);
        d.unlit = com.hollow.core.Json.bool(m.get("unlit"), false);
        d.uvScale = (float) com.hollow.core.Json.num(m.get("uvScale"), 1);
        return d;
    }
}
