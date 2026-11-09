package com.hollow.assets;

import java.util.LinkedHashMap;
import java.util.Map;

/** One skeletal animation clip: per-joint TRS channels with time keys. */
public final class AnimClip {
    public String name = "clip";
    public float duration = 0f;
    public boolean loop = true;
    /** jointIndex -> channel */
    public final Map<Integer, Channel> channels = new LinkedHashMap<>();

    public static final class Channel {
        public float[] times;
        public int interpolation = INTERP_LINEAR;   // STEP, LINEAR, CUBIC
        public float[] translations;  // 3 per key (optional)
        public float[] rotations;     // 4 per key xyzw (optional)
        public float[] scales;        // 3 per key (optional)
        public float[] cubicT;        // cubic spline in/out tangents (4 per key per component) — optional
    }
    public static final int INTERP_STEP = 0, INTERP_LINEAR = 1, INTERP_CUBIC = 2;

    /** sample translations at time t into out (len 3) */
    public void sampleTranslation(Channel c, float t, float[] out){
        sampleVec(c, t, c.translations, 3, out);
    }
    public void sampleScale(Channel c, float t, float[] out){
        sampleVec(c, t, c.scales, 3, out);
    }
    private void sampleVec(Channel c, float t, float[] data, int comps, float[] out){
        int n = c.times.length;
        if(n == 0){ out[0]=out[1]=out[2]=1; return; }
        if(t <= c.times[0]){ copyAt(data, 0, comps, out); return; }
        if(t >= c.times[n-1]){ copyAt(data, n-1, comps, out); return; }
        int i=1; while(i<n && c.times[i] < t) i++;
        float t0=c.times[i-1], t1=c.times[i];
        float f = (t-t0)/Math.max(1e-6f, t1-t0);
        for(int k=0;k<comps;k++){
            float a=data[(i-1)*comps+k], b=data[i*comps+k];
            out[k] = com.hollow.core.MathX.lerp(a, b, f);
        }
    }
    private void copyAt(float[] data, int key, int comps, float[] out){
        for(int k=0;k<comps;k++) out[k] = data[key*comps+k];
    }
    /** sample rotation into out quaternion (x,y,z,w) with lerp+slerp */
    public void sampleRotation(Channel c, float t, com.hollow.core.Quat out){
        int n = c.times.length;
        if(n==0) return;
        if(c.interpolation == INTERP_STEP || t <= c.times[0]){
            int i = t <= c.times[0] ? 0 : keyIndex(c, t);
            out.set(c.rotations[i*4], c.rotations[i*4+1], c.rotations[i*4+2], c.rotations[i*4+3]);
            return;
        }
        if(t >= c.times[n-1]){
            int i=n-1;
            out.set(c.rotations[i*4], c.rotations[i*4+1], c.rotations[i*4+2], c.rotations[i*4+3]);
            return;
        }
        int i=1; while(i<n && c.times[i] < t) i++;
        float f = (t - c.times[i-1]) / Math.max(1e-6f, c.times[i]-c.times[i-1]);
        out.set(c.rotations[(i-1)*4], c.rotations[(i-1)*4+1], c.rotations[(i-1)*4+2], c.rotations[(i-1)*4+3]);
        com.hollow.core.Quat b = new com.hollow.core.Quat(c.rotations[i*4], c.rotations[i*4+1], c.rotations[i*4+2], c.rotations[i*4+3]);
        out.slerp(b, f);
    }
    private int keyIndex(Channel c, float t){
        int n=c.times.length, i=1;
        while(i<n && c.times[i] < t) i++;
        return i-1;
    }
}
