package com.hollow.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/** Generic object pool (MEM). Avoids GC churn for hot paths: particles, events, messages.
 *  Thread-confined by design (engine sim is single-threaded); use one pool per thread if needed. */
public final class Pool<T> {
    private final Supplier<T> factory;
    private final java.util.function.Consumer<T> reset;
    private final Deque<T> free;
    private final int maxIdle;
    private int created = 0, inUse = 0, peakInUse = 0, hits = 0;

    public Pool(Supplier<T> factory, java.util.function.Consumer<T> reset, int prefill, int maxIdle){
        this.factory = factory; this.reset = reset; this.maxIdle = maxIdle;
        this.free = new ArrayDeque<>(Math.max(8, prefill));
        for(int i=0;i<prefill;i++){ free.add(factory.get()); created++; }
    }
    public Pool(Supplier<T> factory){ this(factory, null, 0, 256); }
    public Pool(Supplier<T> factory, int prefill){ this(factory, null, prefill, 256); }

    public T obtain(){
        T t = free.pollFirst();
        if(t == null){ t = factory.get(); created++; }
        else hits++;
        if(t != null) leasedOut.add(t);                    // track for double-free detection
        inUse++;
        if(inUse > peakInUse) peakInUse = inUse;
        return t;
    }
    public void free(T t){
        if(t == null) return;
        if(!leasedOut.remove(t)) return;                   // double free / never leased — ignore
        inUse = Math.max(0, inUse - 1);
        if(reset != null){ try { reset.accept(t); } catch(Exception e){ Log.w("Pool", "reset failed: " + e); } }
        if(free.size() < maxIdle) free.addFirst(t);
    }
    /** return all currently leased objects (tracked by caller list) */
    public void freeAll(List<T> leased){
        for(T t : leased) free(t);
        leased.clear();
    }
    public void clear(){ free.clear(); }
    public int inUse(){ return inUse; }
    public int idle(){ return free.size(); }
    public int created(){ return created; }
    public int hits(){ return hits; }
    public int peakInUse(){ return peakInUse; }
    public String stats(){ return "pool[inUse=" + inUse + " idle=" + free.size() + " created=" + created + " hits=" + hits + " peak=" + peakInUse + "]"; }

    // ---------------- primitive array pools ----------------
    /** pools for float[]/byte[] scratch buffers, bucketed by capacity (rounded up to pow2) */
    private static final List<Pool<float[]>> FLOAT_POOLS = new ArrayList<>();
    private static final List<Pool<byte[]>> BYTE_POOLS = new ArrayList<>();

    public static float[] obtainFloats(int minCapacity){
        int bucket = pow2(minCapacity);
        for(Pool<float[]> p : FLOAT_POOLS){ if(bucket <= bucketOf(p)){ float[] a = p.obtain(); return a; } }
        return new float[bucket];
    }
    public static void freeFloats(float[] a){
        if(a == null) return;
        int bucket = pow2(a.length);
        if(bucket != a.length) return;   // non-pow2 arrays never fit a bucket — dropping avoids short arrays
        for(Pool<float[]> p : FLOAT_POOLS){ if(bucket == bucketOf(p)){ p.free(a); return; } }
    }
    public static byte[] obtainBytes(int minCapacity){
        int bucket = pow2(minCapacity);
        for(Pool<byte[]> p : BYTE_POOLS){ if(bucket <= bucketOf(p)){ return p.obtain(); } }
        return new byte[bucket];
    }
    public static void freeBytes(byte[] a){
        if(a == null) return;
        int bucket = pow2(a.length);
        if(bucket != a.length) return;   // non-pow2 arrays never fit a bucket
        for(Pool<byte[]> p : BYTE_POOLS){ if(bucket == bucketOf(p)){ p.free(a); return; } }
    }

    private static int bucketOf(Pool<?> p){ return (Integer)p.meta; }
    Object meta; // bucket size for static pools
    private final java.util.Set<T> leasedOut = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    static int pow2(int v){ int p = 16; while(p < v) p <<= 1; return p; }
    static { // standard buckets 16..65536
        for(int cap = 16; cap <= 65536; cap <<= 1){
            final int size = cap;
            Pool<float[]> pf = new Pool<float[]>(() -> new float[size], null, 0, 16); pf.meta = size; FLOAT_POOLS.add(pf);
            Pool<byte[]> pb = new Pool<byte[]>(() -> new byte[size], null, 0, 16); pb.meta = size; BYTE_POOLS.add(pb);
        }
    }
}
