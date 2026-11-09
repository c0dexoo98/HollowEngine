package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;

/** Rigid body (sphere/point dynamics). STUB — Agent C integrates. API FINAL. */
public final class RigidBody extends Component {
    public float mass = 1f;
    public float gravityScale = 1f;
    public float drag = 0.02f;
    public float bounciness = 0f;
    public boolean kinematic = false;
    public final Vec3 velocity = new Vec3();
    public boolean grounded = false;

    public void addImpulse(Vec3 imp){ velocity.addScaled(imp, mass > 0 ? 1f/mass : 0f); }

    /** Integration happens in PhysicsWorld.update(dt) (registered on onWorldLoaded) so that
     *  dynamics run in the physics step and never double-tick with World.update. */
    @Override public void onUpdate(float dt){ }
    @Override public void save(JsonW w){
        w.obj(); w.kv("type","RigidBody").kv("mass",mass).kv("gravityScale",gravityScale)
          .kv("drag",drag).kv("bounciness",bounciness).kv("kinematic",kinematic); w.endObj();
    }
    @Override public void load(JsonV n){
        mass = n.flt("mass",1f); gravityScale = n.flt("gravityScale",1f);
        drag = n.flt("drag",0.02f); bounciness = n.flt("bounciness",0f);
        kinematic = n.bool("kinematic",false);
    }
}
