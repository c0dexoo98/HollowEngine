package com.hollow.game;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.scene.Component;

/** Streaming cell volume (HollowEngine 2.0, UE World Partition equivalent).
 *  Marks a world-space cell: the scene chunk file loads/unloads as the listener
 *  moves in/out of range. Managed by StreamingManager.
 *  JSON: {"type":"StreamingVolume","cell":"zone2","scene":"scenes/zone2.scene.json",
 *         "loadRadius":30,"unloadRadius":40}
 */
public final class StreamingVolume extends Component {
    public String cell = "";
    public String scene = "";          // scene chunk to load when in range
    public float loadRadius = 30f;
    public float unloadRadius = 40f;   // hysteresis: > loadRadius
    public boolean loaded = false;
    public boolean loading = false;

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","StreamingVolume").kv("cell",cell).kv("scene",scene)
          .kv("loadRadius",loadRadius).kv("unloadRadius",unloadRadius); w.endObj();
    }

    @Override public void load(JsonV n){
        cell = n.str("cell", n.str("name",""));
        scene = n.str("scene","");
        loadRadius = n.flt("loadRadius",30f);
        unloadRadius = n.flt("unloadRadius",40f);
    }
}
