package com.hollow.vfx;

import com.hollow.core.MathX;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Noise;
import com.hollow.core.RandomStream;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.List;

/** v2.5 VFX batch — VFX-010/011/019/020/022/023/025.
 *  All CPU-side, deterministic (seeded RNG), headless-testable; the renderer
 *  samples the public state arrays. */
public final class VfxFeatures {
    private VfxFeatures() {}

    // =====================================================================
    // VFX-010 — controlled gore (gib pieces)
    // =====================================================================
    /** Explosion/gore burst: pooled gib pieces with gravity, ground bounce,
     *  spin decay and timed despawn. Blood trail hooks ride on each gib. */
    public static final class GibSystem {
        public static final class Gib {
            public final Vec3 pos = new Vec3(), vel = new Vec3(), spin = new Vec3();
            public float life, maxLife = 6f, scale;
            public boolean active, bounced, leftTrail;
            public int gibType;                 // index into gib mesh table
        }
        public final List<Gib> pool = new ArrayList<>();
        public RandomStream rng = new RandomStream(0x60BE);
        public float gravity = -9.8f, bounceDamping = 0.32f, groundY = 0f;
        public int maxGibs = 96;
        public int spawnedTotal = 0, recycled = 0;

        /** burst: `count` gibs launched from `origin` biased away from `dir`. */
        public int burst(Vec3 origin, Vec3 dir, int count, float force, float[] gibTypes, float[] gibScales){
            int spawned = 0;
            for(int i = 0; i < count && spawnedTotal < maxGibs * 1000; i++){
                Gib g = obtain();
                if(g == null) break;
                g.pos.set(origin);
                float a = rng.nextFloat() * (float)(Math.PI * 2);
                float up = 0.55f + rng.nextFloat() * 0.6f;
                float spread = 0.35f + rng.nextFloat() * 0.65f;
                g.vel.set(dir.x * force * spread + (float)Math.cos(a) * force * 0.4f,
                          dir.y * force * 0.3f + up * force * 0.5f,
                          dir.z * force * spread + (float)Math.sin(a) * force * 0.4f);
                g.spin.set(rng.nextFloat() * 12f - 6f, rng.nextFloat() * 12f - 6f, rng.nextFloat() * 12f - 6f);
                g.gibType = gibTypes.length > 0 ? (int)gibTypes[rng.nextInt(gibTypes.length)] : 0;
                g.scale = gibScales.length > 0 ? gibScales[rng.nextInt(gibScales.length)] : 0.12f;
                g.maxLife = 4f + rng.nextFloat() * 3f;
                g.life = g.maxLife;
                g.active = true; g.bounced = false; g.leftTrail = false;
                spawned++; spawnedTotal++;
            }
            return spawned;
        }

        Gib obtain(){
            for(Gib g : pool) if(!g.active){ recycled++; return g; }
            if(pool.size() >= maxGibs) return null;
            Gib g = new Gib();
            pool.add(g);
            return g;
        }

        public void update(float dt){
            for(Gib g : pool){
                if(!g.active) continue;
                g.life -= dt;
                if(g.life <= 0){ g.active = false; continue; }
                g.vel.y += gravity * dt;
                g.pos.addScaled(g.vel, dt);
                if(g.pos.y <= groundY){
                    g.pos.y = groundY;
                    if(Math.abs(g.vel.y) > 0.6f){
                        g.vel.y = -g.vel.y * bounceDamping;
                        g.vel.x *= 0.7f; g.vel.z *= 0.7f;
                        g.bounced = true;
                        g.leftTrail = true;              // decal/blood pool hook
                    } else {
                        g.vel.set(0, 0, 0);
                        g.spin.set(0, 0, 0);
                    }
                }
                g.spin.set(g.spin.x * (1 - dt), g.spin.y * (1 - dt), g.spin.z * (1 - dt));
            }
        }

        public int activeCount(){ int n = 0; for(Gib g : pool) if(g.active) n++; return n; }
        public void clear(){ pool.clear(); spawnedTotal = 0; }
    }

    // =====================================================================
    // VFX-011 — sparks with surface collision
    // =====================================================================
    /** Spark particles that bounce off planes/AABB surfaces: each spark carries a
     *  restitution per surface type; collision callback fires once (sound/decal). */
    public static final class SparkCollision {
        public static final class Spark {
            public final Vec3 pos = new Vec3(), vel = new Vec3();
            public float life, maxLife = 0.8f, heat = 1f;
            public boolean active, collided;
        }
        /** collision surface: axis-aligned box (plane list) */
        public static final class Surface {
            public float minX, minY, minZ, maxX, maxY, maxZ;
            public float restitution = 0.45f;
            public Surface(float x0, float y0, float z0, float x1, float y1, float z1){
                minX = x0; minY = y0; minZ = z0; maxX = x1; maxY = y1; maxZ = z1;
            }
            boolean inside(Vec3 p){
                return p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY && p.z >= minZ && p.z <= maxZ;
            }
        }
        public final List<Spark> sparks = new ArrayList<>();
        public final List<Surface> surfaces = new ArrayList<>();
        public RandomStream rng = new RandomStream(0x5FA2);
        public float gravity = -14f;
        public int maxSparks = 256;
        public int collisions = 0;

        public int emit(Vec3 origin, Vec3 dir, int count, float speed, float coneDeg){
            int emitted = 0;
            for(int i = 0; i < count; i++){
                Spark s = obtain();
                if(s == null) break;
                float cone = (float)Math.toRadians(coneDeg);
                float jitter = cone * (rng.nextFloat() - 0.5f);
                float a = rng.nextFloat() * (float)(Math.PI * 2);
                s.pos.set(origin);
                s.vel.set(dir.x * speed + (float)Math.cos(a) * speed * 0.25f + jitter * speed,
                          dir.y * speed * 0.7f + (float)Math.sin(a) * speed * 0.15f + rng.nextFloat() * speed * 0.2f,
                          dir.z * speed + (float)Math.sin(a) * speed * 0.25f + jitter * speed);
                s.maxLife = 0.4f + rng.nextFloat() * 0.6f;
                s.life = s.maxLife;
                s.heat = 1f;
                s.active = true; s.collided = false;
                emitted++;
            }
            return emitted;
        }

        Spark obtain(){
            for(Spark s : sparks) if(!s.active) return s;
            if(sparks.size() >= maxSparks) return null;
            Spark s = new Spark();
            sparks.add(s);
            return s;
        }

        public void update(float dt){
            for(Spark s : sparks){
                if(!s.active) continue;
                s.life -= dt;
                if(s.life <= 0){ s.active = false; continue; }
                Vec3 prev = new Vec3(s.pos);
                s.vel.y += gravity * dt;
                s.pos.addScaled(s.vel, dt);
                for(Surface surf : surfaces){
                    if(surf.inside(s.pos) && !surf.inside(prev)){
                        s.collided = true; collisions++;
                        // simple normal pick: smallest penetration axis
                        float px = Math.min(s.pos.x - surf.minX, surf.maxX - s.pos.x);
                        float py = Math.min(s.pos.y - surf.minY, surf.maxY - s.pos.y);
                        float pz = Math.min(s.pos.z - surf.minZ, surf.maxZ - s.pos.z);
                        if(py <= px && py <= pz) s.vel.y = -s.vel.y * surf.restitution;
                        else if(px <= pz) s.vel.x = -s.vel.x * surf.restitution;
                        else s.vel.z = -s.vel.z * surf.restitution;
                        s.heat = Math.max(0.3f, s.heat * 0.6f);
                        s.pos.set(prev.x + s.vel.x * dt, prev.y + s.vel.y * dt, prev.z + s.vel.z * dt);
                        break;
                    }
                }
                s.heat = Math.max(0f, s.heat - dt * 1.4f);
            }
        }
        public int activeCount(){ int n = 0; for(Spark s : sparks) if(s.active) n++; return n; }
        public void clear(){ sparks.clear(); surfaces.clear(); }
    }

    // =====================================================================
    // VFX-019 — sword swing arc mesh
    // =====================================================================
    /** Sweeps an arc strip behind a swinging weapon: the renderer draws a curved
     *  ribbon mesh between `fromAngle..toAngle` around the wielder axis; opacity
     *  fades over `fadeSec` after the swing ends. Deterministic sampling. */
    public static final class SwordArc {
        public boolean swinging = false;
        public float fromAngle, toAngle;
        public float radius = 1.1f, height = 0.9f;
        public float swingTime = 0f, swingDuration = 0.22f, fadeSec = 0.18f, fadeLeft = 0f;
        public float tilt = 0f;                       // arc plane tilt (deg)
        public int segments = 12;
        public Vec3 center = new Vec3();
        public Vec3 axis = new Vec3(0, 1, 0);         // swing plane normal
        public String trailColor = "#BFD8FF";         // renderer samples

        /** start a swing: angle from→to (deg, CCW around axis). */
        public void start(Vec3 origin, float fromDeg, float toDeg, float duration){
            center.set(origin);
            fromAngle = fromDeg; toAngle = toDeg;
            swingDuration = Math.max(0.02f, duration);
            swingTime = 0f; fadeLeft = fadeSec;
            swinging = true;
        }

        public void update(float dt){
            if(!swinging) return;
            swingTime += dt;
            if(swingTime >= swingDuration){
                swinging = false;
                fadeLeft = fadeSec;
            }
        }

        /** fade after swing ends */
        public void updateFade(float dt){
            if(!swinging && fadeLeft > 0f) fadeLeft = Math.max(0f, fadeLeft - dt);
        }

        /** 0..1 progress (1 when done) */
        public float progress(){ return swinging ? MathX.clamp(swingTime / swingDuration, 0, 1) : 1f; }
        public float opacity(){
            if(swinging) return 0.9f;
            return MathX.clamp(fadeLeft / Math.max(0.001f, fadeSec), 0, 1) * 0.9f;
        }

        /** vertex strip for the renderer: pairs [innerX,innerY,innerZ, outerX,outerY,outerZ] */
        public List<float[]> meshStrip(){
            List<float[]> out = new ArrayList<>();
            float prog = progress();
            float sweep = (toAngle - fromAngle) * prog;
            for(int i = 0; i <= segments; i++){
                float a = (float)Math.toRadians(fromAngle + sweep * i / segments);
                float rIn = radius * 0.45f, rOut = radius;
                float yOff = tilt * i / segments;
                out.add(new float[]{
                    center.x + (float)Math.cos(a) * rIn, center.y + height * (i / (float)segments) * 0.4f + yOff,
                    center.z + (float)Math.sin(a) * rIn,
                    center.x + (float)Math.cos(a) * rOut, center.y + height * (i / (float)segments) + yOff,
                    center.z + (float)Math.sin(a) * rOut
                });
            }
            return out;
        }
    }

    // =====================================================================
    // VFX-020 — magic aura ring on the ground
    // =====================================================================
    public static final class AuraRing extends Component {
        public float radius = 1.6f;
        public float pulseSpeed = 1.8f;             // Hz-ish
        public float pulseAmount = 0.12f;           // radius wobble fraction
        public float rotationSpeed = 40f;           // deg/sec
        public String color = "#7B4DFF";
        public float opacity = 0.7f;
        public float runeCount = 8;
        /** readouts */
        public float phase = 0f, rotation = 0f;

        @Override public void onUpdate(float dt){
            phase += dt * pulseSpeed * (float)(Math.PI * 2);
            rotation += dt * rotationSpeed;
            if(rotation >= 360f) rotation -= 360f;
        }

        public float currentRadius(){
            return radius * (1f + pulseAmount * (float)Math.sin(phase));
        }
        public float currentOpacity(){ return opacity * (0.85f + 0.15f * (float)Math.sin(phase * 1.7f)); }
        /** rune positions for the renderer (x,z pairs on the ring) */
        public List<float[]> runes(Vec3 origin){
            List<float[]> out = new ArrayList<>();
            int n = Math.max(0, (int)runeCount);
            float r = currentRadius();
            for(int i = 0; i < n; i++){
                float a = (float)Math.toRadians(rotation + 360f * i / n);
                out.add(new float[]{ origin.x + (float)Math.cos(a) * r, origin.z + (float)Math.sin(a) * r });
            }
            return out;
        }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","AuraRing").kv("radius",radius).kv("pulseSpeed",pulseSpeed)
              .kv("color",color).kv("opacity",opacity).kv("runeCount",(int)runeCount); w.endObj();
        }
        @Override public void load(JsonV n){
            radius = n.flt("radius", 1.6f); pulseSpeed = n.flt("pulseSpeed", 1.8f);
            pulseAmount = n.flt("pulseAmount", 0.12f); rotationSpeed = n.flt("rotationSpeed", 40f);
            color = n.str("color", "#7B4DFF"); opacity = n.flt("opacity", 0.7f);
            runeCount = (float)n.num("runeCount", 8);
        }
    }

    // =====================================================================
    // VFX-022 — ambient flight (birds / fireflies) with flocking
    // =====================================================================
    /** Boids-lite flock confined to a spherical habitat around the anchor: birds
     *  gain separation/cohesion/wander steering + wing flap phase; fireflies glow
     *  with individual pulse phases. Deterministic (seeded). */
    public static final class AmbientFlight extends Component {
        public enum Mode { BIRDS, FIREFLY }
        public Mode mode = Mode.BIRDS;
        public int count = 24;
        public float habitatRadius = 14f;
        public float speed = 2.4f;
        public float separation = 1.2f;
        public float flapHz = 3.2f;
        public long seed = 0xA1B2;

        public static final class Agent {
            public final Vec3 pos = new Vec3(), vel = new Vec3();
            public float phase;                 // wing flap / glow
        }
        public final List<Agent> agents = new ArrayList<>();
        public float time = 0f;
        private RandomStream rng;
        private boolean initialized = false;

        void init(){
            if(initialized) return;
            rng = new RandomStream(seed);
            for(int i = 0; i < count; i++){
                Agent a = new Agent();
                a.pos.set((rng.nextFloat() - 0.5f) * habitatRadius, 2f + rng.nextFloat() * 4f,
                          (rng.nextFloat() - 0.5f) * habitatRadius);
                a.vel.set(rng.nextFloat() - 0.5f, 0, rng.nextFloat() - 0.5f).norm().scale(speed);
                a.phase = rng.nextFloat() * (float)(Math.PI * 2);
                agents.add(a);
            }
            initialized = true;
        }

        @Override public void onUpdate(float dt){
            init();
            time += dt;
            Vec3 anchor = entity != null ? entity.worldPosition() : new Vec3();
            for(int i = 0; i < agents.size(); i++){
                Agent a = agents.get(i);
                a.phase += dt * flapHz * (float)(Math.PI * 2) * (mode == Mode.BIRDS ? 1f : 0.35f);
                // steering: cohesion to anchor ring + separation from neighbors
                Vec3 steer = new Vec3(anchor.x - a.pos.x, 0, anchor.z - a.pos.z);
                float d = steer.len();
                if(d > habitatRadius) steer.scale((d - habitatRadius) / habitatRadius);
                else steer.scale(-0.15f);
                for(int j = 0; j < agents.size(); j++){
                    if(j == i) continue;
                    Agent o = agents.get(j);
                    float dd = a.pos.dist(o.pos);
                    if(dd < separation && dd > 1e-4f){
                        steer.x += (a.pos.x - o.pos.x) / dd * 0.6f;
                        steer.z += (a.pos.z - o.pos.z) / dd * 0.6f;
                    }
                }
                // wander
                float w = mode == Mode.BIRDS ? 0.4f : 0.15f;
                steer.x += Math.sin(time * 1.7f + i * 2.4f) * w;
                steer.z += Math.cos(time * 1.3f + i * 1.9f) * w;
                if(mode == Mode.BIRDS) steer.y = (float)Math.sin(time * 2.1f + i) * 0.25f;
                a.vel.addScaled(steer, dt * 2.2f);
                a.vel.norm().scale(speed * (mode == Mode.BIRDS ? 1f : 0.55f));
                a.pos.addScaled(a.vel, dt);
            }
        }

        /** renderer sample: [x, y, z, phaseModulatedAlpha] per agent */
        public List<float[]> sample(){
            List<float[]> out = new ArrayList<>();
            for(Agent a : agents){
                float alpha = mode == Mode.FIREFLY
                    ? 0.35f + 0.65f * (float)Math.max(0, Math.sin(a.phase))
                    : 1f;
                out.add(new float[]{ a.pos.x, a.pos.y, a.pos.z, alpha });
            }
            return out;
        }
        public int agentCount(){ init(); return agents.size(); }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","AmbientFlight").kv("mode",mode.name().toLowerCase())
              .kv("count",count).kv("habitatRadius",habitatRadius).kv("speed",speed)
              .kv("flapHz",flapHz).kv("seed",(int)seed); w.endObj();
        }
        @Override public void load(JsonV n){
            String m = n.str("mode", "birds");
            mode = m.equalsIgnoreCase("firefly") ? Mode.FIREFLY : Mode.BIRDS;
            count = n.int32("count", 24); habitatRadius = n.flt("habitatRadius", 14f);
            speed = n.flt("speed", 2.4f); flapHz = n.flt("flapHz", 3.2f);
            seed = (long)n.num("seed", 0xA1B2);
        }
    }

    // =====================================================================
    // VFX-023 — cinematic blood-on-lens
    // =====================================================================
    /** Damage splatters droplets onto the camera lens (screen-space): droplets grow
     *  from impact point, drip downward, then evaporate. Renderer overlays them. */
    public static final class BloodLens {
        public static final class Droplet {
            public float x, y;                  // screen UV 0..1
            public float size;                  // 0..0.2 of screen height
            public float opacity;
            public float dripSpeed;
            public boolean active;
        }
        public final List<Droplet> droplets = new ArrayList<>();
        public RandomStream rng = new RandomStream(0xB100);
        public int maxDroplets = 32;
        public float dripGravity = 0.06f;       // UV/sec
        public float evaporateRate = 0.10f;     // opacity/sec
        public int splatters = 0;

        public void splatter(float hitX, float hitY, float intensity, int count){
            for(int i = 0; i < count && droplets.size() < maxDroplets * 2; i++){
                Droplet d = obtain();
                if(d == null) break;
                float scatter = 0.08f * intensity;
                d.x = MathX.clamp(hitX + (rng.nextFloat() - 0.5f) * scatter, 0.02f, 0.98f);
                d.y = MathX.clamp(hitY + (rng.nextFloat() - 0.5f) * scatter, 0.02f, 0.98f);
                d.size = 0.02f + rng.nextFloat() * 0.05f * intensity;
                d.opacity = MathX.clamp(0.5f + 0.5f * intensity, 0, 1);
                d.dripSpeed = dripGravity * (0.5f + rng.nextFloat());
                d.active = true;
                splatters++;
            }
        }

        Droplet obtain(){
            for(Droplet d : droplets) if(!d.active) return d;
            if(droplets.size() >= maxDroplets) return null;
            Droplet d = new Droplet();
            droplets.add(d);
            return d;
        }

        public void update(float dt){
            for(Droplet d : droplets){
                if(!d.active) continue;
                d.y += d.dripSpeed * dt;                  // screen space: y grows downward → drips run down
                if(d.y > 1f){ d.active = false; continue; }
                d.opacity -= evaporateRate * dt;
                if(d.opacity <= 0f) d.active = false;
            }
        }
        public int activeCount(){ int n = 0; for(Droplet d : droplets) if(d.active) n++; return n; }
        public void clearAll(){ droplets.clear(); splatters = 0; }
    }

    // =====================================================================
    // VFX-025 — pre-built blood vignette (per camera)
    // =====================================================================
    /** Damage-driven vignette intensity per camera: rises instantly on hits, decays
     *  slowly; a death clamp holds it at full red. Renderer samples `intensity` +
     *  the baked vignette texture (4 pre-built levels). */
    public static final class BloodVignette {
        public static final int LEVELS = 4;             // baked vignette strength levels
        public float intensity = 0f;                    // 0..1
        public float riseAmount = 0.34f;                // per damage event
        public float decayPerSec = 0.07f;
        public float deathClamp = 0.9f;
        public boolean dead = false;
        public long bumps = 0;

        public void damage(float amount01){
            intensity = MathX.clamp(intensity + riseAmount * Math.max(0.2f, amount01), 0, 1);
            bumps++;
        }
        public void setDead(boolean d){
            dead = d;
            if(d) intensity = Math.max(intensity, deathClamp);
        }
        public void update(float dt){
            if(dead){ intensity = MathX.clamp(intensity - dt * 0.02f, deathClamp, 1); return; }
            intensity = Math.max(0f, intensity - decayPerSec * dt);
        }
        /** baked level index for the renderer (0 = clean, LEVELS-1 = heavy) */
        public int level(){ return Math.min(LEVELS - 1, (int)(intensity * LEVELS)); }
        public int pulseTick(){ return (int)(intensity * 24f); }    // heartbeat sync hint
    }
}
