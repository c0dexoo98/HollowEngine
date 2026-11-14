package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;

/** Static/trigger sphere collider. API FINAL. */
public final class SphereCollider extends Component {
    /** v2.5.2: self-registration for dynamically spawned colliders (World.update calls onUpdate) */
    private boolean selfRegDone = false;
    @Override public void onUpdate(float dt){
        if(selfRegDone) return;
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        if(app == null || app.services() == null || app.services().physics == null) return;
        app.services().physics.registerCollider(entity);
        selfRegDone = true;
    }
    public float radius = 0.5f;
    public final Vec3 offset = new Vec3();
    public boolean isTrigger = false;

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","SphereCollider").kv("radius",radius)
          .kvv("offset",offset.x,offset.y,offset.z).kv("trigger",isTrigger); w.endObj();
    }
    @Override public void load(JsonV n){
        radius = n.flt("radius",0.5f);
        Vec3 o = com.hollow.core.Json.vec3(n.get("offset"), offset);
        offset.set(o);
        isTrigger = n.bool("trigger", n.bool("isTrigger", false));
    }
}
