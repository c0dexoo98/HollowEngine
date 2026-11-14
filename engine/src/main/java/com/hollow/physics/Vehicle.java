package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

/** v2.3 PHY-021 — simple 4-wheel vehicle (raycast suspension + balance).
 *  Four down-rays from the chassis corners; each wheel gets a spring/damper
 *  suspension, engine force along the chassis forward, lateral grip with slip,
 *  and yaw torque from steering. Anti-roll keeps the chassis upright (rolls are
 *  damped hard — arcade balance). Drive via setInputs(throttle, steer, brake).
 *  Serialized: {"type":"Vehicle","wheelBase":1.6,"wheelRadius":0.35,"engineForce":9,...}
 */
public final class Vehicle extends Component {
    public float wheelBase = 1.6f;         // half-extent Z between front/rear axles
    public float trackWidth = 0.7f;        // half-extent X between left/right wheels
    public float wheelRadius = 0.35f;
    public float suspensionRest = 0.45f;   // rest length of suspension ray
    public float suspensionStiffness = 26f;
    public float suspensionDamping = 3.2f;
    public float engineForce = 9f;         // m/s² at full throttle
    public float brakeForce = 14f;
    public float maxSpeed = 9f;            // m/s governor
    public float steerAngleMax = 32f;      // degrees
    public float gripFront = 9.5f;         // lateral accel cap (m/s²)
    public float gripRear = 8.0f;
    public boolean rearDrive = true;

    // runtime state (read by HUD/gameplay)
    public float speed = 0f;               // signed forward speed m/s
    public float steerAngle = 0f;
    public float[] wheelCompression = new float[4];   // FL FR RL RR (0..1)
    public boolean airborne = false;
    public boolean flipped = false;

    private float throttle = 0f, steerInput = 0f, brake = 0f;
    private RigidBody chassis;
    /** v2.3: inject PhysicsWorld directly (headless tests); null = resolve via GameApp */
    public PhysicsWorld physicsOverride = null;
    private final Vec3[] wheelOffsets = new Vec3[]{
        new Vec3(-trackWidth, 0, -wheelBase), new Vec3(trackWidth, 0, -wheelBase),
        new Vec3(-trackWidth, 0,  wheelBase), new Vec3(trackWidth, 0,  wheelBase)};
    private final PhysicsWorld.Hit[] hits = new PhysicsWorld.Hit[4];

    public void setInputs(float throttle, float steer, float brake){
        this.throttle = MathX.clamp(throttle, -1f, 1f);
        this.steerInput = MathX.clamp(steer, -1f, 1f);
        this.brake = MathX.clamp(brake, 0f, 1f);
    }

    @Override public void onUpdate(float dt){
        if(entity == null || entity.destroyed) return;
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        PhysicsWorld phy = physicsOverride;
        if(phy == null && app != null && app.services() != null)
            phy = app.services().physics;
        chassis = entity.get(RigidBody.class);
        if(chassis == null){
            chassis = new RigidBody();
            chassis.shape = RigidBody.Shape.BOX;
            chassis.boxSize.set(trackWidth * 2f + 0.4f, 0.5f, wheelBase * 2f + 0.7f);
            chassis.ccd = true;                       // fast vehicle: CCD (PHY-024 synergy)
            entity.add(chassis);
        }
        if(phy == null || chassis.kinematic) return;

        // steering smoothing
        float targetSteer = steerInput * steerAngleMax;
        steerAngle = MathX.approach(steerAngle, targetSteer, 120f * dt);

        // chassis orientation vectors (yaw only — arcade); front = -Z (engine convention)
        float yaw = entity.rotation.yawDeg();
        Vec3 fwd = new Vec3(-(float)Math.sin(Math.toRadians(yaw)), 0, -(float)Math.cos(Math.toRadians(yaw)));
        Vec3 right = new Vec3(-fwd.z, 0, fwd.x);

        speed = chassis.velocity.dot(fwd);

        int groundedWheels = 0;
        float roll = 0f;                            // anti-roll torque accumulator
        for(int i = 0; i < 4; i++){
            Vec3 off = wheelOffsets[i];
            Vec3 wp = entity.worldPosition().cpy().addScaled(fwd, off.z).addScaled(right, off.x);
            PhysicsWorld.Hit hit = phy.raycast(wp, new Vec3(0, -1, 0), suspensionRest + wheelRadius);
            hits[i] = hit;
            float compression = 0f;
            if(hit.hit){
                groundedWheels++;
                float pen = (suspensionRest + wheelRadius) - hit.distance;
                compression = MathX.clamp(pen / suspensionRest, 0f, 1f);
                // suspension spring (Hooke) + damping
                float springA = suspensionStiffness * pen;
                float vY = chassis.velocity.y;
                float springV = -suspensionDamping * vY;
                float aY = (springA + springV) - 9.81f;         // cancel gravity when resting
                chassis.velocity.y += aY * dt;
                // engine: rear wheels only (rearDrive) — force along forward
                boolean driven = rearDrive ? i >= 2 : i < 2;
                if(driven && Math.abs(throttle) > 0.01f && Math.abs(speed) < maxSpeed){
                    Vec3 drive = fwd.cpy().scale(throttle * engineForce * dt);
                    chassis.velocity.add(drive);
                }
                // brake
                if(brake > 0.01f){
                    float fSpd = chassis.velocity.dot(fwd);
                    Vec3 kill = fwd.cpy().scale(-fSpd * Math.min(1f, brakeForce * dt / Math.max(0.5f, Math.abs(fSpd))));
                    chassis.velocity.add(kill);
                }
                // lateral grip with slip: cap sideways acceleration
                float grip = i < 2 ? gripFront : gripRear;
                float latV = chassis.velocity.dot(right);
                float latKill = MathX.clamp(-latV, -grip * dt, grip * dt);
                chassis.velocity.addScaled(right, latKill);
            }
            wheelCompression[i] = compression;
            roll += (i < 2 ? -1f : 1f) * compression;
        }
        airborne = groundedWheels == 0;
        // anti-roll / balance: yaw assist + upright damping (arcade)
        if(!airborne){
            float steerYaw = steerAngle * MathX.clamp(Math.abs(speed) / maxSpeed, 0f, 1f)
                * (speed >= 0f ? 1f : -1f);
            chassis.angularVelocity.y = MathX.approach(chassis.angularVelocity.y, steerYaw, 4f * dt);
            // upright assist: blend chassis orientation toward yaw-only (level) pose
            Vec3 up = entity.worldMatrix.transformDir(new Vec3(0, 1, 0));
            if(up.y < 0.92f){
                com.hollow.core.Quat yawOnly = com.hollow.core.Quat.fromAxisAngle(0, 1, 0, yaw);
                float k = MathX.clamp(2.5f * dt, 0f, 1f);
                entity.rotation.set(entity.rotation.slerp(yawOnly, k)).norm();
                entity.markDirtyDeep();
            }
        }
        // flip detection (gameplay respawn trigger)
        flipped = entity.worldMatrix.transformDir(new Vec3(0, 1, 0)).y < 0.05f;
    }

    /** wheel world contact point (debug draw / skid marks) */
    public Vec3 wheelContact(int i, Vec3 out){
        PhysicsWorld.Hit h = hits[i];
        if(h == null || !h.hit) return out.set(0, -1, 0);
        return out.set(h.point);
    }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Vehicle")
          .kv("wheelBase",wheelBase).kv("trackWidth",trackWidth).kv("wheelRadius",wheelRadius)
          .kv("suspensionRest",suspensionRest).kv("suspensionStiffness",suspensionStiffness)
          .kv("suspensionDamping",suspensionDamping).kv("engineForce",engineForce)
          .kv("brakeForce",brakeForce).kv("maxSpeed",maxSpeed).kv("steerAngleMax",steerAngleMax)
          .kv("gripFront",gripFront).kv("gripRear",gripRear).kv("rearDrive",rearDrive);
        w.endObj();
    }
    @Override public void load(JsonV n){
        wheelBase = n.flt("wheelBase",1.6f);
        trackWidth = n.flt("trackWidth",0.7f);
        wheelRadius = n.flt("wheelRadius",0.35f);
        suspensionRest = n.flt("suspensionRest",0.45f);
        suspensionStiffness = n.flt("suspensionStiffness",26f);
        suspensionDamping = n.flt("suspensionDamping",3.2f);
        engineForce = n.flt("engineForce",9f);
        brakeForce = n.flt("brakeForce",14f);
        maxSpeed = n.flt("maxSpeed",9f);
        steerAngleMax = n.flt("steerAngleMax",32f);
        gripFront = n.flt("gripFront",9.5f);
        gripRear = n.flt("gripRear",8f);
        rearDrive = n.bool("rearDrive",true);
        rebuildOffsets();
    }

    private void rebuildOffsets(){
        wheelOffsets[0].set(-trackWidth, 0, -wheelBase);
        wheelOffsets[1].set( trackWidth, 0, -wheelBase);
        wheelOffsets[2].set(-trackWidth, 0,  wheelBase);
        wheelOffsets[3].set( trackWidth, 0,  wheelBase);
    }
}
