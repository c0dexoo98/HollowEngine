package com.hollow.render;

import com.hollow.core.Json;
import com.hollow.scene.Entity;
import com.hollow.scene.LightComponent;
import com.hollow.scene.World;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Light probe grid (LGT-016): hand-placed ambient probes for interior rooms.
 *  The renderer resolves the blended ambient color at the listener position —
 *  cheap, artist-controlled, no SH baking needed. Probes declare:
 *  {"type":"LightProbe","radius":8,"ambient":[r,g,b],"weight":1}
 *  Multiple overlapping probes blend by inverse-square distance × weight. */
public final class LightProbes {

    public static final class Probe {
        public final Vec3 position;
        public final float radius;
        public final float[] ambient;    // linear rgb 0..N
        public final float weight;
        public Probe(Vec3 pos, float radius, float[] ambient, float weight){
            this.position = pos; this.radius = Math.max(0.01f, radius);
            this.ambient = ambient != null && ambient.length >= 3 ? ambient.clone() : new float[]{1,1,1};
            this.weight = Math.max(0f, weight);
        }
    }

    private final List<Probe> probes = new ArrayList<>();
    private boolean dirty = true;

    /** rebuild from a world's LightProbe components (cheap: call on scene load / structural change) */
    public void rebuild(World world){
        probes.clear();
        if(world != null){
            for(Entity e : world.entities){
                LightProbeComponent c = e.get(LightProbeComponent.class);
                if(c == null) continue;
                e.updateWorld();
                probes.add(new Probe(e.worldPosition(), c.radius, c.ambient, c.weight));
            }
        }
        dirty = false;
    }
    public boolean isDirty(){ return dirty; }
    public void markDirty(){ dirty = true; }
    public int count(){ return probes.size(); }

    /** blended ambient at a position (out = rgb). Returns true when any probe had influence. */
    public boolean resolve(Vec3 pos, float[] out){
        if(probes.isEmpty()){ out[0] = out[1] = out[2] = 0f; return false; }
        float wsum = 0f, r = 0, g = 0, b = 0;
        for(Probe p : probes){
            float d = p.position.dist(pos);
            if(d > p.radius) continue;
            float w = p.weight / (1f + (d * d) / (p.radius * p.radius));
            r += p.ambient[0] * w; g += p.ambient[1] * w; b += p.ambient[2] * w;
            wsum += w;
        }
        if(wsum < 1e-5f){ out[0] = out[1] = out[2] = 0f; return false; }
        out[0] = r / wsum; out[1] = g / wsum; out[2] = b / wsum;
        return true;
    }

    /** nearest probe index (editor gizmo drawing) */
    public int nearest(Vec3 pos){
        int best = -1; float bestD = Float.MAX_VALUE;
        for(int i=0;i<probes.size();i++){
            float d = probes.get(i).position.dist2(pos);
            if(d < bestD){ bestD = d; best = i; }
        }
        return best;
    }

    /** scene component: {"type":"LightProbe","radius":8,"ambient":[r,g,b],"weight":1}
     *  NOTE: type() stays the class simple name — Entity.get(Class) keys on it.
     *  The registry holds both "LightProbe" (JSON alias) and the class name. */
    public static final class LightProbeComponent extends com.hollow.scene.Component {
        public float radius = 8f;
        public final float[] ambient = { 0.4f, 0.4f, 0.5f };
        public float weight = 1f;

        @Override public void onAttach(){ markWorldDirty(); }
        @Override public void onDetach(){ markWorldDirty(); }

        private void markWorldDirty(){
            // world backref is implicit: renderer rebuilds when flagged via GameApp
            com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
            if(app != null && app.svc != null) app.svc.probesDirty = true;
        }

        @Override public void save(com.hollow.core.JsonW w){
            w.obj(); w.kv("type", "LightProbe").kv("radius", radius)
              .kvv("ambient", ambient[0], ambient[1], ambient[2]).kv("weight", weight);
            w.endObj();
        }
        @Override public void load(com.hollow.core.JsonV n){
            radius = n.flt("radius", 8f);
            java.util.List<Object> arr = n.list("ambient");
            if(arr != null && arr.size() >= 3){
                for(int i=0;i<3;i++) ambient[i] = (float) Json.num(arr.get(i), 1f);
            }
            weight = n.flt("weight", 1f);
        }
    }
}
