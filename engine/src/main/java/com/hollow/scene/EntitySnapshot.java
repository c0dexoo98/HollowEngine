package com.hollow.scene;

import com.hollow.core.Json;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Full entity state snapshot (OBJ-023) for editor undo/redo and regression
 *  capture: transform, tag, layers, metadata and every component serialized
 *  through its own save/load. Snapshots are plain JSON maps — diffable, storable
 *  in undo stacks or test baselines. */
public final class EntitySnapshot {
    public final Map<String, Object> node;

    private EntitySnapshot(Map<String, Object> node){ this.node = node; }

    /** capture the full state of an entity (optionally its whole subtree) */
    public static EntitySnapshot capture(Entity e, boolean subtree){
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        putEntity(root, e);
        if(subtree){
            List<Object> kids = new ArrayList<>();
            for(Entity c : e.children()) kids.add(capture(c, true).node);
            root.put("children", kids);
        }
        return new EntitySnapshot(root);
    }

    private static void putEntity(Map<String, Object> root, Entity e){
        root.put("id", e.id);
        root.put("name", e.name);
        root.put("tag", e.tag);
        root.put("active", e.active);
        root.put("layers", e.layers);
        root.put("position", List.of(e.position.x, e.position.y, e.position.z));
        root.put("rotation", List.of(e.rotation.x, e.rotation.y, e.rotation.z, e.rotation.w));
        root.put("scale", List.of(e.scale.x, e.scale.y, e.scale.z));
        if(e.hasMeta("note")) root.put("meta_note", String.valueOf(e.getMeta("note")));
        List<Object> comps = new ArrayList<>();
        for(Component c : e.allComponents()){
            JsonW w = new JsonW();
            w.obj(); c.save(w); w.endObj();
            Map<String, Object> cm = Json.parseObj(w.toString());
            if(cm != null) comps.add(cm);
        }
        root.put("components", comps);
    }

    /** restore onto an existing entity (same component types expected). */
    public void restore(Entity e, boolean subtree){
        applyEntity(node, e);
        if(subtree){
            List<Object> kids = Json.arr(node.get("children"));
            if(kids != null && kids.size() == e.children().size()){
                for(int i=0;i<kids.size();i++){
                    Map<String, Object> k = Json.obj(kids.get(i));
                    if(k != null) new EntitySnapshot(k).restore(e.children().get(i), true);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyEntity(Map<String, Object> node, Entity e){
        e.name = Json.str(node.get("name"), e.name);
        e.tag = Json.str(node.get("tag"), e.tag);
        e.active = Json.bool(node.get("active"), true);
        e.layers = Json.int32(node.get("layers"), 0xFFFFFFFF);
        List<Object> pos = Json.arr(node.get("position"));
        if(pos != null && pos.size() == 3)
            e.position.set((float)Json.num(pos.get(0), 0), (float)Json.num(pos.get(1), 0), (float)Json.num(pos.get(2), 0));
        List<Object> rot = Json.arr(node.get("rotation"));
        if(rot != null && rot.size() == 4)
            e.rotation.set((float)Json.num(rot.get(0), 0), (float)Json.num(rot.get(1), 0),
                           (float)Json.num(rot.get(2), 0), (float)Json.num(rot.get(3), 1));
        List<Object> scl = Json.arr(node.get("scale"));
        if(scl != null && scl.size() == 3)
            e.scale.set((float)Json.num(scl.get(0), 1), (float)Json.num(scl.get(1), 1), (float)Json.num(scl.get(2), 1));
        if(node.containsKey("meta_note")) e.setMeta("note", Json.str(node.get("meta_note"), ""));
        List<Object> comps = Json.arr(node.get("components"));
        if(comps != null){
            for(Object o : comps){
                Map<String, Object> cm = Json.obj(o);
                if(cm == null) continue;
                String type = Json.str(cm.get("type"), "");
                Component c = e.get(type);
                if(c != null) c.load(new com.hollow.core.JsonV(cm));
            }
        }
        e.markDirtyDeep();
    }

    /** JSON text of the snapshot */
    public String toJson(){ return Json.pretty(node); }
    public static EntitySnapshot fromJson(String text){
        Map<String, Object> root = Json.parseObj(text);
        if(root == null) throw new IllegalArgumentException("snapshot JSON invalid");
        return new EntitySnapshot(root);
    }
}
