package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.List;

/** Constraints between two RigidBody entities (HollowEngine 2.0).
 *  <pre>
 *  Joints.Distance : keeps world distance within [min,max]
 *  Joints.Spring   : Hooke spring toward restLength (stiffness/damping)
 *  Joints.Hinge    : pins anchors together, slides/rotates along axis (axis-lock lite, optional motor)
 *  Joints.Manager  : held by PhysicsWorld — collects Joint components on world load, solves per step
 *  </pre>
 *  Joint components serialize as {"type":"DistanceJoint"/"SpringJoint"/"HingeJoint", ...}.
 */
public final class Joints {
    private Joints() {}

    public static abstract class Joint extends Component {
        public String other = "";        // name of the other entity
        public boolean breakable = false;
        public float breakForce = 0f;    // impulse magnitude that breaks (if breakable)
        protected boolean broken = false;
        protected RigidBody bodyA, bodyB;

        public boolean isBroken(){ return broken; }
        public void breakJoint(){ broken = true; }

        /** solve one iteration; returns applied impulse magnitude (for breaking) */
        abstract float solve(float dt);

        void bind(){
            bodyA = entity != null ? entity.get(RigidBody.class) : null;
            com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
            com.hollow.scene.World w = app != null ? app.world : null;
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
              .kv("breakable", breakable).kv("breakForce", breakForce);
            saveFields(w);
            w.endObj();
        }
        abstract void saveFields(JsonW w);

        @Override public void load(JsonV n){
            other = n.str("other","");
            breakable = n.bool("breakable", false);
            breakForce = n.flt("breakForce", 0f);
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
            if(wA > 0f){
                if(!bodyA.kinematic) entity.position.addScaled(n, -corr * wA);
                float vn = bodyA.velocity.dot(n);
                bodyA.velocity.addScaled(n, -vn);         // cancel approach velocity
            }
            if(wB > 0f){
                if(!bodyB.kinematic) bodyB.entity.position.addScaled(n, corr * wB);
                float vn = bodyB.velocity.dot(n);
                bodyB.velocity.addScaled(n, -vn);
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
     *  optional motor drives relative speed along the axis — approximates a hinge for mobile scale. */
    public static final class Hinge extends Joint {
        public Vec3 axis = new Vec3(0, 1, 0);
        public float motorSpeed = 0f;      // units/s along axis (optional motor)
        public Hinge(){ }

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
            entity.markDirtyDeep(); bodyB.entity.markDirtyDeep();
            return Math.abs(relV);
        }

        @Override void saveFields(JsonW w){
            w.kvv("axis", axis.x, axis.y, axis.z).kv("motorSpeed",motorSpeed);
        }
        @Override void loadFields(JsonV n){
            Object ax = n.get("axis");
            if(ax instanceof List<?> l && l.size() >= 3){
                axis.set(f(l.get(0)), f(l.get(1)), f(l.get(2)));
            }
            motorSpeed = n.flt("motorSpeed",0f);
        }
        @Override public String type(){ return "HingeJoint"; }
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
                    if(j.breakable && j.breakForce > 0f && imp > j.breakForce) j.breakJoint();
                }
            }
        }
    }
}
