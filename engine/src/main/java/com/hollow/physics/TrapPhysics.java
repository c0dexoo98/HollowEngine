package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

/** v2.3 PHY-033 — trap physics: spring-loaded snare/crush/pit traps.
 *  State machine: IDLE -> ARMED (waiting for a body in the zone) -> SPRINGING
 *  (applies spring impulse or snap-locks the victim) -> COOLDOWN -> IDLE.
 *  The trap zone is an AABB around the entity (entity scale aware).
 *  Events on the bus: "trap_sprung" {trap, victim, mode}.
 *  Serialized: {"type":"TrapPhysics","mode":"spring","impulse":8,"snapY":-1.5,...}
 */
public final class TrapPhysics extends Component {
    public enum Mode { SPRING, SNAP, CRUSH }   // SPRING=launch impulse, SNAP=lock/teleport, CRUSH=heavy press
    public Mode mode = Mode.SPRING;
    public Vec3 zoneSize = new Vec3(1.5f, 1.2f, 1.5f);
    public float impulse = 8f;                 // spring launch speed (SPRING)
    public Vec3 impulseDir = new Vec3(0, 1, 0);
    public float snapY = -1.5f;                // SNAP: teleport offset applied to victim
    public float crushForce = 30f;             // CRUSH: downward force while pressed
    public float cooldown = 4f;                // seconds before re-arm
    public boolean armed = true;               // master switch (gameplay can disarm)
    public boolean oneShot = false;            // disarm permanently after firing

    public enum State { IDLE, ARMED, SPRINGING, COOLDOWN }
    public State state = State.ARMED;

    public Entity victim = null;               // read by gameplay (grab/hurt hooks)
    private float cooldownLeft = 0f;
    /** v2.3: inject PhysicsWorld directly (headless tests); null = resolve via GameApp */
    public PhysicsWorld physicsOverride = null;

    @Override public void onUpdate(float dt){
        if(entity == null || entity.destroyed) return;
        PhysicsWorld phy = physicsOverride;
        if(phy == null){
            com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
            if(app == null || app.services() == null || app.services().physics == null) return;
            phy = app.services().physics;
        }
        switch(state){
            case ARMED: {
                if(!armed){ state = State.IDLE; break; }
                Entity found = bodyInZone(phy);
                if(found != null){
                    victim = found;
                    trigger();
                }
                break;
            }
            case SPRINGING: {
                // CRUSH keeps pressing while the victim is inside
                if(mode == Mode.CRUSH && victim != null && !victim.destroyed){
                    RigidBody rb = victim.get(RigidBody.class);
                    if(rb != null) rb.addForce(new Vec3(0, -crushForce * Math.max(0.2f, rb.mass), 0), dt);
                } else {
                    state = State.COOLDOWN;
                    cooldownLeft = cooldown;
                }
                break;
            }
            case COOLDOWN: {
                cooldownLeft -= dt;
                if(cooldownLeft <= 0f){
                    victim = null;
                    state = oneShot ? State.IDLE : (armed ? State.ARMED : State.IDLE);
                }
                break;
            }
            case IDLE: {
                if(armed && !oneShot) state = State.ARMED;
                break;
            }
        }
    }

    /** fire manually (pressure plate scripting, remote trigger) */
    public boolean trigger(){
        if(entity == null || entity.destroyed) return false;
        if(state != State.ARMED) return false;
        PhysicsWorld phy = physicsOverride;
        if(phy == null){
            com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
            phy = app != null && app.services() != null ? app.services().physics : null;
        }
        if(victim == null) victim = bodyInZone(phy);
        RigidBody rb = victim != null ? victim.get(RigidBody.class) : null;
        if(rb != null){
            rb.wake();
            switch(mode){
                case SPRING -> rb.addImpulse(impulseDir.cpy().norm().scale(impulse * Math.max(0.2f, rb.mass)));
                case SNAP -> {
                    victim.position.add(0, snapY, 0);
                    rb.velocity.set(0, 0, 0);
                    victim.markDirtyDeep();
                }
                case CRUSH -> { /* sustained force handled in SPRINGING state */ }
            }
        }
        state = State.SPRINGING;
        if(oneShot) armed = false;
        fireTrapEvent(rb != null);
        return true;
    }

    private Entity bodyInZone(PhysicsWorld phy){
        com.hollow.core.AABB zone = com.hollow.core.AABB.fromCenterExtents(
            entity.worldPosition(),
            new Vec3(zoneSize.x * entity.worldScale().x * 0.5f,
                     zoneSize.y * entity.worldScale().y * 0.5f,
                     zoneSize.z * entity.worldScale().z * 0.5f));
        for(RigidBody rb : phy.bodyList()){
            if(rb.entity == null || rb.entity.destroyed || rb.kinematic) continue;
            if(zone.contains(rb.entity.position)) return rb.entity;
        }
        return null;
    }

    private void fireTrapEvent(boolean caught){
        com.hollow.game.GameApp g = com.hollow.game.GameApp.instance;
        if(g == null || g.services() == null || g.services().events == null) return;
        java.util.Map<String,Object> data = new java.util.LinkedHashMap<>();
        data.put("trap", entity.name);
        data.put("victim", victim != null ? victim.name : "");
        data.put("mode", mode.name().toLowerCase());
        data.put("caught", caught);
        g.services().events.fire("trap_sprung", data);
    }

    public boolean isArmed(){ return state == State.ARMED; }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","TrapPhysics")
          .kv("mode",mode.name().toLowerCase())
          .kvv("zoneSize",zoneSize.x,zoneSize.y,zoneSize.z)
          .kv("impulse",impulse).kvv("impulseDir",impulseDir.x,impulseDir.y,impulseDir.z)
          .kv("snapY",snapY).kv("crushForce",crushForce)
          .kv("cooldown",cooldown).kv("armed",armed).kv("oneShot",oneShot);
        w.endObj();
    }
    @Override public void load(JsonV n){
        String m = n.str("mode","spring");
        try { mode = Mode.valueOf(m.toUpperCase()); } catch(Exception ignored){ mode = Mode.SPRING; }
        Object zs = n.get("zoneSize");
        if(zs instanceof java.util.List<?> l && l.size() >= 3)
            zoneSize.set(num(l.get(0)), num(l.get(1)), num(l.get(2)));
        impulse = n.flt("impulse",8f);
        Object id = n.get("impulseDir");
        if(id instanceof java.util.List<?> l && l.size() >= 3)
            impulseDir.set(num(l.get(0)), num(l.get(1)), num(l.get(2)));
        snapY = n.flt("snapY",-1.5f);
        crushForce = n.flt("crushForce",30f);
        cooldown = n.flt("cooldown",4f);
        armed = n.bool("armed",true);
        oneShot = n.bool("oneShot",false);
        state = armed ? State.ARMED : State.IDLE;
    }

    private static float num(Object o){
        if(o instanceof Number num) return num.floatValue();
        try { return Float.parseFloat(String.valueOf(o)); } catch(Exception e){ return 0f; }
    }
}
