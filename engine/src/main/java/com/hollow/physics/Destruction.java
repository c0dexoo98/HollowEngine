package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.List;

/** v2.3 PHY-030 — destruction of pre-fractured objects.
 *  The entity carries hidden fragment children (each with a RigidBody, inactive).
 *  fracture(point, radius, force) hides the intact visual, activates fragments inside
 *  the blast radius, applies radial impulse, and optionally schedules cleanup.
 *  Serialized: {"type":"Destruction","radius":2.0,"force":6,"cleanupAfter":12,...}
 */
public final class Destruction extends Component {
    public float blastRadius = 2f;
    public float blastForce = 6f;
    public float fragmentMass = 0.4f;
    public float cleanupAfter = 12f;         // seconds until fragments despawn (0 = keep)
    public boolean fragmented = false;

    private float cleanupTimer = -1f;

    /** activate fragments within radius of a world point (grenade/explosion/bullet) */
    public int fracture(Vec3 point, float radiusScale){
        if(entity == null || entity.destroyed || fragmented && cleanupAfter <= 0f) return 0;
        float radius = blastRadius * Math.max(0.05f, radiusScale);
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        PhysicsWorld phy = app != null && app.services() != null ? app.services().physics : null;
        int activated = 0;
        for(Entity frag : entity.children()){
            if(frag == null || frag.destroyed) continue;
            float dist = frag.worldPosition().dist(point);
            if(dist > radius) continue;
            frag.active = true;
            RigidBody rb = frag.get(RigidBody.class);
            if(rb == null){
                rb = new RigidBody();
                rb.mass = fragmentMass;
                rb.bounciness = 0.25f;
                rb.friction = 0.7f;
                rb.ccd = false;
                frag.add(rb);
            }
            rb.wake();
            if(phy != null) phy.registerBody(rb);
            // impulse away from the blast point
            Vec3 dir = frag.worldPosition().cpy().sub(point);
            float d = dir.len();
            if(d < 1e-5f) dir.set(0, 1, 0); else dir.scale(1f / d);
            float falloff = 1f - MathX.clamp(d / radius, 0f, 1f);
            rb.addImpulse(dir.scale(blastForce * falloff * Math.max(0.1f, rb.mass)));
            activated++;
        }
        if(activated > 0){
            fragmented = true;
            if(cleanupAfter > 0f) cleanupTimer = cleanupAfter;
            // hide the intact shell visual if present
            com.hollow.scene.MeshComponent mesh = entity.get(com.hollow.scene.MeshComponent.class);
            if(mesh != null) mesh.visible = false;
            fireDestructEvent(activated);
        }
        return activated;
    }

    private void fireDestructEvent(int fragments){
        com.hollow.game.GameApp g = com.hollow.game.GameApp.instance;
        if(g == null || g.services() == null || g.services().events == null) return;
        java.util.Map<String,Object> data = new java.util.LinkedHashMap<>();
        data.put("entity", entity.name);
        data.put("fragments", fragments);
        g.services().events.fire("destruction", data);
    }

    @Override public void onUpdate(float dt){
        if(cleanupTimer > 0f){
            cleanupTimer -= dt;
            if(cleanupTimer <= 0f && entity != null && !entity.destroyed){
                for(Entity frag : new ArrayList<>(entity.children())){
                    if(frag.active) frag.destroyed = true;    // swept by world flush
                }
            }
        }
    }

    /** did this entity already shatter? (gameplay queries) */
    public boolean isFragmented(){ return fragmented; }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Destruction")
          .kv("blastRadius",blastRadius).kv("blastForce",blastForce)
          .kv("fragmentMass",fragmentMass).kv("cleanupAfter",cleanupAfter)
          .kv("fragmented",fragmented);
        w.endObj();
    }
    @Override public void load(JsonV n){
        blastRadius = n.flt("blastRadius",2f);
        blastForce = n.flt("blastForce",6f);
        fragmentMass = n.flt("fragmentMass",0.4f);
        cleanupAfter = n.flt("cleanupAfter",12f);
        fragmented = n.bool("fragmented",false);
    }
}
