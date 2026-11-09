package com.hollow.core;

import java.util.ArrayList;
import java.util.List;

/** Deterministic PCG-XSH-RR random stream (MATH). Same seed -> same sequence on every
 *  device — required for reproducible simulations, world generation and replays.
 *  Fork() derives independent child streams (counter-based family). */
public final class RandomStream {
    private long state;
    private final long streamId;
    private long draws = 0;

    public RandomStream(long seed){ this(seed, 0xDA3E39CB94B95BDBL); }
    public RandomStream(long seed, long streamId){
        this.streamId = streamId;
        this.state = seed ^ (streamId * 0x9E3779B97F4A7C15L);
        nextU32(); // warm up
    }

    // ---------------- core PCG32 ----------------
    private int nextU32(){
        long old = state;
        state = old * 6364136223846793005L + (streamId | 1L);
        int xorshifted = (int)(((old >>> 18) ^ old) >>> 27);
        int rot = (int)(old >>> 59);
        return Integer.rotateRight(xorshifted, rot);
    }

    // ---------------- distributions ----------------
    public int nextInt(){ return nextU32(); }
    /** uniform [0, bound) — rejection-free modulo bias acceptable for gameplay use */
    public int nextInt(int bound){
        if(bound <= 0) throw new IllegalArgumentException("bound");
        return Integer.remainderUnsigned(nextU32(), bound);
    }
    /** uniform [0,1) */
    public float nextFloat(){ return (nextU32() >>> 8) * (1f / (1 << 24)); }
    /** uniform [0,1) double precision */
    public double nextDouble(){ return ((nextU32() >>> 5) * 67108864.0 + (nextU32() >>> 6)) / 9007199254740992.0; }
    /** uniform [min, max) */
    public float range(float min, float max){ return min + (max - min) * nextFloat(); }
    public int range(int min, int maxExclusive){ return min + nextInt(maxExclusive - min); }
    /** true with probability p */
    public boolean chance(float p){ return nextFloat() < p; }
    /** 50/50 coin */
    public boolean flip(){ return (nextU32() & 1) == 1; }
    /** normal distribution via Box–Muller, std=1 */
    public float gaussian(){
        float u1 = Math.max(1e-7f, nextFloat());
        float u2 = nextFloat();
        return (float)Math.sqrt(-2 * Math.log(u1)) * (float)Math.cos(2 * Math.PI * u2);
    }
    public float gaussian(float mean, float std){ return mean + std * gaussian(); }
    /** weighted index pick; weights need not sum to 1 */
    public int weighted(float[] weights){
        float total = 0;
        for(float w : weights) total += Math.max(0, w);
        float r = nextFloat() * total;
        for(int i=0;i<weights.length;i++){ r -= Math.max(0, weights[i]); if(r <= 0) return i; }
        return weights.length - 1;
    }
    /** shuffled copy (Fisher–Yates) */
    public int[] shuffle(int[] src){
        int[] a = src.clone();
        for(int i=a.length-1;i>0;i--){
            int j = nextInt(i+1);
            int t = a[i]; a[i] = a[j]; a[j] = t;
        }
        return a;
    }
    /** pick n distinct elements (n <= src.length) */
    public int[] pick(int[] src, int n){
        return java.util.Arrays.copyOf(shuffle(src), Math.min(n, src.length));
    }

    // ---------------- stream family ----------------
    /** independent child stream (for sub-systems) */
    public RandomStream fork(){ return new RandomStream(state, streamId + 0x9E3779B97F4A7C15L); }
    public long streamId(){ return streamId; }
    public long draws(){ return draws; }

    // ---------------- static convenience ----------------
    private static final RandomStream GLOBAL = new RandomStream(0x484F4C4C4F57L); // "HOLLOW"
    public static RandomStream global(){ return GLOBAL; }

    public static void main(String[] unused){ // tiny self-check
        RandomStream a = new RandomStream(42), b = new RandomStream(42);
        if(a.nextFloat() != b.nextFloat()) throw new AssertionError("PCG not deterministic");
        List<Float> seen = new ArrayList<>();
        for(int i=0;i<10000;i++) seen.add(a.nextFloat());
        System.out.println("RandomStream ok");
    }
}
