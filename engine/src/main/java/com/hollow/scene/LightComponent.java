package com.hollow.scene;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import com.hollow.core.Color;

/** Light: directional / point / spot. Lights the PBR forward pipeline. */
public final class LightComponent extends Component {
    public enum Kind { DIRECTIONAL, POINT, SPOT }

    public Kind kind = Kind.POINT;
    public final Color color = new Color(1,1,1,1);
    public float intensity = 1.0f;      // lux-ish scale
    public float range = 10f;           // point/spot radius
    public float innerAngle = 25f;      // spot cone (deg)
    public float outerAngle = 40f;
    public boolean castShadow = false;  // only one directional shadow caster supported per scene
    /** flicker: amplitude 0..1 (0=off), speed Hz, seed — like torch/candle */
    public float flickerAmplitude = 0f;
    public float flickerSpeed = 8f;
    public int flickerSeed = 1;

    float flickerValue = 1f;
    float time = 0;

    @Override public void onUpdate(float dt){
        if(flickerAmplitude > 0){
            time += dt * flickerSpeed;
            // layered sines + hash — organic flicker
            float a = (float)Math.sin(time*3.1f) * 0.5f + (float)Math.sin(time*7.7f + flickerSeed)*0.3f
                    + (float)Math.sin(time*13.3f + flickerSeed*2)*0.2f;
            flickerValue = 1f - flickerAmplitude * (0.5f + 0.5f*a);
        } else flickerValue = 1f;
    }
    public float effectiveIntensity(){ return intensity * flickerValue; }

    @Override public void save(JsonW w){
        w.obj();
        w.kv("type","Light").kv("kind", kind.name().toLowerCase()).kvv("color", color.r, color.g, color.b);
        w.kv("intensity", intensity).kv("range", range).kv("innerAngle", innerAngle).kv("outerAngle", outerAngle);
        w.kv("castShadow", castShadow).kv("flicker", flickerAmplitude).kv("flickerSpeed", flickerSpeed);
        w.endObj();
    }
    @Override public void load(JsonV n){
        String k = n.str("kind","point").toLowerCase();
        kind = k.equals("directional") ? Kind.DIRECTIONAL : (k.equals("spot") ? Kind.SPOT : Kind.POINT);
        com.hollow.core.Color c = com.hollow.core.Json.color(n.get("color"), com.hollow.core.Color.parse("#ffffff", null));
        if(c != null) color.set(c);
        intensity = n.flt("intensity", 1f);
        range = n.flt("range", 10f);
        innerAngle = n.flt("innerAngle", 25f);
        outerAngle = n.flt("outerAngle", 40f);
        castShadow = n.bool("castShadow", false);
        flickerAmplitude = n.flt("flicker", 0f);
        flickerSpeed = n.flt("flickerSpeed", 8f);
        flickerSeed = n.int32("seed", entity != null ? entity.id : 1);
    }
}
