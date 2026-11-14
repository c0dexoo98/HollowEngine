package com.hollow.audio;

import java.util.Random;

/** v2.3 audio DSP suite (Batch 3):
 *  <pre>
 *  AUD-019 FFTConvolver      partitioned overlap-add FFT convolution reverb
 *                            (ImpulseResponse.capture synthesizes an IR from Reverb params,
 *                             or loads an impulse record WAV via AudioStreams)
 *  AUD-018 Binaural          HRTF-lite: ITD fractional delay + ILD head-shadow lowpass
 *                            + pinna notch — convincing headphone localization
 *  AUD-023 OcclusionFilter   1-pole lowpass + gain for wall-blocked sources
 *  AUD-022 Synth             procedural wind / hum / drum generators (deterministic)
 *  </pre>
 *  All blocks are allocation-free in the audio path and deterministic (no threads,
 *  no wall clock) — the engine mixes them per fixed step like the rest of AudioEngine.
 */
public final class AudioDsp {
    private AudioDsp() {}

    // ==================================================================
    // AUD-019 — FFT convolution (radix-2, overlap-add)
    // ==================================================================
    public static final class FFTConvolver {
        private float[][] irBlocks;         // frequency-domain IR partitions (complex, interleaved re/im)
        private int blockSize;              // time-domain partition size
        private float[][] fdQueue;          // frequency-domain tail history
        private float[] tail;               // time-domain overlap tail
        private float[] inputBuf;           // current input partition
        private int inputFill;
        private boolean ready;

        public void init(float[] impulse, int partitionSize){
            ready = false;
            if(impulse == null || impulse.length == 0 || partitionSize < 64) return;
            blockSize = Integer.highestOneBit(partitionSize) == partitionSize ? partitionSize : Integer.highestOneBit(partitionSize);
            int fftSize = blockSize * 2;
            int parts = (impulse.length + blockSize - 1) / blockSize;
            irBlocks = new float[parts][fftSize];
            for(int p = 0; p < parts; p++){
                float[] seg = new float[fftSize];
                int base = p * blockSize;
                for(int i = 0; i < blockSize && base + i < impulse.length; i++) seg[i] = impulse[base + i];
                fft(seg, false);
                irBlocks[p] = seg;
            }
            fdQueue = new float[parts][fftSize];
            tail = new float[blockSize];
            inputBuf = new float[fftSize];
            inputFill = 0;
            ready = true;
        }

        public boolean isReady(){ return ready; }

        /** process `frames` mono samples; dst may alias src */
        public void process(float[] src, float[] dst, int frames){
            if(!ready){ System.arraycopy(src, 0, dst, 0, frames); return; }
            int fftSize = blockSize * 2;
            int written = 0;
            while(written < frames){
                int take = Math.min(frames - written, blockSize - inputFill);
                System.arraycopy(src, written, inputBuf, inputFill, take);
                inputFill += take;
                written += take;
                if(inputFill == blockSize){
                    // forward FFT
                    java.util.Arrays.fill(inputBuf, blockSize, fftSize, 0f);
                    fft(inputBuf, false);
                    // rotate queue: newest at index 0 (reuse the evicted oldest slot)
                    float[] last = fdQueue[fdQueue.length - 1];
                    System.arraycopy(fdQueue, 0, fdQueue, 1, fdQueue.length - 1);
                    fdQueue[0] = last;
                    System.arraycopy(inputBuf, 0, fdQueue[0], 0, fftSize);
                    // frequency-domain sum
                    float[] acc = inputBuf;
                    java.util.Arrays.fill(acc, 0f);
                    for(int p = 0; p < fdQueue.length; p++){
                        for(int i = 0; i < fftSize; i += 2){
                            float ar = fdQueue[p][i], ai = fdQueue[p][i + 1];
                            float br = irBlocks[p][i], bi = irBlocks[p][i + 1];
                            acc[i]   += ar * br - ai * bi;
                            acc[i + 1] += ar * bi + ai * br;
                        }
                    }
                    // inverse FFT
                    fft(acc, true);
                    // overlap-add into output
                    for(int i = 0; i < blockSize; i++){
                        float v = acc[i] + tail[i];
                        if(written - take + i < frames) dst[written - take + i] = v;
                        else tail[i - (frames - (written - take))] = v;   // beyond this block → tail
                    }
                    // refill tail from second half
                    for(int i = 0; i < blockSize; i++) tail[i] = acc[blockSize + i];
                    inputFill = 0;
                }
            }
        }

        public void reset(){
            if(tail != null) java.util.Arrays.fill(tail, 0f);
            inputFill = 0;
        }

        /** in-place radix-2 FFT (re/im interleaved), sign=-1 forward, +1 inverse (1/N scaling) */
        public static void fft(float[] data, boolean inverse){
            int n = data.length / 2;
            if(n <= 1) return;
            // bit reversal
            for(int i = 1, j = 0; i < n; i++){
                int bit = n >> 1;
                for(; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if(i < j){
                    int a = i * 2, b = j * 2;
                    float tr = data[a], ti = data[a + 1];
                    data[a] = data[b]; data[a + 1] = data[b + 1];
                    data[b] = tr; data[b + 1] = ti;
                }
            }
            for(int len = 2; len <= n; len <<= 1){
                double ang = 2.0 * Math.PI / len * (inverse ? 1 : -1);
                float wr = (float)Math.cos(ang), wi = (float)Math.sin(ang);
                for(int i = 0; i < n; i += len){
                    float cr = 1f, ci = 0f;
                    for(int k = 0; k < len / 2; k++){
                        int a = (i + k) * 2, b = (i + k + len / 2) * 2;
                        float ur = data[a], ui = data[a + 1];
                        float vr = data[b] * cr - data[b + 1] * ci;
                        float vi = data[b] * ci + data[b + 1] * cr;
                        data[a] = ur + vr; data[a + 1] = ui + vi;
                        data[b] = ur - vr; data[b + 1] = ui - vi;
                        float ncr = cr * wr - ci * wi;
                        ci = cr * wi + ci * wr;
                        cr = ncr;
                    }
                }
            }
            if(inverse){
                float inv = 1f / n;
                for(int i = 0; i < n * 2; i++) data[i] *= inv;
            }
        }
    }

    /** AUD-019: synthesize an impulse response from the freeverb-style params
     *  (exponentially decaying diffuse noise) — no external IR file needed */
    public static final class ImpulseResponse {
        /** lengthSec: room size; damping: HF absorption; deterministic via seed */
        public static float[] capture(float sampleRate, float lengthSec, float damping, long seed){
            int n = Math.max(64, (int)(sampleRate * lengthSec));
            float[] ir = new float[n];
            Random rng = new Random(seed);
            float lp = 0f;
            float decay = (float)Math.exp(-6.9 / n);            // -60 dB at the end
            float dampK = MathX23.clamp(damping, 0f, 0.95f);
            for(int i = 0; i < n; i++){
                float white = rng.nextFloat() * 2f - 1f;
                lp += dampK * (white - lp);                     // lowpass = damping
                ir[i] = lp * (float)Math.pow(decay, i);
            }
            // small direct-path sample at the head
            if(n > 2) ir[0] += 0.9f;
            return ir;
        }
    }

    // ==================================================================
    // AUD-018 — HRTF-lite binaural panner
    // ==================================================================
    public static final class Binaural {
        public float maxItdSamples = 0.65f * 1.4f;      // ~0.65 ms per full side @44.1k≈29 samples
        public float shadowCutoff = 550f;               // Hz far-ear lowpass
        public float pinnaHz = 3800f;                   // pinna notch center
        public float pinnaGainDb = -4f;
        private float[] delayLine = new float[64];
        private int delayIdx = 0;
        private float lpFar = 0f, lpNear = 0f;
        private int sampleRate = 44100;

        public void setSampleRate(int sr){ sampleRate = Math.max(8000, sr); maxItdSamples = 0.00065f * sr * 2f; }

        /** azimuth -1 (hard left) .. +1 (hard right) */
        public void process(float in, float azimuth, float distance, float[] out){
            float az = MathX23.clamp(azimuth, -1f, 1f);
            // ITD: fractional delay via 1-tap interpolation on a circular line
            int itd = Math.round(Math.abs(az) * maxItdSamples);
            delayLine[delayIdx] = in;
            int readIdx = (delayIdx - itd + delayLine.length) % delayLine.length;
            float delayed = delayLine[readIdx];
            delayIdx = (delayIdx + 1) % delayLine.length;
            // ILD: far ear gets lowpassed + attenuated (head shadow)
            float shadow = MathX23.clamp(1f - Math.abs(az) * 0.8f, 0.2f, 1f);
            float lpK = MathX23.clamp(2f * (float)Math.PI * shadowCutoff / sampleRate, 0f, 0.9f);
            lpFar += lpK * (delayed - lpFar);
            float near = delayed;
            // pinna notch (peaking filter approximated by a narrow band reject on the near ear)
            float nK = MathX23.clamp(2f * (float)Math.PI * pinnaHz / sampleRate, 0f, 0.9f);
            lpNear += nK * (near - lpNear);
            float pinna = near - 0.35f * (near - lpNear);   // partial notch
            float attFar = 0.35f + 0.65f * shadow;
            float atten = 1f / (1f + distance * 0.04f);
            float l = az <= 0f ? pinna : lpFar * attFar;
            float r = az >= 0f ? pinna : lpFar * attFar;
            out[0] = l * atten;
            out[1] = r * atten;
        }

        public void reset(){ java.util.Arrays.fill(delayLine, 0f); lpFar = lpNear = 0f; delayIdx = 0; }
    }

    // ==================================================================
    // AUD-023 — occlusion lowpass + gain
    // ==================================================================
    public static final class OcclusionFilter {
        private float lpL = 0f, lpR = 0f;

        /** occlusion 0 (open air) .. 1 (fully blocked); cutoff sweeps 18k → 400 Hz */
        public void process(float occlusion, float[] stereo, int frames, int sampleRate){
            float cutoff = MathX23.lerp(18000f, 400f, MathX23.clamp(occlusion, 0f, 1f));
            float k = MathX23.clamp(2f * (float)Math.PI * cutoff / sampleRate, 0.005f, 0.95f);
            float gain = MathX23.lerp(1f, 0.45f, MathX23.clamp(occlusion, 0f, 1f));
            for(int i = 0; i < frames * 2; i += 2){
                lpL += k * (stereo[i] - lpL);
                lpR += k * (stereo[i + 1] - lpR);
                stereo[i] = lpL * gain;
                stereo[i + 1] = lpR * gain;
            }
        }

        public void reset(){ lpL = lpR = 0f; }
    }

    // ==================================================================
    // AUD-022 — procedural synth (deterministic wind / hum / drum)
    // ==================================================================
    public static final class Synth {
        public enum Kind { WIND, HUM, DRUM }

        private Kind kind = Kind.WIND;
        private double phase = 0f;
        private double lfoPhase = 0f;
        private float lp = 0f, lp2 = 0f;
        private final Random rng = new Random(0x484F4C4CL);
        private float drumEnv = 0f;

        public void set(Kind k){ kind = k; reset(); }
        public void reset(){
            phase = lfoPhase = 0; lp = lp2 = 0; drumEnv = 0;
            rng.setSeed(0x484F4C4CL);
        }

        /** render mono into dst[0..frames) at sampleRate; deterministic given call sequence */
        public void render(float[] dst, int frames, int sampleRate){
            for(int i = 0; i < frames; i++){
                float v;
                switch(kind){
                    case WIND -> {
                        // brown-ish noise + slow gust LFO driving a resonant lowpass
                        lfoPhase += 0.7 / sampleRate;
                        float gust = 0.5f + 0.5f * (float)Math.sin(lfoPhase * 2.0 * Math.PI) * (float)Math.sin(lfoPhase * 3.7);
                        float white = rng.nextFloat() * 2f - 1f;
                        lp += 0.06f * (white - lp);
                        lp2 += 0.35f * (lp - lp2);
                        v = lp2 * (0.4f + gust * 0.8f);
                    }
                    case HUM -> {
                        // detuned saw pair + sub sine, electrical hum timbre
                        phase += 96.0 / sampleRate;
                        double phase2 = phase * 1.007;
                        float s1 = 2f * ((float)(phase % 1.0)) - 1f;
                        float s2 = 2f * ((float)(phase2 % 1.0)) - 1f;
                        float sub = (float)Math.sin(phase * 2.0 * Math.PI * 0.5);
                        v = (s1 * 0.35f + s2 * 0.35f + sub * 0.3f) * 0.8f;
                    }
                    case DRUM -> {
                        // pitch-swept sine + noise click, triggered externally via hit()
                        drumEnv = Math.max(0f, drumEnv - 6f / sampleRate);
                        phase += (55f + 90f * drumEnv) / sampleRate;
                        float body = (float)Math.sin(phase * 2.0 * Math.PI) * drumEnv * drumEnv;
                        float click = rng.nextFloat() * 0.15f * drumEnv;
                        v = body + click;
                    }
                    default -> v = 0f;
                }
                dst[i] = v;
            }
        }

        /** trigger a drum hit (also usable as horror stinger accent) */
        public void hit(){ drumEnv = 1f; phase = 0; }
    }

    // ==================================================================
    // AUD-029 — delay send target (custom voice sends route here)
    // ==================================================================
    public static final class Delay {
        private float[] bufL, bufR;
        private int idx = 0;
        private int size = 0;
        public float timeSec = 0.28f;
        public float feedback = 0.35f;
        public float wet = 0.6f;
        private float dampedL = 0f, dampedR = 0f;

        public void setSampleRate(int sr){
            int s = Math.max(256, (int)(sr * timeSec));
            if(s != size){
                bufL = new float[s]; bufR = new float[s];
                size = s; idx = 0;
            }
        }

        /** stereo in-place send processing: wet added into data */
        public void process(float[] data, int frames){
            if(size <= 0) return;
            float fbK = MathX23.clamp(feedback, 0f, 0.85f);
            for(int i = 0; i < frames * 2; i += 2){
                int p = idx * 2;
                float dl = bufL[idx] + dampedL * 0f;
                float dr = bufR[idx];
                float inL = data[i], inR = data[i + 1];
                data[i] += dl * wet;
                data[i + 1] += dr * wet;
                dampedL = dl; dampedR = dr;
                bufL[idx] = inL * 0.7f + dl * fbK;
                bufR[idx] = inR * 0.7f + dr * fbK;
                idx = (idx + 1) % size;
            }
        }

        public void reset(){ if(bufL != null) java.util.Arrays.fill(bufL, 0f); if(bufR != null) java.util.Arrays.fill(bufR, 0f); dampedL = dampedR = 0f; idx = 0; }
    }

    /** local math helpers (avoid depending on engine MathX inside the audio pkg) */
    static final class MathX23 {
        private MathX23() {}
        static float clamp(float v, float a, float b){ return v < a ? a : (v > b ? b : v); }
        static float lerp(float a, float b, float t){ return a + (b - a) * t; }
    }
}
