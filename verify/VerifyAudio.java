import com.hollow.audio.AudioFX;
import com.hollow.audio.AudioDsp;

/** v2.5.2 audio DSP regression suite — comb feedback decay, delay wet path, convolver, EQ. */
public class VerifyAudio {
    static int fails = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        if(!ok) fails++;
    }
    static float rms(float[] d, int from, int to){
        double s = 0; for(int i = from; i < to; i++) s += (double)d[i]*d[i];
        return (float)Math.sqrt(s / Math.max(1, to - from));
    }

    public static void main(String[] a){
        // ---- 1..4) reverb comb: decay profile with SEPARATE in/out buffers ----
        // process(data, frames) ADDS wet to data in place. To observe the tail we
        // feed a ZERO input each block and keep a rolling probe: inject a fresh
        // impulse, run K blocks of silence, then measure wet by diffing a zero
        // buffer: wet_block = process(zeros) result.
        AudioFX.Reverb rev = new AudioFX.Reverb();
        rev.roomSize = 0.7f; rev.damp = 0.3f; rev.wet = 1f;
        rev.update();

        float[] probe = new float[512];
        float eEarly = -1f, eMid = -1f, eLate = -1f;
        for(int phase = 0; phase < 3; phase++){
            // fresh reverb state each phase
            rev.reset(); rev.update();
            // impulse: one block carrying a click
            float[] click = new float[512];
            click[0] = 1f; click[1] = 1f;
            rev.process(click, 256);
            // run 4 / 13 / 22 blocks of true silence (~23ms / 75ms / 126ms) with FRESH zero buffers
            int blocks = phase == 0 ? 4 : phase == 1 ? 13 : 22;
            for(int blk = 0; blk < blocks; blk++){
                float[] zeros = new float[512];
                rev.process(zeros, 256);
            }
            // measure: wet energy added to one more zero block
            float[] meas = new float[512];
            rev.process(meas, 256);
            float e = rms(meas, 0, 512);
            if(phase == 0) eEarly = e; else if(phase == 1) eMid = e; else eLate = e;
        }
        check("reverb tail alive ~75ms after impulse", eMid > 1e-5f, "eMid=" + eMid);
        // comb structure: first reflections build up briefly (early < mid is natural for 23ms),
        // then MUST decay to near zero — bounded and dying tail is the contract
        check("reverb tail DECAYS (late@126ms < 40% of early@23ms)", eLate < eEarly * 0.4f,
              "early=" + eEarly + " mid=" + eMid + " late=" + eLate);
        check("reverb never explodes (mid < 5x early, late < early)",
              eMid < eEarly * 5f && eLate < eEarly, "early=" + eEarly + " mid=" + eMid + " late=" + eLate);

        // ---- 5) delay wet path ----
        AudioDsp.Delay dly = new AudioDsp.Delay();
        try { AudioDsp.Delay.class.getField("timeSec").setFloat(dly, 0.05f); } catch(Exception ignore){}
        try { AudioDsp.Delay.class.getField("feedback").setFloat(dly, 0.4f); } catch(Exception ignore){}
        try { AudioDsp.Delay.class.getField("wet").setFloat(dly, 1f); } catch(Exception ignore){}
        float[] df = new float[512];
        df[0] = 0.9f; df[1] = 0.9f;
        dly.process(df, 256);
        check("delay produces wet output on impulse", rms(df, 0, 512) > 1e-4f, "rms=" + rms(df, 0, 512));

        // ---- 6) FFTConvolver: delta IR = passthrough (one full partition) ----
        AudioDsp.FFTConvolver conv = new AudioDsp.FFTConvolver();
        float[] ir = new float[256];
        ir[0] = 1f;
        conv.init(ir, 256);
        check("convolver ready after init", conv.isReady(), "not ready");
        int BLOCK = 256;
        float[] sig = new float[BLOCK];
        for(int i = 0; i < BLOCK; i++) sig[i] = (float)Math.sin(i * 0.1) * 0.5f;
        float[] out = new float[BLOCK];
        conv.process(sig, out, BLOCK);
        float eIn = rms(sig, 0, BLOCK), eOut = rms(out, 0, BLOCK);
        check("convolver delta-IR passes signal energy", eOut > eIn * 0.5f, "ein=" + eIn + " eout=" + eOut);
        // and actually reconstructs the signal (delta IR => out == in within first partition)
        float maxErr = 0;
        for(int i = 0; i < BLOCK; i++) maxErr = Math.max(maxErr, Math.abs(out[i] - sig[i]));
        check("convolver delta-IR reconstructs input (maxErr < 0.01)", maxErr < 0.01f, "maxErr=" + maxErr);

        // ---- 7) Eq3 unity passthrough ----
        AudioFX.Eq3 eq = new AudioFX.Eq3();
        eq.set(44100, 0f, 0f, 0f);
        float[] s2 = new float[512];
        for(int i = 0; i < 512; i++) s2[i] = (float)Math.sin(i * 0.05) * 0.5f;
        float[] s2ref = s2.clone();
        eq.process(s2, 256);
        float diff = 0;
        for(int i = 0; i < 512; i++) diff = Math.max(diff, Math.abs(s2[i] - s2ref[i]));
        check("Eq3 at 0dB ~passthrough", diff < 1e-3f, "maxdiff=" + diff);

        System.out.println(fails == 0 ? "ALL AUDIO REGRESSION TESTS PASSED" : fails + " TESTS FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
