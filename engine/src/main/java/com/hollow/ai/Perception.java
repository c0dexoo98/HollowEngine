package com.hollow.ai;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.physics.PhysicsWorld;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.List;
import java.util.Map;

/** AI perception: sight cone + occlusion raycast + hearing events. */
public final class Perception extends Component {
    public float sightRange = 12f;
    public float fovDeg = 100f;
    public float hearingRange = 6f;
    public String targetTag = "player";

    private Entity visibleTarget;
    public final Vec3 lastKnownTargetPos = new Vec3();
    private float targetVisibleTime;
    private float timer = 0f;
    private PhysicsWorld physics;
    private com.hollow.script.EventBus events;
    private boolean listening = false;
    private com.hollow.script.EventBus.Listener noiseListener = null;   // P8: kept for unlisten

    public Entity visibleTarget(){ return visibleTarget; }
    public boolean hasTarget(){ return visibleTarget != null; }
    public float targetVisibleFor(){ return targetVisibleTime; }

    @Override public void onAttach(){
        com.hollow.game.EngineServices svc = services();
        if(svc != null){
            physics = svc.physics;
            events = svc.events;
            if(events != null && !listening){
                listening = true;
                noiseListener = (name, data) -> {
                    if(data == null || entity == null || entity.destroyed) return;
                    Object pos = data.get("pos");
                    Object radius = data.get("radius");
                    float r = radius instanceof Number n ? n.floatValue() : hearingRange;
                    if(pos instanceof Vec3 p && entity.position.dist(p) <= r){
                        lastKnownTargetPos.set(p);
                    }
                };
                events.listen("noise", noiseListener);
            }
        }
    }

    /** P8: was missing — every attached enemy leaked its noise listener into the static
     *  EventBus forever (per-entity memory leak + stale writes into dead components). */
    @Override public void onDetach(){
        if(listening && events != null && noiseListener != null){
            events.unlisten("noise", noiseListener);
        }
        noiseListener = null;
        listening = false;
    }

    private com.hollow.game.EngineServices services(){
        return com.hollow.game.GameApp.instance != null ? com.hollow.game.GameApp.instance.services() : null;
    }

    /** perception tick (World.update calls onUpdate) */
    @Override public void onUpdate(float dt){
        if(entity == null || !entity.active || entity.destroyed) return;
        // perception runs at 10 Hz (cheap + organic delay)
        timer -= dt;
        if(timer > 0) return;
        timer = 0.1f;

        Entity best = null;
        float bestDist = Float.MAX_VALUE;
        if(physics != null && targetTag != null){
            Vec3 eye = entity.position.cpy().add(0, 1.5f, 0);
            Vec3 fwd = entity.rotation.forward();
            float cosLimit = (float)Math.cos(Math.toRadians(fovDeg) * 0.5f);
            for(Entity e : worldEntities()){
                if(e == entity || !e.active || e.destroyed) continue;
                if(!e.tag.equals(targetTag)) continue;
                Vec3 to = e.position.cpy().sub(entity.position);
                float dist = to.len();
                if(dist > sightRange || dist < 1e-4f) continue;
                if(dist > 1.2f){ // ignore fov when basically touching
                    Vec3 flatTo = new Vec3(to.x, 0, to.z).norm();
                    Vec3 flatFwd = new Vec3(fwd.x, 0, fwd.z).norm();
                    if(flatTo.dot(flatFwd) < cosLimit) continue;
                }
                // occlusion
                Vec3 targetChest = e.position.cpy().add(0, 1.0f, 0);
                Vec3 dir = targetChest.cpy().sub(eye);
                float len = dir.len();
                if(len < 1e-4f) continue;
                dir.scale(1f/len);
                PhysicsWorld.Hit hit = physics.raycast(eye, dir, len);
                if(hit.hit && hit.distance < len - 0.2f) continue;  // blocked
                if(dist < bestDist){ bestDist = dist; best = e; }
            }
        }
        if(best != null){
            if(visibleTarget != best) targetVisibleTime = 0;
            visibleTarget = best;
            targetVisibleTime += 0.1f;
            lastKnownTargetPos.set(best.position);
        } else {
            visibleTarget = null;
        }
    }
    private List<Entity> worldEntities(){
        // climb to root's world via GameApp
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        return app != null && app.world != null ? app.world.entities : java.util.Collections.emptyList();
    }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Perception").kv("sightRange",sightRange).kv("fov",fovDeg)
          .kv("hearingRange",hearingRange).kv("targetTag",targetTag); w.endObj();
    }
    @Override public void load(JsonV n){
        sightRange = n.flt("sightRange",12f); fovDeg = n.flt("fov",100f);
        hearingRange = n.flt("hearingRange",6f); targetTag = n.str("targetTag","player");
    }
}
