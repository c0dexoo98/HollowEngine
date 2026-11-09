package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** World streaming manager (HollowEngine 2.0 — UE World Partition-lite).
 *  Every frame (throttled to 4 Hz): for each StreamingVolume cell, check listener
 *  distance; inside loadRadius -> load its scene chunk ONCE (entities appended);
 *  beyond unloadRadius -> destroy that cell's entities (tracked by cell name tag
 *  "cell:<name>" on every spawned entity).
 *  Fires EventBus events "cell_loaded" {cell} / "cell_unloaded" {cell}.
 *  Streaming is one-way-safe: loading is deferred via GameApp.loadScene semantics —
 *  here chunks are merged into the live world directly (no player teleport).
 */
public final class StreamingManager {
    public static final String CELL_TAG_PREFIX = "cell:";
    public float updateInterval = 0.25f;    // seconds between distance checks
    public int loadedCells = 0;

    private final List<StreamingVolume> cells = new ArrayList<>();
    private float timer = 0f;
    private boolean busy = false;           // re-entrancy guard

    public void collect(World world){
        cells.clear();
        if(world == null) return;
        for(Entity e : world.entities){
            StreamingVolume sv = e.get(StreamingVolume.class);
            if(sv != null) cells.add(sv);
        }
        loadedCells = 0;
        for(StreamingVolume sv : cells) if(sv.loaded) loadedCells++;
        Log.d("Streaming", "cells=" + cells.size());
    }

    public int cellCount(){ return cells.size(); }

    public void update(float dt, World world, Vec3 listenerPos){
        if(world == null || busy || cells.isEmpty()) return;
        timer += dt;
        if(timer < updateInterval) return;
        timer = 0f;
        GameApp app = GameApp.instance;
        if(app == null || app.services() == null) return;

        busy = true;
        try {
            for(StreamingVolume sv : cells){
                if(sv.entity == null || sv.entity.destroyed) continue;
                float dist = sv.entity.worldPosition().dist(listenerPos);
                if(!sv.loaded && dist <= sv.loadRadius && !sv.scene.isEmpty()){
                    loadCell(app, world, sv);
                } else if(sv.loaded && dist > sv.unloadRadius){
                    unloadCell(app, world, sv);
                }
            }
        } finally {
            busy = false;
        }
    }

    private void loadCell(GameApp app, World world, StreamingVolume sv){
        try {
            String text = app.services().fs.readText(sv.scene);
            Map<String,Object> root = Json.parseObj(text);
            @SuppressWarnings("unchecked")
            List<Object> entities = (List<Object>)root.get("entities");
            int n = 0;
            if(entities != null){
                String tag = CELL_TAG_PREFIX + (sv.cell.isEmpty() ? sv.scene : sv.cell);
                for(Object o : entities){
                    if(!(o instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String,Object> en = (Map<String,Object>)o;
                    en.put("tag", tag);       // mark for unload tracking
                    com.hollow.scene.Scene.spawnFromJson(world, en);
                    n++;
                }
            }
            sv.loaded = true;
            sv.loading = false;
            loadedCells++;
            Map<String,Object> data = new java.util.LinkedHashMap<>();
            data.put("cell", sv.cell);
            data.put("entities", n);
            app.services().events.fire("cell_loaded", data);
            Log.i("Streaming", "cell loaded: " + sv.cell + " entities=" + n);
        } catch(Exception e){
            Log.w("Streaming", "cell load failed (" + sv.scene + "): " + e.getMessage());
            sv.loading = false;
        }
    }

    private void unloadCell(GameApp app, World world, StreamingVolume sv){
        String tag = CELL_TAG_PREFIX + (sv.cell.isEmpty() ? sv.scene : sv.cell);
        List<Entity> doomed = new ArrayList<>();
        for(Entity e : world.entities){
            if(tag.equals(e.tag)) doomed.add(e);
        }
        for(Entity e : doomed) world.destroy(e);
        sv.loaded = false;
        loadedCells = Math.max(0, loadedCells - 1);
        Map<String,Object> data = new java.util.LinkedHashMap<>();
        data.put("cell", sv.cell);
        data.put("entities", doomed.size());
        app.services().events.fire("cell_unloaded", data);
        Log.i("Streaming", "cell unloaded: " + sv.cell + " entities=" + doomed.size());
    }
}
