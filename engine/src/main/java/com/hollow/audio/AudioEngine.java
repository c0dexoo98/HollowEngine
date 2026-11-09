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

    private AssetDB assets;
    private AudioOut out;
    private int sampleRate = 44100;
    private final ArrayList<Voice> voices = new ArrayList<>();
    private short[] mix = new short[8192];

    // scratch (avoid per-update allocation)
    private final Vec3 tmpRel = new Vec3();
    private final Vec3 tmpRight = new Vec3();

    public void init(AssetDB assets, AudioOut out, int sampleRate){
        this.assets = assets; this.out = out; this.sampleRate = Math.max(8000, sampleRate);
        voices.clear();
        if(out != null) out.open(this.sampleRate);
        Log.i("Audio", "initialized (out=" + (out == null ? "null/headless" : out.getClass().getSimpleName()) + ")");
    }
    public int sampleRate(){ return sampleRate; }

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
        try { clip = assets.audio(path); }
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
        java.util.Arrays.fill(mix, 0, needed, (short) 0);
        for(int i = voices.size() - 1; i >= 0; i--){
            Voice v = voices.get(i);
            mixVoice(v, frames);
            if(v.finished) retire(i);
        }
        try { out.write(mix, frames); }
        catch(Exception e){ Log.w("Audio", "sink write failed: " + e.getMessage()); }
    }

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

    /** mix one voice block into mix[]; per-block gain/pan (smooth enough at 30-120 Hz updates) */
    private void mixVoice(Voice v, int frames){
        AudioClipData c = v.clip;
        int ch = c.channels == 2 ? 2 : 1;
        int totalFrames = c.pcm.length / ch;
        double step = (double) c.sampleRate / sampleRate;

        // bus chain: channel bus * master
        float gain = v.baseVolume * busVolume[v.bus];
        if(v.bus != BUS_MASTER) gain *= busVolume[BUS_MASTER];

        float gl = 1f, gr = 1f;
        if(v.p.spatial){
            Vec3 rel = tmpRel.set(v.position).sub(listenerPos);
            float dist = rel.len();
            if(dist > v.p.maxDist) return;                 // cutoff beyond max distance
            float atten = (float) Math.pow(MathX.clamp(v.p.minDist / Math.max(dist, 1e-4f), 0f, 1f), v.p.rolloff);
            // equal-power pan: project relative direction on listener right axis
            Vec3 right = tmpRight.set(listenerForward).cross(listenerUp).norm();
            float pan = dist > 1e-4f ? MathX.clamp(rel.norm().dot(right), -1f, 1f) : 0f;
            float theta = (pan * 0.5f + 0.5f) * (float) (Math.PI * 0.5);
            gl = (float) Math.cos(theta) * atten;
            gr = (float) Math.sin(theta) * atten;
        }
        float lgain = gain * gl, rgain = gain * gr;

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
            int mi = f * 2;
            int L = (int) mix[mi] + (int) (l * 32767f);
            int R = (int) mix[mi + 1] + (int) (r * 32767f);
            mix[mi] = (short) MathX.clamp(L, Short.MIN_VALUE, Short.MAX_VALUE);
            mix[mi + 1] = (short) MathX.clamp(R, Short.MIN_VALUE, Short.MAX_VALUE);
            v.cursor += step;
        }
    }

    // ---------------- handle + params (public API identical to stub) ----------------

    public static final class Handle {
        public volatile boolean playing = true;
        Voice voice;   // package-private link to mixer voice
        public void stop(){ playing = false; if(voice != null) voice.dead = true; }
        public void setVolume(float v){ if(voice != null) voice.baseVolume = Math.max(0f, v); }
        public boolean isPlaying(){ return playing && (voice == null || !voice.dead); }
    }

    public static final class PlayParams {
        public int bus = BUS_SFX;
        public float volume = 1f;
        public boolean loop = false;
        public boolean spatial = false;
        public final Vec3 position = new Vec3();
        public float minDist = 1f, maxDist = 25f, rolloff = 1.4f;

        public static PlayParams defaults(){ return new PlayParams(); }
        public PlayParams bus(int b){ bus = b; return this; }
        public PlayParams volume(float v){ volume = v; return this; }
        public PlayParams loop(){ loop = true; return this; }
        public PlayParams at(Vec3 pos){ spatial = true; position.set(pos); return this; }
        public PlayParams range(float min, float max){ minDist = min; maxDist = max; return this; }
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
    }
}
