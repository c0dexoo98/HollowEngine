package com.hollow.ui;

import com.hollow.core.MathX;

/** UI-019 — semi-transparent blurred menu background ("acrylic/frosted glass").
 *
 *  The platform captures the current frame region behind the menu into an ARGB int[]
 *  (renderer hook), then this class applies a fast separable box blur + tint +
 *  alpha, producing the panel background. Pure CPU, deterministic, headless-testable
 *  (small buffers); the renderer samples the same parameters on-GPU when available. */
public final class BlurPanel {

    public int blurRadius = 6;          // box blur radius in pixels
    public int tintColor = 0xFF101418;  // ARGB tint
    public float tintStrength = 0.55f;  // 0 = pure blurred image, 1 = solid tint
    public float alpha = 0.82f;         // final panel alpha
    public int downscale = 2;           // sample every Nth pixel (perf)
    public boolean animated = true;
    public float reveal = 0f;           // 0 hidden .. 1 fully shown (animated in)

    public BlurPanel(){}
    public BlurPanel(int blurRadius, int tintColor, float tintStrength, float alpha){
        this.blurRadius = blurRadius; this.tintColor = tintColor;
        this.tintStrength = tintStrength; this.alpha = alpha;
    }

    /** animate reveal in/out (UIManager calls with frame dt) */
    public void update(float dt, boolean wantVisible){
        float target = wantVisible ? 1f : 0f;
        reveal = MathX.approach(reveal, target, dt * 4f);
    }

    // ------------------------------------------------------------------
    // CPU blur (renderer falls back to this on desktop debug / screenshots)
    // ------------------------------------------------------------------

    /** in-place separable box blur (two passes) on ARGB pixels. */
    public static void boxBlur(int[] px, int w, int h, int radius){
        if(radius <= 0 || px.length < w * h) return;
        int[] tmp = new int[px.length];
        horizontal(px, tmp, w, h, radius);
        vertical(tmp, px, w, h, radius);
    }

    private static void horizontal(int[] src, int[] dst, int w, int h, int r){
        for(int y = 0; y < h; y++){
            int row = y * w;
            long sr = 0, sg = 0, sb = 0, sa = 0;
            for(int x = -r; x <= r; x++){
                int xi = Math.max(0, Math.min(w - 1, x));
                int c = src[row + xi];
                sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF; sa += (c >>> 24);
            }
            int win = 2 * r + 1;
            for(int x = 0; x < w; x++){
                dst[row + x] = argb((int)(sa / win), (int)(sr / win), (int)(sg / win), (int)(sb / win));
                int out = Math.max(0, x - r);
                int in = Math.min(w - 1, x + r + 1);
                int co = src[row + out], ci = src[row + in];
                sr += ((ci >> 16) & 0xFF) - ((co >> 16) & 0xFF);
                sg += ((ci >> 8) & 0xFF) - ((co >> 8) & 0xFF);
                sb += (ci & 0xFF) - (co & 0xFF);
                sa += (ci >>> 24) - (co >>> 24);
            }
        }
    }

    private static void vertical(int[] src, int[] dst, int w, int h, int r){
        for(int x = 0; x < w; x++){
            long sr = 0, sg = 0, sb = 0, sa = 0;
            for(int y = -r; y <= r; y++){
                int c = src[Math.max(0, Math.min(h - 1, y)) * w + x];
                sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF; sa += (c >>> 24);
            }
            int win = 2 * r + 1;
            for(int y = 0; y < h; y++){
                dst[y * w + x] = argb((int)(sa / win), (int)(sr / win), (int)(sg / win), (int)(sb / win));
                int out = Math.max(0, y - r);
                int in = Math.min(h - 1, y + r + 1);
                int co = src[out * w + x], ci = src[in * w + x];
                sr += ((ci >> 16) & 0xFF) - ((co >> 16) & 0xFF);
                sg += ((ci >> 8) & 0xFF) - ((co >> 8) & 0xFF);
                sb += (ci & 0xFF) - (co & 0xFF);
                sa += (ci >>> 24) - (co >>> 24);
            }
        }
    }

    static int argb(int a, int r, int g, int b){
        return (Math.max(0, Math.min(255, a)) << 24)
             | (Math.max(0, Math.min(255, r)) << 16)
             | (Math.max(0, Math.min(255, g)) << 8)
             |  Math.max(0, Math.min(255, b));
    }

    /** blur + tint + alpha — full panel pipeline over the captured background. */
    public void render(int[] px, int w, int h){
        if(px.length < w * h || w == 0 || h == 0) return;
        boxBlur(px, w, h, Math.max(1, blurRadius));
        float strength = tintStrength * reveal;
        float aOut = alpha * reveal;
        int tr = (tintColor >> 16) & 0xFF, tg = (tintColor >> 8) & 0xFF, tb = tintColor & 0xFF;
        for(int i = 0; i < w * h; i++){
            int c = px[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            r = (int)MathX.lerp(r, tr, strength);
            g = (int)MathX.lerp(g, tg, strength);
            b = (int)MathX.lerp(b, tb, strength);
            px[i] = argb((int)(aOut * 255), r, g, b);
        }
    }

    /** GPU path parameters (renderer reads when GL is available) */
    public String gpuParams(){
        return "blur{radius=" + blurRadius + " tint=#" + Integer.toHexString(tintColor)
            + " strength=" + String.format("%.2f", tintStrength * reveal)
            + " alpha=" + String.format("%.2f", alpha * reveal)
            + " downscale=" + downscale + "}";
    }
}
