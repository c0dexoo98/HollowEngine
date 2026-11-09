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
 *  Implementation notes (Agent C):
 *  - Statics are rebuilt on every onWorldLoaded(world) from Box/Sphere/CapsuleCollider components.
 *    All shapes are stored in world space (AABB for boxes; center+radius for spheres; vertical
 *    segment+radius for capsules). Rotated boxes are approximated by their enclosing world AABB.
 *  - update(dt) integrates RigidBody entities (point-sphere dynamics, radius 0.25) with collision
 *    resolve against statics, then tests movers vs trigger colliders and fires EventBus
 *    "trigger_enter"/"trigger_exit" once per state change. CharacterControllers integrate
 *    themselves via CharacterController.onUpdate (gravity) + move() (collide-and-slide).
 *  - raycast: slab test for boxes, quadratic for spheres, capsules as 2 spheres + bounds AABB.
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
        if(world == null) return;
        for(Entity e : world.entities) collect(e);
        Log.d("Physics", "statics=" + shapes.size() + " controllers=" + controllers.size() + " bodies=" + bodies.size());
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

    // ------------------------------------------------------------------
    // simulation
    // ------------------------------------------------------------------
    public void update(float dt){
        if(navGrid == null) navGrid = new NavGrid(this);
        if(dt <= 0f) return;
        controllers.removeIf(c -> c.entity == null || c.entity.destroyed);
        bodies.removeIf(b -> b.entity == null || b.entity.destroyed);
        for(RigidBody rb : bodies){
            if(rb.entity == null || !rb.entity.active || rb.entity.destroyed) continue;
            if(!rb.kinematic) integrateBody(rb, dt);
        }
        updateTriggers();
    }

    private void integrateBody(RigidBody rb, float dt){
        Entity e = rb.entity;
        rb.velocity.addScaled(gravity, rb.gravityScale * dt);
        if(rb.drag > 0f){
            float damp = Math.max(0f, 1f - rb.drag * dt);
            rb.velocity.scale(damp);
        }
        Vec3 disp = sA.set(rb.velocity).scale(dt);
        int n = 1 + (int)(disp.len() / 0.2f);
        n = Math.min(n, 16);
        Vec3 step = sB.set(disp).scale(1f / n);
        rb.grounded = false;
        for(int i = 0; i < n; i++){
            e.position.add(step);
            resolvePointSphere(rb);
        }
        e.markDirtyDeep();
    }

    /** point-sphere (radius BODY_RADIUS) vs statics; reflects velocity by bounciness */
    private void resolvePointSphere(RigidBody rb){
        Entity e = rb.entity;
        Vec3 p = e.position;
        for(int pass = 0; pass < 3; pass++){
            boolean any = false;
            for(StaticShape s : shapes){
                if(s.isTrigger) continue;
                sA.set(BODY_RADIUS, BODY_RADIUS, BODY_RADIUS);
                if(!expand(s.bounds, sA).overlaps(AABB.fromCenterExtents(p, sA))) continue;
                float pen = 0f;
                sC.set(0, 0, 0); // normal out
                if(s.kind == KIND_BOX){
                    clampToBox(p, s.bounds.min, s.bounds.max, qb);
                    sB.set(p).sub(qb);
                    float d = sB.len();
                    if(d >= BODY_RADIUS) continue;
                    float faceDist;
                    if(d < 1e-6f){
                        minAxisPush(p, s.bounds.min, s.bounds.max, BODY_RADIUS, sC);
                        faceDist = 0f;
                    } else {
                        sC.set(sB).scale(1f / d);
                        faceDist = d;
                    }
                    pen = BODY_RADIUS - faceDist;
                } else if(s.kind == KIND_SPHERE){
                    sB.set(p).sub(s.center);
                    float d = sB.len();
                    if(d >= BODY_RADIUS + s.radius) continue;
                    if(d < 1e-6f) sC.set(0, 1, 0);
                    else sC.set(sB).scale(1f / d);
                    pen = BODY_RADIUS + s.radius - d;
                } else {
                    closestOnSeg(s.capA, s.capB, p, qb);
                    sB.set(p).sub(qb);
                    float d = sB.len();
                    if(d >= BODY_RADIUS + s.radius) continue;
                    if(d < 1e-6f) sC.set(0, 1, 0);
                    else sC.set(sB).scale(1f / d);
                    pen = BODY_RADIUS + s.radius - d;
                }
                any = true;
                p.addScaled(sC, pen);
                // velocity response
                float vn = rb.velocity.dot(sC);
                if(vn < 0f){
                    rb.velocity.addScaled(sC, -(1f + Math.max(0f, rb.bounciness)) * vn);
                    if(rb.bounciness <= 0.001f && sC.y > 0.7f) rb.velocity.y = Math.max(rb.velocity.y, 0f);
                }
                if(sC.y > 0.7f) rb.grounded = true;
            }
            if(!any) break;
        }
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
    // closest-point helpers
    // ------------------------------------------------------------------
    static void clampToBox(Vec3 p, Vec3 mn, Vec3 mx, Vec3 out){
        out.set(MathX.clamp(p.x, mn.x, mx.x), MathX.clamp(p.y, mn.y, mx.y), MathX.clamp(p.z, mn.z, mx.z));
    }

    static void closestOnSeg(Vec3 a, Vec3 b, Vec3 p, Vec3 out){
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
