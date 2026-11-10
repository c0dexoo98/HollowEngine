package com.hollow.render;

import com.hollow.core.Json;

import java.util.Map;

/** Renderer quality/toggle configuration.
 *  Built from project.json "quality" string; individual fields can be overridden via JSON
 *  ({"quality":"HIGH","bloom":false,"exposure":1.1,...}).
 *
 *  Quality presets (SPEC §Agent B):
 *    LOW    -> resolutionScale 0.5,  shadowMap 1024, maxLights 4,  bloom+fxaa OFF
 *    MEDIUM -> resolutionScale 0.75, shadowMap 2048, maxLights 6
 *    HIGH   -> resolutionScale 1.0,  shadowMap 2048, maxLights 8
 *    ULTRA  -> resolutionScale 1.0,  shadowMap 4096, maxLights 12
 */
public final class RendererConfig {
    public enum Quality { LOW, MEDIUM, HIGH, ULTRA }

    public Quality quality = Quality.HIGH;
    /** logical surface size (window/swapchain) — render target = size * resolutionScale */
    public int width = 1280, height = 720;
    public float resolutionScale = 1.0f;

    public boolean bloom = true;
    public boolean fxaa = true;
    public boolean grain = true;
    public boolean vignette = true;
    /** 0..1 radial RGB split strength in the composite pass */
    public float chromaticAberration = 0f;

    public int shadowMapSize = 2048;
    public int maxLights = 8;
    /** tonemap exposure multiplier */
    public float exposure = 1f;

    // ---- v2.0 features ----
    public boolean ibl = true;             // full image-based lighting (cubemaps)
    public boolean ssao = true;            // screen-space ambient occlusion
    public boolean decals = true;          // screen-space projected decals
    public boolean instancing = true;      // foliage / batched instanced draws
    public int ssaoSamples = 10;
    public float ssaoRadius = 0.8f;
    public float ssaoStrength = 0.9f;

    public RendererConfig() {}

    /** Preset from the project.json quality string ("LOW"/"MEDIUM"/"HIGH"/"ULTRA", case-insensitive). */
    public static RendererConfig fromGameConfig(String quality) {
        RendererConfig c = new RendererConfig();
        String q = quality == null ? "HIGH" : quality.trim().toUpperCase();
        switch (q) {
            case "LOW":
                c.quality = Quality.LOW; c.resolutionScale = 0.5f; c.shadowMapSize = 1024; c.maxLights = 4;
                c.bloom = false; c.fxaa = false;
                break;
            case "MEDIUM":
                c.quality = Quality.MEDIUM; c.resolutionScale = 0.75f; c.shadowMapSize = 2048; c.maxLights = 6;
                break;
            case "ULTRA":
                c.quality = Quality.ULTRA; c.resolutionScale = 1.0f; c.shadowMapSize = 4096; c.maxLights = 12;
                break;
            case "HIGH":
            default:
                c.quality = Quality.HIGH; c.resolutionScale = 1.0f; c.shadowMapSize = 2048; c.maxLights = 8;
                break;
        }
        return c;
    }

    /** Full JSON form: {"quality":"HIGH", "bloom":true, "fxaa":true, "grain":true, "vignette":true,
     *  "chromaticAberration":0.0, "exposure":1.0, "resolutionScale":1.0, "shadowMapSize":2048, "maxLights":8} */
    public static RendererConfig fromJson(Map<String,Object> m) {
        RendererConfig c = fromGameConfig(Json.str(m != null ? m.get("quality") : null, "HIGH"));
        if (m == null) return c;
        c.resolutionScale = (float) Json.num(m.get("resolutionScale"), c.resolutionScale);
        c.bloom  = Json.bool(m.get("bloom"),  c.bloom);
        c.fxaa   = Json.bool(m.get("fxaa"),   c.fxaa);
        c.grain  = Json.bool(m.get("grain"),  c.grain);
        c.vignette = Json.bool(m.get("vignette"), c.vignette);
        c.chromaticAberration = (float) Json.num(m.get("chromaticAberration"), c.chromaticAberration);
        c.shadowMapSize = Json.int32(m.get("shadowMapSize"), c.shadowMapSize);
        c.maxLights     = Json.int32(m.get("maxLights"), c.maxLights);
        c.exposure      = (float) Json.num(m.get("exposure"), c.exposure);
        // hard clamps
        c.resolutionScale = com.hollow.core.MathX.clamp(c.resolutionScale, 0.25f, 1.0f);
        c.chromaticAberration = com.hollow.core.MathX.clamp(c.chromaticAberration, 0f, 1f);
        c.shadowMapSize = com.hollow.core.MathX.clamp(c.shadowMapSize, 256, 8192);
        c.maxLights = com.hollow.core.MathX.clamp(c.maxLights, 0, MAX_LIGHTS_SUPPORTED);
        c.exposure = com.hollow.core.MathX.clamp(c.exposure, 0.05f, 8f);
        return c;
    }

    /** shader-side hard cap of the per-object light arrays (see Shaders.PBR_FS) */
    public static final int MAX_LIGHTS_SUPPORTED = 12;
}
