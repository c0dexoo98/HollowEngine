package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;

/** Collide-and-slide capsule character controller (physics package does the math). */
public final class CharacterController extends Component {
    public float radius = 0.4f;
    public float height = 1.8f;
    public float stepHeight = 0.45f;
    public float slopeLimit = 45f;
    public float skinWidth = 0.02f;
    public boolean useGravity = true;

    private boolean grounded;
    private final Vec3 groundNormal = new Vec3(0,1,0);
    private final Vec3 velocity = new Vec3();
    private PhysicsWorld world;   // resolved lazily

    public boolean isGrounded(){ return grounded; }
    public Vec3 groundNormal(){ return groundNormal; }
    public Vec3 getVelocity(){ return velocity; }
    public void setVelocity(Vec3 v){ velocity.set(v); }
    public void setGrounded(boolean g){ grounded = g; }
    public void setGroundNormal(Vec3 n){ groundNormal.set(n); }

    private PhysicsWorld physics(){
        if(world == null){
            com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
            if(app != null && app.services() != null) world = app.services().physics;
        }
        return world;
    }

    /** collide-and-slide; returns actual displacement */
    public Vec3 move(Vec3 displacement){
        PhysicsWorld pw = physics();
        if(pw != null && entity != null){
            pw.moveController(this, displacement);
            return displacement;
        }
        if(entity != null){
            entity.position.add(displacement);
            entity.markDirtyDeep();
        }
        return displacement;
    }
    public void teleport(Vec3 pos){
        if(entity != null){
            entity.position.set(pos);
            entity.markDirtyDeep();
        }
        velocity.set(0,0,0);
    }
    /** jump impulse */
    public void jump(float speed){ velocity.y = speed; setGrounded(false); }

    @Override public void onUpdate(float dt){
        PhysicsWorld pw = physics();
        if(pw != null && entity != null){
            pw.stepController(this, dt);
            pw.groundCheck(this);
        }
    }
    @Override public void save(JsonW w){
        w.obj(); w.kv("type","CharacterController").kv("radius",radius).kv("height",height)
          .kv("stepHeight",stepHeight).kv("slopeLimit",slopeLimit).kv("gravity",useGravity); w.endObj();
    }
    @Override public void load(JsonV n){
        radius = n.flt("radius",0.4f); height = n.flt("height",1.8f);
        stepHeight = n.flt("stepHeight",0.45f); slopeLimit = n.flt("slopeLimit",45f);
        useGravity = n.bool("gravity", true);
    }
}
