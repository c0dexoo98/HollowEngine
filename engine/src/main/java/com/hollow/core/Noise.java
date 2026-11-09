package com.hollow.core;

/** Procedural noise (MATH): seeded Perlin 2D/3D, value noise, fBm and ridged variants.
 *  Powers terrain, textures, weather and the horror director's tension curves. */
public final class Noise {
    private final int[] perm = new int[512];
    private final float[] gradTab = new float[256]; // random gradient angle per hash

    public Noise(){ this(1337); }
    public Noise(long seed){
        int[] p = new int[256];
        for(int i=0;i<256;i++) p[i] = i;
        // deterministic shuffle (xorshift)
        long s = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        for(int i=255;i>0;i--){
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            int j = (int)((s & 0x7FFFFFFFL) % (i + 1));
            int t = p[i]; p[i] = p[j]; p[j] = t;
        }
        for(int i=0;i<512;i++) perm[i] = p[i & 255];
        for(int i=0;i<256;i++){
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            gradTab[i] = (float)((s >>> 33) / (double)0x7FFFFFFF) * (float)Math.PI * 2f;
        }
    }

    // ---------------- Perlin 2D ----------------
    /** classic Perlin, output ~[-1, 1] */
    public float perlin2(float x, float y){
        int xi = floorI(x) & 255, yi = floorI(y) & 255;
        float xf = x - floorI(x), yf = y - floorI(y);
        float u = fade(xf), v = fade(yf);

        float d00 = grad2(perm[perm[xi] + yi],     xf,     yf);
        float d10 = grad2(perm[perm[xi+1] + yi],   xf - 1, yf);
        float d01 = grad2(perm[perm[xi] + yi + 1], xf,     yf - 1);
        float d11 = grad2(perm[perm[xi+1] + yi+1], xf - 1, yf - 1);

        float a = d00 + u * (d10 - d00);
        float b = d01 + u * (d11 - d01);
        return (a + v * (b - a)) * 1.4142f; // normalize toward [-1,1]
    }

    // ---------------- Perlin 3D ----------------
    public float perlin3(float x, float y, float z){
        int xi = floorI(x) & 255, yi = floorI(y) & 255, zi = floorI(z) & 255;
        float xf = x - floorI(x), yf = y - floorI(y), zf = z - floorI(z);
        float u = fade(xf), v = fade(yf), w = fade(zf);

        float n000 = grad3(hash(xi, yi, zi),     xf,     yf,     zf);
        float n100 = grad3(hash(xi+1, yi, zi),   xf - 1, yf,     zf);
        float n010 = grad3(hash(xi, yi+1, zi),   xf,     yf - 1, zf);
        float n110 = grad3(hash(xi+1, yi+1, zi), xf - 1, yf - 1, zf);
        float n001 = grad3(hash(xi, yi, zi+1),   xf,     yf,     zf - 1);
        float n101 = grad3(hash(xi+1, yi, zi+1), xf - 1, yf,     zf - 1);
        float n011 = grad3(hash(xi, yi+1, zi+1), xf,     yf - 1, zf - 1);
        float n111 = grad3(hash(xi+1, yi+1, zi+1), xf-1, yf-1,   zf - 1);

        float x00 = n000 + u * (n100 - n000), x10 = n010 + u * (n110 - n010);
        float x01 = n001 + u * (n101 - n001), x11 = n011 + u * (n111 - n011);
        float y0 = x00 + v * (x10 - x00), y1 = x01 + v * (x11 - x01);
        return (y0 + w * (y1 - y0)) * 1.1547f;
    }

    // ---------------- value noise ----------------
    public float value2(float x, float y){
        int xi = floorI(x), yi = floorI(y);
        float xf = x - xi, yf = y - yi;
        float u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
        float a = val(hash(xi, yi)),     b = val(hash(xi+1, yi));
        float c = val(hash(xi, yi+1)),   d = val(hash(xi+1, yi+1));
        return (a + u * (b - a)) + v * ((c + u * (d - c)) - (a + u * (b - a)));
    }

    // ---------------- fBm ----------------
    /** fractal Brownian motion in [-1, 1]-ish; octaves = detail layers */
    public float fbm2(float x, float y, int octaves, float lacunarity, float gain){
        float sum = 0, amp = 1, norm = 0;
        for(int i=0;i<octaves;i++){
            sum += perlin2(x, y) * amp;
            norm += amp;
            amp *= gain;
            x *= lacunarity; y *= lacunarity;
        }
        return sum / Math.max(1e-6f, norm);
    }
    /** ridged multifractal — sharp mountain/scar lines */
    public float ridged2(float x, float y, int octaves){
        float sum = 0, amp = 0.5f, norm = 0;
        for(int i=0;i<octaves;i++){
            float n = 1f - Math.abs(perlin2(x, y));
            n *= n;
            sum += n * amp;
            norm += amp;
            amp *= 0.5f;
            x *= 2.03f; y *= 2.01f;
        }
        return sum / Math.max(1e-6f, norm);
    }

    // ---------------- helpers ----------------
    private int hash(int x, int y, int z){ return perm[perm[perm[x & 255] + (y & 255)] + (z & 255)]; }
    private int hash(int x, int y){ return perm[perm[x & 255] + (y & 255)] & 255; }
    private float val(int h){ return gradTab[h] / ((float)Math.PI * 2f) * 2f - 1f; }

    private float grad2(int hash, float x, float y){
        float a = gradTab[hash & 255];
        return (float)(Math.cos(a) * x + Math.sin(a) * y);
    }
    private float grad3(int hash, float x, float y, float z){
        float a = gradTab[hash & 255];
        float cz = (float)Math.cos(a), sz = (float)Math.sin(a);
        float gxx = cz * x - sz * y;
        float gyy = sz * x + cz * y;
        return gxx * 0.8165f + gyy * 0.4082f + z * 0.4082f;
    }
    private static int floorI(float v){ int i = (int) v; return v < i ? i - 1 : i; }
    private static float fade(float t){ return t * t * t * (t * (t * 6 - 15) + 10); }
}
