package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

/** v2.3 PHY-031 — foot planting: feet stay world-anchored while the body moves
 *  over them (no ice-skating), replanting when the stride budget is exhausted.
 *  Works with the anim.FootIK chain: each foot stores a world anchor when planted;
 *  the IK target is pulled back toward the anchor by the slide error, capped by
 *  maxStride; a new plant occurs when the anchor drifts beyond strideLength or
 *  the foot lifts (moving swing phase). Ground velocity from CharacterController
 *  keeps planted feet from sliding on moving platforms.
 *  Serialized: {"type":"FootPlant","footL":"foot_l","footR":"foot_r","strideLength":0.55}
 */
public final class FootPlant extends Component {
    public String footL = "foot_l";
    public String footR = "foot_r";
    public float strideLength = 0.55f;      // world drift before replant
    public float maxStride = 0.65f;         // hard cap on per-stride correction
    public float plantRadius = 0.06f;       // contact sphere at the sole

    private final Foot[] feet = { new Foot(), new Foot() };

    public static final class Foot {
        public Entity bone;
        public boolean planted;
        public final Vec3 anchor = new Vec3();
        public final Vec3 lastFootPos = new Vec3();
        public float drift;
    }

    @Override public void onAttach(){
        if(entity == null) return;
        feet[0].bone = entity.findChild(footL);
        feet[1].bone = entity.findChild(footR);
        for(Foot f : feet) f.planted = false;
    }

    /** call once per frame AFTER animation update, BEFORE FootIK solve */
    public void update(float dt){
        if(entity == null || entity.destroyed) return;
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        CharacterController cc = entity.get(CharacterController.class);
        for(Foot f : feet){
            if(f.bone == null || f.bone.destroyed) continue;
            Vec3 footPos = f.bone.worldPosition();
            float moved = footPos.dist(f.lastFootPos);
            f.lastFootPos.set(footPos);
            if(!f.planted){
                // foot moving down or stationary near ground -> plant here
                PhysicsWorld.Hit h = rayDown(app, footPos);
                if(h.hit && h.distance < plantRadius + 0.12f && moved < 0.06f){
                    f.planted = true;
                    f.anchor.set(footPos);
                    f.drift = 0f;
                }
            } else {
                // drift = how far the foot went from its anchor while planted
                f.drift = footPos.dist(f.anchor);
                if(f.drift > strideLength || moved > 0.12f){
                    f.planted = false;               // stride budget exhausted / lifted
                }
            }
        }
        entity.setMeta("footPlantL", feet[0].planted ? 1 : 0);
        entity.setMeta("footPlantR", feet[1].planted ? 1 : 0);
    }

    /** world anchor of a planted foot (FootIK reads it to build the correction) */
    public Vec3 anchorOf(String boneName, Vec3 out){
        Foot f = footByName(boneName);
        if(f == null || !f.planted) return out.set(0, -1, 0);
        return out.set(f.anchor);
    }

    /** slide correction for IK: pull toward anchor, capped by maxStride */
    public Vec3 correction(String boneName, Vec3 footPos, Vec3 out){
        Foot f = footByName(boneName);
        if(f == null || !f.planted){ out.set(0, 0, 0); return out; }
        out.set(f.anchor).sub(footPos);
        float len = out.len();
        if(len > maxStride){ out.scale(maxStride / Math.max(1e-5f, len)); }
        return out;
    }

    private Foot footByName(String name){
        if(name.equalsIgnoreCase(footL)) return feet[0];
        if(name.equalsIgnoreCase(footR)) return feet[1];
        for(Foot f : feet) if(f.bone != null && f.bone.name.equalsIgnoreCase(name)) return f;
        return null;
    }

    private PhysicsWorld.Hit rayDown(com.hollow.game.GameApp app, Vec3 from){
        PhysicsWorld.Hit miss = new PhysicsWorld.Hit();
        if(app == null || app.services() == null || app.services().physics == null) return miss;
        return app.services().physics.raycast(from, new Vec3(0, -1, 0), 0.35f);
    }

    public boolean isPlanted(int i){ return i >= 0 && i < 2 && feet[i].planted; }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","FootPlant").kv("footL",footL).kv("footR",footR)
          .kv("strideLength",strideLength).kv("maxStride",maxStride).kv("plantRadius",plantRadius);
        w.endObj();
    }
    @Override public void load(JsonV n){
        footL = n.str("footL","foot_l");
        footR = n.str("footR","foot_r");
        strideLength = n.flt("strideLength",0.55f);
        maxStride = n.flt("maxStride",0.65f);
        plantRadius = n.flt("plantRadius",0.06f);
    }
}
