package com.hollow.render;

import com.hollow.core.Json;
import com.hollow.core.MathX;

import java.util.Map;

/** Central post-FX state (PST-009..020, RND-029/031): every screen-space effect the
 *  composite chain supports, with game-driven intensities. The horror game drives
 *  `sanity`, `blood`, `trauma`...; the renderer reads this object each frame.
 *  Pure data + math — headless-testable. */
public final class PostFX {

    // ---- PST-009 sanity filter: wave + hue shift + micro-jitter ----
    public float sanity = 0f;              // 0 = sane, 1 = full madness
    public float sanityPulseSpeed = 1.7f;

    // ---- PST-010 radial blur (hit/acceleration) ----
    public float radialBlur = 0f;          // 0..1
    public final float[] radialCenter = { 0.5f, 0.5f };

    // ---- PST-011 depth of field (half-res) ----
    public boolean dofEnabled = false;
    public float dofFocus = 6f;            // world units
    public float dofRange = 12f;           // full blur beyond this

    // ---- PST-012 / RND-031 god rays / light shafts ----
    public float godRays = 0f;             // 0..1 density
    public final float[] sunScreen = { -1f, -1f };   // sun position in screen UV (offscreen = skip)

    // ---- PST-013 retro CRT / halftone (video sequences) ----
    public float crt = 0f;                 // 0..1

    // ---- PST-014 depth-edge outline (interactables) ----
    public boolean outlineEnabled = false;
    public final float[] outlineColor = { 0.9f, 0.85f, 0.6f };
    public float outlineThreshold = 0.0015f;   // depth-edge sensitivity

    // ---- PST-015 screen-space blood splatter ----
    public float blood = 0f;               // 0..1 coverage intensity
    public float bloodSeed = 1f;

    // ---- PST-016 heat/water distortion with procedural normal waves ----
    public float distortion = 0f;          // 0..1
    public float distortionScale = 24f;
    public float distortionSpeed = 2.2f;

    // ---- PST-017 posterize / pixelate (flashbacks) ----
    public float posterize = 0f;           // 0..1 mix
    public int posterizeLevels = 6;

    // ---- PST-019 LUT crossfade (day/madness grading) ----
    public float lutBlend = 0f;            // 0 = day grade, 1 = madness grade
    public final float[] lutA = { 1.0f, 0.98f, 0.94f };     // warm day
    public final float[] lutB = { 0.82f, 0.72f, 0.95f };    // cold violet madness

    // ---- PST-020 camera motion blur on fast head-turns ----
    public float motionBlur = 0f;          // computed each frame from camera angular velocity
    public float motionBlurGain = 0.9f;
    public final float[] motionDir = { 0f, 0f };            // screen-space blur direction (UV)

    // ---- VFX-017-adjacent: screen shake feeds uv jitter here ----
    public float shake = 0f;

    /** singleton-ish accessor (renderer + game share one instance) */
    private static final PostFX GLOBAL = new PostFX();
    public static PostFX global(){ return GLOBAL; }

    /** advance time-dependent values (call once per frame from the game loop) */
    public void update(float dt, float cameraYawVelocityDeg, float cameraPitchVelocityDeg){
        // PST-020: map angular velocity to screen-space blur vector
        float angVel = (float)Math.sqrt(cameraYawVelocityDeg * cameraYawVelocityDeg
                + cameraPitchVelocityDeg * cameraPitchVelocityDeg);
        float target = MathX.clamp(angVel / 240f, 0f, 1f) * motionBlurGain;
        motionBlur = MathX.approach(motionBlur, target, dt * 6f);
        float len = Math.max(1e-4f, (float)Math.sqrt(cameraYawVelocityDeg * cameraYawVelocityDeg + cameraPitchVelocityDeg * cameraPitchVelocityDeg));
        motionDir[0] = cameraYawVelocityDeg / len;
        motionDir[1] = -cameraPitchVelocityDeg / len;
    }

    /** any effect currently costing a pass? (renderer skip logic) */
    public boolean needsFxPass(){
        return sanity > 0.001f || radialBlur > 0.001f || crt > 0.001f || blood > 0.001f
                || distortion > 0.001f || posterize > 0.001f || godRays > 0.001f
                || outlineEnabled || (dofEnabled && dofRange > 0.01f)
                || motionBlur > 0.001f || lutBlend > 0.001f || shake > 0.001f;
    }

    /** radial/DOF/shaft blur budget in taps — quality-scaled by the renderer */
    public int blurTaps(Quality q){
        return switch(q){
            case LOW -> 4;
            case MEDIUM -> 8;
            case HIGH -> 12;
            case ULTRA -> 16;
        };
    }
    public enum Quality { LOW, MEDIUM, HIGH, ULTRA }

    /** load a full state from project.json-style map */
    public static PostFX fromJson(Map<String,Object> m){
        PostFX p = new PostFX();
        if(m == null) return p;
        p.sanity      = f(m.get("sanity"), 0);
        p.radialBlur  = f(m.get("radialBlur"), 0);
        p.dofEnabled  = Json.bool(m.get("dof"), false);
        p.dofFocus    = f(m.get("dofFocus"), 6);
        p.dofRange    = f(m.get("dofRange"), 12);
        p.godRays     = f(m.get("godRays"), 0);
        p.crt         = f(m.get("crt"), 0);
        p.outlineEnabled = Json.bool(m.get("outline"), false);
        p.blood       = f(m.get("blood"), 0);
        p.distortion  = f(m.get("distortion"), 0);
        p.posterize   = f(m.get("posterize"), 0);
        p.posterizeLevels = Json.int32(m.get("posterizeLevels"), 6);
        p.lutBlend    = f(m.get("lutBlend"), 0);
        clampAll(p);
        return p;
    }

    public Map<String,Object> toJson(){
        Map<String,Object> m = new java.util.LinkedHashMap<>();
        m.put("sanity", sanity);
        m.put("radialBlur", radialBlur);
        m.put("dof", dofEnabled);
        m.put("dofFocus", dofFocus);
        m.put("dofRange", dofRange);
        m.put("godRays", godRays);
        m.put("crt", crt);
        m.put("outline", outlineEnabled);
        m.put("blood", blood);
        m.put("distortion", distortion);
        m.put("posterize", posterize);
        m.put("posterizeLevels", posterizeLevels);
        m.put("lutBlend", lutBlend);
        return m;
    }

    public void clampAll(){
        clampAll(this);
    }
    private static void clampAll(PostFX p){
        p.sanity = MathX.clamp(p.sanity, 0f, 1f);
        p.radialBlur = MathX.clamp(p.radialBlur, 0f, 1f);
        p.godRays = MathX.clamp(p.godRays, 0f, 1f);
        p.crt = MathX.clamp(p.crt, 0f, 1f);
        p.blood = MathX.clamp(p.blood, 0f, 1f);
        p.distortion = MathX.clamp(p.distortion, 0f, 1f);
        p.posterize = MathX.clamp(p.posterize, 0f, 1f);
        p.lutBlend = MathX.clamp(p.lutBlend, 0f, 1f);
        p.motionBlur = MathX.clamp(p.motionBlur, 0f, 1f);
        p.shake = MathX.clamp(p.shake, 0f, 1f);
        p.posterizeLevels = MathX.clamp(p.posterizeLevels, 2, 32);
    }

    private static float f(Object o, float d){ return (float) Json.num(o, d); }
}
