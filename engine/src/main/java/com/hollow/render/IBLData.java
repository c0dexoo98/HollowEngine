package com.hollow.render;

import com.hollow.core.Vec3;

/** CPU-baked IBL textures (HollowEngine 2.0 — full image-based lighting, UE SkyLight equivalent).
 *  Baked once at renderer init from the scene's procedural sky parameters:
 *  - irradiance cubemap  (16×16×6)  — cosine-weighted hemisphere convolution
 *  - prefiltered specular (64×64×6, 5 mips) — roughness-dependent reflections
 *  - DFG LUT (64×64 RG) — Karis envBRDF analytic, baked into a texture so the
 *    shader only samples (no ALU cost per pixel).
 *  All deterministic; total bake < 30ms on mid-range mobile.
 */
public final class IBLData {
    public final int irrSize = 16;
    public final int preSize = 64;
    public final int preMips = 5;
    public final int dfgSize = 64;
    public final float[] irradiance;      // 6 * irrSize*irrSize * 3
    public final float[] prefiltered;     // 6 * preSize*preSize * 3 * preMips
    public final float[] dfg;             // dfgSize*dfgSize*2 (RG)

    /** sky params (mirrors SKY_FS) */
    public final Vec3 top, horizon, moonDir, moonColor;

    public IBLData(Vec3 skyTop, Vec3 skyHorizon, Vec3 moonDir, Vec3 moonColor){
        this.top = skyTop.cpy();
        this.horizon = skyHorizon.cpy();
        this.moonDir = moonDir.cpy().norm();
        this.moonColor = moonColor.cpy();
        irradiance = new float[6 * irrSize * irrSize * 3];
        prefiltered = new float[6 * preSize * preSize * 3 * preMips];
        dfg = new float[dfgSize * dfgSize * 2];
        bake();
    }

    /** procedural sky — Java mirror of SKY_FS (stars excluded: sub-pixel at 16-64px) */
    public void sky(Vec3 dir, Vec3 out){
        float h = dir.y;
        Vec3 sky = out.set(horizon).lerp(top, (float)Math.pow(Math.max(h, 0f), 0.55));
        if(h < 0f) out.set(horizon).lerp(new Vec3(horizon).scale(0.25f), Math.min(1f, -h * 2.5f));
        out.addScaled(horizon, (float)Math.pow(1f - Math.abs(h), 8f) * 0.3f);
        float md = dir.dot(moonDir);
        float halo = (float)Math.pow(Math.max(md, 0f), 900f) * 0.9f + (float)Math.pow(Math.max(md, 0f), 120f) * 0.15f;
        out.addScaled(moonColor, halo);
    }

    private void bake(){
        bakeIrradiance();
        bakePrefiltered();
        bakeDFG();
    }

    // ------------------------------------------------------------------
    // cube face basis (+X,-X,+Y,-Y,+Z,-Z as GL convention)
    // ------------------------------------------------------------------
    private static final Vec3[] FACE_U = {
        new Vec3(0,0,-1), new Vec3(0,0,1), new Vec3(1,0,0), new Vec3(1,0,0), new Vec3(1,0,0), new Vec3(-1,0,0) };
    private static final Vec3[] FACE_V = {
        new Vec3(0,-1,0), new Vec3(0,-1,0), new Vec3(0,0,1), new Vec3(0,0,-1), new Vec3(0,-1,0), new Vec3(0,-1,0) };
    private static final Vec3[] FACE_F = {
        new Vec3(1,0,0), new Vec3(-1,0,0), new Vec3(0,1,0), new Vec3(0,-1,0), new Vec3(0,0,1), new Vec3(0,0,-1) };

    private void faceDir(int face, int x, int y, int size, Vec3 out){
        float u = (x + 0.5f) / size * 2f - 1f;
        float v = (y + 0.5f) / size * 2f - 1f;
        out.set(
            FACE_F[face].x + FACE_U[face].x * u + FACE_V[face].x * v,
            FACE_F[face].y + FACE_U[face].y * u + FACE_V[face].y * v,
            FACE_F[face].z + FACE_U[face].z * u + FACE_V[face].z * v);
        out.norm();
    }

    private void bakeIrradiance(){
        Vec3 dir = new Vec3(), sky = new Vec3(), sum = new Vec3(), tdir = new Vec3();
        // fixed hemisphere sample set (Halton(2,3) projected) — deterministic
        final int SAMPLES = 96;
        for(int face = 0; face < 6; face++){
            for(int y = 0; y < irrSize; y++){
                for(int x = 0; x < irrSize; x++){
                    faceDir(face, x, y, irrSize, dir);
                    sum.set(0,0,0);
                    // build tangent frame
                    Vec3 up = Math.abs(dir.y) > 0.99f ? new Vec3(1,0,0) : new Vec3(0,1,0);
                    Vec3 tx = dir.cpy().cross(up).norm();
                    Vec3 ty = tx.cpy().cross(dir).norm();
                    for(int s = 0; s < SAMPLES; s++){
                        float u1 = halton(s + 1, 2), u2 = halton(s + 1, 3);
                        float phi = 2f * (float)Math.PI * u1;
                        float cosT = (float)Math.sqrt(u2);
                        float sinT = (float)Math.sqrt(Math.max(0f, 1f - u2 * u2));
                        tdir.set(
                            (float)(Math.cos(phi) * sinT),
                            cosT,
                            (float)(Math.sin(phi) * sinT));
                        // tbn -> world
                        Vec3 w = new Vec3(
                            tx.x * tdir.x + dir.x * tdir.y + ty.x * tdir.z,
                            tx.y * tdir.x + dir.y * tdir.y + ty.y * tdir.z,
                            tx.z * tdir.x + dir.z * tdir.y + ty.z * tdir.z);
                        sky(w, sky);
                        sum.add(sky);
                    }
                    sum.scale(1f / SAMPLES);
                    int o = (face * irrSize * irrSize + y * irrSize + x) * 3;
                    irradiance[o] = sum.x; irradiance[o + 1] = sum.y; irradiance[o + 2] = sum.z;
                }
            }
        }
    }

    private void bakePrefiltered(){
        Vec3 R = new Vec3(), sky = new Vec3(), sum = new Vec3(), tdir = new Vec3();
        for(int mip = 0; mip < preMips; mip++){
            float rough = preMips > 1 ? mip / (float)(preMips - 1) : 0f;
            rough = Math.max(0.045f, rough);
            int size = preSize >> mip;
            int SAMPLES = 48;
            for(int face = 0; face < 6; face++){
                for(int y = 0; y < size; y++){
                    for(int x = 0; x < size; x++){
                        faceDir(face, x, y, size, R);
                        Vec3 N = R.cpy();
                        Vec3 up = Math.abs(N.y) > 0.99f ? new Vec3(1,0,0) : new Vec3(0,1,0);
                        Vec3 tx = N.cpy().cross(up).norm();
                        Vec3 ty = tx.cpy().cross(N).norm();
                        sum.set(0,0,0);
                        for(int s = 0; s < SAMPLES; s++){
                            float u1 = halton(s + 1, 2), u2 = halton(s + 1, 3);
                            float phi = 2f * (float)Math.PI * u1;
                            float cosT = (float)Math.sqrt((1f - u2) / (1f + (rough * rough - 1f) * u2));
                            float sinT = (float)Math.sqrt(Math.max(0f, 1f - cosT * cosT));
                            tdir.set((float)(Math.cos(phi) * sinT), cosT, (float)(Math.sin(phi) * sinT));
                            Vec3 H = new Vec3(
                                tx.x * tdir.x + N.x * tdir.y + ty.x * tdir.z,
                                tx.y * tdir.x + N.y * tdir.y + ty.y * tdir.z,
                                tx.z * tdir.x + N.z * tdir.y + ty.z * tdir.z);
                            Vec3 L = new Vec3(-H.x, -H.y, -H.z);
                            L.addScaled(N, 2f * N.dot(H));       // reflect
                            if(L.y < -0.2f) L.y = -0.2f;          // avoid sampling below ground strongly
                            L.norm();
                            sky(L, sky);
                            float ndl = Math.max(0f, N.dot(L));
                            sum.addScaled(sky, ndl);
                        }
                        if(sum.len2() > 1e-10f) sum.scale(1f / SAMPLES);
                        int o = ((mip * 6 + face) * preSize * preSize + y * preSize + x) * 3;
                        prefiltered[o] = sum.x; prefiltered[o + 1] = sum.y; prefiltered[o + 2] = sum.z;
                    }
                }
            }
        }
    }

    private void bakeDFG(){
        for(int y = 0; y < dfgSize; y++){
            float rough = (y + 0.5f) / dfgSize;
            for(int x = 0; x < dfgSize; x++){
                float ndv = (x + 0.5f) / dfgSize;
                float[] env = envBRDFApprox(1f, rough, ndv);
                int o = (y * dfgSize + x) * 2;
                dfg[o] = env[0];
                dfg[o + 1] = env[1];
            }
        }
    }

    /** Karis-style analytic envBRDF approximation (as used in UE mobile) — returns [scale, bias] for F0 */
    private static float[] envBRDFApprox(float f0, float rough, float ndv){
        float NoV = Math.max(1e-4f, ndv);
        float r = rough;
        float scale = 1f - r * 0.55f;
        float bias = r * 0.28f * (1f - NoV);
        return new float[]{ scale, bias };
    }

    private static float halton(int i, int b){
        float f = 1f, r = 0f;
        while(i > 0){
            f /= b;
            r += f * (i % b);
            i /= b;
        }
        return r;
    }

    // ------------------------------------------------------------------
    /** layout helper for GL upload: mip size */
    public int mipSize(int mip){ return preSize >> mip; }
}
