package com.hollow.scene;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

/** Camera projection parameters. Active camera chosen by game framework. */
public final class CameraComponent extends Component {
    public float fovY = 60f;
    public float near = 0.1f;
    public float far = 300f;
    public boolean active = false;

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Camera").kv("fov",fovY).kv("near",near).kv("far",far).kv("active",active); w.endObj();
    }
    @Override public void load(JsonV n){
        fovY = n.flt("fov", 60f); near = n.flt("near", 0.1f); far = n.flt("far", 300f); active = n.bool("active", false);
    }
}
