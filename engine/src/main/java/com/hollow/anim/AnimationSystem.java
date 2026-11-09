package com.hollow.anim;

import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.List;

/** Animation update driver (Agent C).
 *
 *  Animator.onUpdate is invoked by World.update for every entity, so this system's job is
 *  ordering/cleanup bookkeeping: it keeps the registry of live animators in world entity
 *  order, drops animators from destroyed entities, and counts actively playing animators.
 *  It runs before AI/physics in the frame pipeline (see GameApp.frame).
 */
public final class AnimationSystem {
    private final List<Animator> registry = new ArrayList<>();
    private int playingCount;
    private int totalCount;

    /** number of animators currently registered (live entities) */
    public int animatorCount(){ return totalCount; }

    /** number of animators with a playing clip as of the last update() */
    public int playingCount(){ return playingCount; }

    public void update(float dt, World world){
        // cleanup: drop animators whose entity is gone
        for(int i = registry.size() - 1; i >= 0; i--){
            Animator a = registry.get(i);
            if(a.entity == null || a.entity.destroyed) registry.remove(i);
        }
        playingCount = 0;
        if(world == null){ totalCount = registry.size(); return; }
        // maintain registration in world iteration order (deterministic sampling order)
        int regIdx = 0;
        for(Entity e : world.entities){
            if(!e.active || e.destroyed) continue;
            Animator a = e.get(Animator.class);
            if(a == null) continue;
            if(regIdx >= registry.size() || registry.get(regIdx) != a){
                int idx = registry.indexOf(a);
                if(idx >= 0) registry.remove(idx);
                registry.add(Math.min(regIdx, registry.size()), a);
            }
            regIdx++;
            if(a.playing) playingCount++;
        }
        totalCount = registry.size();
    }
}
