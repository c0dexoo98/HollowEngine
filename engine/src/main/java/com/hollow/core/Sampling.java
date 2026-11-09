package com.hollow.core;

import java.util.ArrayList;
import java.util.List;

/** Sampling utilities (MATH-025): Poisson-disk distribution (Mitchell's best-candidate
 *  + dart throwing) for scatter placement (foliage, debris, star fields) — deterministic
 *  via {@link RandomStream}. Also gradient/color ramp sampling helpers. */
public final class Sampling {
    private Sampling(){}

    /** 2D point */
    public static final class P2 {
        public final float x, y;
        public P2(float x, float y){ this.x = x; this.y = y; }
        @Override public String toString(){ return "(" + x + ", " + y + ")"; }
    }

    /** Mitchell's best-candidate Poisson-ish disk: each new point tries k candidates and
     *  keeps the one farthest from all accepted points. O(n²·k) — fine for scatter counts. */
    public static List<P2> poissonDiskBestCandidate(int count, RandomStream rng, int candidatesPerTry){
        List<P2> pts = new ArrayList<>(count);
        int k = Math.max(1, candidatesPerTry);
        for(int i=0;i<count;i++){
            P2 best = null;
            float bestD = -1f;
            for(int c=0;c<k;c++){
                P2 cand = new P2(rng.nextFloat(), rng.nextFloat());
                float d = Float.MAX_VALUE;
                for(P2 p : pts) d = Math.min(d, dist2(cand, p));
                if(pts.isEmpty()){ d = 2f; }
                if(d > bestD){ bestD = d; best = cand; }
            }
            pts.add(best);
        }
        return pts;
    }

    /** Bridson-style variable-radius dart throwing with min-distance (radius in unit space) */
    public static List<P2> poissonDisk(float radius, RandomStream rng, int maxAttempts){
        List<P2> pts = new ArrayList<>();
        float r2 = radius * radius;
        int attempts = 0;
        while(attempts < maxAttempts){
            P2 cand = new P2(rng.nextFloat(), rng.nextFloat());
            attempts++;
            boolean ok = true;
            for(P2 p : pts){
                if(dist2(cand, p) < r2){ ok = false; break; }
            }
            if(ok) pts.add(cand);
        }
        return pts;
    }

    /** Poisson disk in an arbitrary rect with margin (world-space scatter) */
    public static List<P2> poissonDiskRect(float x0, float y0, float x1, float y1,
                                           float radius, RandomStream rng, int maxAttempts){
        List<P2> unit = poissonDisk(radius / Math.max(1e-6f, Math.max(x1 - x0, y1 - y0)), rng, maxAttempts);
        List<P2> out = new ArrayList<>(unit.size());
        for(P2 p : unit) out.add(new P2(x0 + p.x * (x1 - x0), y0 + p.y * (y1 - y0)));
        return out;
    }

    /** sunflower/Vogel spiral — deterministic blue-ish noise, no RNG needed */
    public static List<P2> vogelSpiral(int count){
        List<P2> pts = new ArrayList<>(count);
        final float GA = (float)(Math.PI * (3 - Math.sqrt(5)));
        for(int i=0;i<count;i++){
            float r = (float)Math.sqrt(i + 0.5f) / (float)Math.sqrt(count);
            float th = GA * i;
            pts.add(new P2(0.5f + r * 0.5f * (float)Math.cos(th), 0.5f + r * 0.5f * (float)Math.sin(th)));
        }
        return pts;
    }

    /** points on a sphere (Fibonacci sphere) — uniform directions for probes/shadows */
    public static List<Vec3> fibonacciSphere(int count){
        List<Vec3> out = new ArrayList<>(count);
        final float GA = (float)(Math.PI * (3 - Math.sqrt(5)));
        for(int i=0;i<count;i++){
            float y = 1f - (i + 0.5f) * 2f / count;
            float r = (float)Math.sqrt(Math.max(0f, 1f - y * y));
            float th = GA * i;
            out.add(new Vec3((float)Math.cos(th) * r, y, (float)Math.sin(th) * r));
        }
        return out;
    }

    private static float dist2(P2 a, P2 b){
        float dx = a.x - b.x, dy = a.y - b.y;
        return dx * dx + dy * dy;
    }
}
