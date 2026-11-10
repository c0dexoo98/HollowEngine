package com.hollow.render;

import com.hollow.core.Json;
import com.hollow.scene.Entity;
import com.hollow.scene.LightComponent;
import com.hollow.scene.World;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.List;

/** One-bounce bounce light (LGT-017): for every shadow-casting directional light the
 *  renderer conceptually lights the floor; we approximate the single diffuse bounce by
 *  injecting a dim, tinted FILL light (opposite direction, half intensity, surface
 *  color). Pure Java — no shader change — runs in the light-collection step. */
public final class BounceLight {

    public float intensityScale = 0.22f;   // energy kept after one bounce
    public boolean enabled = true;

    /** result: one synthetic fill light */
    public static final class Fill {
        public final Vec3 direction = new Vec3();    // FROM surface TO scene (light travel dir)
        public final float[] color = new float[3];
        public float intensity;
        public Fill(){ }
    }

    private final List<Fill> pool = new ArrayList<>();
    private int used = 0;

    public BounceLight(){
        for(int i=0;i<4;i++) pool.add(new Fill());
    }

    /** collect bounce fills for the frame. bounceColor approximates the dominant
     *  surface albedo (from the scene's environment; artist-tunable). */
    public List<Fill> collect(World world, Vec3 listenerPos, float[] bounceColor){
        used = 0;
        if(!enabled || world == null) return pool.subList(0, 0);
        for(Entity e : world.entities){
            if(used >= pool.size()) break;
            LightComponent lc = e.get(LightComponent.class);
            if(lc == null || lc.kind != LightComponent.Kind.DIRECTIONAL) continue;
            e.updateWorld();
            Vec3 fwd = e.rotation.forward();               // light travel direction
            Fill f = pool.get(used++);
            // bounce leaves the floor upward: flip the vertical component, keep spread
            f.direction.set(-fwd.x, Math.abs(fwd.y) < 0.25f ? 0.6f : -fwd.y, -fwd.z).norm();
            f.color[0] = Math.min(4f, lc.color.r * bounceColor[0]);
            f.color[1] = Math.min(4f, lc.color.g * bounceColor[1]);
            f.color[2] = Math.min(4f, lc.color.b * bounceColor[2]);
            f.intensity = lc.effectiveIntensity() * intensityScale;
        }
        return pool.subList(0, used);
    }

    public int lastCount(){ return used; }

    public String stats(){
        return "bounceLights[fills=" + used + " scale=" + intensityScale + "]";
    }
}
