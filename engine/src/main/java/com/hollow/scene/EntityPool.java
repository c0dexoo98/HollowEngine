package com.hollow.scene;

import java.util.function.Supplier;

/** Pooled entities/components (MEM-013): mass spawn (bullet storms, particle
 *  debris, crowds) without GC churn. Entities are recycled with full component
 *  reset — identity stays stable (id increments never recycle), so stale
 *  references are detectable via {@link Entity#destroyed}. */
public final class EntityPool {
    private final World world;
    private final java.util.ArrayDeque<Entity> free = new java.util.ArrayDeque<>();
    private final Supplier<Component>[] componentFactories;   // reset on every reuse
    private final String baseName;
    private int spawned = 0, recycled = 0, peakLive = 0, live = 0;

    @SuppressWarnings("unchecked")
    public EntityPool(World world, String baseName, Supplier<Component>... componentFactories){
        this.world = world;
        this.baseName = baseName;
        this.componentFactories = componentFactories;
    }

    /** obtain a fresh or recycled entity (active, at origin, components attached) */
    public Entity obtain(){
        Entity e = free.pollFirst();
        if(e != null){
            recycleReset(e);
            recycled++;
        } else {
            e = world.spawn(baseName + "-" + (++spawned));
            for(Supplier<Component> f : componentFactories) e.add(f.get());
        }
        e.destroyed = false;
        e.active = true;
        e.position.set(0,0,0);
        e.rotation.identity();
        e.scale.set(1,1,1);
        e.markDirtyDeep();
        live++;
        if(live > peakLive) peakLive = live;
        return e;
    }

    /** return an entity to the pool */
    public void release(Entity e){
        if(e == null || e.destroyed && !world.entities.contains(e)){
            // tolerate double release silently
        }
        e.active = false;
        e.destroyed = true;                     // hint for stale-reference checks
        world.entities.remove(e);
        free.addFirst(e);
        live = Math.max(0, live - 1);
    }

    private void recycleReset(Entity e){
        e.tag = "";
        e.components.clear();
        for(Supplier<Component> f : componentFactories) e.add(f.get());
        e.children.clear();
        e.parent = null;
    }

    /** pre-warm the pool with n entities */
    public void prewarm(int n){
        java.util.List<Entity> warmed = new java.util.ArrayList<>(n);
        for(int i=0;i<n;i++) warmed.add(obtain());
        for(Entity e : warmed) release(e);
    }

    public int live(){ return live; }
    public int idle(){ return free.size(); }
    public int recycled(){ return recycled; }
    public int peakLive(){ return peakLive; }
    public String stats(){
        return "entityPool[live=" + live + " idle=" + free.size() + " created=" + spawned + " recycled=" + recycled + " peak=" + peakLive + "]";
    }
}
