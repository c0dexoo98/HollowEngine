package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Noise;
import com.hollow.core.Vec3;

/** AAA-01 — heightfield terrain system (HollowEngine 4.0).
 *  Grid of heights with bilinear sampling, sculpt brushes (raise/lower/flatten/smooth),
 *  4-layer splat painting, holes (cave entrances / destructible floor), CPU raycast and
 *  JSON round-trip. Deterministic generation via core fbm noise.
 *
 *  Gameplay/physics coupling: heightAt()/normalAt() drive character grounding and
 *  object placement; raycast() works headless for tests and tools.
 */
public final class Terrain {
    public float[] heights;                  // size*size, row-major (x + z*size)
    public int size = 129;
    public float cellSize = 2f;              // meters per cell
    public float heightScale = 40f;
    public final float[] origin = {0f, 0f, 0f};
    /** 4 splat layers per cell, normalized weights (index = (x + z*size)*4 + layer) */
    public float[] splat;
    public final String[] layerNames = {"grass", "rock", "dirt", "snow"};
    /** holes: no ground at these cells (caves, gates, destroyed floor) */
    public boolean[] holes;

    public Terrain(){ generate(0x7E3AL, 129, 2f, 40f); }
    public Terrain(long seed, int size, float cell, float scale){ generate(seed, size, cell, scale); }

    // ---------------- generation ----------------
    public void generate(long seed, int size, float cell, float scale){
        Noise nz = new Noise(seed);
        this.size = Math.max(17, size);
        this.cellSize = Math.max(0.1f, cell);
        this.heightScale = scale;
        int n = this.size * this.size;
        heights = new float[n];
        splat = new float[n * 4];
        holes = new boolean[n];
        for(int z = 0; z < this.size; z++){
            for(int x = 0; x < this.size; x++){
                float wx = x * cellSize, wz = z * cellSize;
                heights[z * this.size + x] = nz.fbm2(wx * 0.008f, wz * 0.008f, 5, 2f, 0.5f) * scale;
            }
        }
    }

    // ---------------- sampling ----------------
    public float heightAt(float wx, float wz){
        float fx = (wx - origin[0]) / cellSize;
        float fz = (wz - origin[2]) / cellSize;
        int x0 = MathX.clamp((int)Math.floor(fx), 0, size - 2);
        int z0 = MathX.clamp((int)Math.floor(fz), 0, size - 2);
        float tx = MathX.clamp(fx - x0, 0f, 1f), tz = MathX.clamp(fz - z0, 0f, 1f);
        float h00 = heights[z0 * size + x0],       h10 = heights[z0 * size + x0 + 1];
        float h01 = heights[(z0 + 1) * size + x0], h11 = heights[(z0 + 1) * size + x0 + 1];
        return MathX.lerp(MathX.lerp(h00, h10, tx), MathX.lerp(h01, h11, tx), tz);
    }

    /** true when the point sits over a holed cell (ground absent) */
    public boolean isHole(float wx, float wz){
        int x = MathX.clamp((int)((wx - origin[0]) / cellSize), 0, size - 1);
        int z = MathX.clamp((int)((wz - origin[2]) / cellSize), 0, size - 1);
        return holes[z * size + x];
    }

    public Vec3 normalAt(float wx, float wz, Vec3 out){
        float d = cellSize;
        float hl = heightAt(wx - d, wz), hr = heightAt(wx + d, wz);
        float hd = heightAt(wx, wz - d), hu = heightAt(wx, wz + d);
        // central-difference normal: (-dh/dx, 2, -dh/dz) scaled by cell
        out.set(hl - hr, 2f * d, hd - hu).norm();
        return out;
    }
    /** 0 = flat .. 1 = vertical wall */
    public float slopeAt(float wx, float wz){
        Vec3 n = normalAt(wx, wz, new Vec3());
        return MathX.clamp(1f - n.y, 0f, 1f);
    }

    // ---------------- sculpt brushes (editor + runtime deformation) ----------------
    /** radial-falloff raise (negative strength lowers) */
    public void raise(float wx, float wz, float radius, float strength){
        int cx = MathX.clamp((int)((wx - origin[0]) / cellSize), 0, size - 1);
        int cz = MathX.clamp((int)((wz - origin[2]) / cellSize), 0, size - 1);
        int r = Math.max(1, Math.round(radius / cellSize));
        for(int z = Math.max(0, cz - r); z <= Math.min(size - 1, cz + r); z++){
            for(int x = Math.max(0, cx - r); x <= Math.min(size - 1, cx + r); x++){
                float dx = (x - cx) * cellSize, dz = (z - cz) * cellSize;
                float d = (float)Math.sqrt(dx * dx + dz * dz) / Math.max(1e-4f, radius);
                if(d > 1f) continue;
                float w = 1f - d * d;                    // smooth cosine-ish falloff
                heights[z * size + x] += strength * w;
            }
        }
    }
    public void lower(float wx, float wz, float radius, float strength){ raise(wx, wz, radius, -strength); }

    /** flatten toward the average height under the brush */
    public void flatten(float wx, float wz, float radius, float strength){
        float sum = 0f; int cnt = 0;
        int cx = MathX.clamp((int)((wx - origin[0]) / cellSize), 0, size - 1);
        int cz = MathX.clamp((int)((wz - origin[2]) / cellSize), 0, size - 1);
        int r = Math.max(1, Math.round(radius / cellSize));
        for(int z = Math.max(0, cz - r); z <= Math.min(size - 1, cz + r); z++)
            for(int x = Math.max(0, cx - r); x <= Math.min(size - 1, cx + r); x++){ sum += heights[z * size + x]; cnt++; }
        float avg = cnt > 0 ? sum / cnt : 0f;
        for(int z = Math.max(0, cz - r); z <= Math.min(size - 1, cz + r); z++){
            for(int x = Math.max(0, cx - r); x <= Math.min(size - 1, cx + r); x++){
                float dx = (x - cx) * cellSize, dz = (z - cz) * cellSize;
                float d = (float)Math.sqrt(dx * dx + dz * dz) / Math.max(1e-4f, radius);
                if(d > 1f) continue;
                float w = (1f - d * d) * MathX.clamp(strength, 0f, 1f);
                int i = z * size + x;
                heights[i] = MathX.lerp(heights[i], avg, w);
            }
        }
    }

    /** 3x3 (radius-scaled) box blur — removes sculpt noise */
    public void smooth(float wx, float wz, float radius, float strength){
        int cx = MathX.clamp((int)((wx - origin[0]) / cellSize), 0, size - 1);
        int cz = MathX.clamp((int)((wz - origin[2]) / cellSize), 0, size - 1);
        int r = Math.max(1, Math.round(radius / cellSize));
        float[] src = heights.clone();
        float k = MathX.clamp(strength, 0f, 1f);
        for(int z = Math.max(0, cz - r); z <= Math.min(size - 1, cz + r); z++){
            for(int x = Math.max(0, cx - r); x <= Math.min(size - 1, cx + r); x++){
                float dx = (x - cx) * cellSize, dz = (z - cz) * cellSize;
                float d = (float)Math.sqrt(dx * dx + dz * dz) / Math.max(1e-4f, radius);
                if(d > 1f) continue;
                int i = z * size + x;
                float sum = 0f; int c = 0;
                for(int oz = -1; oz <= 1; oz++)
                    for(int ox = -1; ox <= 1; ox++){
                        int nx = MathX.clamp(x + ox, 0, size - 1), nz2 = MathX.clamp(z + oz, 0, size - 1);
                        sum += src[nz2 * size + nx]; c++;
                    }
                heights[i] = MathX.lerp(src[i], sum / c, k * (1f - d * d));
            }
        }
    }

    public void setHole(float wx, float wz, float radius, boolean hole){
        int cx = MathX.clamp((int)((wx - origin[0]) / cellSize), 0, size - 1);
        int cz = MathX.clamp((int)((wz - origin[2]) / cellSize), 0, size - 1);
        int r = Math.max(0, Math.round(radius / cellSize));
        for(int z = Math.max(0, cz - r); z <= Math.min(size - 1, cz + r); z++)
            for(int x = Math.max(0, cx - r); x <= Math.min(size - 1, cx + r); x++)
                holes[z * size + x] = hole;
    }

    // ---------------- splat painting ----------------
    /** push `layer` weight up by `flow` inside the brush, renormalize to 1 */
    public void paint(float wx, float wz, float radius, int layer, float flow){
        if(layer < 0 || layer > 3) return;
        int cx = MathX.clamp((int)((wx - origin[0]) / cellSize), 0, size - 1);
        int cz = MathX.clamp((int)((wz - origin[2]) / cellSize), 0, size - 1);
        int r = Math.max(1, Math.round(radius / cellSize));
        for(int z = Math.max(0, cz - r); z <= Math.min(size - 1, cz + r); z++){
            for(int x = Math.max(0, cx - r); x <= Math.min(size - 1, cx + r); x++){
                float dx = (x - cx) * cellSize, dz = (z - cz) * cellSize;
                float d = (float)Math.sqrt(dx * dx + dz * dz) / Math.max(1e-4f, radius);
                if(d > 1f) continue;
                int base = (z * size + x) * 4;
                float add = MathX.clamp(flow, 0f, 1f) * (1f - d * d);
                splat[base + layer] += add;
                float sum = splat[base] + splat[base + 1] + splat[base + 2] + splat[base + 3];
                if(sum > 1e-6f) for(int l = 0; l < 4; l++) splat[base + l] /= sum;
            }
        }
    }
    /** strongest layer at a world point */
    public int dominantLayer(float wx, float wz){
        int x = MathX.clamp((int)((wx - origin[0]) / cellSize), 0, size - 1);
        int z = MathX.clamp((int)((wz - origin[2]) / cellSize), 0, size - 1);
        int base = (z * size + x) * 4, best = 0;
        for(int l = 1; l < 4; l++) if(splat[base + l] > splat[base + best]) best = l;
        return best;
    }

    // ---------------- raycast (gameplay queries, headless-safe) ----------------
    public static final class Hit {
        public boolean hit;
        public float t;
        public final Vec3 point = new Vec3();
        public final Vec3 normal = new Vec3(0, 1, 0);
    }
    /** ray vs heightfield: coarse march + binary refinement. Skips holed cells. */
    public boolean raycast(Vec3 o, Vec3 d, float maxDist, Hit out){
        out.hit = false;
        float len = d.len();
        if(len < 1e-9f || maxDist <= 0f) return false;
        float step = cellSize * 0.5f;
        float tPrev = 0f;
        float abovePrev = o.y - heightAt(o.x, o.z);
        for(float t = step; t <= maxDist; t += step){
            float px = o.x + d.x * t, py = o.y + d.y * t, pz = o.z + d.z * t;
            if(isHole(px, pz)){ tPrev = t; abovePrev = Float.MAX_VALUE; continue; }
            float above = py - heightAt(px, pz);
            if(above <= 0f && abovePrev > 0f){
                // binary refine between tPrev..t
                float lo = tPrev, hi = t;
                for(int it = 0; it < 12; it++){
                    float mid = (lo + hi) * 0.5f;
                    float mx = o.x + d.x * mid, my = o.y + d.y * mid, mz = o.z + d.z * mid;
                    float a = my - heightAt(mx, mz);
                    if(a > 0f) lo = mid; else hi = mid;
                }
                out.t = (lo + hi) * 0.5f;
                out.point.set(o.x + d.x * out.t, o.y + d.y * out.t, o.z + d.z * out.t);
                normalAt(out.point.x, out.point.z, out.normal);
                out.hit = true;
                return true;
            }
            tPrev = t;
            abovePrev = above;
        }
        return false;
    }

    // ---------------- serialization ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj();
        w.kv("size", size).kv("cell", cellSize).kv("scale", heightScale);
        StringBuilder hb = new StringBuilder(size * size * 3);
        for(int i = 0; i < size * size; i++){
            if(i > 0) hb.append(',');
            hb.append(Math.round(heights[i] * 100f) / 100f);
        }
        w.kv("h", hb.toString());
        w.endObj();
        return w.toString();
    }

    public static Terrain load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            int size = Math.max(17, (int)n.flt("size", 129));
            Terrain t = new Terrain(0x7E3AL, size, n.flt("cell", 2f), n.flt("scale", 40f));
            String h = n.str("h", "");
            String[] parts = h.split(",");
            for(int i = 0; i < t.size * t.size && i < parts.length; i++)
                t.heights[i] = Float.parseFloat(parts[i].trim());
            return t;
        } catch(Exception e){
            return new Terrain();
        }
    }
}
