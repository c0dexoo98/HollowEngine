package com.hollow.vfx;

import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Entity;

import java.util.ArrayList;

/** Particle world state — renderer pulls render data each frame.
 *  Real CPU particle system: registered ParticleEmitter components simulate here.
 *  update(dt): spawn (rate accumulator + one-shot burst), integrate, age, kill.
 *  Deterministic: per-update Rng seeded from svc.frameSeed ^ entity id.
 *  API is FINAL. */
public final class ParticleLibrary {
    public static final int GLOBAL_CAP = 8192;
    public static final int STRIDE = 8;            // x,y,z,size,r,g,b,a
    static final int PSTRIDE = 13;                 // pos(3) vel(3) age maxLife size0 color(4)

    /** per-emitter live state */
    static final class Instance {
        final ParticleEmitter src;
        final ArrayList<float[]> particles = new ArrayList<>();
        float spawnAccum = 0f;
        boolean burstDone = false;
        // parsed curves (built lazily on first update)
        float[] sizeT, sizeV;
        float[][] colorKeys;   // rows {t, r, g, b, a}
        boolean curvesParsed = false;
        // v2.0: trail state
        final Vec3 lastSpawnPos = new Vec3();
        boolean trailInit = false;
        Instance(ParticleEmitter src){ this.src = src; }
    }

    private final ArrayList<Instance> emitters = new ArrayList<>();
    private int live = 0;

    // scratch
    private final Vec3 tmpDir = new Vec3();
    private final Vec3 tmpU = new Vec3();
    private final Vec3 tmpV = new Vec3();
    private final Vec3 tmpVel = new Vec3();
    private final Vec3 tmpPos = new Vec3();

    public void clear(){
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        for(int i = emitters.size() - 1; i >= 0; i--){
            Instance inst = emitters.get(i);
            inst.particles.clear();
            inst.burstDone = false;
            inst.spawnAccum = 0f;
            Entity ent = inst.src.entity;
            boolean drop = ent == null || ent.destroyed;
            if(!drop && app != null && app.world != null) drop = !app.world.entities.contains(ent);
            if(drop) emitters.remove(i);
        }
        live = 0;
    }

    public void update(float dt){
        if(dt <= 0) return;
        com.hollow.game.EngineServices svc = services();
        long seed = svc != null ? svc.frameSeed : 0x51ED2701L;
        for(int e = emitters.size() - 1; e >= 0; e--){
            Instance inst = emitters.get(e);
            ParticleEmitter src = inst.src;
            Entity ent = src.entity;
            if(ent == null || ent.destroyed){
                live -= inst.particles.size();   // R3: was leaked — GLOBAL_CAP filled up and spawning died forever
                emitters.remove(e);
                continue;
            }
            try {
                updateInstance(inst, ent, dt, seed);
            } catch(Exception ex){
                Log.w("VFX", "emitter update failed on " + ent.name + ": " + ex.getMessage());
            }
        }
    }

    private void updateInstance(Instance inst, Entity ent, float dt, long seed){
        ParticleEmitter src = inst.src;
        parseCurves(inst);
        if(!ent.active) return;    // paused emitter keeps its particles frozen

        MathX.Rng rng = new MathX.Rng(seed ^ (ent.id * 0x9E3779B97F4A7C15L));

        // one-shot burst at start of life
        if(!inst.burstDone && src.burst > 0){
            inst.burstDone = true;
            spawn(inst, src.burst, rng, null);
        }
        // continuous rate via accumulator
        if(src.rate > 0){
            inst.spawnAccum += src.rate * dt;
            int n = (int) inst.spawnAccum;
            if(n > 0){
                inst.spawnAccum -= n;
                if(n > 256) n = 256;   // clamp huge catch-up spawns
                spawn(inst, n, rng, null);
            }
        } else inst.spawnAccum = 0f;

        // v2.0: trail mode — spawn along entity movement (min distance between spawns)
        if(src.trail){
            Vec3 wp = ent.worldPosition();
            if(!inst.trailInit){ inst.lastSpawnPos.set(wp); inst.trailInit = true; }
            if(wp.dist2(inst.lastSpawnPos) >= src.trailMinDist * src.trailMinDist){
                Vec3 step = wp.cpy().sub(inst.lastSpawnPos);
                float d = step.len();
                int n = MathX.clamp((int)(d / Math.max(0.05f, src.trailSpacing)), 1, 16);
                for(int k = 0; k < n; k++){
                    Vec3 at = inst.lastSpawnPos.cpy().addScaled(step, (k + 1f) / n);
                    spawn(inst, 1, rng, at);
                }
                inst.lastSpawnPos.set(wp);
            }
        }

        // integrate + age
        float gx = src.gravity.x, gy = src.gravity.y, gz = src.gravity.z;
        float damp = Math.max(0f, 1f - src.drag * dt);
        com.hollow.physics.PhysicsWorld phys = src.collide ? physics() : null;
        for(int i = inst.particles.size() - 1; i >= 0; i--){
            float[] p = inst.particles.get(i);
            p[6] += dt;                                   // age
            if(p[6] >= p[7]){                             // kill at maxLife
                // v2.0: death sub-emitter burst
                if(src.deathEffect != null && !src.deathEffect.isEmpty()){
                    deathBurst(src, p);
                }
                inst.particles.remove(i);
                live--;
                continue;
            }
            float nx = p[0] + p[3] * dt;
            float ny = p[1] + p[4] * dt;
            float nz = p[2] + p[5] * dt;
            // v2.0: world collision (raycast the segment; kill or bounce)
            if(phys != null){
                seg.set(p[0], p[1], p[2]);
                segDir.set(nx - p[0], ny - p[1], nz - p[2]);
                float segLen = segDir.len();
                if(segLen > 1e-6f){
                    com.hollow.physics.PhysicsWorld.Hit hit = phys.raycast(seg, segDir, segLen + src.collideRadius);
                    if(hit.hit && hit.distance <= segLen + src.collideRadius){
                        if(src.collideKill){
                            if(src.deathEffect != null && !src.deathEffect.isEmpty()) deathBurst(src, p);
                            inst.particles.remove(i);
                            live--;
                            continue;
                        }
                        // bounce: reflect velocity around hit normal, dampen
                        float vn = p[3] * hit.normal.x + p[4] * hit.normal.y + p[5] * hit.normal.z;
                        p[3] -= 2f * vn * hit.normal.x;
                        p[4] -= 2f * vn * hit.normal.y;
                        p[5] -= 2f * vn * hit.normal.z;
                        p[3] *= src.collideBounce; p[4] *= src.collideBounce; p[5] *= src.collideBounce;
                        nx = p[0] + p[3] * dt;
                        ny = p[1] + p[4] * dt;
                        nz = p[2] + p[5] * dt;
                    }
                }
            }
            p[0] = nx; p[1] = ny; p[2] = nz;
            p[3] = (p[3] + gx * dt) * damp;
            p[4] = (p[4] + gy * dt) * damp;
            p[5] = (p[5] + gz * dt) * damp;
        }
    }

    private final Vec3 seg = new Vec3();
    private final Vec3 segDir = new Vec3();

    /** v2.0: fire the death sub-emitter effect at a particle position (recursive depth-safe:
     *  effects that trigger themselves are blocked by the effect-name != deathEffect check) */
    private void deathBurst(ParticleEmitter src, float[] p){
        Vec3 pos = new Vec3(p[0], p[1], p[2]);
        burst(src.deathEffect, pos);
    }

    private static com.hollow.physics.PhysicsWorld physics(){
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        return app != null && app.services() != null ? app.services().physics : null;
    }

    private void spawn(Instance inst, int count, MathX.Rng rng, Vec3 overridePos){
        ParticleEmitter src = inst.src;
        int max = MathX.clamp(src.maxCount, 1, 4096);
        for(int k = 0; k < count; k++){
            if(inst.particles.size() >= max) return;
            if(live >= GLOBAL_CAP) return;
            // random cone around direction
            Vec3 dir = tmpDir.set(src.direction);
            if(dir.len2() < 1e-8f) dir.set(0, 1, 0);
            basis(dir, tmpU, tmpV);
            float theta = rng.range(0f, MathX.PI * 2f);
            float phi = (float) Math.toRadians(rng.range(0f, Math.max(0f, src.spreadDeg)));
            float speed = rng.range(src.speedMin, Math.max(src.speedMin, src.speedMax));
            float sinP = (float) Math.sin(phi), cosP = (float) Math.cos(phi);
            float ct = (float) Math.cos(theta), st = (float) Math.sin(theta);
            Vec3 vel = tmpVel.set(dir).scale(cosP)
                    .addScaled(tmpU, ct * sinP)
                    .addScaled(tmpV, st * sinP)
                    .scale(speed);

            float[] p = new float[PSTRIDE];
            if(overridePos != null){
                p[0] = overridePos.x; p[1] = overridePos.y; p[2] = overridePos.z;
            } else if(src.localSpace){
                p[0] = 0; p[1] = 0; p[2] = 0;
            } else {
                Vec3 wp = src.entity.worldPosition();
                p[0] = wp.x; p[1] = wp.y; p[2] = wp.z;
            }
            p[3] = vel.x; p[4] = vel.y; p[5] = vel.z;
            p[6] = 0f;
            p[7] = rng.range(src.lifeMin, Math.max(src.lifeMin, src.lifeMax));
            p[8] = rng.range(src.sizeMin, Math.max(src.sizeMin, src.sizeMax));
            p[9] = 1f; p[10] = 1f; p[11] = 1f; p[12] = 1f;
            inst.particles.add(p);
            live++;
        }
    }

    /** orthonormal basis (u, v) perpendicular to dir */
    private static void basis(Vec3 dir, Vec3 u, Vec3 v){
        Vec3 helper = Math.abs(dir.y) < 0.99f ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        u.set(helper).cross(dir).norm();
        v.set(dir).cross(u).norm();
    }

    // ---------------- render pull ----------------

    /** 1-arg contract: all particles (kept for compatibility; delegates with additiveFilter=false) */
    public int getRenderData(float[] out){
        return getRenderData(out, false);
    }

    /** interleaved per particle: x,y,z,size,r,g,b,a. additiveFilter=true → only additive emitters.
     *  returns particle count written. */
    public int getRenderData(float[] out, boolean additiveFilter){
        if(out == null || out.length < STRIDE) return 0;
        int cap = out.length / STRIDE;
        int o = 0, count = 0;
        for(int e = 0; e < emitters.size(); e++){
            Instance inst = emitters.get(e);
            if(additiveFilter && !inst.src.additive) continue;
            Entity ent = inst.src.entity;
            ArrayList<float[]> ps = inst.particles;
            for(int i = 0; i < ps.size(); i++){
                if(count >= cap) return count;
                float[] p = ps.get(i);
                float t = p[6] / Math.max(1e-4f, p[7]);
                float size = p[8];
                if(inst.sizeT != null && inst.sizeT.length > 0) size *= sampleCurve(inst.sizeT, inst.sizeV, t);
                float r = p[9], g = p[10], b = p[11], a = p[12];
                if(inst.colorKeys != null && inst.colorKeys.length > 0){
                    float[] c = sampleColor(inst.colorKeys, t);
                    r = c[0]; g = c[1]; b = c[2]; a = c[3];
                }
                float x = p[0], y = p[1], z = p[2];
                if(inst.src.localSpace && ent != null){
                    // R4: transformPoint is pure — the old code discarded the returned vector,
                    // so local-space particles never followed their emitter
                    Vec3 tp = ent.worldMatrix.transformPoint(tmpPos.set(x, y, z));
                    x = tp.x; y = tp.y; z = tp.z;
                }
                out[o] = x; out[o + 1] = y; out[o + 2] = z;
                out[o + 3] = Math.max(0f, size);
                out[o + 4] = r; out[o + 5] = g; out[o + 6] = b; out[o + 7] = a;
                o += STRIDE;
                count++;
            }
        }
        return count;
    }

    public int activeCount(){ return live; }
    public int emitterCount(){ return emitters.size(); }

    // ---------------- registration ----------------

    public void registerEmitter(ParticleEmitter e){
        if(e == null) return;
        for(Instance inst : emitters) if(inst.src == e) return;   // already registered
        emitters.add(new Instance(e));
    }

    public void unregisterEmitter(ParticleEmitter e){
        if(e == null) return;
        for(int i = emitters.size() - 1; i >= 0; i--){
            if(emitters.get(i).src == e){
                live -= emitters.get(i).particles.size();
                emitters.remove(i);
            }
        }
    }

    /** fire a one-shot transient burst of a registered effect (emitter with "effect" field == name) */
    public void burst(String effectName, Vec3 pos){
        if(effectName == null) return;
        for(Instance inst : emitters){
            if(effectName.equals(inst.src.effect)){
                com.hollow.game.EngineServices svc = services();
                long seed = svc != null ? svc.frameSeed : 0x51ED2701L;
                Entity ent = inst.src.entity;
                MathX.Rng rng = new MathX.Rng(seed ^ (long) effectName.hashCode() ^ (ent != null ? ent.id : 7));
                int n = Math.max(1, MathX.clamp(inst.src.maxCount, 4, 4096) / 4);
                spawn(inst, n, rng, pos);
                return;
            }
        }
        Log.w("VFX", "burst: no effect named '" + effectName + "'");
    }

    // ---------------- curves ----------------

    private static void parseCurves(Instance inst){
        if(inst.curvesParsed) return;
        inst.curvesParsed = true;
        try {
            if(inst.src.sizeCurve != null && !inst.src.sizeCurve.isEmpty()){
                int n = inst.src.sizeCurve.size();
                inst.sizeT = new float[n];
                inst.sizeV = new float[n];
                for(int i = 0; i < n; i++){
                    java.util.List<Object> kv = com.hollow.core.Json.arr(inst.src.sizeCurve.get(i));
                    inst.sizeT[i] = kv != null && kv.size() > 0 ? (float) com.hollow.core.Json.num(kv.get(0), 0) : 0;
                    inst.sizeV[i] = kv != null && kv.size() > 1 ? (float) com.hollow.core.Json.num(kv.get(1), 1) : 1;
                }
            }
            if(inst.src.colorCurve != null && !inst.src.colorCurve.isEmpty()){
                int n = inst.src.colorCurve.size();
                inst.colorKeys = new float[n][];
                for(int i = 0; i < n; i++){
                    java.util.List<Object> kv = com.hollow.core.Json.arr(inst.src.colorCurve.get(i));
                    inst.colorKeys[i] = new float[]{
                            kv != null && kv.size() > 0 ? (float) com.hollow.core.Json.num(kv.get(0), 0) : 0,
                            kv != null && kv.size() > 1 ? (float) com.hollow.core.Json.num(kv.get(1), 1) : 1,
                            kv != null && kv.size() > 2 ? (float) com.hollow.core.Json.num(kv.get(2), 1) : 1,
                            kv != null && kv.size() > 3 ? (float) com.hollow.core.Json.num(kv.get(3), 1) : 1,
                            kv != null && kv.size() > 4 ? (float) com.hollow.core.Json.num(kv.get(4), 1) : 1};
                }
            }
        } catch(Exception ex){
            Log.w("VFX", "curve parse failed: " + ex.getMessage());
            inst.sizeT = null; inst.colorKeys = null;
        }
    }

    private static float sampleCurve(float[] ts, float[] vs, float t){
        int n = ts.length;
        if(n == 0) return 1;
        if(t <= ts[0]) return vs[0];
        if(t >= ts[n - 1]) return vs[n - 1];
        int i = 1;
        while(i < n && ts[i] < t) i++;
        float f = (t - ts[i - 1]) / Math.max(1e-6f, ts[i] - ts[i - 1]);
        return MathX.lerp(vs[i - 1], vs[i], f);
    }

    private static float[] sampleColor(float[][] keys, float t){
        int n = keys.length;
        if(n == 1) return keys[0];
        if(t <= keys[0][0]) return keys[0];
        if(t >= keys[n - 1][0]) return keys[n - 1];
        int i = 1;
        while(i < n && keys[i][0] < t) i++;
        float f = (t - keys[i - 1][0]) / Math.max(1e-6f, keys[i][0] - keys[i - 1][0]);
        float[] a = keys[i - 1], b = keys[i];
        return new float[]{
                MathX.lerp(a[1], b[1], f),
                MathX.lerp(a[2], b[2], f),
                MathX.lerp(a[3], b[3], f),
                MathX.lerp(a[4], b[4], f)};
    }

    static com.hollow.game.EngineServices services(){
        return com.hollow.game.GameApp.instance != null ? com.hollow.game.GameApp.instance.services() : null;
    }
}
