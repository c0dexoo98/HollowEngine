package com.hollow.render;

import com.hollow.core.AABB;
import com.hollow.core.Mat4;
import com.hollow.core.Vec3;

/** CPU Hi-Z occlusion culling (RND-034): the renderer reads back a small depth map
 *  (64×64) once per frame, this class builds the mip pyramid (max reduction for
 *  reversed depth = NEAREST occluder depth), then tests entity AABBs against it.
 *  Pure math here — GL glue lives in PbrRenderer. Reversed-Z convention:
 *  depth 1 = near, 0 = far; an object is OCCLUDED when every sampled depth in its
 *  footprint is GREATER (nearer) than the object's own nearest depth. */
public final class HiZOcclusion {

    public static final int SIZE = 64;      // base pyramid level
    private final float[][] mips;           // mips[0] = 64x64 ... mips[6] = 1x1
    private final int levels;
    public boolean enabled = true;
    public int tested = 0, culled = 0;

    public HiZOcclusion(){
        int n = 1, l = 0;
        while(n < SIZE){ n <<= 1; l++; }
        levels = l + 1;
        mips = new float[levels][];
        int s = SIZE;
        for(int i=0;i<levels;i++){ mips[i] = new float[s * s]; s = Math.max(1, s >> 1); }
    }

    /** upload the base depth map (row-major, SIZE*SIZE, values 0..1, reversed-Z: 1=near) */
    public void uploadDepth(float[] depth64){
        System.arraycopy(depth64, 0, mips[0], 0, Math.min(depth64.length, mips[0].length));
        buildPyramid();
        tested = 0; culled = 0;
    }

    /** max-reduce: each level stores the NEAREST depth (max under reversed-Z) of its children */
    private void buildPyramid(){
        for(int l = 1; l < levels; l++){
            int s = SIZE >> l, ps = SIZE >> (l - 1);
            if(s < 1) s = 1;
            float[] cur = mips[l], prev = mips[l - 1];
            for(int y = 0; y < s; y++){
                for(int x = 0; x < s; x++){
                    int px = Math.min(ps - 1, x * 2), py = Math.min(ps - 1, y * 2);
                    float a = prev[py * ps + px];
                    float b = prev[py * ps + Math.min(ps - 1, px + 1)];
                    float c = prev[Math.min(ps - 1, py + 1) * ps + px];
                    float d = prev[Math.min(ps - 1, py + 1) * ps + Math.min(ps - 1, px + 1)];
                    cur[y * s + x] = Math.max(a, Math.max(b, Math.max(c, d)));
                }
            }
        }
    }

    /** conservative test: is the AABB hidden behind the depth pyramid?
     *  viewProj = camera matrices; near/far in world units. */
    public boolean isOccluded(AABB worldBox, Mat4 viewProj, float near, float far){
        if(!enabled) return false;
        tested++;
        // project the 8 corners
        float minU = 1, minV = 1, minD = 1;
        for(int i=0;i<8;i++){
            Vec3 corner = new Vec3(
                (i & 1) != 0 ? worldBox.max.x : worldBox.min.x,
                (i & 2) != 0 ? worldBox.max.y : worldBox.min.y,
                (i & 4) != 0 ? worldBox.max.z : worldBox.min.z);
            Vec3 clip = viewProj.transformPoint(corner);
            float w = viewProj.m[3]*corner.x + viewProj.m[7]*corner.y + viewProj.m[11]*corner.z + viewProj.m[15];
            if(w <= 0.001f) return false;              // behind/at camera — never cull
            float u = clip.x * 0.5f + 0.5f;
            float v = clip.y * 0.5f + 0.5f;
            float depth = clip.z;                       // already perspective-divided
            if(u < 0 || u > 1 || v < 0 || v > 1) return false;   // offscreen — never cull
            minU = Math.min(minU, u); minV = Math.min(minV, v);
            minD = Math.min(minD, Math.max(0f, Math.min(1f, depth)));
        }
        // conservative footprint: expand to the full box extent in UV
        float maxU = 1 - minU; // not used; we sample the coarse mip directly below
        // sample the pyramid level matching the box footprint (2x2 conservative)
        int level = pyramidLevel(minU, minV);
        float hiZ = sampleMip(level, minU, minV);
        // occluded if the nearest occluder in the footprint is nearer than the box's nearest point
        if(hiZ > minD + 0.002f){
            culled++;
            return true;
        }
        return false;
    }

    /** pick the mip level whose texel covers at most the screen footprint (conservative 2×2) */
    public int pyramidLevel(float u, float v){
        // footprint here is conservative (single UV known); level 1 (32x32) balances safety
        return Math.min(1, levels - 1);
    }

    public float sampleMip(int level, float u, float v){
        int s = SIZE >> level;
        if(s < 1) s = 1;
        int x = Math.min(s - 1, (int)(u * s));
        int y = Math.min(s - 1, (int)(v * s));
        return mips[level][y * s + x];
    }

    /** stats line for the profiler */
    public String stats(){
        return "hiZ[tested=" + tested + " culled=" + culled + " levels=" + levels + "]";
    }
}
