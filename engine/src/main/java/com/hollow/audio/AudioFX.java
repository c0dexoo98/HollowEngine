package com.hollow.audio;

/** Pure-Java DSP effects chain (HollowEngine 2.0 — MetaSounds-lite).
 *  - Freeverb reverb (Schroeder-Mözer: 8 combs + 4 allpasses per channel)
 *  - 3-band EQ (low shelf / peaking / high shelf biquads)
 *  - Feed-forward compressor (limiter) with attack/release
 *  - Ducking envelope (sidechain activity -> music gain)
 *  Everything processes float blocks in place; deterministic; no allocation per block.
 */
public final class AudioFX {

    // ================= Freeverb =================
    public static final class Reverb {
        private static final int[] COMB_TUNING = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        private static final int[] ALLP_TUNING = {556, 441, 341, 225};
        private static final float MUTED = 0f;
        private static final float FIXED_GAIN = 0.015f;
        private static final float SCALE_WET = 3f;
        private static final float SCALE_DAMP = 0.4f;
        private static final float ROOM_SCALE = 0.28f;
        private static final float ROOM_OFFSET = 0.7f;

        public float wet = 0f;              // 0..1 send level
        public float roomSize = 0.5f;       // 0..1
        public float damp = 0.5f;           // 0..1

        private final float[][] combBufL = new float[8][];
        private final float[][] combBufR = new float[8][];
        private final int[] combIdxL = new int[8];
        private final int[] combIdxR = new int[8];
        private final float[] combFilL = new float[8];
        private final float[] combFilR = new float[8];
        private final float[][] apBufL = new float[4][];
        private final float[][] apBufR = new float[4][];
        private final int[] apIdxL = new int[4];
        private final int[] apIdxR = new int[4];
        private float roomSize1, damp1 = 0f, wet1 = 0f;

        public Reverb(){
            for(int i = 0; i < 8; i++){
                combBufL[i] = new float[COMB_TUNING[i]];
                combBufR[i] = new float[COMB_TUNING[i] + 23];  // stereo spread
            }
            for(int i = 0; i < 4; i++){
                apBufL[i] = new float[ALLP_TUNING[i]];
                apBufR[i] = new float[ALLP_TUNING[i] + 19];
            }
            update();
        }

        public void update(){
            roomSize1 = roomSize * ROOM_SCALE + ROOM_OFFSET;
            damp1 = damp * SCALE_DAMP;
            wet1 = wet * SCALE_WET;
        }

        public void reset(){
            for(float[] b : combBufL) java.util.Arrays.fill(b, MUTED);
            for(float[] b : combBufR) java.util.Arrays.fill(b, MUTED);
            for(float[] b : apBufL) java.util.Arrays.fill(b, MUTED);
            for(float[] b : apBufR) java.util.Arrays.fill(b, MUTED);
            java.util.Arrays.fill(combIdxL, 0);
            java.util.Arrays.fill(combIdxR, 0);
            java.util.Arrays.fill(apIdxL, 0);
            java.util.Arrays.fill(apIdxR, 0);
            java.util.Arrays.fill(combFilL, 0f);
            java.util.Arrays.fill(combFilR, 0f);
 }

        /** processes interleaved stereo in-place (wet added at wet1 level) */
        public void process(float[] data, int frames){
            if(wet1 <= 0.0001f) return;
            for(int f = 0; f < frames; f++){
                int i = f * 2;
                float inL = data[i] * FIXED_GAIN;
                float inR = data[i + 1] * FIXED_GAIN;
                float outL = 0f, outR = 0f;
                for(int c = 0; c < 8; c++){
                    outL += combProcess(combBufL[c], combIdxL, combFilL, inL, c);
                    outR += combProcess(combBufR[c], combIdxR, combFilR, inR, c);
                }
                for(int a = 0; a < 4; a++){
                    outL = allpassProcess(apBufL[a], apIdxL, a, outL);
                    outR = allpassProcess(apBufR[a], apIdxR, a, outR);
                }
                data[i] += outL * wet1;
                data[i + 1] += outR * wet1;
            }
        }

        private float combProcess(float[] buf, int[] idx, float[] fil, float in, int cIdx){
            float output = buf[idx[cIdx]];
            fil[cIdx] = output * damp1 + in * (1f - damp1);
            buf[idx[cIdx]] = fil[cIdx];
            if(++idx[cIdx] >= buf.length) idx[cIdx] = 0;
            return output;
        }

        private float allpassProcess(float[] buf, int[] idx, int a, float in){
            float bufout = buf[idx[a]];
            float output = -in + bufout;
            buf[idx[a]] = in + bufout * 0.5f;
            if(++idx[a] >= buf.length) idx[a] = 0;
            return output;
        }
    }

    // ================= 3-band EQ (biquads) =================
    public static final class Eq3 {
        private float lowGainDb = 0f, midGainDb = 0f, highGainDb = 0f;
        private double lowFreq = 250, highFreq = 3500;

        // per-channel biquad states
        private final Biquad lowL = new Biquad(), lowR = new Biquad();
        private final Biquad midL = new Biquad(), midR = new Biquad();
        private final Biquad highL = new Biquad(), highR = new Biquad();

        public void set(int sampleRate, float lowDb, float midDb, float highDb){
            lowGainDb = lowDb; midGainDb = midDb; highGainDb = highDb;
            lowL.setLowShelf(sampleRate, lowFreq, dbToLin(lowDb));
            lowR.setLowShelf(sampleRate, lowFreq, dbToLin(lowDb));
            midL.setPeaking(sampleRate, 1000, 0.8, dbToLin(midDb));
            midR.setPeaking(sampleRate, 1000, 0.8, dbToLin(midDb));
            highL.setHighShelf(sampleRate, highFreq, dbToLin(highDb));
            highR.setHighShelf(sampleRate, highFreq, dbToLin(highDb));
        }

        public boolean active(){ return Math.abs(lowGainDb) > 0.01f || Math.abs(midGainDb) > 0.01f || Math.abs(highGainDb) > 0.01f; }

        public void process(float[] data, int frames){
            if(!active()) return;
            for(int f = 0; f < frames; f++){
                int i = f * 2;
                float l = data[i], r = data[i + 1];
                l = highL.process(lowL.process(midL.process(l)));
                r = highR.process(lowR.process(midR.process(r)));
                data[i] = l; data[i + 1] = r;
            }
        }

        private static float dbToLin(float db){ return (float)Math.pow(10, db / 20.0); }
    }

    /** transposed direct-form-II biquad */
    public static final class Biquad {
        private double b0, b1, b2, a1, a2;
        private double z1, z2;

        public void setLowShelf(int sr, double f0, float gain){
            double A = Math.pow(10, gain / 40.0);
            double w0 = 2 * Math.PI * f0 / sr;
            double cw = Math.cos(w0), sw = Math.sin(w0);
            double S = 1.0;                       // shelf slope
            double alpha = sw / 2 * Math.sqrt((A + 1 / A) * (1 / S - 1) + 2);
            double twoSqrtAalpha = 2 * Math.sqrt(A) * alpha;
            double a0 = (A + 1) + (A - 1) * cw + twoSqrtAalpha;
            double b0 = A * ((A + 1) - (A - 1) * cw + twoSqrtAalpha);
            double b1 = 2 * A * ((A - 1) - (A + 1) * cw);
            double b2 = A * ((A + 1) - (A - 1) * cw - twoSqrtAalpha);
            double a1 = -2 * ((A - 1) + (A + 1) * cw);
            double a2 = (A + 1) + (A - 1) * cw - twoSqrtAalpha;
            set(a0, b0, b1, b2, a1, a2);
        }

        public void setHighShelf(int sr, double f0, float gain){
            double A = Math.pow(10, gain / 40.0);
            double w0 = 2 * Math.PI * f0 / sr;
            double cw = Math.cos(w0), sw = Math.sin(w0);
            double S = 1.0;
            double alpha = sw / 2 * Math.sqrt((A + 1 / A) * (1 / S - 1) + 2);
            double twoSqrtAalpha = 2 * Math.sqrt(A) * alpha;
            double a0 = (A + 1) - (A - 1) * cw + twoSqrtAalpha;
            double b0 = A * ((A + 1) + (A - 1) * cw + twoSqrtAalpha);
            double b1 = -2 * A * ((A - 1) + (A + 1) * cw);
            double b2 = A * ((A + 1) + (A - 1) * cw - twoSqrtAalpha);
            double a1 = 2 * ((A - 1) - (A + 1) * cw);
            double a2 = (A + 1) - (A - 1) * cw - twoSqrtAalpha;
            set(a0, b0, b1, b2, a1, a2);
        }

        public void setPeaking(int sr, double f0, double Q, float gain){
            double A = Math.pow(10, gain / 40.0);
            double w0 = 2 * Math.PI * f0 / sr;
            double cw = Math.cos(w0), sw = Math.sin(w0);
            double alpha = sw / (2 * Q);
            double a0 = 1 + alpha / A;
            double b0 = 1 + alpha * A;
            double b1 = -2 * cw;
            double b2 = 1 - alpha * A;
            double a1 = -2 * cw;
            double a2 = 1 - alpha / A;
            set(a0, b0, b1, b2, a1, a2);
        }

        private void set(double a0, double b0, double b1, double b2, double a1, double a2){
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0;
            this.a1 = a1 / a0; this.a2 = a2 / a0;
            z1 = 0; z2 = 0;
        }

        public float process(float x){
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return (float) y;
        }
    }

    // ================= Compressor (limiter) =================
    public static final class Compressor {
        public float threshold = 0.7f;       // linear peak
        public float ratio = 6f;
        public float attackMs = 5f;
        public float releaseMs = 120f;

        private float env = 0f;

        public void process(float[] data, int frames, int sampleRate){
            float atk = (float)Math.exp(-1000.0 / (attackMs * sampleRate));
            float rel = (float)Math.exp(-1000.0 / (releaseMs * sampleRate));
            for(int f = 0; f < frames; f++){
                int i = f * 2;
                float peak = Math.max(Math.abs(data[i]), Math.abs(data[i + 1]));
                float coef = peak > env ? atk : rel;
                env = peak + (env - peak) * coef;
                float gain = 1f;
                if(env > threshold){
                    float over = env / threshold;
                    gain = (float)Math.pow(over, -(1f - 1f / ratio));
                }
                data[i] *= gain;
                data[i + 1] *= gain;
            }
        }
    }

    // ================= Ducking (sidechain) =================
    public static final class Ducker {
        public float amount = 0.45f;         // music reduction when voice active (0..1)
        public float attackMs = 60f;
        public float releaseMs = 600f;
        private float duck = 0f;             // 0 none, 1 full duck

        /** returns current music gain multiplier (1 = no duck) */
        public float update(boolean voiceActive, int sampleRate){
            float target = voiceActive ? amount : 0f;
            float ms = target > duck ? attackMs : releaseMs;
            float coef = 1f - (float)Math.exp(-1000.0 / (ms * Math.max(1, sampleRate)));
            duck += (target - duck) * coef;
            return 1f - duck;
        }

        public void reset(){ duck = 0f; }
    }
}
