package com.hollow.physics;

import com.hollow.core.AABB;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.List;

/** v2.3 environment physics in one file:
 *  <pre>
 *  WaterVolume (PHY-022 + PHY-034): AABB water body with buoyancy per submerged depth,
 *    linear/angular drag while inside, flow current vector, gentle surface waves
 *    (COL-025 compatible height sampling). Floating objects stabilize at the
 *    waterline via depth-proportional lift ("simple waterline rule").
 *  WindZone   (PHY-023): direction + base strength + gust (sin pulse + perlin-ish noise),
 *    pushes dynamic bodies each tick; optional player push.
 *  </pre>
 *  Both are Components registered in EngineBootstrap; volumes only affect bodies
 *  whose bounds overlap — cost is O(bodies) per volume per tick.
 */
public final class EnvironmentPhysics {

    // ==================================================================
    // PHY-022 + PHY-034 + COL-025 helper
    // ==================================================================
    public static final class WaterVolume extends Component {
        public Vec3 size = new Vec3(8f, 2f, 8f);      // full extents (local; entity scale applies)
        public float density = 1.0f;                  // water = 1.0
        public float flowStrength = 0f;               // current speed along flowDir (m/s)
        public final Vec3 flowDir = new Vec3(1, 0, 0);
        public float waveHeight = 0.05f;              // surface wave amplitude
        public float waveSpeed = 1.6f;                // rad/s
        public float buoyancyScale = 1.15f;           // >1 so floaters poke above the surface
        public float waterDrag = 0.9f;                // linear drag while submerged
        public float angularWaterDrag = 0.6f;

        private transient float wavePhase = 0f;
        /** v2.3: inject PhysicsWorld directly (headless tests); null = resolve via GameApp */
        public PhysicsWorld physicsOverride = null;

        public AABB worldBounds(Entity e){
            Vec3 c = e.worldPosition();
            Vec3 s = new Vec3(size.x * e.worldScale().x * 0.5f, size.y * e.worldScale().y * 0.5f, size.z * e.worldScale().z * 0.5f);
            return AABB.fromCenterExtents(c, s);
        }

        /** COL-025: animated surface height at a world XZ point (sinusoid pair) */
        public float surfaceHeight(Entity e, float wx, float wz, float time){
            Vec3 c = e.worldPosition();
            return c.y + size.y * 0.5f * e.worldScale().y
                + waveHeight * (float)Math.sin(time * waveSpeed + wx * 0.8f)
                + waveHeight * 0.6f * (float)Math.sin(time * waveSpeed * 1.7f + wz * 1.1f);
        }

        @Override public void onUpdate(float dt){
            if(entity == null || entity.destroyed) return;
            wavePhase += dt * waveSpeed;
            PhysicsWorld phy = physicsOverride;
            if(phy == null){
                com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
                if(app == null || app.services() == null || app.services().physics == null) return;
                phy = app.services().physics;
            }
            AABB wb = worldBounds(entity);
            Vec3 surfTop = entity.worldPosition();
            float topY = surfTop.y + size.y * 0.5f * entity.worldScale().y;
            List<RigidBody> bodies = phy.bodyList();
            for(RigidBody rb : bodies){
                if(rb.entity == null || rb.entity.destroyed || rb.kinematic) continue;
                Vec3 p = rb.entity.position;
                float r = rb.effectiveRadius() * Math.max(1e-3f, entity.worldScale().x);
                if(p.x < wb.min.x - r || p.x > wb.max.x + r) continue;
                if(p.z < wb.min.z - r || p.z > wb.max.z + r) continue;
                // submerged depth ratio: -1 fully out, 0 at waterline, 1 fully under
                float depthRatio = MathX.clamp((topY - p.y) / (2f * r), -1f, 1f);
                if(depthRatio <= 0f) continue;
                rb.wake();
                // PHY-034: buoyancy = density * submergedRatio * g (waterline stabilizer;
                // buoyancyScale > 1 makes floaters bob above the surface)
                float buoy = density * depthRatio * buoyancyScale * 9.81f;
                rb.velocity.y += buoy * dt;
                // drag: strong underwater, weak at the waterline
                float dragK = waterDrag * depthRatio;
                if(dragK > 0f){
                    float damp = Math.max(0f, 1f - dragK * dt);
                    rb.velocity.x *= damp;
                    rb.velocity.z *= damp;
                    rb.velocity.y *= Math.max(0f, 1f - dragK * 1.4f * dt);   // vertical settle
                }
                float angDamp = Math.max(0f, 1f - angularWaterDrag * depthRatio * dt);
                rb.angularVelocity.scale(angDamp);
                // flow current: push along flowDir while submerged
                if(flowStrength > 1e-4f){
                    Vec3 f = flowDir.cpy().norm().scale(flowStrength * depthRatio);
                    rb.velocity.addScaled(f, dt);
                }
            }
        }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","WaterVolume")
              .kvv("size",size.x,size.y,size.z).kv("density",density)
              .kv("flowStrength",flowStrength).kvv("flowDir",flowDir.x,flowDir.y,flowDir.z)
              .kv("waveHeight",waveHeight).kv("waveSpeed",waveSpeed)
              .kv("buoyancyScale",buoyancyScale).kv("waterDrag",waterDrag).kv("angularWaterDrag",angularWaterDrag);
            w.endObj();
        }
        @Override public void load(JsonV n){
            Object s = n.get("size");
            if(s instanceof List<?> l && l.size() >= 3) size.set(num(l.get(0)), num(l.get(1)), num(l.get(2)));
            density = n.flt("density",1f);
            flowStrength = n.flt("flowStrength",0f);
            Object fd = n.get("flowDir");
            if(fd instanceof List<?> l && l.size() >= 3) flowDir.set(num(l.get(0)), num(l.get(1)), num(l.get(2)));
            waveHeight = n.flt("waveHeight",0.05f);
            waveSpeed = n.flt("waveSpeed",1.6f);
            buoyancyScale = n.flt("buoyancyScale",1.15f);
            waterDrag = n.flt("waterDrag",0.9f);
            angularWaterDrag = n.flt("angularWaterDrag",0.6f);
        }
    }

    // ==================================================================
    // PHY-023 wind zone
    // ==================================================================
    public static final class WindZone extends Component {
        public final Vec3 direction = new Vec3(1, 0, 0);
        public float strength = 4f;              // base m/s
        public float gustStrength = 2.5f;        // gust amplitude
        public float gustFrequency = 0.35f;      // Hz
        public float turbulence = 0.4f;          // high-freq noise amount
        public boolean pushPlayer = false;       // also affect CharacterController
        public float playerPushScale = 0.15f;    // force transfer to characters

        private transient float time = 0f;
        /** v2.3: inject PhysicsWorld directly (headless tests); null = resolve via GameApp */
        public PhysicsWorld physicsOverride = null;

        /** instantaneous wind vector (direction * base + gust pulse + turbulence) */
        public Vec3 sampleWind(float dt){
            time += dt;
            float TAU = (float)(2.0 * Math.PI);
            float gust = 1f + gustStrength / Math.max(0.01f, strength)
                * 0.5f * (1f + (float)Math.sin(time * gustFrequency * TAU))
                * (float)(0.8 + 0.2 * Math.sin(time * gustFrequency * 3.7));
            float turb = 1f + turbulence * (float)Math.sin(time * 7.3f) * (float)Math.sin(time * 11.7f);
            Vec3 w = direction.cpy().norm().scale(strength * gust * turb);
            return w;
        }

        @Override public void onUpdate(float dt){
            if(entity == null || entity.destroyed) return;
            PhysicsWorld phy = physicsOverride;
            if(phy == null){
                com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
                if(app == null || app.services() == null || app.services().physics == null) return;
                phy = app.services().physics;
            }
            Vec3 wind = sampleWind(dt);
            for(RigidBody rb : phy.bodyList()){
                if(rb.entity == null || rb.entity.destroyed || rb.kinematic) continue;
                if(rb.sleeping && wind.len2() < 0.05f) continue;
                // wind force ~ area ~ r²; heavy bodies need force*dt/m — scaled down for feel
                float area = rb.effectiveRadius() * rb.effectiveRadius();
                rb.addForce(wind.scale(area * 0.06f), dt);
            }
            if(pushPlayer){
                for(CharacterController cc : phy.controllersView()){
                    Vec3 push = wind.cpy().scale(playerPushScale * dt);
                    cc.move(push);
                }
            }
        }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","WindZone")
              .kvv("direction",direction.x,direction.y,direction.z)
              .kv("strength",strength).kv("gustStrength",gustStrength)
              .kv("gustFrequency",gustFrequency).kv("turbulence",turbulence)
              .kv("pushPlayer",pushPlayer).kv("playerPushScale",playerPushScale);
            w.endObj();
        }
        @Override public void load(JsonV n){
            Object d = n.get("direction");
            if(d instanceof List<?> l && l.size() >= 3) direction.set(num(l.get(0)), num(l.get(1)), num(l.get(2)));
            strength = n.flt("strength",4f);
            gustStrength = n.flt("gustStrength",2.5f);
            gustFrequency = n.flt("gustFrequency",0.35f);
            turbulence = n.flt("turbulence",0.4f);
            pushPlayer = n.bool("pushPlayer",false);
            playerPushScale = n.flt("playerPushScale",0.15f);
        }
    }

    private static float num(Object o){
        if(o instanceof Number num) return num.floatValue();
        try { return Float.parseFloat(String.valueOf(o)); } catch(Exception e){ return 0f; }
    }
}
