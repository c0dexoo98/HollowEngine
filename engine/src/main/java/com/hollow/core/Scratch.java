package com.hollow.core;

import java.nio.FloatBuffer;

/** Zero-allocation scratch space (MEM-018) for the render loop and sim hot paths.
 *  Thread-local reusable vectors, matrices and grow-only float buffers — call sites
 *  borrow, fill and read without ever touching the allocator. Buffers are reused
 *  (grown on demand, never shrunk), so a warm frame allocates exactly zero objects. */
public final class Scratch {
    private Scratch(){}

    private static final ThreadLocal<Scratch> TL = ThreadLocal.withInitial(Scratch::new);

    public static Scratch get(){ return TL.get(); }

    // ---------------- vectors / matrices (single-deep borrow) ----------------
    private final Vec3 v0 = new Vec3(), v1 = new Vec3(), v2 = new Vec3(), v3 = new Vec3();
    private final Mat4 m0 = new Mat4(), m1 = new Mat4();
    private boolean v0Busy, v1Busy, v2Busy, v3Busy, m0Busy, m1Busy;

    public Vec3 vec(){ if(v0Busy) throw new IllegalStateException("scratch vec0 double-borrow"); v0Busy = true; return v0; }
    public Vec3 vec2nd(){ if(v1Busy) throw new IllegalStateException("scratch vec1 double-borrow"); v1Busy = true; return v1; }
    public void free(Vec3 v){
        if(v == v0) v0Busy = false;
        else if(v == v1) v1Busy = false;
    }
    public Mat4 mat(){ if(m0Busy) throw new IllegalStateException("scratch mat0 double-borrow"); m0Busy = true; return m0; }
    public void free(Mat4 m){ if(m == m0) m0Busy = false; else if(m == m1) m1Busy = false; }

    // ---------------- grow-only float buffers ----------------
    private float[] floatScratch = new float[256];

    /** reuse a float[] of at least n elements (contents undefined) */
    public float[] floats(int n){
        if(floatScratch.length < n) floatScratch = new float[Math.max(n, floatScratch.length * 2)];
        return floatScratch;
    }

    // ---------------- direct NIO buffers (upload paths) ----------------
    private FloatBuffer floatBuffer;

    /** reuse a direct FloatBuffer with capacity >= n (position reset to 0) */
    public FloatBuffer directFloats(int n){
        if(floatBuffer == null || floatBuffer.capacity() < n){
            int cap = floatBuffer == null ? 1024 : floatBuffer.capacity();
            while(cap < n) cap <<= 1;
            floatBuffer = NioUtil.floatBuffer(cap);
        }
        floatBuffer.position(0);
        floatBuffer.limit(n);
        return floatBuffer;
    }

    public String stats(){
        return "scratch[floats=" + floatScratch.length + " direct=" + (floatBuffer == null ? 0 : floatBuffer.capacity()) + "]";
    }
}
