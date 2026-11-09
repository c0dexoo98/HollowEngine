package com.hollow.scene;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import com.hollow.core.Color;

/** 2D sprite billboard (atlas cell) — for 2D games, particles-of-world, and markers. */
public final class SpriteComponent extends Component {
    public String atlas = "atlas/main.atlas";
    public String sprite = "";
    public final Color color = new Color(1,1,1,1);
    public float width = 1f, height = 1f;
    public boolean billboard = true;    // face camera (world-space) else screen-space UI-like
    public int fps = 0;                 // >0 = animate through sprite frames "name_0..name_N"

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Sprite").kv("atlas",atlas).kv("sprite",sprite);
        w.kvv("color", color.r, color.g, color.b).kv("width",width).kv("height",height).kv("billboard",billboard).kv("fps",fps);
        w.endObj();
    }
    @Override public void load(JsonV n){
        atlas = n.str("atlas", atlas); sprite = n.str("sprite","");
        Color c = com.hollow.core.Json.color(n.get("color"), null); if(c!=null) color.set(c);
        width = n.flt("width",1f); height = n.flt("height",1f);
        billboard = n.bool("billboard", true); fps = n.int32("fps", 0);
    }
}
