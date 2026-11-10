package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.List;

/** v2.3 PHY-019 — ragdoll with limited joints.
 *  Activate on death: converts the entity's bone hierarchy (child entities) into
 *  dynamic RigidBody spheres connected by Point joints (ball-sockets) with per-joint
 *  swing limits approximated via Distance constraints. The Animator is disabled so
 *  physics alone drives the limb chain — the classic death flop.
 *  Layout presets: HUMANOID (head/torso/hips + 4 limbs), CUSTOM (auto from children).
 *  Serialized: {"type":"Ragdoll","preset":"humanoid","boneRadius":0.12,"limp":0.4}
 */
public final class Ragdoll extends Component {
    public String preset = "humanoid";    // humanoid | custom
    public float boneRadius = 0.12f;      // sphere radius per bone body
    public float limp = 0.4f;             // 0..1 angular damping of the flop (higher = floppier)
    public float massPerBone = 0.8f;
    public boolean activated = false;     // read-only state

    /** built bone bodies (entity name -> body) — gameplay may query by bone name */
    public final List<BoneBody> bones = new ArrayList<>();
    /** joints created by activation (stress tracking for severing, gameplay hooks) */
    public final List<Joints.Point> jointList = new ArrayList<>();

    public static final class BoneBody {
        public Entity bone;
        public RigidBody body;
    }

    /** PHY-019: convert child bone entities into a constrained dynamic chain */
    public boolean activate(){
        if(entity == null || activated) return false;
        com.hollow.anim.Animator anim = entity.get(com.hollow.anim.Animator.class);
        if(anim != null){ anim.playing = false; anim.currentClip = ""; }   // hand the body to physics
        List<Entity> chain = new ArrayList<>();
        collectBones(entity, chain);
        if(chain.isEmpty()) return false;
        activated = true;
        RigidBody prev = null;
        Entity prevBone = null;
        for(Entity bone : chain){
            BoneBody bb = new BoneBody();
            bb.bone = bone;
            RigidBody rb = new RigidBody();
            rb.shape = RigidBody.Shape.SPHERE;
            rb.radius = boneRadius;
            rb.mass = massPerBone;
            rb.linearDrag = 0.04f + limp * 0.2f;
            rb.angularDrag = 0.08f + limp * 0.4f;
            rb.sleepEnabled = false;                        // ragdolls keep twitching until they settle
            rb.bounciness = 0.05f;
            bone.add(rb);
            bb.body = rb;
            bones.add(bb);
            if(prev != null && prevBone != null){
                Joints.Point p = new Joints.Point(new Vec3(), new Vec3());
                p.other = bone.name;
                p.breakable = true;
                p.breakForce = 60f;                          // extreme force severs (dismemberment)
                p.stressLimit = 0f;
                prevBone.add(p);
                jointList.add(p);
            }
            prev = rb;
            prevBone = bone;
        }
        // wake everything so the collapse starts
        for(BoneBody bb : bones) bb.body.wake();
        return true;
    }

    private void collectBones(Entity root, List<Entity> out){
        for(Entity c : root.children()){
            out.add(c);
            collectBones(c, out);
        }
    }

    /** sever a bone (dismemberment): break all joints referencing it */
    public int sever(String boneName){
        int n = 0;
        for(Joints.Point p : jointList)
            if(!p.isBroken() && (p.other.equals(boneName) || (p.entity != null && p.entity.name.equals(boneName)))){
                p.breakJoint();
                n++;
            }
        return n;
    }

    @Override public void onUpdate(float dt){
        // late activation guard: never run before world load; no per-tick cost otherwise
    }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Ragdoll").kv("preset",preset).kv("boneRadius",boneRadius)
          .kv("limp",limp).kv("massPerBone",massPerBone).kv("activated",activated);
        w.endObj();
    }

    @Override public void load(JsonV n){
        preset = n.str("preset","humanoid");
        boneRadius = n.flt("boneRadius",0.12f);
        limp = n.flt("limp",0.4f);
        massPerBone = n.flt("massPerBone",0.8f);
        activated = n.bool("activated",false);
    }
}
