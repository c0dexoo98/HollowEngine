package com.hollow.anim;

import com.hollow.assets.Skeleton;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.scene.Component;

/** Foot IK (HollowEngine 2.0) — raycasts the ground under the character and shifts the
 *  hip joint vertically so feet stay planted on slopes/stairs.
 *  The Animator consumes the offset each frame (applyFootIK hook) — ordering independent.
 *  JSON: {"type":"FootIK","enabled":true,"hipBone":"Hips","footBone":"LeftFoot","footHeight":0.1,"rayDown":1.2}
 */
public final class FootIK extends Component {
    public boolean enabled = true;
    public String hipBone = "Hips";
    public String footBone = "LeftFoot";
    public float footHeight = 0.1f;      // distance foot bottom -> joint origin
    public float rayDown = 1.2f;
    public float smooth = 12f;           // lerp speed

    private int hipIndex = -1;
    private float hipOffset = 0f;        // consumed by Animator
    private float targetOffset = 0f;

    /** resolve hip joint index once per skeleton */
    public int hipJoint(Skeleton sk){
        if(hipIndex >= 0 || sk == null) return hipIndex;
        hipIndex = sk.indexOf(hipBone);
        return hipIndex;
    }

    /** Animator calls once per frame; returns and clears the pending offset */
    public float consumeHipOffset(){
        float o = hipOffset;
        hipOffset = 0f;
        return o;
    }

    @Override public void onUpdate(float dt){
        if(entity == null || !enabled) return;
        com.hollow.game.EngineServices svc = services();
        if(svc == null || svc.physics == null) return;
        // raycast down from character center
        com.hollow.core.Vec3 from = entity.worldPosition();
        com.hollow.physics.PhysicsWorld.Hit hit = svc.physics.raycast(
            from, new com.hollow.core.Vec3(0, -1, 0), rayDown);
        float desired = 0f;
        if(hit.hit){
            // how much the ground differs from the assumed flat level (entity origin)
            desired = hit.point.y + footHeight - from.y;
            // clamp to sane range
            if(desired > 0.5f) desired = 0.5f;
            if(desired < -0.5f) desired = -0.5f;
        }
        // smooth toward target
        targetOffset += (desired - targetOffset) * Math.min(1f, smooth * dt);
        hipOffset = targetOffset;
    }

    private com.hollow.game.EngineServices services(){
        return com.hollow.game.GameApp.instance != null ? com.hollow.game.GameApp.instance.services() : null;
    }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","FootIK").kv("enabled",enabled)
          .kv("hipBone",hipBone).kv("footBone",footBone)
          .kv("footHeight",footHeight).kv("rayDown",rayDown).kv("smooth",smooth); w.endObj();
    }

    @Override public void load(JsonV n){
        enabled = n.bool("enabled", true);
        hipBone = n.str("hipBone", "Hips");
        footBone = n.str("footBone", "LeftFoot");
        footHeight = n.flt("footHeight", 0.1f);
        rayDown = n.flt("rayDown", 1.2f);
        smooth = n.flt("smooth", 12f);
    }
}
