package com.hollow.physics;

import com.hollow.core.AABB;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Static colliders + dynamics + character controller + raycast + triggers.
 *  v2.0 dynamics:
 *  - RigidBody shapes: sphere / box(AABB) / capsule(vertical) — entity scale multiplies dims.
 *  - Dynamic-dynamic impulse solver (equal-weight exchange + restitution + Coulomb friction),
 *    dynamic-static pushout with friction, positional correction (Baumgarte-style clamp).
 *  - Sleeping: bodies below velocity threshold sleep (wake() on impulse/contact).
 *  - Joints: Distance/Spring/Hinge via Joint.Manager (collected on world load, solved per step).
 *  - Events: EventBus "collision" {entity, other, speed} on hard contacts (rate-limited).
 *  - Queries: overlapSphere, applyRadialImpulse.
 *  Statics/triggers/raycast/character controller unchanged from v1 (see history).
 */
public final class PhysicsWorld {
    public final Vec3 gravity = new Vec3(0, -9.81f, 0);

    public static final int KIND_BOX = 0, KIND_SPHERE = 1, KIND_CAPSULE = 2;
    /** collision radius used for RigidBody point dynamics */
    public static final float BODY_RADIUS = 0.25f;

    /** world-space collider snapshot (statics + triggers) */
    public static final class StaticShape {
        public int kind;
        public Entity entity;
        public boolean isTrigger;
        public final AABB bounds = new AABB();       // world-space bounds (all kinds)
        public final Vec3 center = new Vec3();       // box center / sphere center / capsule mid
        public final Vec3 halfExtents = new Vec3();  // box only
        public float radius;                          // sphere / capsule
        public final Vec3 capA = new Vec3();          // capsule segment bottom sphere center
        public final Vec3 capB = new Vec3();          // capsule segment top sphere center
    }

    private final List<StaticShape> shapes = new ArrayList<>();
    private final List<CharacterController> controllers = new ArrayList<>();
    private final List<RigidBody> bodies = new ArrayList<>();
    private final Map<Object, HashSet<StaticShape>> insideSets = new HashMap<>();
    /** v2: joint manager — collected on world load, solved after body integration */
    public final Joints.Manager joints = new Joints.Manager();
    private final Map<Long, Float> collisionCooldown = new HashMap<>();  // pairKey -> time left
    private float collisionEventCooldown = 0f;

    // scratch (single-threaded simulation)
    private final Vec3 sA = new Vec3(), sB = new Vec3(), sC = new Vec3();
    private final Vec3 qs = new Vec3(), qb = new Vec3();
    private final Vec3 ca = new Vec3(), cb = new Vec3(), cn = new Vec3(), cv = new Vec3();

    // ------------------------------------------------------------------
    // registration
    // ------------------------------------------------------------------
    /** shared navigation grid (lazy-built from static colliders) */
    public NavGrid navGrid;

    public void onWorldLoaded(World world){
        shapes.clear();
        controllers.clear();
        bodies.clear();
        insideSets.clear();
        collisionCooldown.clear();
        joints.collect(world);
        if(world == null) return;
        for(Entity e : world.entities) collect(e);
        Log.d("Physics", "statics=" + shapes.size() + " controllers=" + controllers.size()
            + " bodies=" + bodies.size() + " joints=" + joints.joints.size());
    }

    private void collect(Entity e){
        BoxCollider b = e.get(BoxCollider.class);
        if(b != null) addBox(e, b);
        SphereCollider s = e.get(SphereCollider.class);
        if(s != null) addSphere(e, s);
        CapsuleCollider c = e.get(CapsuleCollider.class);
        if(c != null) addCapsule(e, c);
        CharacterController cc = e.get(CharacterController.class);
        if(cc != null) controllers.add(cc);
        RigidBody rb = e.get(RigidBody.class);
        if(rb != null) bodies.add(rb);
    }

    private void addBox(Entity e, BoxCollider c){
        StaticShape s = new StaticShape();
        s.kind = KIND_BOX;
        s.entity = e;
        s.isTrigger = c.isTrigger;
        AABB local = AABB.fromCenterExtents(c.offset, sA.set(c.size.x * 0.5f, c.size.y * 0.5f, c.size.z * 0.5f));
        AABB world = e.worldAABB(local);
        s.bounds.min.set(world.min);
        s.bounds.max.set(world.max);
        s.center.set(s.bounds.center());
        s.halfExtents.set(s.bounds.size()).scale(0.5f);
        shapes.add(s);
    }

    private void addSphere(Entity e, SphereCollider c){
        StaticShape s = new StaticShape();
        s.kind = KIND_SPHERE;
        s.entity = e;
        s.isTrigger = c.isTrigger;
        s.center.set(e.worldMatrix.transformPoint(c.offset));
        s.radius = Math.max(0.01f, c.radius * worldScale(e));
        sA.set(s.radius, s.radius, s.radius);
        s.bounds.min.set(s.center).sub(sA);
        s.bounds.max.set(s.center).add(sA);
        shapes.add(s);
    }

    private void addCapsule(Entity e, CapsuleCollider c){
        StaticShape s = new StaticShape();
        s.kind = KIND_CAPSULE;
        s.entity = e;
        s.isTrigger = c.isTrigger;
        float r = Math.max(0.01f, c.radius * worldScale(e));
        float halfSeg = Math.max(0f, c.height * 0.5f - c.radius);
        s.capA.set(e.worldMatrix.transformPoint(sA.set(c.offset.x, c.offset.y - halfSeg, c.offset.z)));
        s.capB.set(e.worldMatrix.transformPoint(sA.set(c.offset.x, c.offset.y + halfSeg, c.offset.z)));
        s.radius = r;
        s.center.set(s.capA).add(s.capB).scale(0.5f);
        AABB local = AABB.fromCenterExtents(c.offset, sA.set(r, Math.max(r, c.height * 0.5f), r));
        AABB world = e.worldAABB(local);
        s.bounds.min.set(world.min);
        s.bounds.max.set(world.max);
        shapes.add(s);
    }

    private float worldScale(Entity e){
        e.updateWorld();
        float l0 = (float)Math.sqrt(e.worldMatrix.m[0]*e.worldMatrix.m[0] + e.worldMatrix.m[1]*e.worldMatrix.m[1] + e.worldMatrix.m[2]*e.worldMatrix.m[2]);
        float l1 = (float)Math.sqrt(e.worldMatrix.m[4]*e.worldMatrix.m[4] + e.worldMatrix.m[5]*e.worldMatrix.m[5] + e.worldMatrix.m[6]*e.worldMatrix.m[6]);
        float l2 = (float)Math.sqrt(e.worldMatrix.m[8]*e.worldMatrix.m[8] + e.worldMatrix.m[9]*e.worldMatrix.m[9] + e.worldMatrix.m[10]*e.worldMatrix.m[10]);
        return Math.max(l0, Math.max(l1, l2));
    }

    /** read-only view of all registered static/trigger shapes (used by NavGrid). */
    public List<StaticShape> staticShapes(){ return java.util.Collections.unmodifiableList(shapes); }

    /** v2.0: runtime registration for dynamically spawned bodies (idempotent) */
    public void registerBody(RigidBody rb){
        if(rb == null || rb.entity == null) return;
        if(!bodies.contains(rb)) bodies.add(rb);
    }

    /** v2.0: runtime registration for dynamically spawned character controllers */
    public void registerController(CharacterController cc){
        if(cc == null || cc.entity == null) return;
        if(!controllers.contains(cc)) controllers.add(cc);
    }

    // ------------------------------------------------------------------
    // simulation
    // ------------------------------------------------------------------
    // v2.3 physics tuning (PHY-032 substep, PHY-027 broadphase, COL-020 stats)
    public int substeps = 1;                      // 1..4 — fixed sub-step slices per frame
    public final Broadphase broadphase = new Broadphase();
    public static final int BROADPHASE_THRESHOLD = 48;   // above this body count, grid pairs replace O(n²)
    // v2.3 COL-020 collision statistics (read by profiler / hollow bench)
    public int statPairChecks = 0, statPairHits = 0, statStaticChecks = 0, statStaticHits = 0;

    public void update(float dt){
        if(navGrid == null) navGrid = new NavGrid(this);
        if(dt <= 0f) return;
        controllers.removeIf(c -> c.entity == null || c.entity.destroyed);
        bodies.removeIf(b -> b.entity == null || b.entity.destroyed);
        collisionEventCooldown = Math.max(0f, collisionEventCooldown - dt);
        for(Map.Entry<Long, Float> kv : collisionCooldown.entrySet())
            if(kv.getValue() > 0f) kv.setValue(kv.getValue() - dt);
        // PHY-032: split frame dt into equal sub-steps for contact quality
        int n = Math.max(1, Math.min(4, substeps));
        float sdt = dt / n;
        for(int s = 0; s < n; s++) subStep(sdt);
        updateTriggers();
    }

    private void subStep(float dt){
        statPairChecks = 0; statPairHits = 0;
        boolean anyAwake = false;
        for(RigidBody rb : bodies){
            if(rb.entity == null || !rb.entity.active || rb.entity.destroyed) continue;
            if(rb.kinematic || rb.sleeping) continue;
            integrateBody(rb, dt);
            anyAwake = true;
        }
        if(anyAwake || joints.joints.size() > 0){
            if(bodies.size() > BROADPHASE_THRESHOLD){
                broadphase.refresh(bodies, 1f);
                solveDynamicPairsBroadphase(dt);
            } else {
                solveDynamicPairs(dt);
            }
            joints.solve(dt);
        }
    }

    // ------------------------------------------------------------------
    // v2 body integration (shape-aware) + v2.3 CCD (PHY-024)
    // ------------------------------------------------------------------
    private void integrateBody(RigidBody rb, float dt){
        Entity e = rb.entity;
        rb.velocity.addScaled(gravity, rb.gravityScale * dt);
        if(rb.linearDrag > 0f){
            float damp = Math.max(0f, 1f - rb.linearDrag * dt);
            rb.velocity.scale(damp);
        }
        if(rb.angularDrag > 0f){
            float damp = Math.max(0f, 1f - rb.angularDrag * dt);
            rb.angularVelocity.scale(damp);
        }
        Vec3 disp = sA.set(rb.velocity).scale(dt);
        float r = rb.effectiveRadius();
        // PHY-024 CCD: tunneling guard — swept ray to the target position clamps at first wall
        if(rb.ccd && disp.len() > r * 0.9f){
            integrateBodyCCD(rb, disp);
        } else {
            int n = 1 + (int)(disp.len() / Math.max(0.05f, r * 0.5f));
            n = Math.min(n, 16);
            Vec3 step = sB.set(disp).scale(1f / n);
            rb.grounded = false;
            for(int i = 0; i < n; i++){
                e.position.add(step);
                resolveBodyStatics(rb);
            }
        }
        // angular integration: yaw only (mobile-friendly single-axis spin; pitch/roll reserved)
        if(Math.abs(rb.angularVelocity.y) > 1e-5f){
            com.hollow.core.Quat delta = com.hollow.core.Quat.fromAxisAngle(0, 1, 0, rb.angularVelocity.y * dt * MathX.DEG);
            delta.mul(e.rotation);
            e.rotation.set(delta).norm();
            e.markDirtyDeep();
        }
        // sleeping
        if(rb.sleepEnabled){
            float lin = rb.velocity.len(), ang = rb.angularVelocity.len();
            if(lin < 0.06f && ang < 0.06f && rb.grounded){
                rb.sleepTimer += dt;
                if(rb.sleepTimer > 0.6f){
                    rb.sleeping = true;
                    rb.velocity.set(0,0,0);
                    rb.angularVelocity.set(0,0,0);
                    rb.sleepTimer = 0f;
                }
            } else rb.sleepTimer = 0f;
        }
        e.markDirtyDeep();
    }

    /** PHY-024: continuous sweep — ray from prev position to target; on hit, land at contact,
     *  kill the normal component (restitution via statics pass) — no tunneling at any speed */
    private void integrateBodyCCD(RigidBody rb, Vec3 disp){
        Entity e = rb.entity;
        Vec3 from = e.position.cpy();
        Vec3 to = from.cpy().add(disp);
        staticSweepScratch.clear();
        broadphase.queryStatics(shapes, from, to, staticSweepScratch);
        float r = bodyRadiusScaled(rb);
        float bestT = Float.MAX_VALUE;
        Vec3 bestN = new Vec3();
        Entity bestE = null;
        Vec3 d = disp.cpy();
        float dl = d.len();
        if(dl < 1e-9f) return;
        d.scale(1f / dl);
        Vec3 nrm = new Vec3();
        for(StaticShape s : staticSweepScratch){
            if(s.isTrigger) continue;
            statStaticChecks++;
            // inflate shape by body radius: test sphere-cast as 3 rays (center + ±tangent offset)
            float t = -1f;
            if(s.kind == KIND_BOX){
                t = raySphereCastBox(from, d, dl, s.bounds, r, nrm);
            } else if(s.kind == KIND_SPHERE){
                t = raySphereCastSphere(from, d, dl, s.center, s.radius + r, nrm);
            } else {
                t = raySphereCastCapsule(from, d, dl, s.capA, s.capB, s.radius + r, nrm);
            }
            if(t >= 0f && t < bestT){
                bestT = t;
                bestN.set(nrm);
                bestE = s.entity;
            }
        }
        rb.grounded = false;
        if(bestT <= dl){
            float lead = Math.min(dl, Math.max(0f, bestT - r * 0.25f));
            e.position.addScaled(d, lead);
            float vn = rb.velocity.dot(bestN);
            if(vn < 0f){
                float rest = Math.max(0f, PhysicsMaterials.pairRestitution(rb, null)) * (Math.abs(vn) > 1.0f ? 1f : 0.4f);
                rb.velocity.addScaled(bestN, -(1f + rest) * vn);
                applyFriction(rb, bestN, vn);
            }
            if(bestE != null && !collisionEventCooldown(e, bestE)) fireCollision(e, bestE, Math.abs(vn));
            statStaticHits++;
        } else {
            e.position.add(disp);
        }
        resolveBodyStatics(rb);
    }

    private final List<StaticShape> staticSweepScratch = new ArrayList<>();

    /** sphere-cast vs AABB: treat as ray vs inflated box (conservative but stable) */
    private static float raySphereCastBox(Vec3 o, Vec3 d, float maxT, AABB b, float r, Vec3 outN){
        AABB inflated = new AABB(
            new Vec3(b.min.x - r, b.min.y - r, b.min.z - r),
            new Vec3(b.max.x + r, b.max.y + r, b.max.z + r));
        float t = rayBox(o, d, inflated, outN);
        return (t >= 0f && t <= maxT) ? t : -1f;
    }

    private static float raySphereCastSphere(Vec3 o, Vec3 d, float maxT, Vec3 c, float rad, Vec3 outN){
        float t = raySphere(o, d, c, rad, outN);
        return (t >= 0f && t <= maxT) ? t : -1f;
    }

    private static float raySphereCastCapsule(Vec3 o, Vec3 d, float maxT, Vec3 a, Vec3 b2, float rad, Vec3 outN){
        float t1 = raySphere(o, d, a, rad, outN);
        Vec3 n2 = new Vec3();
        float t2 = raySphere(o, d, b2, rad, n2);
        float t = -1f;
        if(t1 >= 0f && t1 <= maxT){ t = t1; }
        if(t2 >= 0f && t2 <= maxT && (t < 0f || t2 < t)){ t = t2; outN.set(n2); }
        return t;
    }

    private float bodyRadiusScaled(RigidBody rb){
        return rb.effectiveRadius() * Math.max(1e-3f, worldScale(rb.entity));
    }

    /** v2: resolve body shape vs static shapes; v2.3 friction/restitution from PhysicsMaterials (PHY-029) */
    private void resolveBodyStatics(RigidBody rb){
        Entity e = rb.entity;
        Vec3 p = e.position;
        float r = bodyRadiusScaled(rb);
        for(int pass = 0; pass < 3; pass++){
            boolean any = false;
            for(StaticShape s : shapes){
                if(s.isTrigger) continue;
                statStaticChecks++;
                float pen; Vec3 outN;
                if(s.kind == KIND_BOX){
                    pen = spherePenBox(p, r, s.bounds.min, s.bounds.max, cn);
                } else if(s.kind == KIND_SPHERE){
                    pen = spherePenSphere(p, r, s.center, s.radius, cn);
                } else {
                    pen = spherePenCapsule(p, r, s.capA, s.capB, s.radius, cn);
                }
                if(pen <= 0f) continue;
                any = true;
                statStaticHits++;
                outN = cn;
                p.addScaled(outN, pen);
                float vn = rb.velocity.dot(outN);
                if(vn < 0f){
                    // impulse with restitution (material pair combined)
                    RigidBody matRef = s.entity != null ? s.entity.get(RigidBody.class) : null;
                    float rest = Math.max(0f, PhysicsMaterials.pairRestitution(rb, matRef)) * (Math.abs(vn) > 1.0f ? 1f : 0.4f);
                    rb.velocity.addScaled(outN, -(1f + rest) * vn);
                    // Coulomb friction on tangential component (material pair)
                    float mu = matRef != null && matRef.material != null && !matRef.material.isEmpty()
                        ? PhysicsMaterials.pairFriction(rb, matRef)
                        : Math.max(0f, rb.friction);
                    applyFrictionMu(rb, outN, vn, mu);
                    if(rb.sleepEnabled && rb.sleeping) rb.wake();
                    if(outN.y > 0.7f) rb.grounded = true;
                    // collision event (rate limited per pair)
                    if(s.entity != null && !collisionEventCooldown(s.entity, e)) fireCollision(e, s.entity, Math.abs(vn));
                }
            }
            if(!any) break;
        }
    }

    private void applyFrictionMu(RigidBody rb, Vec3 normal, float vn, float mu){
        if(mu <= 0.001f) return;
        Vec3 tang = rb.velocity.cpy().addScaled(normal, -rb.velocity.dot(normal));
        float tl = tang.len();
        if(tl < 1e-6f) return;
        float maxD = mu * Math.abs(vn) + 0.02f;
        float remove = Math.min(tl, maxD * 60f * 0.016f * 10f);
        tang.scale(-remove / tl);
        rb.velocity.add(tang);
    }

    private void applyFriction(RigidBody rb, Vec3 normal, float vn){
        applyFrictionMu(rb, normal, vn, Math.max(0f, rb.friction));
    }

    private boolean collisionEventCooldown(Entity a, Entity b){
        long key = pairKey(a, b);
        Float v = collisionCooldown.get(key);
        return v != null && v > 0f;
    }

    private static long pairKey(Entity a, Entity b){
        long lo = Math.min(a.id, b.id), hi = Math.max(a.id, b.id);
        return (lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private void fireCollision(Entity a, Entity b, float speed){
        collisionCooldown.put(pairKey(a, b), 0.5f);
        com.hollow.game.GameApp g = com.hollow.game.GameApp.instance;
        if(g == null || g.services() == null || g.services().events == null) return;
        if(speed < 0.8f) return;                       // ignore soft touches
        java.util.Map<String,Object> data = new java.util.LinkedHashMap<>();
        data.put("entity", a.name);
        data.put("other", b.name);
        data.put("speed", (double)speed);
        g.services().events.fire("collision", data);
    }

    // ------------------------------------------------------------------
    // v2 dynamic-dynamic pairs (sphere approx, equal-weight impulse exchange)
    // v2.3: layer filter (PHY-028) + material pairs (PHY-029) + stats (COL-020)
    // ------------------------------------------------------------------
    private void solveDynamicPairs(float dt){
        int n = bodies.size();
        statPairChecks++;
        for(int i = 0; i < n; i++){
            RigidBody A = bodies.get(i);
            if(A.entity == null || !A.entity.active || A.entity.destroyed) continue;
            for(int j2 = i + 1; j2 < n; j2++){
                RigidBody B = bodies.get(j2);
                if(B.entity == null || !B.entity.active || B.entity.destroyed) continue;
                if(A.kinematic && B.kinematic) continue;
                if(A.sleeping && B.sleeping) continue;
                if(!A.interacts(B)) continue;                      // PHY-028 layer mask
                statPairChecks++;
                float ra = bodyRadiusScaled(A), rbB = bodyRadiusScaled(B);
                Vec3 pa = A.entity.position, pb = B.entity.position;
                Vec3 d = sC.set(pb).sub(pa);
                float dist = d.len();
                float minD = ra + rbB;
                if(dist >= minD || dist < 1e-6f) continue;
                statPairHits++;
                solvePair(A, B, d, dist, minD);
            }
        }
    }

    /** PHY-027: broadphase-accelerated pair pass (bodies > threshold) */
    private void solveDynamicPairsBroadphase(float dt){
        for(long[] pairArr : broadphase.dynamicPairs){
            long pair = pairArr[0];
            int lo = (int)(pair >>> 32), hi = (int)(pair & 0xFFFFFFFFL);
            RigidBody A = bodyById(lo), B = bodyById(hi);
            if(A == null || B == null || A.entity == null || B.entity == null) continue;
            if(!A.entity.active || !B.entity.active || A.entity.destroyed || B.entity.destroyed) continue;
            if(A.kinematic && B.kinematic) continue;
            if(A.sleeping && B.sleeping) continue;
            if(!A.interacts(B)) continue;
            Vec3 d = sC.set(B.entity.position).sub(A.entity.position);
            float dist = d.len();
            float minD = bodyRadiusScaled(A) + bodyRadiusScaled(B);
            if(dist >= minD || dist < 1e-6f) continue;
            solvePair(A, B, d, dist, minD);
        }
    }

    private RigidBody bodyById(int id){
        for(RigidBody rb : bodies)
            if(rb.entity != null && rb.entity.id == id) return rb;
        return null;
    }

    /** shared pair resolution — position split, restitution/friction from materials, events */
    private void solvePair(RigidBody A, RigidBody B, Vec3 dRaw, float dist, float minD){
        Vec3 nrm = dRaw.cpy().scale(1f / dist);
        float pen = minD - dist;
        float wA = A.kinematic || A.mass <= 0f ? 0f : 1f / A.mass;
        float wB = B.kinematic || B.mass <= 0f ? 0f : 1f / B.mass;
        float wSum = wA + wB;
        if(wSum < 1e-8f) return;
        if(!A.kinematic) A.entity.position.addScaled(nrm, -pen * (wA / wSum));
        if(!B.kinematic) B.entity.position.addScaled(nrm, pen * (wB / wSum));
        float relV = B.velocity.dot(nrm) - A.velocity.dot(nrm);
        if(relV < 0f){
            float e2 = Math.max(0f, PhysicsMaterials.pairRestitution(A, B));
            float jImp = -(1f + e2) * relV / wSum;
            if(!A.kinematic) A.velocity.addScaled(nrm, -jImp * wA);
            if(!B.kinematic) B.velocity.addScaled(nrm,  jImp * wB);
            Vec3 vt = sB.set(B.velocity).sub(A.velocity).addScaled(nrm, -relV);
            float vtLen = vt.len();
            if(vtLen > 1e-5f){
                float mu = PhysicsMaterials.pairFriction(A, B);
                float jt = Math.min(vtLen / wSum, mu * jImp);
                Vec3 t = vt.scale(1f / vtLen);
                if(!A.kinematic) A.velocity.addScaled(t, -jt * wA);
                if(!B.kinematic) B.velocity.addScaled(t,  jt * wB);
            }
            if(A.sleepEnabled) A.wake();
            if(B.sleepEnabled) B.wake();
            if(!collisionEventCooldown(A.entity, B.entity)) fireCollision(A.entity, B.entity, Math.abs(relV));
        }
        A.entity.markDirtyDeep(); B.entity.markDirtyDeep();
    }

    // ------------------------------------------------------------------
    // v2 queries
    // ------------------------------------------------------------------
    /** all entities whose static shape overlaps the sphere (triggers included with flag) */
    public List<Entity> overlapSphere(Vec3 center, float radius, boolean includeTriggers){
        List<Entity> out = new ArrayList<>();
        for(StaticShape s : shapes){
            if(s.isTrigger && !includeTriggers) continue;
            float d;
            if(s.kind == KIND_BOX){
                clampToBox(center, s.bounds.min, s.bounds.max, qb);
                d = center.dist(qb);
            } else if(s.kind == KIND_SPHERE){
                d = center.dist(s.center) - s.radius;
            } else {
                closestOnSeg(s.capA, s.capB, center, qb);
                d = center.dist(qb) - s.radius;
            }
            if(d <= radius && s.entity != null) out.add(s.entity);
        }
        return out;
    }

    /** radial impulse to all dynamic bodies in range (grenades, explosions) */
    public void applyRadialImpulse(Vec3 center, float radius, float force){
        for(RigidBody rb : bodies){
            if(rb.entity == null || !rb.entity.active || rb.kinematic) continue;
            rb.addRadialImpulse(center, radius, force);
        }
    }

    public int bodyCount(){ return bodies.size(); }
    /** v2.3: read-only dynamic body list (cloth collision, ragdoll helpers, audio emitters) */
    public List<RigidBody> bodyList(){ return bodies; }
    /** v2.3: registered character controllers (wind zones, horror director queries) */
    public List<CharacterController> controllersView(){ return controllers; }
    public int awakeCount(){
        int n = 0;
        for(RigidBody rb : bodies) if(!rb.sleeping && !rb.kinematic) n++;
        return n;
    }

    /** sphere(box r) vs AABB penetration; returns penetration (>0) and out normal */
    private static float spherePenBox(Vec3 p, float r, Vec3 mn, Vec3 mx, Vec3 outN){
        float cx = MathX.clamp(p.x, mn.x, mx.x);
        float cy = MathX.clamp(p.y, mn.y, mx.y);
        float cz = MathX.clamp(p.z, mn.z, mx.z);
        float dx = p.x - cx, dy = p.y - cy, dz = p.z - cz;
        float d2 = dx*dx + dy*dy + dz*dz;
        if(d2 > r*r) return 0f;
        if(d2 > 1e-12f){
            float d = (float)Math.sqrt(d2);
            outN.set(dx/d, dy/d, dz/d);
            return r - d;
        }
        // center inside box: push along min axis
        float dxm = p.x - mn.x, dxM = mx.x - p.x;
        float dym = p.y - mn.y, dyM = mx.y - p.y;
        float dzm = p.z - mn.z, dzM = mx.z - p.z;
        float m = Math.min(dxm, Math.min(dxM, Math.min(dym, Math.min(dyM, Math.min(dzm, dzM)))));
        if(m == dxm){ outN.set(-1,0,0); return dxm + r; }
        if(m == dxM){ outN.set( 1,0,0); return dxM + r; }
        if(m == dym){ outN.set(0,-1,0); return dym + r; }
        if(m == dyM){ outN.set(0, 1,0); return dyM + r; }
        if(m == dzm){ outN.set(0,0,-1); return dzm + r; }
        outN.set(0,0, 1); return dzM + r;
    }

    private static float spherePenSphere(Vec3 p, float r, Vec3 c, float cr, Vec3 outN){
        Vec3 d = p.cpy().sub(c);
        float dist = d.len();
        float minD = r + cr;
        if(dist >= minD) return 0f;
        if(dist < 1e-6f){ outN.set(0,1,0); return minD; }
        outN.set(d.scale(1f/dist));
        return minD - dist;
    }

    private static float spherePenCapsule(Vec3 p, float r, Vec3 a, Vec3 b, float cr, Vec3 outN){
        Vec3 tmp = new Vec3();
        closestOnSeg(a, b, p, tmp);
        return spherePenSphere(p, r, tmp, cr, outN);
    }

    // ------------------------------------------------------------------
    // v1 legacy point-sphere solver (kept for compatibility; superseded by resolveBodyStatics)
    // ------------------------------------------------------------------
    private void resolvePointSphere(RigidBody rb){
        resolveBodyStatics(rb);
    }

    private AABB expand(AABB b, Vec3 r){
        return new AABB(
            new Vec3(b.min.x - r.x, b.min.y - r.y, b.min.z - r.z),
            new Vec3(b.max.x + r.x, b.max.y + r.y, b.max.z + r.z));
    }

    private void minAxisPush(Vec3 p, Vec3 mn, Vec3 mx, float radius, Vec3 outNormal){
        float dxm = p.x - mn.x, dxM = mx.x - p.x;
        float dym = p.y - mn.y, dyM = mx.y - p.y;
        float dzm = p.z - mn.z, dzM = mx.z - p.z;
        float m = Math.min(dxm, Math.min(dxM, Math.min(dym, Math.min(dyM, Math.min(dzm, dzM)))));
        // outNormal points out of the box on the closest face; caller applies (faceDist + radius) push
        if(m == dxm){ outNormal.set(-1, 0, 0); }
        else if(m == dxM){ outNormal.set(1, 0, 0); }
        else if(m == dym){ outNormal.set(0, -1, 0); }
        else if(m == dyM){ outNormal.set(0, 1, 0); }
        else if(m == dzm){ outNormal.set(0, 0, -1); }
        else { outNormal.set(0, 0, 1); }
    }

    // ------------------------------------------------------------------
    // triggers
    // ------------------------------------------------------------------
    private void updateTriggers(){
        List<Object> movers = new ArrayList<>(controllers.size() + bodies.size());
        movers.addAll(controllers);
        movers.addAll(bodies);
        insideSets.keySet().removeIf(k -> !movers.contains(k));
        for(Object mover : movers){
            Entity e;
            float mr;
            Vec3 center;
            if(mover instanceof CharacterController){
                CharacterController cc = (CharacterController) mover;
                e = cc.entity;
                if(e == null || !e.active || e.destroyed) continue;
                mr = cc.radius;
                center = e.worldPosition().cpy().add(0, cc.height * 0.5f, 0);
            } else {
                RigidBody rb = (RigidBody) mover;
                e = rb.entity;
                if(e == null || !e.active || e.destroyed) continue;
                mr = BODY_RADIUS;
                center = e.worldPosition().cpy();
            }
            HashSet<StaticShape> prev = insideSets.get(mover);
            HashSet<StaticShape> now = new HashSet<>();
            for(StaticShape s : shapes){
                if(!s.isTrigger) continue;
                if(overlapMover(center, mr, s)) now.add(s);
            }
            for(StaticShape s : now)
                if(prev == null || !prev.contains(s)) fireTrigger("trigger_enter", s, e);
            if(prev != null)
                for(StaticShape s : prev)
                    if(!now.contains(s)) fireTrigger("trigger_exit", s, e);
            insideSets.put(mover, now);
        }
    }

    private boolean overlapMover(Vec3 center, float mr, StaticShape s){
        if(s.kind == KIND_BOX){
            clampToBox(center, s.bounds.min, s.bounds.max, qb);
            return center.dist2(qb) < mr * mr;
        }
        if(s.kind == KIND_SPHERE) return center.dist2(s.center) < (mr + s.radius) * (mr + s.radius);
        closestOnSeg(s.capA, s.capB, center, qb);
        return center.dist2(qb) < (mr + s.radius) * (mr + s.radius);
    }

    private void fireTrigger(String name, StaticShape zone, Entity mover){
        com.hollow.game.GameApp g = com.hollow.game.GameApp.instance;
        if(g == null || g.services() == null || g.services().events == null) return;
        java.util.Map<String,Object> data = new java.util.LinkedHashMap<>();
        data.put("zone", zone.entity != null ? zone.entity.name : "?");
        data.put("entity", mover != null ? mover.name : "?");
        g.services().events.fire(name, data);
    }

    // ------------------------------------------------------------------
    // character controller support (package-private; called by CharacterController)
    // ------------------------------------------------------------------
    void moveController(CharacterController c, Vec3 displacement){
        Entity e = c.entity;
        if(e == null) return;
        Vec3 start = e.position.cpy();
        Vec3 d = displacement.cpy();
        int n = substeps(d, c.radius * 0.5f);
        Vec3 step = d.cpy().scale(1f / n);
        for(int i = 0; i < n; i++) sweepStep(c, step);
        // step-up assist: horizontal progress blocked while grounded
        float wantH = (float)Math.sqrt(d.x * d.x + d.z * d.z);
        Vec3 moved = e.position.cpy().sub(start);
        float gotH = (float)Math.sqrt(moved.x * moved.x + moved.z * moved.z);
        if(wantH > 1e-4f && gotH < wantH - 1e-3f && c.isGrounded()){
            Vec3 saved = e.position.cpy();
            e.position.set(start);
            sweepStep(c, sA.set(0, c.stepHeight, 0));
            Vec3 h = new Vec3(d.x, 0, d.z);
            int m = substeps(h, c.radius * 0.5f);
            Vec3 hs = h.scale(1f / m);
            for(int i = 0; i < m; i++) sweepStep(c, hs);
            Vec3 moved2 = e.position.cpy().sub(start);
            float gotH2 = (float)Math.sqrt(moved2.x * moved2.x + moved2.z * moved2.z);
            sweepStep(c, sA.set(0, -(c.stepHeight + c.skinWidth + 0.05f), 0));
            if(gotH2 <= gotH + 1e-3f) e.position.set(saved);
        }
        groundCheck(c);
        e.markDirtyDeep();
    }

    void stepController(CharacterController c, float dt){
        // gravity channel lives in velocity.y
        if(c.useGravity){
            if(c.isGrounded() && c.getVelocity().y < 0f) c.getVelocity().y = -0.5f; // ground stick
            c.getVelocity().y = Math.max(-55f, c.getVelocity().y + gravity.y * dt);
        }
        float dy = c.getVelocity().y * dt;
        if(Math.abs(dy) > 1e-7f) c.move(new Vec3(0, dy, 0));
        groundCheck(c);
        if(c.isGrounded() && c.getVelocity().y < 0f) c.getVelocity().y = 0f;
    }

    private int substeps(Vec3 d, float maxLen){
        int n = 1 + (int)(d.len() / Math.max(0.02f, maxLen));
        return Math.min(n, 32);
    }

    private void sweepStep(CharacterController c, Vec3 step){
        if(step.len2() < 1e-12f) return;
        Entity e = c.entity;
        e.position.add(step);
        resolveCapsule(c);
    }

    /** push capsule out of all overlapping statics (iterate to settle; sliding emerges) */
    private void resolveCapsule(CharacterController c){
        Entity e = c.entity;
        Vec3 p = e.position;
        float r = c.radius, h = c.height;
        ca.set(p.x, p.y + r, p.z);
        cb.set(p.x, p.y + Math.max(r, h - r), p.z);
        for(int pass = 0; pass < 4; pass++){
            boolean any = false;
            for(StaticShape s : shapes){
                if(s.isTrigger) continue;
                sA.set(r, h * 0.5f, r);
                sB.set(p.x, p.y + h * 0.5f, p.z);
                if(!expand(s.bounds, sA).overlaps(AABB.fromCenterExtents(sB, sA))) continue;
                float pen;
                if(s.kind == KIND_BOX){
                    closestSegBox(ca, cb, s.bounds.min, s.bounds.max, qs, qb);
                    cv.set(qs).sub(qb);
                    float dist = cv.len();
                    if(dist >= r) continue;
                    any = true;
                    if(dist < 1e-5f){
                        // deep: segment point inside box -> push along min axis + radius
                        cn.set(0, 1, 0);
                        pen = pushOutMinAxis(qs, s.bounds.min, s.bounds.max, r, cn);
                    } else {
                        cn.set(cv).scale(1f / dist);
                        pen = r - dist;
                    }
                } else if(s.kind == KIND_SPHERE){
                    closestOnSeg(ca, cb, s.center, qb);
                    cv.set(qb).sub(s.center);
                    float dist = cv.len();
                    if(dist >= r + s.radius) continue;
                    any = true;
                    if(dist < 1e-5f){ cn.set(0, 1, 0); pen = r + s.radius; }
                    else { cn.set(cv).scale(1f / dist); pen = r + s.radius - dist; }
                } else {
                    closestSegSeg(ca, cb, s.capA, s.capB, qs, qb);
                    cv.set(qs).sub(qb);
                    float dist = cv.len();
                    if(dist >= r + s.radius) continue;
                    any = true;
                    if(dist < 1e-5f){ cn.set(0, 1, 0); pen = r + s.radius; }
                    else { cn.set(cv).scale(1f / dist); pen = r + s.radius - dist; }
                }
                p.addScaled(cn, pen);
                ca.set(p.x, p.y + r, p.z);
                cb.set(p.x, p.y + Math.max(r, h - r), p.z);
                if(cn.y > 0.7f && c.groundNormal().y < cn.y) c.setGroundNormal(cn);
            }
            if(!any) break;
        }
    }

    private float pushOutMinAxis(Vec3 p, Vec3 mn, Vec3 mx, float radius, Vec3 outNormal){
        float dxm = p.x - mn.x, dxM = mx.x - p.x;
        float dym = p.y - mn.y, dyM = mx.y - p.y;
        float dzm = p.z - mn.z, dzM = mx.z - p.z;
        float m = Math.min(dxm, Math.min(dxM, Math.min(dym, Math.min(dyM, Math.min(dzm, dzM)))));
        float pen;
        if(m == dxm){ outNormal.set(-1, 0, 0); pen = dxm + radius; }
        else if(m == dxM){ outNormal.set(1, 0, 0); pen = dxM + radius; }
        else if(m == dym){ outNormal.set(0, -1, 0); pen = dym + radius; }
        else if(m == dyM){ outNormal.set(0, 1, 0); pen = dyM + radius; }
        else if(m == dzm){ outNormal.set(0, 0, -1); pen = dzm + radius; }
        else { outNormal.set(0, 0, 1); pen = dzM + radius; }
        return pen;
    }

    /** ground probe: capsule moved down by skinWidth+0.05 must touch something walkable */
    void groundCheck(CharacterController c){
        Entity e = c.entity;
        if(e == null) return;
        c.setGrounded(false);
        c.groundNormal().set(0, 1, 0);
        if(c.getVelocity().y > 0.01f) return; // moving up (jump) — not grounded
        float r = c.radius, h = c.height;
        float probe = c.skinWidth + 0.05f;
        float px = e.position.x, py = e.position.y - probe, pz = e.position.z;
        ca.set(px, py + r, pz);
        cb.set(px, py + Math.max(r, h - r), pz);
        float bestNy = 0f;
        Vec3 bestN = new Vec3(0, 1, 0);
        float cosLimit = (float)Math.cos(Math.toRadians(Math.max(1f, Math.min(89f, c.slopeLimit))));
        for(StaticShape s : shapes){
            if(s.isTrigger) continue;
            sA.set(r, h * 0.5f + probe, r);
            sB.set(px, py + h * 0.5f, pz);
            if(!expand(s.bounds, sA).overlaps(AABB.fromCenterExtents(sB, sA))) continue;
            float dist;
            if(s.kind == KIND_BOX){
                closestSegBox(ca, cb, s.bounds.min, s.bounds.max, qs, qb);
                cv.set(qs).sub(qb);
                dist = cv.len();
                if(dist >= r) continue;
                if(dist < 1e-5f) cn.set(0, 1, 0);
                else cn.set(cv).scale(1f / dist);
            } else if(s.kind == KIND_SPHERE){
                closestOnSeg(ca, cb, s.center, qb);
                cv.set(qb).sub(s.center);
                dist = cv.len();
                if(dist >= r + s.radius) continue;
                if(dist < 1e-5f) cn.set(0, 1, 0);
                else cn.set(cv).scale(1f / dist);
            } else {
                closestSegSeg(ca, cb, s.capA, s.capB, qs, qb);
                cv.set(qs).sub(qb);
                dist = cv.len();
                if(dist >= r + s.radius) continue;
                if(dist < 1e-5f) cn.set(0, 1, 0);
                else cn.set(cv).scale(1f / dist);
            }
            if(cn.y >= bestNy && cn.y >= cosLimit){
                bestNy = cn.y;
                bestN.set(cn);
            }
        }
        if(bestNy > 0f){
            c.setGrounded(true);
            c.setGroundNormal(bestN);
        }
    }

    // ------------------------------------------------------------------
    // raycast
    // ------------------------------------------------------------------
    public static final class Hit {
        public boolean hit;
        public final com.hollow.core.Vec3 point = new com.hollow.core.Vec3();
        public final com.hollow.core.Vec3 normal = new com.hollow.core.Vec3(0,1,0);
        public Entity entity;
        public float distance;
    }

    public Hit raycast(Vec3 origin, Vec3 dir, float maxDist){
        Hit out = new Hit();
        if(origin == null || dir == null) return out;
        Vec3 d = dir.cpy();
        float L = d.len();
        if(L < 1e-9f) return out;
        d.scale(1f / L);
        if(maxDist < 0f) return out;
        float bestT = maxDist;
        boolean found = false;
        Vec3 bestN = new Vec3(0, 1, 0);
        Entity bestE = null;
        Vec3 nrm = new Vec3();
        for(StaticShape s : shapes){
            if(s.isTrigger) continue;
            float t = -1f;
            if(s.kind == KIND_BOX){
                t = rayBox(origin, d, s.bounds, nrm);
            } else if(s.kind == KIND_SPHERE){
                t = raySphere(origin, d, s.center, s.radius, nrm);
            } else {
                float t1 = raySphere(origin, d, s.capA, s.radius, nrm);
                Vec3 n2 = new Vec3();
                float t2 = raySphere(origin, d, s.capB, s.radius, n2);
                Vec3 n3 = new Vec3();
                float t3 = rayBox(origin, d, s.bounds, n3);
                t = t1;
                if(t2 >= 0f && (t < 0f || t2 < t)){ t = t2; nrm.set(n2); }
                if(t3 >= 0f && (t < 0f || t3 < t)){ t = t3; nrm.set(n3); }
            }
            if(t >= 0f && t < bestT){
                bestT = t;
                found = true;
                bestN.set(nrm);
                bestE = s.entity;
            }
        }
        out.hit = found;
        if(found){
            out.distance = bestT;
            out.point.set(origin).addScaled(d, bestT);
            out.normal.set(bestN);
            out.entity = bestE;
        } else {
            out.distance = maxDist;
        }
        return out;
    }

    /** slab test; returns entry t or -1; normal faces the ray */
    static float rayBox(Vec3 o, Vec3 d, AABB b, Vec3 outNormal){
        float tmin = 0f, tmax = Float.MAX_VALUE;
        int axis = 1; float sign = -1f;
        // X
        if(Math.abs(d.x) < 1e-9f){
            if(o.x < b.min.x || o.x > b.max.x) return -1f;
        } else {
            float inv = 1f / d.x;
            float t1 = (b.min.x - o.x) * inv, t2 = (b.max.x - o.x) * inv;
            float s = -1f;
            if(t1 > t2){ float tt = t1; t1 = t2; t2 = tt; s = 1f; }
            if(t1 > tmin){ tmin = t1; axis = 0; sign = s; }
            if(t2 < tmax) tmax = t2;
            if(tmin > tmax) return -1f;
        }
        // Y
        if(Math.abs(d.y) < 1e-9f){
            if(o.y < b.min.y || o.y > b.max.y) return -1f;
        } else {
            float inv = 1f / d.y;
            float t1 = (b.min.y - o.y) * inv, t2 = (b.max.y - o.y) * inv;
            float s = -1f;
            if(t1 > t2){ float tt = t1; t1 = t2; t2 = tt; s = 1f; }
            if(t1 > tmin){ tmin = t1; axis = 1; sign = s; }
            if(t2 < tmax) tmax = t2;
            if(tmin > tmax) return -1f;
        }
        // Z
        if(Math.abs(d.z) < 1e-9f){
            if(o.z < b.min.z || o.z > b.max.z) return -1f;
        } else {
            float inv = 1f / d.z;
            float t1 = (b.min.z - o.z) * inv, t2 = (b.max.z - o.z) * inv;
            float s = -1f;
            if(t1 > t2){ float tt = t1; t1 = t2; t2 = tt; s = 1f; }
            if(t1 > tmin){ tmin = t1; axis = 2; sign = s; }
            if(t2 < tmax) tmax = t2;
            if(tmin > tmax) return -1f;
        }
        if(tmin < 0f) return -1f; // origin inside (or behind) — treat as miss
        switch(axis){
            case 0: outNormal.set(sign, 0, 0); break;
            case 1: outNormal.set(0, sign, 0); break;
            default: outNormal.set(0, 0, sign); break;
        }
        return tmin;
    }

    static float raySphere(Vec3 o, Vec3 d, Vec3 center, float radius, Vec3 outNormal){
        float ox = o.x - center.x, oy = o.y - center.y, oz = o.z - center.z;
        float b = ox * d.x + oy * d.y + oz * d.z;
        float c = ox * ox + oy * oy + oz * oz - radius * radius;
        float disc = b * b - c;
        if(disc < 0f) return -1f;
        float sq = (float)Math.sqrt(disc);
        float t = -b - sq;
        if(t < 0f) t = -b + sq;
        if(t < 0f) return -1f;
        outNormal.set(o.x + d.x * t - center.x, o.y + d.y * t - center.y, o.z + d.z * t - center.z).scale(1f / Math.max(1e-9f, radius));
        return t;
    }

    public int colliderCount(){ return shapes.size(); }

    // ------------------------------------------------------------------
    // closest-point helpers (public: EQS and gameplay queries reuse them)
    // ------------------------------------------------------------------
    public static void clampToBox(Vec3 p, Vec3 mn, Vec3 mx, Vec3 out){
        out.set(MathX.clamp(p.x, mn.x, mx.x), MathX.clamp(p.y, mn.y, mx.y), MathX.clamp(p.z, mn.z, mx.z));
    }

    public static void closestOnSeg(Vec3 a, Vec3 b, Vec3 p, Vec3 out){
        float abx = b.x - a.x, aby = b.y - a.y, abz = b.z - a.z;
        float l2 = abx * abx + aby * aby + abz * abz;
        float t = 0f;
        if(l2 > 1e-12f) t = MathX.clamp(((p.x - a.x) * abx + (p.y - a.y) * aby + (p.z - a.z) * abz) / l2, 0f, 1f);
        out.set(a.x + abx * t, a.y + aby * t, a.z + abz * t);
    }

    /** closest points between segment (a,b) and AABB via alternating projection */
    static void closestSegBox(Vec3 a, Vec3 b, Vec3 mn, Vec3 mx, Vec3 outSeg, Vec3 outBox){
        Vec3 mid = a.cpy().add(b).scale(0.5f);
        clampToBox(mid, mn, mx, outBox);
        closestOnSeg(a, b, outBox, outSeg);
        for(int i = 0; i < 4; i++){
            clampToBox(outSeg, mn, mx, outBox);
            closestOnSeg(a, b, outBox, outSeg);
        }
    }

    /** Ericson closest point segment-segment */
    static void closestSegSeg(Vec3 p1, Vec3 q1, Vec3 p2, Vec3 q2, Vec3 c1, Vec3 c2){
        float d1x = q1.x - p1.x, d1y = q1.y - p1.y, d1z = q1.z - p1.z;
        float d2x = q2.x - p2.x, d2y = q2.y - p2.y, d2z = q2.z - p2.z;
        float rx = p1.x - p2.x, ry = p1.y - p2.y, rz = p1.z - p2.z;
        float a = d1x * d1x + d1y * d1y + d1z * d1z;
        float e = d2x * d2x + d2y * d2y + d2z * d2z;
        float f = d2x * rx + d2y * ry + d2z * rz;
        float c = d1x * rx + d1y * ry + d1z * rz;
        float b = d1x * d2x + d1y * d2y + d1z * d2z;
        float denom = a * e - b * b;
        float s, t;
        if(denom < 1e-12f){
            s = 0f;
            t = e > 1e-12f ? MathX.clamp(f / e, 0f, 1f) : 0f;
        } else {
            s = MathX.clamp((b * f - c * e) / denom, 0f, 1f);
            t = e > 1e-12f ? (b * s + f) / e : 0f;
            if(t < 0f){ t = 0f; s = a > 1e-12f ? MathX.clamp(-c / a, 0f, 1f) : 0f; }
            else if(t > 1f){ t = 1f; s = a > 1e-12f ? MathX.clamp((b - c) / a, 0f, 1f) : 0f; }
        }
        c1.set(p1.x + d1x * s, p1.y + d1y * s, p1.z + d1z * s);
        c2.set(p2.x + d2x * t, p2.y + d2y * t, p2.z + d2z * t);
    }
}
