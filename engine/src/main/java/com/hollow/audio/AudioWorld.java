package com.hollow.audio;

import com.hollow.core.AABB;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.List;

/** v2.3 world-audio systems (Batch 3):
 *  <pre>
 *  AUD-017 Doppler          relative-velocity pitch shift for spatial voices
 *  AUD-024 ReverbZone       scene-defined room presets with smooth cross-blend
 *  AUD-025 ListenerSmoother exponential listener follow (anti-jitter)
 *  AUD-030 SubtitleTrack    timed cues fired on the EventBus against the audio clock
 *  AUD-020  BusCVarBinds    bus volumes bound to audio.volume.* CVar names
 *  </pre>
 */
public final class AudioWorld {
    private AudioWorld() {}

    // ==================================================================
    // AUD-017 — doppler helper
    // ==================================================================
    public static final class Doppler {
        public float strength = 0.35f;          // 0 = off (HUD defaults keep 0.35 for feel)
        public float maxPitch = 1.35f, minPitch = 0.65f;
        public static final float SPEED_OF_SOUND = 343f;

        /** pitch multiplier for a source moving relative to the listener */
        public float pitchFor(Vec3 sourcePos, Vec3 sourceVel, Vec3 listenerPos, Vec3 listenerVel){
            Vec3 rel = sourcePos.cpy().sub(listenerPos);
            float dist = rel.len();
            if(dist < 1e-4f || strength <= 0f) return 1f;
            Vec3 dir = rel.scale(1f / dist);
            float radialSrc = sourceVel.dot(dir);       // toward listener = negative
            float radialLst = listenerVel.dot(dir);
            float relRadial = radialSrc - radialLst;    // >0 = separating
            // classic approximation: f' = f * (c + vL) / (c + vS) → simplified ratio form
            float numerator = SPEED_OF_SOUND + radialLst * strength * 4f;
            float denominator = SPEED_OF_SOUND + radialSrc * strength * 4f;
            if(denominator < 1f) denominator = 1f;
            float pitch = MathX23.clamp(numerator / denominator, minPitch, maxPitch);
            return pitch;
        }
    }

    // ==================================================================
    // AUD-024 — reverb zones
    // ==================================================================
    public static final class ReverbZone {
        public String name = "zone";
        public final AABB bounds;
        public float decaySec = 1.2f;            // room decay
        public float damping = 0.5f;
        public float wetLevel = 0.3f;
        public float fadeSec = 0.8f;             // crossfade into this zone

        public ReverbZone(String name, AABB bounds, float decaySec, float damping, float wetLevel){
            this.name = name; this.bounds = bounds;
            this.decaySec = decaySec; this.damping = damping; this.wetLevel = wetLevel;
        }

        public boolean contains(Vec3 p){ return bounds.contains(p); }
    }

    /** zone manager: picks the listener's zone and crossfades the engine reverb params */
    public static final class ReverbZoneManager {
        public final List<ReverbZone> zones = new ArrayList<>();
        private ReverbZone current;
        private float blend = 0f;                // 0 = engine default, 1 = zone params

        public void add(ReverbZone z){ zones.add(z); }
        public void clear(){ zones.clear(); current = null; blend = 0f; }

        /** per fixed step: evaluate zone membership + blend; writes effective params to engine reverb */
        public void update(AudioEngine engine, Vec3 listener, float dt){
            ReverbZone target = null;
            for(ReverbZone z : zones) if(z.contains(listener)){ target = z; break; }
            if(target != current){
                current = target;
                blend = 0f;
            }
            float fade = current != null ? Math.max(0.05f, current.fadeSec) : 0.8f;
            float goal = current != null ? 1f : 0f;
            blend += Math.signum(goal - blend) * dt / fade;
            blend = MathX23.clamp(blend, 0f, 1f);
            if(blend > 0f && current != null){
                engine.reverb.roomSize = MathX23.lerp(engine.reverb.roomSize, clamp01(current.decaySec / 4f), blend);
                engine.reverb.damp = MathX23.lerp(engine.reverb.damp, current.damping, blend);
                engine.reverb.wet = MathX23.lerp(engine.reverb.wet, current.wetLevel, blend);
            }
        }

        public ReverbZone current(){ return current; }
        public float blend(){ return blend; }
        private static float clamp01(float v){ return MathX23.clamp(v, 0f, 1f); }
    }

    // ==================================================================
    // AUD-025 — listener smoothing
    // ==================================================================
    public static final class ListenerSmoother {
        public float speed = 14f;                // higher = snappier
        public float snapDist = 8f;              // teleport threshold
        private final Vec3 pos = new Vec3();
        private final Vec3 fwd = new Vec3(0, 0, -1);
        private boolean started = false;

        /** feed the raw camera transform each frame; returns the smoothed listener state */
        public void update(Vec3 targetPos, Vec3 targetFwd, float dt, Vec3 outPos, Vec3 outFwd){
            if(!started || pos.dist(targetPos) > snapDist){
                pos.set(targetPos);
                fwd.set(targetFwd).norm();
                started = true;
            } else {
                float k = MathX23.clamp(speed * dt, 0f, 1f);
                pos.lerp(targetPos, k);
                fwd.lerp(targetFwd, k).norm();
            }
            outPos.set(pos);
            outFwd.set(fwd);
        }

        public void reset(){ started = false; pos.set(0, 0, 0); fwd.set(0, 0, -1); }
    }

    // ==================================================================
    // AUD-030 — subtitle sync
    // ==================================================================
    public static final class SubtitleTrack {
        public static final class Cue {
            public double start, end;
            public String text;
            public String style = "";              // speaker/style id for UI theming
            public Cue(double s, double e, String t, String style){ start = s; end = e; text = t; this.style = style; }
        }

        public final List<Cue> cues = new ArrayList<>();
        private int activeIdx = -1;
        public double clock = 0f;

        public void add(Cue c){ cues.add(c); cues.sort((a, b) -> Double.compare(a.start, b.start)); }
        public void clear(){ cues.clear(); activeIdx = -1; clock = 0f; }

        /** advance the clock and fire subtitle_show / subtitle_hide on the bus */
        public void update(AudioEngine engine, float dt){
            clock += dt;
            int newIdx = -1;
            for(int i = 0; i < cues.size(); i++){
                Cue c = cues.get(i);
                if(clock >= c.start && clock < c.end){ newIdx = i; break; }
                if(c.start > clock) break;
            }
            if(newIdx != activeIdx){
                com.hollow.game.GameApp g = com.hollow.game.GameApp.instance;
                if(g != null && g.services() != null && g.services().events != null){
                    if(activeIdx >= 0){
                        Cue old = cues.get(activeIdx);
                        java.util.Map<String,Object> hide = new java.util.LinkedHashMap<>();
                        hide.put("text", old.text);
                        hide.put("style", old.style);
                        g.services().events.fire("subtitle_hide", hide);
                    }
                    if(newIdx >= 0){
                        Cue cur = cues.get(newIdx);
                        java.util.Map<String,Object> show = new java.util.LinkedHashMap<>();
                        show.put("text", cur.text);
                        show.put("style", cur.style);
                        show.put("t", (double)clock);
                        g.services().events.fire("subtitle_show", show);
                    }
                }
                activeIdx = newIdx;
            }
        }

        /** bind the clock to a playing voice's playback position (dialogue WAV sync) */
        public void syncToClock(double seconds){ clock = seconds; }

        public Cue active(){ return activeIdx >= 0 && activeIdx < cues.size() ? cues.get(activeIdx) : null; }
    }

    // ==================================================================
    // AUD-020 — CVar binds for bus volumes
    // ==================================================================
    public static final class BusCVarBinds {
        public static final String[] CVAR_NAMES = {
            "audio.volume.master", "audio.volume.music", "audio.volume.sfx",
            "audio.volume.ambient", "audio.volume.voice" };
        private static final int[] BUS_IDS = {
            AudioEngine.BUS_MASTER, AudioEngine.BUS_MUSIC, AudioEngine.BUS_SFX,
            AudioEngine.BUS_AMBIENT, AudioEngine.BUS_VOICE };

        /** register the 5 volume CVars (idempotent) and push current engine values in */
        public static void register(AudioEngine engine){
            for(int i = 0; i < CVAR_NAMES.length; i++){
                if(com.hollow.core.CVar.get(CVAR_NAMES[i]) == null)
                    com.hollow.core.CVar.registerFloat(CVAR_NAMES[i], engine.busVolume[BUS_IDS[i]], "bus volume 0..4 (" + CVAR_NAMES[i] + ")");
            }
            apply(engine);
        }

        /** push CVar values into the engine (called on CVar change + startup) */
        public static void apply(AudioEngine engine){
            for(int i = 0; i < CVAR_NAMES.length; i++){
                com.hollow.core.CVar v = com.hollow.core.CVar.get(CVAR_NAMES[i]);
                if(v != null) engine.setBusVolume(BUS_IDS[i], v.getFloat());
            }
        }

        /** pull engine values back into CVars (for settings UI save) */
        public static void saveFrom(AudioEngine engine){
            for(int i = 0; i < CVAR_NAMES.length; i++){
                com.hollow.core.CVar v = com.hollow.core.CVar.get(CVAR_NAMES[i]);
                if(v != null) v.setFloat(engine.busVolume[BUS_IDS[i]]);
            }
        }
    }

    static final class MathX23 {
        private MathX23() {}
        static float clamp(float v, float a, float b){ return v < a ? a : (v > b ? b : v); }
        static float lerp(float a, float b, float t){ return a + (b - a) * t; }
    }
}
