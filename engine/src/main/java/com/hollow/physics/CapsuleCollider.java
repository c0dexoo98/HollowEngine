package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;

/** Static/trigger capsule collider (vertical). API FINAL. */
public final class CapsuleCollider extends Component {
    public float radius = 0.4f;
    public float height = 1.8f;
    public final Vec3 offset = new Vec3();
    public boolean isTrigger = false;

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","CapsuleCollider").kv("radius",radius).kv("height",height)
          .kvv("offset",offset.x,offset.y,offset.z).kv("trigger",isTrigger); w.endObj();
    }
    @Override public void load(JsonV n){
        radius = n.flt("radius",0.4f);
        height = n.flt("height",1.8f);
        Vec3 o = com.hollow.core.Json.vec3(n.get("offset"), offset);
        offset.set(o);
        isTrigger = n.bool("trigger", n.bool("isTrigger", false));
    }
}
