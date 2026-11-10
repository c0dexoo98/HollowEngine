package com.hollow.assets;

import com.hollow.core.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Material instance (MAT-019): overrides parameters of a BASE material without
 *  compiling anything new — the classic UE MaterialInstance. The renderer resolves
 *  the effective MaterialDef by copying the base and applying overrides.
 *  JSON: {"base":"materials/rust.json","params":{"roughnessFactor":0.4,"baseColorFactor":[0.8,0.2,0.1,1]}} */
public final class MaterialInstance {
    public String base;                       // path/key of the base MaterialDef
    public final Map<String, Object> params = new LinkedHashMap<>();
    public final Map<String, String> textureOverrides = new LinkedHashMap<>();

    /** resolve into an effective MaterialDef given a base lookup */
    public MaterialDef resolve(java.util.function.Function<String, MaterialDef> baseLookup){
        MaterialDef d = base != null && baseLookup != null
                ? baseLookup.apply(base)
                : null;
        MaterialDef out = d != null ? copyOf(d) : new MaterialDef();
        for(Map.Entry<String, Object> e : params.entrySet()){
            apply(out, e.getKey(), e.getValue());
        }
        for(Map.Entry<String, String> e : textureOverrides.entrySet()){
            applyTexture(out, e.getKey(), e.getValue());
        }
        return out;
    }

    private static MaterialDef copyOf(MaterialDef src){
        MaterialDef d = new MaterialDef();
        d.name = src.name;
        d.baseColorFactor = src.baseColorFactor.clone();
        d.baseColorTexture = src.baseColorTexture;
        d.metallicFactor = src.metallicFactor;
        d.roughnessFactor = src.roughnessFactor;
        d.normalTexture = src.normalTexture;
        d.normalScale = src.normalScale;
        d.emissiveTexture = src.emissiveTexture;
        d.emissiveFactor = src.emissiveFactor.clone();
        d.alphaMode = src.alphaMode;
        d.alphaCutoff = src.alphaCutoff;
        d.doubleSided = src.doubleSided;
        d.unlit = src.unlit;
        d.uvScale = src.uvScale;
        return d;
    }

    private static void apply(MaterialDef d, String key, Object v){
        switch(key){
            case "baseColorFactor" -> {
                if(v instanceof java.util.List<?> l && l.size() >= 3){
                    for(int i=0;i<4;i++) d.baseColorFactor[i] = (float) Json.num(l.get(Math.min(i, l.size()-1)), 1);
                }
            }
            case "metallicFactor" -> d.metallicFactor = (float) Json.num(v, d.metallicFactor);
            case "roughnessFactor" -> d.roughnessFactor = (float) Json.num(v, d.roughnessFactor);
            case "normalScale" -> d.normalScale = (float) Json.num(v, d.normalScale);
            case "alphaCutoff" -> d.alphaCutoff = (float) Json.num(v, d.alphaCutoff);
            case "alphaMode" -> { try { d.alphaMode = MaterialDef.AlphaMode.valueOf(Json.str(v,"OPAQUE").toUpperCase()); } catch(Exception ignored){} }
            case "doubleSided" -> d.doubleSided = Json.bool(v, d.doubleSided);
            case "unlit" -> d.unlit = Json.bool(v, d.unlit);
            case "uvScale" -> d.uvScale = (float) Json.num(v, d.uvScale);
            case "name" -> d.name = Json.str(v, d.name);
            default -> { /* unknown scalar params are ignored (forward compat) */ }
        }
    }
    private static void applyTexture(MaterialDef d, String slot, String path){
        switch(slot){
            case "baseColor" -> d.baseColorTexture = path;
            case "normal" -> d.normalTexture = path;
            case "emissive" -> d.emissiveTexture = path;
            default -> { }
        }
    }

    public static MaterialInstance fromJson(Map<String,Object> m){
        MaterialInstance mi = new MaterialInstance();
        mi.base = Json.str(m.get("base"), null);
        Map<String,Object> p = Json.obj(m.get("params"));
        if(p != null) mi.params.putAll(p);
        Map<String,Object> t = Json.obj(m.get("textures"));
        if(t != null){
            for(Map.Entry<String,Object> e : t.entrySet()){
                mi.textureOverrides.put(e.getKey(), Json.str(e.getValue(), ""));
            }
        }
        return mi;
    }
}
