package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.List;

/** Constraints between two RigidBody entities (HollowEngine 2.0, extended v2.3).
 *  <pre>
 *  Joints.Distance : keeps world distance within [min,max]
 *  Joints.Spring   : Hooke spring toward restLength (stiffness/damping)
 *  Joints.Hinge    : pins anchors together, slides/rotates along axis (axis-lock lite)
 *                    v2.3: angular motor + swing limits + auto-stop (PHY-025 door physics)
 *  Joints.Point    : v2.3 ball-socket — pins two local anchor points together (PHY-026)
 *  Joints.Manager  : held by PhysicsWorld — collects Joint components on world load, solves per step
 *                    v2.3: stress meter + stress-break + break events (PHY-035)
 *  </pre>
 *  Joint components serialize as {"type":"DistanceJoint"/"SpringJoint"/"HingeJoint"/"PointJoint", ...}.
 */
public final class Joints {
    private Joints() {}

    public static abstract class Joint extends Component {
        public String other = "";        // name of the other entity
        public boolean breakable = false;
        public float breakForce = 0f;    // impulse magnitude that breaks (if breakable)
        // v2.3 PHY-035 stress meter: running average of applied impulse; break when exceeding limit
        public float stressLimit = 0f;   // 0 = stress tracking without break
        public float stress = 0f;        // smoothed impulse magnitude (read by gameplay)
        protected boolean broken = false;
        protected RigidBody bodyA, bodyB;
        /** v2.3: headless/tests — explicit world for "other" lookup when GameApp is absent */
        public com.hollow.scene.World bindWorldOverride = null;

        public boolean isBroken(){ return broken; }
        public void breakJoint(){ broken = true; }

        /** solve one iteration; returns applied impulse magnitude (for breaking) */
        abstract float solve(float dt);

        void bind(){
            bodyA = entity != null ? entity.get(RigidBody.class) : null;
            com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
            com.hollow.scene.World w = bindWorldOverride != null ? bindWorldOverride
                : (app != null ? app.world : null);
            Entity ob = entity != null && w != null ? w.find(other) : null;
            bodyB = ob != null ? ob.get(RigidBody.class) : null;
            if(bodyA == null) bodyA = ensureBody(entity);
            if(bodyB == null) bodyB = ensureBody(ob);
        }

        private static RigidBody ensureBody(Entity e){
            if(e == null) return null;
            RigidBody rb = e.get(RigidBody.class);
            if(rb == null){ rb = new RigidBody(); e.add(rb); }
            return rb;
        }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type", type()).kv("other", other)
              .kv("breakable", breakable).kv("breakForce", breakForce)
              .kv("stressLimit", stressLimit);
            saveFields(w);
            w.endObj();
        }
        abstract void saveFields(JsonW w);

        @Override public void load(JsonV n){
            other = n.str("other","");
            breakable = n.bool("breakable", false);
            breakForce = n.flt("breakForce", 0f);
            stressLimit = n.flt("stressLimit", 0f);
            loadFields(n);
        }
        abstract void loadFields(JsonV n);
    }

    // ------------------------------------------------------------------
    public static final class Distance extends Joint {
        public float min = 1f, max = 1f;
        public Distance(){ }
        public Distance(float min, float max){ this.min = min; this.max = max; }

        @Override float solve(float dt){
            if(entity == null || bodyA == null || bodyB == null || broken) return 0f;
            Vec3 pa = entity.worldPosition(), pb = bodyB.entity.worldPosition();
            Vec3 d = pb.cpy().sub(pa);
            float dist = d.len();
            if(dist < 1e-5f) return 0f;
            if(dist >= min - 1e-4f && dist <= max + 1e-4f) return 0f;
            float target = dist < min ? min : max;
            Vec3 n = d.scale(1f / dist);
            float err = target - dist;                    // >0 push apart, <0 pull
            float wA = bodyA.kinematic || bodyA.mass <= 0f ? 0f : 1f / bodyA.mass;
            float wB = bodyB.kinematic || bodyB.mass <= 0f ? 0f : 1f / bodyB.mass;
            float wSum = wA + wB;
            if(wSum < 1e-8f) return 0f;
            float corr = err / wSum;
            float relV = bodyB.velocity.dot(n) - bodyA.velocity.dot(n);
            if(wA > 0f){
                if(!bodyA.kinematic) entity.position.addScaled(n, -corr * wA);
                bodyA.velocity.addScaled(n, relV * (wA / wSum));   // cancel approach velocity (relative)
            }
            if(wB > 0f){
                if(!bodyB.kinematic) bodyB.entity.position.addScaled(n, corr * wB);
                bodyB.velocity.addScaled(n, -relV * (wB / wSum));
            }
            entity.markDirtyDeep(); bodyB.entity.markDirtyDeep();
            return Math.abs(corr);
        }

        @Override void saveFields(JsonW w){ w.kv("min", min).kv("max", max); }
        @Override void loadFields(JsonV n){ min = n.flt("min",1f); max = n.flt("max",1f); }
        @Override public String type(){ return "DistanceJoint"; }
    }

    public static final class Spring extends Joint {
        public float restLength = 2f;
        public float stiffness = 20f;
        public float damping = 1.5f;
        public Spring(){ }

        @Override float solve(float dt){
            if(entity == null || bodyA == null || bodyB == null || broken || dt <= 0f) return 0f;
            Vec3 pa = entity.worldPosition(), pb = bodyB.entity.worldPosition();
            Vec3 d = pb.cpy().sub(pa);
            float dist = d.len();
            if(dist < 1e-5f) return 0f;
            Vec3 n = d.scale(1f / dist);
            float x = dist - restLength;
            float relV = bodyB.velocity.dot(n) - bodyA.velocity.dot(n);
            float f = -stiffness * x - damping * relV;   // spring force on B toward rest
            float wA = bodyA.kinematic || bodyA.mass <= 0f ? 0f : 1f / bodyA.mass;
            float wB = bodyB.kinematic || bodyB.mass <= 0f ? 0f : 1f / bodyB.mass;
            if(wA + wB < 1e-8f) return 0f;
            float imp = f * dt;
            if(wA > 0f) bodyA.velocity.addScaled(n, -imp * wA);
            if(wB > 0f) bodyB.velocity.addScaled(n,  imp * wB);
            return Math.abs(imp);
        }

        @Override void saveFields(JsonW w){ w.kv("restLength",restLength).kv("stiffness",stiffness).kv("damping",damping); }
        @Override void loadFields(JsonV n){ restLength=n.flt("restLength",2f); stiffness=n.flt("stiffness",20f); damping=n.flt("damping",1.5f); }
        @Override public String type(){ return "SpringJoint"; }
    }

    /** axis-lock lite: pins two bodies together and locks relative motion along an axis,
     *  optional motor drives relative speed along the axis — approximates a hinge for mobile scale.
     *  v2.3 (PHY-025): angular motor spins bodyB around the axis (rad/s) with a torque cap,
     *  and optional limits stop rotation at [minAngle,maxAngle] — a real door hinge. */
    public static final class Hinge extends Joint {
        public Vec3 axis = new Vec3(0, 1, 0);
        public float motorSpeed = 0f;      // units/s along axis (optional linear motor)
        // v2.3 door-hinge extension:
        public float angularMotorSpeed = 0f;   // rad/s around axis (0 = free)
        public float maxMotorTorque = 0f;      // 0 = unlimited
        public boolean useLimits = false;
        public float minAngle = 0f, maxAngle = 90f;   // degrees, relative to bodyA yaw at bind time
        public Hinge(){ }

        float boundYawA = 0f;

        @Override float solve(float dt){
            if(entity == null || bodyA == null || bodyB == null || broken) return 0f;
            // position pin: pull anchors to midpoint
            Vec3 pa = entity.worldPosition(), pb = bodyB.entity.worldPosition();
            Vec3 mid = pa.cpy().add(pb).scale(0.5f);
            if(!bodyA.kinematic && bodyA.mass > 0f) entity.position.add(mid.cpy().sub(pa).scale(0.5f));
            if(!bodyB.kinematic && bodyB.mass > 0f) bodyB.entity.position.add(mid.cpy().sub(pb).scale(0.5f));
            Vec3 n = axis.cpy();
            float len = n.len(); if(len < 1e-6f) return 0f;
            n.scale(1f / len);
            float relV = bodyB.velocity.dot(n) - bodyA.velocity.dot(n);
            float imp;
            if(Math.abs(motorSpeed) > 1e-5f){
                imp = (motorSpeed - relV) * 0.5f;
            } else {
                imp = -relV * 0.5f;
            }
            if(!bodyA.kinematic) bodyA.velocity.addScaled(n, -imp);
            if(!bodyB.kinematic) bodyB.velocity.addScaled(n,  imp);

            float applied = Math.abs(relV);
            // v2.3 angular motor: drive relative yaw around axis toward motorSpeed
            if(Math.abs(angularMotorSpeed) > 1e-5f && dt > 0f){
                float relYaw = relativeYaw();
                float stepYaw = angularMotorSpeed * dt;              // radians this step
                if(useLimits){
                    float relDeg = (float)Math.toDegrees(relYaw);
                    float next = clampAngle(relDeg + (float)Math.toDegrees(stepYaw));
                    stepYaw = (float)Math.toRadians(next - relDeg);
                }
                if(Math.abs(stepYaw) > 1e-6f){
                    float torqueImp = Math.abs(stepYaw) / Math.max(1e-4f, dt);
                    if(maxMotorTorque > 0f && torqueImp > maxMotorTorque) stepYaw *= maxMotorTorque / torqueImp;
                    com.hollow.core.Quat delta = com.hollow.core.Quat.fromAxisAngle(n.x, n.y, n.z, (float)Math.toDegrees(stepYaw));
                    delta.mul(bodyB.entity.rotation);
                    bodyB.entity.rotation.set(delta).norm();
                    bodyB.entity.markDirtyDeep();
                    applied = Math.max(applied, Math.abs(stepYaw) / Math.max(1e-4f, dt));
                }
            }
            entity.markDirtyDeep(); bodyB.entity.markDirtyDeep();
            return applied;
        }

        private float relativeYaw(){
            // q_rel = qB * qA^-1 — then extract yaw from the relative quaternion
            com.hollow.core.Quat q = bodyB.entity.rotation.cpy();
            com.hollow.core.Quat invA = bodyA.entity.rotation.cpy(); invA.invert();
            q.mul(invA);
            float yaw = (float)Math.atan2(2f * (q.w * q.y + q.z * q.x), 1f - 2f * (q.y * q.y + q.z * q.z));
            yaw -= (float)Math.toRadians(boundYawA);
            return (float)Math.toDegrees(yaw);
        }

        private float clampAngle(float deg){
            float d = deg;
            while(d > 180f) d -= 360f;
            while(d < -180f) d += 360f;
            return Math.max(minAngle, Math.min(maxAngle, d));
        }

        @Override void bind(){
            super.bind();
            if(bodyA != null && bodyA.entity != null){
                com.hollow.core.Quat q = bodyA.entity.rotation;
                boundYawA = (float)Math.toDegrees(Math.atan2(2f * (q.w * q.y + q.z * q.x), 1f - 2f * (q.y * q.y + q.z * q.z)));
            }
        }

        @Override void saveFields(JsonW w){
            w.kvv("axis", axis.x, axis.y, axis.z).kv("motorSpeed",motorSpeed)
             .kv("angularMotorSpeed",angularMotorSpeed).kv("maxMotorTorque",maxMotorTorque)
             .kv("useLimits",useLimits).kv("minAngle",minAngle).kv("maxAngle",maxAngle);
        }
        @Override void loadFields(JsonV n){
            Object ax = n.get("axis");
            if(ax instanceof List<?> l && l.size() >= 3){
                axis.set(f(l.get(0)), f(l.get(1)), f(l.get(2)));
            }
            motorSpeed = n.flt("motorSpeed",0f);
            angularMotorSpeed = n.flt("angularMotorSpeed",0f);
            maxMotorTorque = n.flt("maxMotorTorque",0f);
            useLimits = n.bool("useLimits",false);
            minAngle = n.flt("minAngle",0f);
            maxAngle = n.flt("maxAngle",90f);
        }
        @Override public String type(){ return "HingeJoint"; }
        private static float f(Object o){
            if(o instanceof Number num) return num.floatValue();
            try { return Float.parseFloat(String.valueOf(o)); } catch(Exception e){ return 0f; }
        }
    }

    /** v2.3 PHY-026 — ball/socket point constraint: pins local anchorA (this entity) to local
     *  anchorB (other entity) in world space. Full 3-axis position lock with velocity projection
     *  at the anchors; rotation stays free (ball-socket semantics). */
    public static final class Point extends Joint {
        public Vec3 anchorA = new Vec3();     // local offset on this entity
        public Vec3 anchorB = new Vec3();     // local offset on the other entity
        public Point(){ }
        public Point(Vec3 aLocal, Vec3 bLocal){ anchorA.set(aLocal); anchorB.set(bLocal); }

        @Override float solve(float dt){
            if(entity == null || bodyA == null || bodyB == null || broken) return 0f;
            Vec3 wa = entity.worldMatrix.transformPoint(anchorA);
            Vec3 wb = bodyB.entity.worldMatrix.transformPoint(anchorB);
            Vec3 err = wb.cpy().sub(wa);
            float d = err.len();
            if(d < 1e-5f) return 0f;
            float wA = bodyA.kinematic || bodyA.mass <= 0f ? 0f : 1f / bodyA.mass;
            float wB = bodyB.kinematic || bodyB.mass <= 0f ? 0f : 1f / bodyB.mass;
            float wSum = wA + wB;
            if(wSum < 1e-8f) return 0f;
            Vec3 n = err.scale(1f / d);
            float corr = d;
            if(wA > 0f && !bodyA.kinematic){
                entity.position.addScaled(n, corr * (wA / wSum));
                bodyA.velocity.addScaled(n, -bodyA.velocity.dot(n) * 0.5f);
            }
            if(wB > 0f && !bodyB.kinematic){
                bodyB.entity.position.addScaled(n, -corr * (wB / wSum));
                bodyB.velocity.addScaled(n, -bodyB.velocity.dot(n) * 0.5f);
            }
            entity.markDirtyDeep(); bodyB.entity.markDirtyDeep();
            return d;
        }

        @Override void saveFields(JsonW w){
            w.kvv("anchorA",anchorA.x,anchorA.y,anchorA.z).kvv("anchorB",anchorB.x,anchorB.y,anchorB.z);
        }
        @Override void loadFields(JsonV n){
            Object a = n.get("anchorA"), b = n.get("anchorB");
            if(a instanceof List<?> l && l.size() >= 3) anchorA.set(f(l.get(0)), f(l.get(1)), f(l.get(2)));
            if(b instanceof List<?> l && l.size() >= 3) anchorB.set(f(l.get(0)), f(l.get(1)), f(l.get(2)));
        }
        @Override public String type(){ return "PointJoint"; }
        private static float f(Object o){
            if(o instanceof Number num) return num.floatValue();
            try { return Float.parseFloat(String.valueOf(o)); } catch(Exception e){ return 0f; }
        }
    }

    // ------------------------------------------------------------------
    /** world joint manager (held by PhysicsWorld) */
    public static final class Manager {
        public final List<Joint> joints = new ArrayList<>();
        public int iterations = 4;

        public void clear(){ joints.clear(); }

        public void collect(com.hollow.scene.World world){
            clear();
            if(world == null) return;
            for(Entity e : world.entities){
                for(Component c : e.allComponents()){
                    if(c instanceof Joint j){ j.bind(); joints.add(j); }
                }
            }
        }

        public void solve(float dt){
            for(int it = 0; it < iterations; it++){
                for(int i = joints.size() - 1; i >= 0; i--){
                    Joint j = joints.get(i);
                    if(j.isBroken() || j.entity == null || j.entity.destroyed){ joints.remove(i); continue; }
                    float imp = j.solve(dt);
                    // v2.3 PHY-035 stress meter: smoothed load + optional stress break
                    if(j.stressLimit > 0f){
                        j.stress = j.stress * 0.9f + imp * 0.1f;
                        if(j.stress > j.stressLimit) j.breakJoint();
                    }
                    if(j.breakable && j.breakForce > 0f && imp > j.breakForce) j.breakJoint();
                    if(j.isBroken()) fireJointBreak(j);
                }
            }
        }

        /** v2.3: "joint_break" EventBus event (name of holder entity + stress at break) */
        private void fireJointBreak(Joint j){
            com.hollow.game.GameApp g = com.hollow.game.GameApp.instance;
            if(g == null || g.services() == null || g.services().events == null) return;
            java.util.Map<String,Object> data = new java.util.LinkedHashMap<>();
            data.put("entity", j.entity != null ? j.entity.name : "?");
            data.put("other", j.other);
            data.put("stress", (double)j.stress);
            g.services().events.fire("joint_break", data);
        }

        /** v2.3 PHY-035 summary for profiler/CLI: highest stressed joints */
        public String stressReport(int topN){
            List<Joint> sorted = new ArrayList<>(joints);
            sorted.sort((a, b) -> Float.compare(b.stress, a.stress));
            StringBuilder sb = new StringBuilder();
            for(int i = 0; i < Math.min(topN, sorted.size()); i++){
                Joint j = sorted.get(i);
                sb.append(String.format("%s<->%s stress=%.2f%s%n",
                    j.entity != null ? j.entity.name : "?", j.other, j.stress,
                    j.isBroken() ? " BROKEN" : ""));
            }
            return sb.toString();
        }
    }
}
