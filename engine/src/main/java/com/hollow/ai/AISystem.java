package com.hollow.ai;

import com.hollow.core.Vec3;
import com.hollow.physics.PhysicsWorld;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

/** AI update driver: perception runs via World.update; agents tick here in list order. */
public final class AISystem {
    private int activeAgents = 0;

    public void update(float dt, World world){
        if(world == null) return;
        activeAgents = 0;
        for(Entity e : world.entities){
            if(!e.active || e.destroyed) continue;
            Agent a = e.get(Agent.class);
            if(a != null){
                a.tick(dt);
                activeAgents++;
            }
        }
    }
    public int agentCount(){ return activeAgents; }
}
