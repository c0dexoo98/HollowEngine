package com.hollow.render;

import com.hollow.scene.Entity;
import com.hollow.scene.MeshComponent;
import com.hollow.core.Vec3;

import java.util.List;

/** Automatic LOD selection (RND-033): picks the mesh variant by screen-size metric
 *  d = projectedSize / screenHeight (bigger = closer). The game declares LODs on the
 *  MeshComponent — entries ASCENDING by threshold, where the threshold is the MINIMUM
 *  metric at which that LOD applies:
 *  {"lods":[{"mesh":"a_lo.hvm","d":0.0},{"mesh":"a_md.hvm","d":0.08},{"mesh":"a_hi.hvm","d":0.25}]}
 *  The LAST entry whose threshold <= current metric wins. */
public final class LODSelector {

    public float[] refValues = { 0.02f, 0.08f, 0.25f };   // default 3-level thresholds
    public boolean enabled = true;

    /** returns the mesh path to render, or null when the component has no LODs */
    public String select(Entity e, MeshComponent mc, float distanceToCamera, float screenPerPixelScale){
        if(!enabled || mc == null) return null;
        List<MeshComponent.LodEntry> lods = mc.lods;
        if(lods == null || lods.isEmpty()) return null;

        float d = screenMetric(e, distanceToCamera, screenPerPixelScale);
        String best = lods.get(0).mesh;
        for(MeshComponent.LodEntry l : lods){
            if(d >= l.d) best = l.mesh;
        }
        return best;
    }

    /** d-screen metric: approximate fraction of screen height the object covers
     *  (can exceed 1 — clamping would destroy near-field LOD discrimination) */
    public float screenMetric(Entity e, float distanceToCamera, float screenPerPixelScale){
        e.updateWorld();
        float radius = e.getMeta("lodRadius") instanceof Number n ? n.floatValue() : 1f;
        if(distanceToCamera < 0.05f) distanceToCamera = 0.05f;
        return (2f * radius * screenPerPixelScale) / distanceToCamera;
    }

    /** hysteresis wrapper: avoids LOD popping — switches only when crossing band ± margin */
    public static class Hysteresis {
        private final float margin;
        private int current = 0;
        public Hysteresis(float margin){ this.margin = margin; }

        public int update(int desired, int levelCount){
            if(desired == current) return current;
            int step = Integer.compare(desired, current);
            int next = current;
            // only cross when clearly past the boundary
            if(step > 0 && desired > current) next = current + 1;
            else if(step < 0) next = current - 1;
            next = Math.max(0, Math.min(levelCount - 1, next));
            current = next;
            return current;
        }
        public int current(){ return current; }
        public void reset(){ current = 0; }
    }
}
