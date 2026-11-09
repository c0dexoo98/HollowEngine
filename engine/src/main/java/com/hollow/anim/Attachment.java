package com.hollow.anim;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Mat4;
import com.hollow.core.Quat;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.List;

/** Bone socket attachment (HollowEngine 2.0) — pins this entity to a bone of another
 *  entity's Animator every frame (weapon in hand, helmet on head, vfx at muzzle).
 *  JSON: {"type":"Attachment","target":"Player","bone":"RightHand","offset":[0,0,0],"rotateWithBone":true}
 */
public final class Attachment extends Component {
    public String target = "";            // entity name owning the Animator
    public String bone = "";
    public final Vec3 offset = new Vec3();
    public boolean rotateWithBone = true;

    private transient Entity targetEntity;
    private final Mat4 socket = new Mat4();

    @Override public void onUpdate(float dt){
        if(entity == null) return;
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        if(app == null || app.world == null) return;
        if(targetEntity == null || targetEntity.destroyed)
            targetEntity = target.isEmpty() ? null : app.world.find(target);
        if(targetEntity == null) return;
        Animator anim = targetEntity.get(Animator.class);
        if(anim == null) return;
        if(!anim.boneWorldTransform(bone, socket)) return;
        // decompose socket matrix into position + rotation (basis vectors -> quat)
        Vec3 pos = socket.translation();
        if(rotateWithBone){
            Vec3 bx = socket.transformDir(new Vec3(1,0,0)).norm();
            Vec3 by = socket.transformDir(new Vec3(0,1,0)).norm();
            Vec3 bz = socket.transformDir(new Vec3(0,0,1)).norm();
            Quat rot = quatFromBasis(bx, by, bz);
            entity.rotation.set(rot).norm();
        }
        if(offset.len2() > 1e-10f){
            Vec3 wo = socket.transformDir(new Vec3(offset));
            pos.add(wo);
        }
        entity.position.set(pos);
        entity.markDirtyDeep();
    }

    /** Shepperd's method: quaternion from orthonormal column basis */
    private static Quat quatFromBasis(Vec3 x, Vec3 y, Vec3 z){
        float m00 = x.x, m01 = y.x, m02 = z.x;
        float m10 = x.y, m11 = y.y, m12 = z.y;
        float m20 = x.z, m21 = y.z, m22 = z.z;
        float tr = m00 + m11 + m22;
        float qx, qy, qz, qw;
        if(tr > 0f){
            float s = (float)Math.sqrt(tr + 1f) * 2f;
            qw = 0.25f * s;
            qx = (m21 - m12) / s;
            qy = (m02 - m20) / s;
            qz = (m10 - m01) / s;
        } else if(m00 > m11 && m00 > m22){
            float s = (float)Math.sqrt(1f + m00 - m11 - m22) * 2f;
            qw = (m21 - m12) / s;
            qx = 0.25f * s;
            qy = (m01 + m10) / s;
            qz = (m02 + m20) / s;
        } else if(m11 > m22){
            float s = (float)Math.sqrt(1f + m11 - m00 - m22) * 2f;
            qw = (m02 - m20) / s;
            qx = (m01 + m10) / s;
            qy = 0.25f * s;
            qz = (m12 + m21) / s;
        } else {
            float s = (float)Math.sqrt(1f + m22 - m00 - m11) * 2f;
            qw = (m10 - m01) / s;
            qx = (m02 + m20) / s;
            qy = (m12 + m21) / s;
            qz = 0.25f * s;
        }
        return new Quat(qx, qy, qz, qw).norm();
    }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Attachment").kv("target",target).kv("bone",bone)
          .kvv("offset",offset.x,offset.y,offset.z).kv("rotateWithBone",rotateWithBone); w.endObj();
    }

    @Override public void load(JsonV n){
        target = n.str("target","");
        bone = n.str("bone","");
        rotateWithBone = n.bool("rotateWithBone", true);
        Object o = n.get("offset");
        if(o instanceof List<?> l && l.size() >= 3){
            offset.set(num(l.get(0)), num(l.get(1)), num(l.get(2)));
        }
    }

    private static float num(Object o){
        if(o instanceof Number nb) return nb.floatValue();
        try { return Float.parseFloat(String.valueOf(o)); } catch(Exception e){ return 0f; }
    }
}
