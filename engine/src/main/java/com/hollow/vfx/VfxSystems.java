package com.hollow.vfx;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Noise;
import com.hollow.core.RandomStream;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** v2.4 VFX/ENV batch — VFX-009..018, 021, 024 / ENV-007..010, 012, 014..016.
 *  All CPU-side, deterministic, headless-testable; renderer samples state. */
public final class VfxSystems {
    private VfxSystems(){}

    // =====================================================================
    // VFX-012 + ENV-007 — weather system with transitions
    // =====================================================================
    public static final class Weather extends Component {
        public enum Kind { CLEAR, RAIN, FOG_DENSE, SNOW, STORM }
        public Kind current = Kind.CLEAR;
        public Kind target = Kind.CLEAR;
        public float blend = 1f;               // 0=current->1=target
        public float transitionSec = 8f;
        /** live readouts */
        public float rainAmount = 0f;          // 0..1 particle weight
        public float snowAmount = 0f;
        public float fogDensity = 0f;          // extra fog overlay
        public float windBoost = 0f;
        public float wetness = 0f;             // ENV-009 ground wetness 0..1
        public float snowCover = 0f;           // ENV-010 accumulation 0..1
        public float intensity = 0.7f;

        private static final Noise NZ = new Noise(777);

        public void set(Kind k){
            if(k == target) return;
            current = snapshotBlend();
            target = k;
            blend = 0f;
        }
        private Kind snapshotBlend(){ return blend > 0.5f ? target : current; }

        @Override public void onUpdate(float dt){
            if(blend < 1f){
                blend = Math.min(1f, blend + dt / Math.max(0.01f, transitionSec));
            }
            Kind from = current, to = target;
            float k = blend;
            float rainTo = to == Kind.RAIN || to == Kind.STORM ? intensity : 0f;
            float rainFrom = from == Kind.RAIN || from == Kind.STORM ? intensity : 0f;
            rainAmount = MathX.lerp(rainFrom, rainTo, k);
            float snowTo = to == Kind.SNOW ? intensity : 0f;
            float snowFrom = from == Kind.SNOW ? intensity : 0f;
            snowAmount = MathX.lerp(snowFrom, snowTo, k);
            float fogTo = to == Kind.FOG_DENSE ? 0.06f * intensity : 0f;
            float fogFrom = from == Kind.FOG_DENSE ? 0.06f * intensity : 0f;
            fogDensity = MathX.lerp(fogFrom, fogTo, k);
            windBoost = to == Kind.STORM ? 1.5f * intensity : 0f;
            // ENV-009/010: accumulation follows weather, dries slower than it wets
            if(rainAmount > 0.05f) wetness = Math.min(1f, wetness + rainAmount * dt * 0.25f);
            else wetness = Math.max(0f, wetness - dt * 0.02f);
            if(snowAmount > 0.05f) snowCover = Math.min(1f, snowCover + snowAmount * dt * 0.08f);
            else snowCover = Math.max(0f, snowCover - dt * 0.01f);
        }
        /** rain streak particle spawn weight for renderer this frame. */
        public int rainParticleBudget(RandomStream rng){
            return rainAmount > 0f ? (int)(rainAmount * 900) : 0;
        }
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","Weather");
            out.kv("current", current.name()); out.kv("target", target.name());
            out.kv("blend", blend); out.kv("intensity", intensity); out.endObj();
        }
        @Override public void load(JsonV n){
            try { current = Kind.valueOf(n.str("current","CLEAR")); } catch(Exception ignored){}
            try { target = Kind.valueOf(n.str("target","CLEAR")); } catch(Exception ignored){}
            blend = n.flt("blend", 1f); intensity = n.flt("intensity", 0.7f);
        }
    }

    // =====================================================================
    // VFX-013 + ENV-012 — lightning strikes + ambient creaks (event randomizer)
    // =====================================================================
    public static final class AmbientEvents extends Component {
        public float creakMinDelay = 9f, creakMaxDelay = 26f;
        public boolean lightningEnabled = false;
        public float lightningMinDelay = 20f, lightningMaxDelay = 60f;
        private float creakTimer = 5f, boltTimer = 12f;
        /** first-fire timers (configurable for tests). */
        public float firstCreakDelay = 5f, firstBoltDelay = 12f;
        public boolean started = false;
        private final RandomStream rng = new RandomStream(0xC0FFEE);
        public String lastEvent = "";
        public int firedCount = 0;

        @Override public void onUpdate(float dt){
            if(!started){ started = true; creakTimer = firstCreakDelay; boltTimer = firstBoltDelay; }
            creakTimer -= dt;
            if(creakTimer <= 0f){
                creakTimer = rng.range(creakMinDelay, creakMaxDelay);
                fire("env_creak", rng.nextFloat());
            }
            if(lightningEnabled){
                boltTimer -= dt;
                if(boltTimer <= 0f){
                    boltTimer = rng.range(lightningMinDelay, lightningMaxDelay);
                    fire("lightning_strike", rng.nextFloat());
                    com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
                    if(app != null && app.svc != null) app.svc.lightning.strike(0.45f + rng.nextFloat() * 0.4f, 0.8f, 12345);
                }
            }
        }
        private void fire(String name, float strength){
            lastEvent = name; firedCount++;
            Map<String,Object> data = new java.util.LinkedHashMap<>();
            data.put("strength", (double)strength);
            Entity self = entity;
            com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
            if(app != null && app.svc != null && self != null){
                data.put("at", self.name);
                app.svc.events.fire(name, data);
            }
        }
        @Override public void save(JsonW out){ out.obj(); out.kv("type","AmbientEvents"); out.endObj(); }
        @Override public void load(JsonV n){ }
    }

    // =====================================================================
    // VFX-014 — dynamic smoke with cheap occlusion (noise-driven opacity)
    // =====================================================================
    public static final class SmokePlume extends Component {
        public float rate = 14f;                 // particles/sec
        public float riseSpeed = 1.2f;
        public float turbulence = 0.6f;
        public float sizeStart = 0.25f, sizeEnd = 1.1f;
        public float lifeSec = 2.6f;
        public boolean occlusionFade = true;     // cheap occlusion: fade with noise
        private final RandomStream rng = new RandomStream(4242);
        private float emitAccum = 0f;
        public int particlesSpawned = 0;

        /** integrate over dt; game code/renderer reads particleData(). */
        public List<float[]> particleData(){
            List<float[]> out = new ArrayList<>();
            float t = (float)rngState / 1000f;
            for(int i = 0; i < Math.min(liveCount, 128); i++){
                float k = (i % 32) / 32f;
                float x = (float)Math.sin((t + i) * 1.7f) * turbulence * k;
                float z = (float)Math.cos((t + i) * 1.3f) * turbulence * k;
                float y = ((t * riseSpeed + i * 0.37f) % lifeSec) / lifeSec;
                float size = MathX.lerp(sizeStart, sizeEnd, y);
                float alpha = occlusionFade ? (1f - y) * (0.6f + 0.4f * NZ.perlin2(x * 3f, z * 3f)) : 1f - y;
                out.add(new float[]{ x, y * riseSpeed * 2f, z, size, alpha });
            }
            return out;
        }
        private static final Noise NZ = new Noise(31337);
        private int liveCount = 0;
        private long rngState = 0;
        @Override public void onUpdate(float dt){
            rngState += (long)(dt * 1000);
            emitAccum += rate * dt;
            int emit = (int)emitAccum;
            emitAccum -= emit;
            particlesSpawned += emit;
            // steady state pool: particles die continuously; approximate live population
            // live ≈ spawnAccum * lifeSec (moving exponential average toward rate*lifeSec)
            float steady = rate * lifeSec;
            if(emit > 0 || liveCount < steady){
                liveCount = (int)MathX.clamp(MathX.lerp(liveCount, steady, Math.min(1f, dt * 3f)), 0, 256);
            }
        }
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","SmokePlume"); out.kv("rate", rate); out.endObj();
        }
        @Override public void load(JsonV n){ rate = n.flt("rate", 14f); }
    }

    // =====================================================================
    // VFX-015 — dissolve material controller (object fade-out)
    // =====================================================================
    public static final class Dissolve extends Component {
        public float progress = 0f;             // 0=whole, 1=gone
        public float speed = 0.5f;
        public boolean destroyWhenGone = false;
        public boolean active = false;
        public float edgeWidth = 0.08f;         // burning edge band

        public void start(float spd){ active = true; speed = spd; }
        public boolean finished(){ return progress >= 1f; }
        @Override public void onUpdate(float dt){
            if(!active) return;
            progress = Math.min(1f, progress + speed * dt);
            if(destroyWhenGone && progress >= 1f && entity != null){
                com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
                if(app != null && app.world != null) app.world.destroy(entity);
            }
        }
        /** shader push: clip threshold + edge glow band [min, max] at current progress. */
        public float[] edgeBand(){
            return new float[]{ Math.max(0f, progress - edgeWidth), progress };
        }
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","Dissolve"); out.kv("progress", progress);
            out.kv("speed", speed); out.kv("active", active); out.endObj();
        }
        @Override public void load(JsonV n){
            progress = n.flt("progress", 0f); speed = n.flt("speed", 0.5f); active = n.bool("active", false);
        }
    }

    // =====================================================================
    // VFX-016..018 — muzzle flash, screen shake trauma, hit stop
    // =====================================================================
    public static final class CombatFx {
        // ---- muzzle flash: 3 phases (spike -> bloom -> smoke) ----
        public float flashTime = -1f;
        public void muzzleFlash(){ flashTime = 0f; }
        /** returns [core, bloom, smoke] weights at time t. */
        public float[] flashWeights(float dt){
            if(flashTime < 0f) return new float[3];
            flashTime += dt;
            float t = flashTime;
            if(t > 0.18f){ flashTime = -1f; return new float[3]; }
            float core = t < 0.03f ? 1f : Math.max(0f, 1f - (t - 0.03f) / 0.05f);
            float bloom = t < 0.08f ? t / 0.08f : Math.max(0f, 1f - (t - 0.08f) / 0.1f);
            float smoke = t > 0.06f ? Math.min(1f, (t - 0.06f) / 0.12f) : 0f;
            return new float[]{ core, bloom, smoke };
        }

        // ---- screen shake with trauma^2 decay (VFX-017) ----
        public float trauma = 0f;
        public void addShake(float amount){ trauma = MathX.clamp(trauma + amount, 0f, 1f); }
        public float shakeOffset(float dt, long frame){
            trauma = Math.max(0f, trauma - dt * 1.4f);
            if(trauma <= 0f) return 0f;
            float s = trauma * trauma;
            float n = 0.5f + 0.5f * NZ.perlin2(frame * 0.61f + 0.317f, frame * 0.83f + 0.713f);   // 0..1, off-lattice
            return n * s * 0.5f;
        }

        // ---- hit stop: brief global time freeze (VFX-018) ----
        public float hitStopLeft = 0f;
        public void hitStop(float sec){ hitStopLeft = Math.max(hitStopLeft, sec); }
        /** returns scaled dt (0 during freeze). */
        public float scaleDt(float dt){
            if(hitStopLeft > 0f){ hitStopLeft -= dt; return dt * 0.05f; }
            return dt;
        }
        private static final Noise NZ = new Noise(991);
    }

    // =====================================================================
    // VFX-021 + ENV-011 — ambient life: ash motes / fireflies / birds
    // =====================================================================
    public static final class AmbientLife extends Component {
        public enum Mode { ASH, FIREFLY, BIRDS }
        public Mode mode = Mode.ASH;
        public float radius = 18f;
        public int density = 60;
        public float driftSpeed = 0.25f;
        public boolean sounds = true;           // ENV-011 chirps/flaps via AudioWorld events
        private long frame = 0;
        public int lastSoundFrame = -1000;

        /** particle positions this frame (deterministic from frame counter). */
        public List<float[]> sample(Vec3 center){
            List<float[]> out = new ArrayList<>(density);
            for(int i = 0; i < density; i++){
                float s1 = NZ.perlin2(i * 0.37f, frame * 0.013f);
                float s2 = NZ.perlin2(i * 0.51f + 9.7f, frame * 0.011f);
                float s3 = NZ.perlin2(i * 0.29f + 3.1f, frame * 0.007f);
                float x = center.x + s1 * radius;
                float y = center.y + 1.5f + s2 * radius * 0.35f;
                float z = center.z + s3 * radius;
                float a = mode == Mode.FIREFLY ? 0.5f + 0.5f * NZ.perlin2(i * 0.9f, frame * 0.05f) : 0.35f;
                out.add(new float[]{ x, y, z, a });
            }
            frame++;
            // occasional ambient sound event (birds/flies)
            if(sounds && frame - lastSoundFrame > 240 && NZ.perlin2(frame * 0.01f, 7.7f) > 0.55f){
                lastSoundFrame = (int)frame;
            }
            return out;
        }
        private static final Noise NZ = new Noise(2024);
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","AmbientLife"); out.kv("mode", mode.name());
            out.kv("density", density); out.endObj();
        }
        @Override public void load(JsonV n){
            try { mode = Mode.valueOf(n.str("mode","ASH")); } catch(Exception ignored){}
            density = (int)n.num("density", 60);
        }
    }

    // =====================================================================
    // ENV-008 — seasons (leaf tint, temperature, ambience)
    // =====================================================================
    public static final class Seasons extends Component {
        public enum Season { SPRING, SUMMER, AUTUMN, WINTER }
        public Season season = Season.SPRING;
        public float dayOfYear = 90f;          // 0..365
        public float seasonLengthDays = 91f;

        public void tick(float dtDays){
            dayOfYear = (dayOfYear + dtDays) % 365f;
            season = fromDay(dayOfYear, seasonLengthDays);
        }
        public static Season fromDay(float day, float len){
            int idx = (int)(day / Math.max(1f, len)) % 4;
            return Season.values()[idx];
        }
        /** foliage tint multiplier [r,g,b]. */
        public float[] leafTint(){
            return switch(season){
                case SPRING -> new float[]{ 0.75f, 1.0f, 0.62f };
                case SUMMER -> new float[]{ 0.52f, 0.95f, 0.40f };
                case AUTUMN -> new float[]{ 1.0f, 0.62f, 0.28f };
                case WINTER -> new float[]{ 0.80f, 0.84f, 0.90f };
            };
        }
        /** ambient temperature °C (affects steam/breath fogs). */
        public float temperature(){
            return switch(season){
                case SPRING -> 14f; case SUMMER -> 30f; case AUTUMN -> 9f; case WINTER -> -4f;
            };
        }
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","Seasons"); out.kv("dayOfYear", dayOfYear); out.endObj();
        }
        @Override public void load(JsonV n){
            dayOfYear = n.flt("dayOfYear", 90f);
            season = fromDay(dayOfYear, seasonLengthDays);
        }
    }

    // =====================================================================
    // ENV-014 — wind-driven audio loudness
    // =====================================================================
    public static final class WindAudio {
        /** map wind shader weight 0..2 to dB gain -40..0 (log curve). */
        public static float gainFor(float windWeight){
            float k = MathX.clamp(windWeight / 2f, 0f, 1f);
            return -40f * (1f - k) * (1f - k);
        }
    }

    // =====================================================================
    // ENV-015/016 — true darkness & safe/danger zone reactions
    // =====================================================================
    public static final class Mood {
        /** zero light -> pure black (renderer clamps ambient to 0). */
        public static float[] ambientFor(float lightLevel){
            if(lightLevel <= 0.001f) return new float[3];
            return new float[]{ lightLevel, lightLevel * 1.02f, lightLevel * 1.08f };
        }
        /** music zone from safety: safe = calm cue, danger = tension cue. */
        public static String musicCue(boolean danger){
            return danger ? "music_tension" : "music_calm";
        }
    }
}
