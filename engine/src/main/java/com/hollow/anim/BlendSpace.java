package com.hollow.anim;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Blend Space 1D/2D (Unreal-style) — HollowEngine 2.0.
 *  Grid of (x[,y]) → clip samples; sampling returns per-clip weights for smooth
 *  locomotion blending (idle→walk→run). Data-driven from JSON:
 *  <pre>
 *  { "axis1": {"name":"speed","min":0,"max":6},
 *    "axis2": {"name":"turn","min":-1,"max":1},          // optional (2D)
 *    "snap": 1,                                          // optional grid snap
 *    "samples":[ {"x":0,"clip":"idle"}, {"x":3,"clip":"walk"}, {"x":6,"clip":"run"} ] }
 *  </pre>
 *  Weights use inverse-distance (shepard) interpolation over the 4 nearest grid
 *  samples — monotone, stable, no baked grid needed.
 */
public final class BlendSpace {
    public static final class Sample {
        public float x, y;               // parameter space position (y=0 for 1D)
        public String clip;
        public float speed = 1f;
    }

    public String name = "blend";
    public final List<Sample> samples = new ArrayList<>();
    public boolean twoD = false;
    public float axis1Min, axis1Max = 1f;
    public float axis2Min = -1f, axis2Max = 1f;

    /** result of a sample: up to 4 clips + weights (sum = 1) */
    public static final class Weighted {
        public final String[] clips = new String[4];
        public final float[] weights = new float[4];
        public int count = 0;
        public final float[] baseSpeeds = new float[4];
    }

    private final Weighted result = new Weighted();

    public static BlendSpace parse(Map<String,Object> root){
        BlendSpace bs = new BlendSpace();
        bs.name = Json.str(root.get("name"), "blend");
        Map<String,Object> a1 = Json.obj(root.get("axis1"));
        if(!a1.isEmpty()){
            bs.axis1Min = (float)Json.num(a1.get("min"), 0);
            bs.axis1Max = (float)Json.num(a1.get("max"), 1);
        }
        Map<String,Object> a2 = Json.obj(root.get("axis2"));
        bs.twoD = !a2.isEmpty();
        if(bs.twoD){
            bs.axis2Min = (float)Json.num(a2.get("min"), -1);
            bs.axis2Max = (float)Json.num(a2.get("max"), 1);
        }
        List<Object> arr = Json.arr(root.get("samples"));
        for(Object o : arr){
            if(!(o instanceof Map)) continue;
            Map<String,Object> m = (Map<String,Object>)o;
            Sample s = new Sample();
            s.x = (float)Json.num(m.get("x"), 0);
            if(bs.twoD) s.y = (float)Json.num(m.get("y"), 0);
            s.clip = Json.str(m.get("clip"), "");
            s.speed = (float)Json.num(m.get("speed"), 1);
            if(!s.clip.isEmpty()) bs.samples.add(s);
        }
        return bs;
    }

    public static BlendSpace loadFromProject(com.hollow.core.FileSystem fs, String path){
        try {
            String text = fs.readText(path.endsWith(".json") ? path : path + ".json");
            return parse(Json.parseObj(text));
        } catch(Exception e){
            com.hollow.core.Log.w("BlendSpace", "load failed " + path + ": " + e.getMessage());
            return null;
        }
    }

    /** sample the space; returns shared weighted result (up to 4 active clips) */
    public Weighted sample(float x, float y){
        Weighted out = result;
        out.count = 0;
        if(samples.isEmpty()) return out;
        if(samples.size() == 1){
            Sample s = samples.get(0);
            out.clips[0] = s.clip; out.weights[0] = 1f; out.baseSpeeds[0] = s.speed;
            out.count = 1;
            return out;
        }
        // normalize params to 0..1 ranges
        float nx = norm(x, axis1Min, axis1Max);
        float ny = twoD ? norm(y, axis2Min, axis2Max) : 0f;
        // inverse distance weights over nearest samples (cap influence radius)
        float wSum = 0f;
        int n = 0;
        // find max distance to limit to 4 nearest (simple selection)
        float[] dists = new float[samples.size()];
        for(int i = 0; i < samples.size(); i++){
            Sample s = samples.get(i);
            float sx = norm(s.x, axis1Min, axis1Max);
            float sy = twoD ? norm(s.y, axis2Min, axis2Max) : 0f;
            float dx = sx - nx, dy = sy - ny;
            float d = (float)Math.sqrt(dx * dx + dy * dy);
            dists[i] = d;
            if(d < 1e-5f){                    // exactly on a sample
                out.clips[0] = s.clip; out.weights[0] = 1f; out.baseSpeeds[0] = s.speed;
                out.count = 1;
                return out;
            }
        }
        // pick 4 smallest distances
        int[] idx = new int[Math.min(4, samples.size())];
        float[] picked = new float[idx.length];
        java.util.Arrays.fill(idx, -1);
        for(int k = 0; k < idx.length; k++){
            int best = -1; float bestD = Float.MAX_VALUE;
            for(int i = 0; i < samples.size(); i++){
                if(dists[i] < 0f) continue;                       // used
                if(dists[i] < bestD){ bestD = dists[i]; best = i; }
            }
            if(best < 0) break;
            idx[k] = best;
            picked[k] = bestD;
            dists[best] = -1f;
        }
        for(int k = 0; k < idx.length; k++){
            int i = idx[k];
            if(i < 0) continue;
            Sample s = samples.get(i);
            float w = 1f / Math.max(1e-4f, picked[k] * picked[k]);
            out.clips[n] = s.clip;
            out.weights[n] = w;
            out.baseSpeeds[n] = s.speed;
            wSum += w;
            n++;
        }
        if(wSum > 1e-6f){
            for(int k = 0; k < n; k++) out.weights[k] /= wSum;
        }
        out.count = n;
        return out;
    }

    private static float norm(float v, float mn, float mx){
        if(mx - mn < 1e-6f) return 0f;
        return MathX.clamp((v - mn) / (mx - mn), 0f, 1f);
    }
}
