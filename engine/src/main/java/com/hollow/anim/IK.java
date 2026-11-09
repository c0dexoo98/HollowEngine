package com.hollow.anim;

import com.hollow.core.Mat4;
import com.hollow.core.MathX;
import com.hollow.core.Quat;
import com.hollow.core.Vec3;

/** Skeletal IK solvers (HollowEngine 2.0).
 *  - TwoBoneIK: analytic 2-bone (arm/leg) using law of cosines — matches UE's IK enablement.
 *  - LookAtIK:  aim joint toward target with clamped angles.
 *  All solvers write into the sampled local-space joint TRS before the Animator builds
 *  skinning matrices, so GPU skinning stays unchanged.
 */
public final class IK {
    private IK() {}

    public static final class TwoBoneResult {
        public Quat rotA = new Quat();       // local rotation for upper joint
        public Quat rotB = new Quat();       // local rotation for lower joint
        public boolean valid = false;
    }

    private static final TwoBoneResult RES = new TwoBoneResult();

    /** analytic two-bone IK.
     *  @param upperLen,lowerLen  bone lengths
     *  @param rootWorld          world pos of upper joint origin
     *  @param targetWorld        IK target
     *  @param poleWorld          elbow/knee hint (bend direction)
     *  @param upperRest,lowerRest rest-pose LOCAL rotations of the two joints
     *  @param rootWorldRot       world rotation of the root joint (chain base)
     */
    public static TwoBoneResult solveTwoBone(
            float upperLen, float lowerLen,
            Vec3 rootWorld, Vec3 targetWorld, Vec3 poleWorld,
            Quat upperRest, Quat lowerRest, Quat rootWorldRot){

        RES.valid = false;
        float maxReach = (upperLen + lowerLen) * 0.999f;
        Vec3 toTarget = targetWorld.cpy().sub(rootWorld);
        float dist = toTarget.len();
        if(dist < 1e-5f) return RES;
        Vec3 dir = toTarget.scale(1f / dist);
        if(dist > maxReach){ targetWorld = rootWorld.cpy().addScaled(dir, maxReach); dist = maxReach; }
        dist = MathX.clamp(dist, Math.abs(upperLen - lowerLen) + 0.001f, maxReach);

        // law of cosines at root: angle between chain direction and upper bone
        float a = upperLen, b = lowerLen, c = dist;
        float cosA = (a*a + c*c - b*b) / (2f * a * c);
        cosA = MathX.clamp(cosA, -1f, 1f);
        float angleA = (float)Math.acos(cosA);          // interior angle

        // bend plane: pole hint defines elbow direction
        Vec3 poleDir = poleWorld.cpy().sub(rootWorld);
        Vec3 bendAxis = dir.cross(poleDir);
        if(bendAxis.len2() < 1e-8f){
            // pole collinear with dir — pick any perpendicular
            bendAxis.set(dir.y, -dir.z, dir.x);
        }
        bendAxis.norm();

        // rotate chain direction back by angleA around bendAxis to get upper-bone direction
        Quat qA = Quat.fromAxisAngle(bendAxis.x, bendAxis.y, bendAxis.z, -angleA * MathX.DEG);
        Vec3 upperDir = qA.rotate(dir.cpy()).norm();

        // world rotations that bring +Y (bone axis convention) onto upperDir / limb dir
        Quat targetA = fromUpVector(upperDir);
        Quat worldA = rootWorldRot.cpy().invert().mul(targetA);
        RES.rotA.set(upperRest.cpy().invert().mul(worldA)).norm();

        // lower bone: direction from upper joint end to effector
        Vec3 upperEnd = rootWorld.cpy().addScaled(upperDir, upperLen);
        Vec3 lowerDir = targetWorld.cpy().sub(upperEnd).norm();
        Quat targetB = fromUpVector(lowerDir);
        Quat worldB = targetA.cpy().invert().mul(targetB);
        RES.rotB.set(lowerRest.cpy().invert().mul(worldB)).norm();

        RES.valid = true;
        return RES;
    }

    /** build quaternion rotating +Y onto v */
    public static Quat fromUpVector(Vec3 v){
        Vec3 up = new Vec3(0, 1, 0);
        float d = up.dot(v);
        if(d > 0.99999f) return new Quat();
        if(d < -0.99999f) return Quat.fromAxisAngle(1, 0, 0, 180f);
        Vec3 axis = up.cross(v).norm();
        float angle = (float)Math.toDegrees(Math.acos(MathX.clamp(d, -1f, 1f)));
        return Quat.fromAxisAngle(axis.x, axis.y, axis.z, angle);
    }

    /** smooth aim: returns a rotation that points 'forward' toward target, clamped by maxDeg */
    public static Quat lookAt(Quat currentWorld, Vec3 eyePos, Vec3 targetPos, float maxDeg, float dt){
        Vec3 to = targetPos.cpy().sub(eyePos);
        if(to.len2() < 1e-8f) return currentWorld;
        to.norm();
        Quat want = fromUpVector(to);                  // +Y convention stays consistent
        // convert to local-space delta relative to current
        Quat inv = currentWorld.cpy().invert();
        Quat deltaLocal = inv.cpy().mul(want);
        // clamp angle
        float angle = (float)Math.toDegrees(2f * Math.acos(MathX.clamp(Math.abs(deltaLocal.w), -1f, 1f)));
        float t = MathX.clamp(maxDeg * dt / Math.max(0.001f, angle), 0f, 1f);
        Quat clamped = currentWorld.cpy().slerp(want, t);
        return clamped;
    }

    // ------------------------------------------------------------------
    /** Foot IK pass — computed by FootIK component each frame; helper shared here. */
    public static final class FootLock {
        public boolean active;
        public final Vec3 groundPos = new Vec3();
        public final Vec3 groundNormal = new Vec3(0,1,0);
    }
}
