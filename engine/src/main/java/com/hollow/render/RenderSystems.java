package com.hollow.render;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.4 render-support batch — RND-028..035 (CPU-side state), LGT-013/015/019,
 *  MAT-013..018 (material flags/defs), SEQ-005..008 (Sequencer tracks are in cine;
 *  this carries the data model), AST-010..012. GPU passes sample these on device. */
public final class RenderSystems {
    private RenderSystems(){}

    // =====================================================================
    // RND-028 — SSR state (screen-space reflections)
    // =====================================================================
    public static final class SSRSettings {
        public boolean enabled = false;
        public float intensity = 0.6f;
        public float maxDistance = 24f;          // world units ray-march
        public float thickness = 0.35f;
        public int steps = 12;                   // march steps (mobile budget)
        public float fadeStart = 0.75f;          // screen-edge fade
        public void qualityPreset(String q){
            switch(q){
                case "LOW" -> { steps = 6; enabled = false; }
                case "MEDIUM" -> { steps = 8; enabled = true; intensity = 0.45f; }
                case "HIGH" -> { steps = 12; enabled = true; intensity = 0.6f; }
                case "ULTRA" -> { steps = 20; enabled = true; intensity = 0.8f; }
            }
        }
    }

    // =====================================================================
    // RND-029 — per-object cinematic motion blur state
    // =====================================================================
    public static final class MotionBlurState {
        public float amount = 0f;                // 0..1 per object velocity scale
        public float maxVelocity = 12f;          // m/s at full blur
        public Vec3 lastPos = new Vec3();
        public Vec3 velocity = new Vec3();

        public void update(Vec3 worldPos, float dt){
            if(dt > 1e-5f){
                velocity.set(worldPos).sub(lastPos).scale(1f / dt);
            }
            lastPos.set(worldPos);
            amount = MathX.clamp(velocity.len() / maxVelocity, 0f, 1f);
        }
    }

    // =====================================================================
    // RND-030 — TAA jitter sequence (CPU computes offsets; shader accumulates)
    // =====================================================================
    public static final class TaaState {
        public boolean enabled = false;
        public int phase = 0;
        public int sampleCount = 8;
        public float feedback = 0.85f;           // history blend weight
        private static final float[] HALTON2 = {0.5f, 0.25f, 0.75f, 0.125f, 0.625f, 0.375f, 0.875f, 0.0625f};
        private static final float[] HALTON3 = {0.3333f, 0.6667f, 0.1111f, 0.4444f, 0.7778f, 0.2222f, 0.5556f, 0.8889f};

        /** current sub-pixel jitter [-0.5..0.5]^2, advances phase. */
        public float[] nextJitter(){
            float[] j = { HALTON2[phase % sampleCount] - 0.5f, HALTON3[phase % sampleCount] - 0.5f };
            phase = (phase + 1) % sampleCount;
            return j;
        }
        public void qualityPreset(String q){
            enabled = switch(q){ case "ULTRA" -> true; case "HIGH" -> true; default -> false; };
            sampleCount = "ULTRA".equals(q) ? 8 : 4;
        }
    }

    // =====================================================================
    // RND-032 — point light shadow state (cube faces budget)
    // =====================================================================
    public static final class PointShadowState {
        public boolean enabled = false;
        public int maxPointShadows = 2;           // mobile budget
        public int faceRes = 256;                 // per-cubemap-face resolution
        public final List<String> casters = new ArrayList<>();   // entity names with castShadow
        public void budget(int max, int res){ maxPointShadows = max; faceRes = res; }
        public boolean addCaster(String entityName){
            if(casters.size() >= maxPointShadows) return false;
            casters.add(entityName);
            return true;
        }
    }

    // =====================================================================
    // RND-034 — Hi-Z occlusion culling state
    // =====================================================================
    public static final class HiZCulling {
        public boolean enabled = false;
        public int mipLevels = 5;
        public float conservativePadding = 0.08f;  // inflate AABB 8%
        public int lastCulled = 0, lastVisible = 0;
        /** CPU-side test hook: a query against a synthetic depth pyramid (min per level). */
        public float[] pyramid = null;
        public int[] dims = null;
        public void buildTestPyramid(float[] level0, int w, int h){
            pyramid = new float[level0.length * 2];
            dims = new int[2];
            System.arraycopy(level0, 0, pyramid, 0, level0.length);
            dims[0] = w; dims[1] = h;
        }
        public boolean occluded(float depthBound){
            if(pyramid == null || pyramid.length == 0) return false;
            return depthBound > pyramid[0];
        }
    }

    // =====================================================================
    // RND-035 — stereo multi-view state (VR-ready framebuffers)
    // =====================================================================
    public static final class StereoView {
        public boolean enabled = false;
        public float ipdMeters = 0.064f;          // interpupillary distance
        public float eyeOffset = 0.032f;          // half IPD
        public float projectionSeparation = 1.0f;
        /** per-eye projection matrices (column-major, filled by renderer). */
        public final float[] projLeft = new float[16];
        public final float[] projRight = new float[16];
        /** head yaw/pitch from sensors (VR-002). */
        public float headYawDeg, headPitchDeg;
        public void setHead(float yawDeg, float pitchDeg){ headYawDeg = yawDeg; headPitchDeg = pitchDeg; }
        /** VR-005 IPD calibration: eyeOffset = ipd/2. */
        public void calibrate(float ipd){ ipdMeters = ipd; eyeOffset = ipd * 0.5f; }
    }

    // =====================================================================
    // LGT-013/015/019 — cookie mask, soft shadows, per-light quality
    // =====================================================================
    public static final class LightExtras {
        /** LGT-013: analytic gobo cookies (no texture needed): blinds/blades/window. */
        public static float cookieSample(String kind, float u, float v){
            return switch(kind){
                case "blinds" -> (frac(v * 6f) > 0.45f ? 1f : 0.05f);
                case "fan" -> (frac((float)(Math.atan2(v - 0.5, u - 0.5) / (2 * Math.PI)) * 4f) > 0.5f ? 1f : 0.05f);
                case "window" -> (u > 0.1f && u < 0.9f && v > 0.15f && v < 0.85f ? 1f : 0f);
                default -> 1f;
            };
        }
        private static float frac(float x){ return x - (float)Math.floor(x); }
        /** LGT-015: VSM/ESM soft shadow config. */
        public static final class SoftShadows {
            public String mode = "pcf";           // pcf | vsm | esm
            public float vsmLightBleed = 0.15f;
            public float esmExponent = 80f;
            public int pcfRadius = 1;
            public float blurRadiusPx = 2.5f;
        }
        /** LGT-019: per-light quality via CVars. */
        public static int shadowResFor(int quality){
            return switch(quality){ case 0 -> 0; case 1 -> 512; case 2 -> 1024; default -> 2048; };
        }
    }

    // =====================================================================
    // MAT-013..018 — material model extension flags
    // =====================================================================
    public static final class MaterialExtras {
        public boolean triplanar = false;        // MAT-013 terrain w/o UV
        public float triplanarScale = 0.25f;
        public boolean parallax = false;         // MAT-014
        public float parallaxHeight = 0.04f;
        public int parallaxSteps = 6;
        public float subsurface = 0f;            // MAT-015 skin/wax 0..1
        public float subsurfaceRadius = 0.3f;
        public float clearcoat = 0f;             // MAT-016 lacquer
        public float clearcoatRough = 0.15f;
        public float sheen = 0f;                 // MAT-016 fabric rim
        public boolean matcap = false;           // MAT-017 quick preview
        public String matcapTexture = "";
        public boolean displacement = false;     // MAT-018 light tessellation
        public float displacementScale = 0.05f;
        /** encode to JSON for scene material defs. */
        public Map<String,Object> toJson(){
            Map<String,Object> m = new LinkedHashMap<>();
            if(triplanar) m.put("triplanar", triplanarScale);
            if(parallax){ m.put("parallax", parallaxHeight); m.put("parallaxSteps", (double)parallaxSteps); }
            if(subsurface > 0) m.put("subsurface", subsurface);
            if(clearcoat > 0) m.put("clearcoat", clearcoat);
            if(sheen > 0) m.put("sheen", sheen);
            if(matcap) m.put("matcap", matcapTexture);
            if(displacement) m.put("displacement", displacementScale);
            return m;
        }
        public void fromJson(Map<String,Object> m){
            triplanar = m.containsKey("triplanar");
            if(triplanar) triplanarScale = (float)Json.num(m.get("triplanar"), 0.25);
            parallax = m.containsKey("parallax");
            if(parallax) parallaxHeight = (float)Json.num(m.get("parallax"), 0.04);
            subsurface = (float)Json.num(m.get("subsurface"), 0);
            clearcoat = (float)Json.num(m.get("clearcoat"), 0);
            sheen = (float)Json.num(m.get("sheen"), 0);
            matcap = m.containsKey("matcap");
            if(matcap) matcapTexture = Json.str(m.get("matcap"), "");
            displacement = m.containsKey("displacement");
            if(displacement) displacementScale = (float)Json.num(m.get("displacement"), 0.05);
        }
    }

    // =====================================================================
    // SEQ-005/006/007/008 — sequencer data models (animation/env/spline tracks)
    // =====================================================================
    public static final class SeqTracks {
        /** SEQ-005: {t, target, clip, loop} — play animation clip on entity. */
        public static final class AnimTrack {
            public static final class Key { public float t; public String target, clip; public boolean loop; }
            public final List<Key> keys = new ArrayList<>();
            public void add(float t, String target, String clip, boolean loop){
                Key k = new Key(); k.t = t; k.target = target; k.clip = clip; k.loop = loop;
                keys.add(k);
                keys.sort((a, b) -> Float.compare(a.t, b.t));
            }
            /** which key is active at time t (latest with k.t <= t). */
            public Key active(float t){
                Key out = null;
                for(Key k : keys) if(k.t <= t) out = k;
                return out;
            }
        }
        /** SEQ-006: environment track — animate fog density/fog color/ambient over time. */
        public static final class EnvTrack {
            public static final class Key { public float t; public Float fogDensity; public float[] fogColor; public float[] ambient; }
            public final List<Key> keys = new ArrayList<>();
            public void add(float t, Float fogDensity, float[] fogColor, float[] ambient){
                Key k = new Key(); k.t = t; k.fogDensity = fogDensity; k.fogColor = fogColor; k.ambient = ambient;
                keys.add(k);
                keys.sort((a, b) -> Float.compare(a.t, b.t));
            }
            /** interpolated env state at t. */
            public float[] sample(float t){
                float[] out = new float[7];   // [fogDensity, fogR, fogG, fogB, ambR, ambG, ambB]
                if(keys.isEmpty()) return out;
                Key a = keys.get(0), b = keys.get(keys.size()-1);
                for(Key k : keys){
                    if(k.t <= t) a = k;
                    if(k.t >= t){ b = k; break; }
                }
                float span = Math.max(1e-5f, b.t - a.t);
                float k2 = MathX.clamp((t - a.t) / span, 0f, 1f);
                out[0] = MathX.lerp(a.fogDensity != null ? a.fogDensity : 0, b.fogDensity != null ? b.fogDensity : 0, k2);
                for(int i = 0; i < 3; i++){
                    float fcA = a.fogColor != null ? a.fogColor[i] : 0;
                    float fcB = b.fogColor != null ? b.fogColor[i] : 0;
                    out[1 + i] = MathX.lerp(fcA, fcB, k2);
                    float amA = a.ambient != null ? a.ambient[i] : 0;
                    float amB = b.ambient != null ? b.ambient[i] : 0;
                    out[4 + i] = MathX.lerp(amA, amB, k2);
                }
                return out;
            }
        }
        /** SEQ-007: headless preview — sample all state at time t without GL. */
        public static Map<String,Object> preview(AnimTrack anim, EnvTrack env, float t){
            Map<String,Object> m = new LinkedHashMap<>();
            AnimTrack.Key ak = anim != null ? anim.active(t) : null;
            if(ak != null){ m.put("animTarget", ak.target); m.put("animClip", ak.clip); }
            if(env != null){
                float[] e = env.sample(t);
                m.put("fogDensity", (double)e[0]);
                m.put("fogColor", new double[]{ e[1], e[2], e[3] });
                m.put("ambient", new double[]{ e[4], e[5], e[6] });
            }
            m.put("t", (double)t);
            return m;
        }
        /** SEQ-008: camera spline track — sample position/lookAt from engine Spline curves. */
        public static final class CamSpline {
            public com.hollow.core.Spline pos, look;
            public CamSpline(com.hollow.core.Spline position, com.hollow.core.Spline lookAt){ pos = position; look = lookAt; }
            public float[] sampleAt(float k){
                Vec3 p = pos != null ? pos.sample(k) : new Vec3();
                Vec3 l = look != null ? look.sample(k) : new Vec3();
                return new float[]{ p.x, p.y, p.z, l.x, l.y, l.z };
            }
        }
    }

    // =====================================================================
    // AST-010..012 — asset dependency graph, hot reload, streaming priority
    // =====================================================================
    public static final class AssetGraph {
        private final Map<String, List<String>> deps = new LinkedHashMap<>();
        public void edge(String asset, String dependsOn){
            deps.computeIfAbsent(asset, k -> new ArrayList<>()).add(dependsOn);
        }
        /** transitive closure (what breaks if asset is removed). */
        public List<String> closure(String asset){
            List<String> out = new ArrayList<>();
            visit(asset, out);
            return out;
        }
        private void visit(String a, List<String> out){
            for(String d : deps.getOrDefault(a, List.of())){
                if(!out.contains(d)){ out.add(d); visit(d, out); }
            }
        }
        /** reverse: who depends on asset. */
        public List<String> dependents(String asset){
            List<String> out = new ArrayList<>();
            for(Map.Entry<String,List<String>> e : deps.entrySet())
                if(e.getValue().contains(asset)) out.add(e.getKey());
            return out;
        }
        /** detect circular deps (empty = clean). */
        public List<String> cycles(){
            List<String> cyc = new ArrayList<>();
            for(String k : deps.keySet()){
                if(closure(k).contains(k) && !cyc.contains(k)) cyc.add(k);
            }
            return cyc;
        }
        public int nodeCount(){ return deps.size(); }
    }

    public static final class AssetHotReload {
        public final Map<String,long[]> stamps = new LinkedHashMap<>();     // path -> {stamp,size}
        public int reloads = 0;
        public List<String> poll(Map<String,long[]> current){
            List<String> changed = new ArrayList<>();
            for(Map.Entry<String,long[]> e : current.entrySet()){
                long[] prev = stamps.get(e.getKey());
                if(prev == null){ stamps.put(e.getKey(), e.getValue()); continue; }
                if(prev[0] != e.getValue()[0] || prev[1] != e.getValue()[1]){
                    stamps.put(e.getKey(), e.getValue());
                    changed.add(e.getKey());
                    reloads++;
                }
            }
            return changed;
        }
    }

    public static final class StreamingPriority {
        public static class Request {
            public String asset; public float distance; public float sizeMB;
            public Request(){}
            public Request(String asset, float distance, float sizeMB){
                this.asset = asset; this.distance = distance; this.sizeMB = sizeMB;
            }
        }
        /** priority = cost/distance: nearer + cheaper first; fills budgetMB. */
        public static List<String> prioritize(List<Request> reqs, float budgetMB){
            List<Request> sorted = new ArrayList<>(reqs);
            sorted.sort((a, b) -> {
                float pa = a.sizeMB / Math.max(0.5f, a.distance);
                float pb = b.sizeMB / Math.max(0.5f, b.distance);
                return Float.compare(pb, pa);
            });
            List<String> out = new ArrayList<>();
            float used = 0;
            for(Request r : sorted){
                if(used + r.sizeMB > budgetMB) continue;
                out.add(r.asset);
                used += r.sizeMB;
            }
            return out;
        }
    }
}
