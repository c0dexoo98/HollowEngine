package com.hollow.ai;

import com.hollow.core.Vec3;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.List;

/** Local crowd avoidance (HollowEngine 2.0 — RVO-lite, UE Crowd/Detour avoidance style).
 *  Given an agent's desired velocity, samples neighbor agents within `radius` and
 *  laterally steers around them (no full velocity-obstacle math — mobile-friendly).
 *  Deterministic: no RNG, pure geometry.
 */
public final class CrowdAvoidance {
    public float radius = 2.5f;          // neighbor detection radius
    public float sideBias = 0f;          // -1 left, +1 right preference, 0 auto
    public float strength = 1.0f;

    private final Vec3 steer = new Vec3();

    /** computes adjusted displacement for this tick.
     *  @param desired move delta this tick (before avoidance)
     *  @param me the moving agent's entity
     *  @param world to enumerate other agents
     */
    public Vec3 apply(Vec3 desired, Agent me, World world){
        steer.set(0,0,0);
        Entity self = me.entity;
        if(self == null || world == null || desired.len2() < 1e-10f) return desired;
        Vec3 myPos = self.worldPosition();

        for(Entity oe2 : world.byComponent("Agent")){
            Agent other = oe2.get(Agent.class);
            if(other == me || other == null || oe2.destroyed) continue;
            Entity oe = oe2;
            Vec3 op = oe.worldPosition();
            Vec3 d = op.cpy().sub(myPos);
            float dist = d.len();
            if(dist > radius || dist < 1e-4f) continue;
            d.scale(1f / dist);
            // only avoid if the neighbor is roughly in front of our movement
            Vec3 moveDir = desired.cpy().norm();
            float front = d.dot(moveDir);
            if(front < 0.1f) continue;
            // lateral push away from neighbor, perpendicular to movement
            Vec3 side = new Vec3(-moveDir.z, 0, moveDir.x);
            float s = d.dot(side);
            float w = (1f - dist / radius) * front * strength;
            if(Math.abs(s) < 0.05f){
                // head-on: pick deterministic side from entity ids
                s = self.id < oe.id ? 1f : -1f;
            } else {
                s = Math.signum(s);
            }
            if(sideBias != 0f) s = Math.signum(sideBias);
            steer.addScaled(side, -s * w);          // push opposite the neighbor side
        }
        if(steer.len2() < 1e-10f) return desired;
        // scale steer to same magnitude as desired, blend 50/50
        Vec3 out = desired.cpy();
        steer.norm().scale(desired.len());
        out.addScaled(steer, 0.5f);
        return out;
    }
}
