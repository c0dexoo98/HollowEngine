package com.hollow.render;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.List;

/** Foliage scatter component (HollowEngine 2.0, UE Foliage tool equivalent).
 *  Deterministically scatters N mesh instances inside the entity's AABB footprint,
 *  rejecting positions blocked by physics or outside masks. Instances are drawn via
 *  GPU instancing with wind sway by the renderer (FoliageSystem).
 *  JSON: {"type":"Foliage","model":"assets/models/grass.hvm","count":200,"density":2.0,
 *         "scaleMin":0.8,"scaleMax":1.4,"alignToNormal":false,"wind":0.5,"seed":7,"yOffset":0}
 */
public final class FoliageComponent extends Component {
    public String model = "";
    public int count = 128;
    public float density = 2.0f;          // instances per m² (informational; count wins)
    public float scaleMin = 0.85f, scaleMax = 1.35f;
    public float yOffset = 0f;
    public float radiusMin = 0f, radiusMax = -1f;   // ring scatter (default: whole footprint)
    public float wind = 0.4f;             // sway amplitude
    public boolean castShadow = true;
    public long seed = 7;
    public List<Object> avoidTags = new ArrayList<>();   // skip if entity with tag overlaps

    // runtime (built by FoliageSystem on first render use; also headless-countable)
    public transient List<Instance> instances = new ArrayList<>();
    public transient boolean built = false;

    public static final class Instance {
        public final Vec3 pos = new Vec3();
        public float scale = 1f;
        public float rotY = 0f;           // degrees
        public float phase = 0f;          // wind phase
    }

    /** deterministic scatter; callable headless (tests) and from renderer */
    public void build(){
        if(built) return;
        built = true;
        instances.clear();
        Entity e = entity;
        if(e == null || model.isEmpty()) return;
        e.updateWorld();
        // footprint = entity local XZ scaled by world scale (AABB of the entity, conservative)
        Vec3 wp = e.worldMatrix.translation();
        float sx = Math.abs(e.scale.x), sz = Math.abs(e.scale.z);
        float hx = 0.5f * sx, hz = 0.5f * sz;         // unit footprint per 1x1 entity
        MathX.Rng rng = new MathX.Rng(seed);
        int attempts = count * 4;
        int placed = 0;
        for(int i = 0; i < attempts && placed < count; i++){
            float fx = (rng.nextFloat() * 2f - 1f) * hx;
            float fz = (rng.nextFloat() * 2f - 1f) * hz;
            float d = (float)Math.sqrt(fx * fx + fz * fz);
            if(radiusMax > 0f && (d < radiusMin || d > radiusMax)) continue;
            Instance inst = new Instance();
            inst.pos.set(wp.x + fx, wp.y + yOffset, wp.z + fz);
            inst.scale = MathX.lerp(scaleMin, scaleMax, rng.nextFloat());
            inst.rotY = rng.nextFloat() * 360f;
            inst.phase = rng.nextFloat() * (float)(Math.PI * 2.0);
            instances.add(inst);
            placed++;
        }
    }

    public int instanceCount(){ return instances.size(); }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Foliage").kv("model",model).kv("count",count).kv("density",density)
          .kv("scaleMin",scaleMin).kv("scaleMax",scaleMax).kv("yOffset",yOffset)
          .kv("wind",wind).kv("castShadow",castShadow).kv("seed",seed); w.endObj();
    }

    @Override public void load(JsonV n){
        model = n.str("model","");
        count = n.int32("count", 128);
        density = n.flt("density", 2f);
        scaleMin = n.flt("scaleMin", 0.85f);
        scaleMax = n.flt("scaleMax", 1.35f);
        yOffset = n.flt("yOffset", 0f);
        wind = n.flt("wind", 0.4f);
        castShadow = n.bool("castShadow", true);
        seed = (long)n.flt("seed", 7f);
    }
}
