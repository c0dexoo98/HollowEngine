package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;

import java.util.List;

/** Rigid body — full 6-DOF dynamics v2 (HollowEngine 2.0).
 *  Shapes: sphere (default), box (AABB-aligned), capsule (vertical).
 *  Integration happens in PhysicsWorld.update(dt) — never double-tick with World.update.
 *  Collision events: PhysicsWorld fires "collision" EventBus events with entity/other/normal.
 */
public final class RigidBody extends Component {
    public enum Shape { SPHERE, BOX, CAPSULE }

    public float mass = 1f;
    public float gravityScale = 1f;
    public float linearDrag = 0.02f;
    public float angularDrag = 0.05f;
    public float bounciness = 0f;          // restitution 0..1
    public float friction = 0.6f;          // Coulomb
    public boolean kinematic = false;
    public boolean sleepEnabled = true;
    public Shape shape = Shape.SPHERE;
    // shape dims (world units; entity scale multiplies)
    public float radius = 0.25f;           // sphere / capsule
    public final Vec3 boxSize = new Vec3(0.5f, 0.5f, 0.5f);  // full extents (box)
    public float capsuleHeight = 1.0f;     // capsule full height

    // state
    public final Vec3 velocity = new Vec3();
    public final Vec3 angularVelocity = new Vec3();   // rad/s around entity-local axes (world axis-angle simplified)
    public boolean grounded = false;
    public boolean sleeping = false;

    // scratch (package-visible for PhysicsWorld sleep bookkeeping)
    float sleepTimer = 0f;
    private boolean registered = false;

    /** v2.0: self-registration for dynamically spawned bodies (World.update calls onUpdate) */
    @Override public void onUpdate(float dt){
        if(registered) return;
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        if(app == null || app.services() == null || app.services().physics == null) return;
        app.services().physics.registerBody(this);
        registered = true;
    }

    public void addImpulse(Vec3 imp){
        if(mass <= 0f || kinematic) return;
        wake();
        velocity.addScaled(imp, 1f / mass);
    }

    public void addForce(Vec3 f, float dt){
        if(mass <= 0f || kinematic) return;
        wake();
        velocity.addScaled(f, dt / mass);
    }

    public void addImpulseAt(Vec3 imp, Vec3 worldPoint){
        addImpulse(imp);
        // angular response r x J / I
        if(mass <= 0f || kinematic) return;
        Vec3 r = worldPoint.cpy().sub(entity.worldPosition());
        Vec3 torque = r.cross(imp);
        float I = inertiaApprox();
        if(I > 1e-6f) angularVelocity.addScaled(torque, 1f / I);
    }

    public void addRadialImpulse(Vec3 center, float radius, float force){
        Vec3 d = entity.worldPosition().cpy().sub(center);
        float dist = d.len();
        if(dist > radius || mass <= 0f) return;
        float falloff = 1f - dist / radius;
        if(dist > 1e-4f) d.scale(1f / dist); else d.set(0, 1, 0);
        addImpulse(d.scale(force * falloff * mass * 0.1f));
        wake();
    }

    public void setVelocity(Vec3 v){ wake(); velocity.set(v); }

    public void wake(){ sleeping = false; sleepTimer = 0f; }

    /** sphere inertia 2/5 m r² (box/capsule approximated with same order) */
    public float inertiaApprox(){
        float r = Math.max(0.05f, effectiveRadius());
        return 0.4f * Math.max(0.001f, mass) * r * r;
    }

    public float effectiveRadius(){
        return switch(shape){
            case SPHERE -> radius;
            case BOX -> 0.5f * boxSize.len();
            case CAPSULE -> Math.max(radius, capsuleHeight * 0.5f);
        };
    }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","RigidBody").kv("mass",mass).kv("gravityScale",gravityScale)
          .kv("linearDrag",linearDrag).kv("angularDrag",angularDrag)
          .kv("bounciness",bounciness).kv("friction",friction).kv("kinematic",kinematic)
          .kv("sleepEnabled",sleepEnabled)
          .kv("shape", shape.name().toLowerCase())
          .kv("radius",radius).kv("capsuleHeight",capsuleHeight);
        if(shape == Shape.BOX) w.kvv("boxSize", boxSize.x, boxSize.y, boxSize.z);
        w.endObj();
    }

    @Override public void load(JsonV n){
        mass = n.flt("mass",1f); gravityScale = n.flt("gravityScale",1f);
        bounciness = n.flt("bounciness",0f);
        kinematic = n.bool("kinematic",false);
        linearDrag = n.flt("linearDrag", n.flt("drag", 0.02f));   // v1 compat
        angularDrag = n.flt("angularDrag",0.05f);
        friction = n.flt("friction",0.6f);
        sleepEnabled = n.bool("sleepEnabled",true);
        String sh = n.str("shape","sphere");
        try { shape = Shape.valueOf(sh.toUpperCase()); } catch(Exception ignored){ shape = Shape.SPHERE; }
        radius = n.flt("radius",0.25f);
        capsuleHeight = n.flt("capsuleHeight",1.0f);
        Object bs = n.get("boxSize");
        if(bs instanceof List<?> l && l.size() >= 3){
            boxSize.set(fnum(l.get(0)), fnum(l.get(1)), fnum(l.get(2)));
        }
    }

    private static float fnum(Object o){
        if(o instanceof Number num) return num.floatValue();
        try { return Float.parseFloat(String.valueOf(o)); } catch(Exception e){ return 0f; }
    }
}
