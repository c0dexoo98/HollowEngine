package com.hollow.scene;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/** Container of entities. Supports iteration, query by tag/name, spawn/destroy.
 *  v2.2 additions: deferred spawn (OBJ-021), reverse component index (OBJ-022),
 *  thread-safe read access (OBJ-024), ordered traversal (OBJ-029). */
public final class World {
    public String name;
    public final List<Entity> entities = new ArrayList<>();
    private final List<Entity> pendingDestroy = new ArrayList<>();
    private final List<Entity> pendingSpawn = new ArrayList<>();        // OBJ-021
    public final Map<String,Object> blackboard = new LinkedHashMap<>(); // world-level vars (FlowScript)
    /** scene environment JSON: {sky:{...}, ambient:[r,g,b], fog:{...}} */
    public Map<String,Object> environment = null;

    public interface ComponentFactory { Component create(); }
    /** registry of component type -> factory (filled by engine bootstrap; games may extend) */
    public final Map<String, ComponentFactory> componentRegistry = new LinkedHashMap<>();

    /** OBJ-024: read/write lock — render thread takes the read side, sim takes the write side */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public World(String name){ this.name = name; }

    // ---------------- spawn / destroy ----------------

    public Entity spawn(String name){
        Entity e = new Entity(name);
        entities.add(e);
        invalidateIndex();
        return e;
    }

    /** OBJ-021: deferred spawn — entity appears at the NEXT update(), one-frame delay.
     *  Safe to call from event handlers and callbacks mid-iteration. */
    public Entity spawnDeferred(String name){
        Entity e = new Entity(name);
        pendingSpawn.add(e);
        return e;
    }

    public void destroy(Entity e){
        if(e == null || e.destroyed) return;
        e.destroyed = true;
        pendingDestroy.add(e);
        if(e.parent != null) e.parent.fireChildRemoved(e, true);   // OBJ-028: notify owner listeners
        invalidateIndex();
    }
    public void flushDestroy(){
        if(pendingDestroy.isEmpty()) return;
        for(Entity e : pendingDestroy){
            detachDeep(e);
            if(e.parent != null) e.parent.removeChildSilent(e);   // destroy() already fired the event
            entities.remove(e);
        }
        pendingDestroy.clear();
        invalidateIndex();
    }

    /** OBJ-021: flush deferred spawns (called at the start of update) */
    public void flushSpawn(){
        if(pendingSpawn.isEmpty()) return;
        List<Entity> batch = new ArrayList<>(pendingSpawn);
        pendingSpawn.clear();
        for(Entity e : batch){
            e.destroyed = false;
            entities.add(e);
        }
        invalidateIndex();
    }
    public int pendingSpawnCount(){ return pendingSpawn.size(); }

    private void detachDeep(Entity e){
        for(Entity c : e.children) detachDeep(c);
        for(Component c : e.allComponents()) c.forEachDeep(ch -> ch.onDetach());      // OBJ-020 deep
    }

    // ---------------- update ----------------

    public void update(float dt){
        flushSpawn();                                        // OBJ-021
        for(Entity e : entities){
            if(!e.active || e.destroyed) continue;
            e.updateWorld();
            for(Component c : e.allComponents()) c.forEachDeep(ch -> ch.onUpdate(dt));   // OBJ-020 deep
        }
        flushDestroy();
    }

    // ---------------- queries ----------------

    public Entity find(String name){
        for(Entity e : entities) if(e.name.equals(name)) return e;
        return null;
    }
    public List<Entity> byTag(String tag){
        List<Entity> out = new ArrayList<>();
        for(Entity e : entities) if(e.tag.equals(tag)) out.add(e);
        return out;
    }
    public List<Entity> byComponent(String typeName){
        List<Entity> out = new ArrayList<>();
        for(Entity e : entities) if(e.has(typeName)) out.add(e);
        return out;
    }
    /** OBJ-017: entities sharing any bit with the mask */
    public List<Entity> byLayer(int mask){
        List<Entity> out = new ArrayList<>();
        for(Entity e : entities) if((e.layers & mask) != 0) out.add(e);
        return out;
    }

    // ---------------- OBJ-022: reverse component index ----------------

    private Map<String, List<Entity>> componentIndex;    // null = stale
    private int indexBuilds = 0, indexQueries = 0;
    private float indexHitRate = 0f;

    public void invalidateIndex(){ componentIndex = null; }

    /** cached reverse query: component type -> entities. Rebuilds lazily after
     *  structural changes (spawn/destroy); invalidate via {@link #invalidateIndex()}
     *  after adding components to existing entities at runtime. */
    public List<Entity> byComponentCached(String typeName){
        indexQueries++;
        if(componentIndex == null){
            componentIndex = new LinkedHashMap<>();
            for(Entity e : entities){
                for(String type : e.components.keySet()){
                    componentIndex.computeIfAbsent(type, k -> new ArrayList<>()).add(e);
                }
            }
            indexBuilds++;
        }
        List<Entity> l = componentIndex.get(typeName);
        return l != null ? l : Collections.emptyList();
    }
    public String indexStats(){
        return "componentIndex[builds=" + indexBuilds + " queries=" + indexQueries + "]";
    }

    // ---------------- OBJ-029: traversal ----------------

    /** OBJ-029: pre-order traversal across root entities only (children appear once,
     *  even though this engine keeps child entities in the flat list as well) */
    public void traverseAll(Consumer<Entity> fn){
        for(Entity e : entities) if(e.parent == null) e.traverse(fn);
    }
    /** maximum hierarchy depth (root-only world = 1) */
    public int maxDepth(){
        int max = 0;
        for(Entity e : entities) max = Math.max(max, e.depth() + 1);
        return max;
    }

    // ---------------- OBJ-024: locking ----------------

    /** take the read side (renderer thread); pair with {@link #readUnlock} */
    public void readLock(){ lock.readLock().lock(); }
    public void readUnlock(){ lock.readLock().unlock(); }
    /** take the write side (sim thread); pair with {@link #writeUnlock} */
    public void writeLock(){ lock.writeLock().lock(); }
    public void writeUnlock(){ lock.writeLock().unlock(); }
    /** run fn under the write lock */
    public <T> T withWrite(java.util.function.Supplier<T> fn){
        lock.writeLock().lock();
        try { return fn.get(); } finally { lock.writeLock().unlock(); }
    }
    /** run fn under the read lock */
    public <T> T withRead(java.util.function.Supplier<T> fn){
        lock.readLock().lock();
        try { return fn.get(); } finally { lock.readLock().unlock(); }
    }

    // ---------------- misc ----------------

    public void forEach(Consumer<Entity> fn){ for(Entity e : entities) fn.accept(e); }

    public int count(){ return entities.size(); }

    public static World empty(String name){
        World w = new World(name);
        Scene.registerStandardComponents(w);
        for(java.util.function.Consumer<World> hook : registryHooks) hook.accept(w);
        return w;
    }
    /** module registration hooks — engine modules append here (static init) */
    public static final List<java.util.function.Consumer<World>> registryHooks = new ArrayList<>();
}
