package com.hollow.audio;

import com.hollow.assets.AudioClipData;
import com.hollow.assets.AssetDB;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;

import java.util.ArrayList;

/** Multi-bus audio mixer with 3D spatialization.
 *  Real implementation: voices mix into a 16-bit stereo block each update(dt) tick
 *  (called from the fixed engine step), then the block is pushed to the AudioOut sink.
 *  Headless (out == null): cursors still advance so clips finish and handles retire.
 *  Deterministic: no threads, no wall-clock, pure function of (voices, dt).
 *  API is FINAL: do not change signatures. */
public final class AudioEngine {
    public final float[] busVolume = {1f, 1f, 1f, 1f, 1f};   // master, music, sfx, ambient, voice

    public static final int BUS_MASTER = 0, BUS_MUSIC = 1, BUS_SFX = 2, BUS_AMBIENT = 3, BUS_VOICE = 4;
    public static final int MAX_VOICES = 24;

    public final Vec3 listenerPos = new Vec3();
    public final Vec3 listenerForward = new Vec3(0, 0, -1);
    public final Vec3 listenerUp = new Vec3(0, 1, 0);
    public final Vec3 listenerVelocity = new Vec3();   // v2.3 AUD-017 doppler listener velocity

    private AssetDB assets;
    private AudioOut out;
    private int sampleRate = 44100;
    private final ArrayList<Voice> voices = new ArrayList<>();
    private short[] mix = new short[8192];

    // ---- v2.0 DSP chain ----
    public final AudioFX.Reverb reverb = new AudioFX.Reverb();
    public final AudioFX.Eq3 eq = new AudioFX.Eq3();
    public final AudioFX.Compressor compressor = new AudioFX.Compressor();
    public final AudioFX.Ducker ducker = new AudioFX.Ducker();
    public boolean fxEnabled = true;
    /** reverb send level per bus (master excluded) */
    public final float[] busReverbSend = {0f, 0.05f, 0.15f, 0.35f, 0.1f};
    private float[] fMix = new float[8192];       // float master accumulate
    private float[] fWet = new float[8192];       // reverb send accumulate
    private float[] fDelay = new float[8192];     // v2.3 delay send accumulate

    // ---- v2.3 (Batch 3) ----
    public final com.hollow.audio.AudioWorld.Doppler doppler = new com.hollow.audio.AudioWorld.Doppler();   // AUD-017
    public boolean hrtfEnabled = false;                                                                     // AUD-018
    public final com.hollow.audio.AudioDsp.Binaural binaural = new com.hollow.audio.AudioDsp.Binaural();
    public final com.hollow.audio.AudioDsp.FFTConvolver convolver = new com.hollow.audio.AudioDsp.FFTConvolver();  // AUD-019
    public boolean convolutionReverb = false;             // true = convolver replaces freeverb
    public final com.hollow.audio.AudioDsp.Delay delay = new com.hollow.audio.AudioDsp.Delay();             // AUD-029
    public AudioStreams.AudioClipCache clipCache = null;  // AUD-027 (optional)
    public final com.hollow.audio.AudioWorld.ReverbZoneManager reverbZones = new com.hollow.audio.AudioWorld.ReverbZoneManager(); // AUD-024
    public final com.hollow.audio.AudioWorld.ListenerSmoother listenerSmoother = new com.hollow.audio.AudioWorld.ListenerSmoother(); // AUD-025
    public final com.hollow.audio.AudioStreams.FocusPolicy focusPolicy = new com.hollow.audio.AudioStreams.FocusPolicy(); // AUD-026
    public com.hollow.audio.AudioStreams.AudioFocusBridge focusBridge = null;
    private boolean focusGranted = true;
    private float[] convWetMono = new float[2048];   // AUD-019 scratch (no per-tick alloc)

    // scratch (avoid per-update allocation)
    private final Vec3 tmpRel = new Vec3();
    private final Vec3 tmpRight = new Vec3();

    public void init(AssetDB assets, AudioOut out, int sampleRate){
        this.assets = assets; this.out = out; this.sampleRate = Math.max(8000, sampleRate);
        voices.clear();
        eq.set(this.sampleRate, 0f, 0f, 0f);
        binaural.setSampleRate(this.sampleRate);
        delay.setSampleRate(this.sampleRate);
        if(out != null) out.open(this.sampleRate);
        Log.i("Audio", "initialized (out=" + (out == null ? "null/headless" : out.getClass().getSimpleName()) + ")");
    }
    public int sampleRate(){ return sampleRate; }

    /** v2.3 AUD-026: install + request focus; focus loss ducks music and stops buses */
    public void installFocus(com.hollow.audio.AudioStreams.AudioFocusBridge bridge){
        focusBridge = bridge;
        if(bridge == null) return;
        bridge.onFocusLost(() -> {
            focusGranted = false;
            setBusVolume(BUS_MUSIC, focusPolicy.musicDuck);
            if(focusPolicy.pauseVoices){
                stopBus(BUS_SFX); stopBus(BUS_AMBIENT); stopBus(BUS_VOICE);
            }
            Log.i("Audio", "focus lost — ducked");
        });
        focusGranted = bridge.requestFocus();
    }

    /** v2.3 AUD-026: regain focus (foreground resume) */
    public void refocus(){
        focusGranted = true;
        com.hollow.core.CVar master = com.hollow.core.CVar.get("audio.volume.master");
        if(master != null) setBusVolume(BUS_MASTER, master.getFloat());
        com.hollow.core.CVar music = com.hollow.core.CVar.get("audio.volume.music");
        if(music != null) setBusVolume(BUS_MUSIC, music.getFloat());
    }

    /** v2.3 AUD-025: smoothed listener (anti-jitter follow) */
    public void setListenerSmoothed(Vec3 targetPos, Vec3 targetFwd, Vec3 up, float dt){
        com.hollow.core.Vec3 sp = new com.hollow.core.Vec3();
        com.hollow.core.Vec3 sf = new com.hollow.core.Vec3();
        listenerSmoother.update(targetPos, targetFwd, dt, sp, sf);
        setListener(sp, sf, up);
    }

    /** update listener frame for 3D panning/attenuation */
    public void setListener(Vec3 pos, Vec3 forward, Vec3 up){
        if(pos != null) listenerPos.set(pos);
        if(forward != null && forward.len2() > 1e-8f) listenerForward.set(forward).norm();
        if(up != null && up.len2() > 1e-8f) listenerUp.set(up).norm();
    }

    public Handle play(String path){ return play(path, PlayParams.defaults()); }

    public Handle play(String path, PlayParams p){
        Handle h = new Handle();
        if(assets == null){ h.playing = false; return h; }
        AudioClipData clip;
        try {
            if(clipCache != null){                       // AUD-027 LRU decode cache
                clip = clipCache.get(path);
                if(clip == null){
                    clip = assets.audio(path);
                    if(clip != null && clip.pcm != null && clip.pcm.length > 0) clipCache.put(path, clip);
                }
            } else {
                clip = assets.audio(path);
            }
        }
        catch(Exception e){ Log.w("Audio", "play: clip load failed " + path + ": " + e.getMessage()); h.playing = false; return h; }
        if(clip == null || clip.pcm == null || clip.pcm.length == 0){
            Log.w("Audio", "play: empty/missing clip " + path);
            h.playing = false; return h;
        }
        PlayParams pp = p != null ? p : PlayParams.defaults();
        // voice limit — newest wins, oldest active voice is retired
        while(voices.size() >= MAX_VOICES){
            Voice old = voices.remove(0);
            if(old.handle != null) old.handle.playing = false;
        }
        Voice v = new Voice();
        v.clip = clip;
        v.p = pp;
        v.handle = h;
        v.baseVolume = Math.max(0f, pp.volume);
        v.bus = MathX.clamp(pp.bus, 0, busVolume.length - 1);
        v.position.set(pp.position);
        v.cursor = 0;
        v.dead = false;
        v.finished = false;
        h.voice = v;
        h.playing = true;
        voices.add(v);
        Log.d("Audio", "play " + path + " (" + clip.durationSeconds() + "s, voices=" + voices.size() + ")");
        return h;
    }

    public void stopBus(int bus){
        for(int i = 0; i < voices.size(); i++){
            Voice v = voices.get(i);
            if(v.bus == bus) v.dead = true;
        }
    }
    public void stopAll(){
        for(int i = 0; i < voices.size(); i++) voices.get(i).dead = true;
    }
    public void setBusVolume(int bus, float v){
        if(bus >= 0 && bus < busVolume.length) busVolume[bus] = MathX.clamp(v, 0f, 4f);
    }
    public int activeVoices(){ return voices.size(); }

    /** mix one fixed step: advance all voices, mix into stereo 16-bit block, push to sink */
    public void update(float dt){
        if(dt <= 0) return;
        updateMusicFade(dt);
        reverbZones.update(this, listenerPos, dt);        // AUD-024 zone blend
        int frames = Math.round(sampleRate * dt);
        frames = MathX.clamp(frames, 1, sampleRate);
        // retire dead voices first (Handle.stop() kills voice next update)
        for(int i = voices.size() - 1; i >= 0; i--){
            Voice v = voices.get(i);
            if(v.dead){
                voices.remove(i);
                if(v.handle != null) v.handle.playing = false;
            }
        }
        if(out == null){
            // headless: just advance cursors so non-loop clips finish deterministically
            for(int i = voices.size() - 1; i >= 0; i--){
                Voice v = voices.get(i);
                advance(v, frames);
                if(v.finished) retire(i);
            }
            return;
        }
        int needed = frames * 2;
        if(mix.length < needed) mix = new short[Math.max(needed, mix.length * 2)];
        if(fMix.length < needed) fMix = new float[Math.max(needed, fMix.length * 2)];
        if(fWet.length < needed) fWet = new float[Math.max(needed, fWet.length * 2)];
        if(fDelay.length < needed) fDelay = new float[Math.max(needed, fDelay.length * 2)];
        java.util.Arrays.fill(mix, 0, needed, (short) 0);
        java.util.Arrays.fill(fMix, 0, needed, 0f);
        java.util.Arrays.fill(fWet, 0, needed, 0f);
        java.util.Arrays.fill(fDelay, 0, needed, 0f);
        boolean voiceActive = false;
        for(int i = voices.size() - 1; i >= 0; i--){
            Voice v = voices.get(i);
            mixVoice(v, frames, fMix, fWet);
            if(v.bus == BUS_VOICE && !v.finished) voiceActive = true;
            if(v.finished) retire(i);
        }
        // ---- v2.0 post chain: reverb -> EQ -> compressor -> ducking ----
        if(fxEnabled){
            if(convolutionReverb && convolver.isReady()){
                // AUD-019: mono-sum wet through the FFT convolver (partitioned overlap-add)
                if(convWetMono.length < frames) convWetMono = new float[frames];
                for(int s = 0; s < frames; s++) convWetMono[s] = fWet[s * 2] + fWet[s * 2 + 1];
                convolver.process(convWetMono, convWetMono, frames);
                for(int s = 0; s < frames; s++){ fWet[s * 2] = convWetMono[s]; fWet[s * 2 + 1] = convWetMono[s]; }
            } else {
                reverb.process(fWet, frames);
            }
            float musicGain = ducker.update(voiceActive, sampleRate);
            for(int s = 0; s < needed; s++) fMix[s] += fWet[s];
            delay.process(fMix, frames);                 // AUD-029 send bus
            eq.process(fMix, frames);
            compressor.process(fMix, frames, sampleRate);
            nextMusicGain = busVolume[BUS_MUSIC] * musicGain;
        } else {
            for(int s = 0; s < needed; s++) fMix[s] += fWet[s];
            delay.process(fMix, frames);
            nextMusicGain = busVolume[BUS_MUSIC];
        }
        for(int s = 0; s < needed; s++){
            int v = (int) MathX.clamp(fMix[s] * 32767f, Short.MIN_VALUE, Short.MAX_VALUE);
            mix[s] = (short) v;
        }
        try { out.write(mix, frames); }
        catch(Exception e){ Log.w("Audio", "sink write failed: " + e.getMessage()); }
    }

    private float nextMusicGain = 1f;   // ducking applied at voice-mix time (next block)

    public void shutdown(){
        voices.clear();
        if(out != null){ try { out.close(); } catch(Exception e){ Log.w("Audio", "close failed: " + e.getMessage()); } }
    }

    // ---------------- internals ----------------

    private void retire(int index){
        Voice v = voices.remove(index);
        if(v.handle != null) v.handle.playing = false;
    }

    private void advance(Voice v, int frames){
        AudioClipData c = v.clip;
        int totalFrames = c.pcm.length / (c.channels == 2 ? 2 : 1);
        double step = (double) c.sampleRate / sampleRate;
        v.cursor += step * frames;
        if(v.cursor >= totalFrames){
            if(v.p.loop){ v.cursor %= Math.max(1, totalFrames); }
            else v.finished = true;
        }
    }

    /** mix one voice block into float master + reverb send; v2.3: doppler + occlusion + HRTF + loop crossfade */
    private void mixVoice(Voice v, int frames, float[] dst, float[] wet){
        AudioClipData c = v.clip;
        int ch = c.channels == 2 ? 2 : 1;
        int totalFrames = c.pcm.length / ch;
        double step = (double) c.sampleRate / sampleRate;

        // bus chain: channel bus * master; music bus additionally ducked (v2.0)
        float busVol = v.bus == BUS_MUSIC ? nextMusicGain : busVolume[v.bus];
        float gain = v.baseVolume * busVol;
        if(v.bus != BUS_MASTER) gain *= busVolume[BUS_MASTER];
        float send = v.bus != BUS_MASTER ? busReverbSend[Math.min(v.bus, busReverbSend.length - 1)] : 0f;

        float gl = 1f, gr = 1f;
        float pitchMul = 1f;
        float occlusion = v.occlusion;
        if(v.p.spatial){
            Vec3 rel = tmpRel.set(v.position).sub(listenerPos);
            float dist = rel.len();
            if(dist > v.p.maxDist) return;                 // cutoff beyond max distance
            float atten = (float) Math.pow(MathX.clamp(v.p.minDist / Math.max(dist, 1e-4f), 0f, 1f), v.p.rolloff);
            // equal-power pan: project relative direction on listener right axis
            Vec3 right = tmpRight.set(listenerForward).cross(listenerUp).norm();
            float pan = dist > 1e-4f ? MathX.clamp(rel.norm().dot(right), -1f, 1f) : 0f;
            if(hrtfEnabled){
                // AUD-018: HRTF-lite per-sample panner runs below (block-level prep here)
                gl = atten; gr = atten;
                v.hrtfAz = pan;
                v.hrtfDist = dist;
                v.hrtfActive = true;
            } else {
                v.hrtfActive = false;
                float theta = (pan * 0.5f + 0.5f) * (float) (Math.PI * 0.5);
                gl = (float) Math.cos(theta) * atten;
                gr = (float) Math.sin(theta) * atten;
            }
            // AUD-017 doppler: relative-velocity pitch shift
            pitchMul = doppler.pitchFor(v.position, v.velocity, listenerPos, listenerVelocity);
        } else {
            v.hrtfActive = false;
        }
        float lgain = gain * gl, rgain = gain * gr;
        double sstep = step * pitchMul;
        // AUD-028 seamless loop crossfade window (in clip frames)
        int fadeFrames = v.p.loopCrossfadeSec > 0f ? (int)(v.p.loopCrossfadeSec * c.sampleRate) : 0;
        fadeFrames = Math.min(fadeFrames, Math.max(1, totalFrames / 8));

        for(int f = 0; f < frames; f++){
            if(v.cursor >= totalFrames){
                if(v.p.loop){ v.cursor -= totalFrames; }
                else { v.finished = true; break; }
            }
            int i0 = (int) v.cursor;
            float frac = (float) (v.cursor - i0);
            int i1 = i0 + 1 < totalFrames ? i0 + 1 : (v.p.loop ? 0 : i0);
            float sl, sr, sl1, sr1;
            if(ch == 1){
                sl = sr = c.pcm[i0]; sl1 = sr1 = c.pcm[i1];
            } else {
                sl = c.pcm[i0 * 2]; sr = c.pcm[i0 * 2 + 1];
                sl1 = c.pcm[i1 * 2]; sr1 = c.pcm[i1 * 2 + 1];
            }
            float l = (sl + (sl1 - sl) * frac) * lgain;
            float r = (sr + (sr1 - sr) * frac) * rgain;
            // AUD-028: crossfade the loop seam — blend the tail with the head
            if(v.p.loop && fadeFrames > 0 && v.cursor > totalFrames - fadeFrames){
                float t = (float)((v.cursor - (totalFrames - fadeFrames)) / fadeFrames);
                int j0 = i0 < fadeFrames ? i0 : i0 - fadeFrames;
                if(j0 < 0) j0 = 0;
                if(ch == 1){
                    l = lerpF(l, c.pcm[j0] * lgain, t);
                    r = lerpF(r, c.pcm[j0] * rgain, t);
                } else {
                    l = lerpF(l, c.pcm[j0 * 2] * lgain, t);
                    r = lerpF(r, c.pcm[j0 * 2 + 1] * rgain, t);
                }
            }
            // AUD-018: HRTF-lite replaces static pan (ITD+ILD in the Binaural block)
            if(v.hrtfActive){
                binauralScratch[0] = l; binauralScratch[1] = r;
                binaural.process((l + r) * 0.5f, v.hrtfAz, v.hrtfDist, binauralScratch);
                l = binauralScratch[0]; r = binauralScratch[1];
            }
            int mi = f * 2;
            dst[mi] += l;
            dst[mi + 1] += r;
            // AUD-029: per-voice delay send
            float ds = v.p.delaySend;
            if(ds > 0f){
                fDelay[mi] += l * ds;
                fDelay[mi + 1] += r * ds;
            }
            if(send > 0f){
                wet[mi] += l * send;
                wet[mi + 1] += r * send;
            }
            v.cursor += sstep;
        }
        // AUD-023: wall occlusion — lowpass + gain on this voice's contribution
        if(occlusion > 0.01f){
            v.occlFilter.process(occlusion, dst, frames, sampleRate);
        }
    }

    private static float lerpF(float a, float b, float t){ return a + (b - a) * t; }
    private final float[] binauralScratch = new float[2];   // AUD-018 per-sample out

    // ---------------- handle + params (public API identical to stub) ----------------

    public static final class Handle {
        public volatile boolean playing = true;
        Voice voice;   // package-private link to mixer voice
        public void stop(){ playing = false; if(voice != null) voice.dead = true; }
        public void setVolume(float v){ if(voice != null) voice.baseVolume = Math.max(0f, v); }
        public boolean isPlaying(){ return playing && (voice == null || !voice.dead); }
        /** v2.3 AUD-030: playback position in seconds (subtitle/dialogue sync) */
        public double timeSeconds(){
            if(voice == null || voice.clip == null) return 0;
            return voice.cursor / Math.max(1, voice.clip.sampleRate);
        }
        /** v2.3 AUD-023: set wall occlusion 0..1 (AudioSource updates per tick) */
        public void setOcclusion(float o){ if(voice != null) voice.occlusion = MathX23.clamp(o, 0f, 1f); }
        /** v2.3 AUD-017: live velocity for doppler (AudioSource refreshes) */
        public void setVelocity(com.hollow.core.Vec3 vel){ if(voice != null && vel != null) voice.velocity.set(vel); }
    }

    public static final class PlayParams {
        public int bus = BUS_SFX;
        public float volume = 1f;
        public boolean loop = false;
        public boolean spatial = false;
        public final Vec3 position = new Vec3();
        public float minDist = 1f, maxDist = 25f, rolloff = 1.4f;
        // v2.3 (Batch 3)
        public float occlusion = 0f;                 // AUD-023 wall block 0..1
        public float loopCrossfadeSec = 0f;          // AUD-028 seamless loop seam
        public float delaySend = 0f;                 // AUD-029 custom send amount
        public final Vec3 velocity = new Vec3();     // AUD-017 doppler source velocity

        public static PlayParams defaults(){ return new PlayParams(); }
        public PlayParams bus(int b){ bus = b; return this; }
        public PlayParams volume(float v){ volume = v; return this; }
        public PlayParams loop(){ loop = true; return this; }
        public PlayParams at(Vec3 pos){ spatial = true; position.set(pos); return this; }
        public PlayParams range(float min, float max){ minDist = min; maxDist = max; return this; }
        public PlayParams occlusion(float o){ occlusion = o; return this; }
        public PlayParams loopCrossfade(float sec){ loopCrossfadeSec = sec; return this; }
        public PlayParams delaySend(float amt){ delaySend = amt; return this; }
        public PlayParams velocity(Vec3 v){ velocity.set(v); return this; }
    }

    static final class MathX23 {
        static float clamp(float v, float a, float b){ return v < a ? a : (v > b ? b : v); }
    }

    /** one playing clip instance */
    static final class Voice {
        AudioClipData clip;
        PlayParams p;
        Handle handle;
        double cursor;          // position inside clip, in clip frames (clip sampleRate domain)
        float baseVolume = 1f;
        int bus = BUS_SFX;
        final Vec3 position = new Vec3();   // live 3D position (refreshed by AudioSource)
        boolean dead;
        boolean finished;
        // v2.3 per-voice state
        float occlusion = 0f;
        final Vec3 velocity = new Vec3();
        boolean hrtfActive;
        float hrtfAz, hrtfDist, hrtfL, hrtfR;
        final AudioDsp.OcclusionFilter occlFilter = new AudioDsp.OcclusionFilter();
    }

    // ==================================================================
    // v2.0: music crossfade + offline render
    // ==================================================================

    /** starts music on the music bus with a linear fade-in; previous music fades out over the same time */
    private Handle musicA = null, musicB = null;
    private float musicFade = -1f, musicFadeT = 0f;

    public Handle playMusic(String path, float fadeSeconds){
        Handle old = musicA != null && musicA.isPlaying() ? musicA : musicB;
        Handle h = play(path, PlayParams.defaults().bus(BUS_MUSIC).loop());
        if(old != null && fadeSeconds > 0f){
            musicB = old;                    // fading out
            musicA = h;
            musicFade = Math.max(0.05f, fadeSeconds);
            musicFadeT = 0f;
        } else {
            if(old != null) old.stop();
            musicA = h;
            musicB = null;
            musicFade = -1f;
        }
        return h;
    }

    /** advance crossfade bookkeeping (called from update()) */
    private void updateMusicFade(float dt){
        if(musicFade <= 0f || (musicA == null && musicB == null)) return;
        musicFadeT += dt;
        float t = MathX.clamp(musicFadeT / musicFade, 0f, 1f);
        if(musicA != null && musicA.isPlaying()) musicA.setVolume(t);
        if(musicB != null && musicB.isPlaying()) musicB.setVolume(1f - t);
        if(t >= 1f){
            if(musicB != null){ musicB.stop(); musicB = null; }
            musicFade = -1f;
        }
    }

    /** one scheduled clip for offline rendering */
    public static final class Cue {
        public final String path;
        public final double startSec;
        public final PlayParams params;
        public Cue(String path, double startSec, PlayParams params){
            this.path = path; this.startSec = startSec; this.params = params;
        }
    }

    /** collecting sink for offline rendering */
    public static final class CollectOut implements AudioOut {
        public final java.util.ArrayList<short[]> blocks = new java.util.ArrayList<>();
        public int sampleRate = 44100;
        @Override public void open(int sr){ sampleRate = sr; }
        @Override public void write(short[] data, int frames){
            short[] c = new short[frames * 2];
            System.arraycopy(data, 0, c, 0, c.length);
            blocks.add(c);
        }
        @Override public void close(){}
        public int totalFrames(){ int n = 0; for(short[] b : blocks) n += b.length / 2; return n; }
        /** de-interleave into stereo short arrays [L][R] */
        public short[] toInterleaved(){
            short[] out = new short[totalFrames() * 2];
            int o = 0;
            for(short[] b : blocks){ System.arraycopy(b, 0, out, o, b.length); o += b.length; }
            return out;
        }
    }

    /** deterministic offline mixdown (CLI `hollow audio-render`) — renders `seconds` of
     *  the scheduled cues through the FULL v2.0 chain (spatialization, reverb, EQ,
     *  compressor, ducking). Returns interleaved 16-bit stereo PCM. */
    public static short[] renderOffline(AssetDB assets, int sampleRate, java.util.List<Cue> cues, double seconds){
        AudioEngine eng = new AudioEngine();
        CollectOut sink = new CollectOut();
        eng.init(assets, sink, sampleRate);
        final double STEP = 1.0 / 60.0;
        java.util.ArrayList<Cue> pending = new java.util.ArrayList<>(cues);
        pending.sort((a, b) -> Double.compare(a.startSec, b.startSec));
        double t = 0;
        while(t < seconds){
            while(!pending.isEmpty() && pending.get(0).startSec <= t + 1e-9){
                Cue c = pending.remove(0);
                eng.play(c.path, c.params != null ? c.params : PlayParams.defaults());
            }
            eng.updateMusicFade((float) STEP);
            eng.update((float) STEP);
            t += STEP;
        }
        return sink.toInterleaved();
    }
}
