package com.hollow.scene;

import com.hollow.core.Log;

import java.util.*;
import java.util.function.Consumer;

/** Container of entities. Supports iteration, query by tag/name, spawn/destroy. */
public final class World {
    public String name;
    public final List<Entity> entities = new ArrayList<>();
    private final List<Entity> pendingDestroy = new ArrayList<>();
    public final Map<String,Object> blackboard = new LinkedHashMap<>();   // world-level vars (FlowScript)
    /** scene environment JSON: {sky:{...}, ambient:[r,g,b], fog:{...}} */
    public Map<String,Object> environment = null;

    public interface ComponentFactory { Component create(); }
    /** registry of component type -> factory (filled by engine bootstrap; games may extend) */
    public final Map<String, ComponentFactory> componentRegistry = new LinkedHashMap<>();

    public World(String name){ this.name = name; }

    public Entity spawn(String name){
        Entity e = new Entity(name);
        entities.add(e);
        return e;
    }
    public void destroy(Entity e){
        if(e == null || e.destroyed) return;
        e.destroyed = true;
        pendingDestroy.add(e);
    }
    public void flushDestroy(){
        if(pendingDestroy.isEmpty()) return;
        for(Entity e : pendingDestroy){
            detachDeep(e);
            if(e.parent != null) e.parent.removeChild(e);
            entities.remove(e);
        }
        pendingDestroy.clear();
    }
    private void detachDeep(Entity e){
        for(Entity c : e.children) detachDeep(c);
        for(Component c : e.allComponents()) c.onDetach();
    }

    public void update(float dt){
        for(Entity e : entities){
            if(!e.active || e.destroyed) continue;
            e.updateWorld();
            for(Component c : e.allComponents()) c.onUpdate(dt);
        }
        flushDestroy();
    }

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
