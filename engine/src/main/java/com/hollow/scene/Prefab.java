package com.hollow.scene;

import com.hollow.core.Json;
import com.hollow.core.Log;

import java.util.List;
import java.util.Map;

/** Prefab (OBJ): a reusable entity template stored as JSON (single entity with components).
 *  Instantiate into any world with an optional position override. Prefabs are the unit of
 *  content reuse — a lamp, an NPC, a trap — shared across scenes. */
public final class Prefab {
    private final Map<String, Object> node;
    public final String name;

    private Prefab(Map<String, Object> node, String name){
        this.node = node;
        this.name = name;
    }

    /** parse from JSON text: { "name": "...", "components": [ ... ], "tag": ... } */
    public static Prefab fromJson(String text){
        Map<String, Object> root = Json.parseObj(text);
        if(root == null) throw new IllegalArgumentException("prefab JSON invalid");
        String name = Json.str(root.get("name"), "prefab");
        return new Prefab(root, name);
    }

    /** spawn an instance into the world; pos != null overrides stored position */
    public Entity instantiate(World w, com.hollow.core.Vec3 pos){
        Entity e = Scene.spawnFromJson(w, node);
        if(pos != null){
            e.position.set(pos);
            e.markDirtyDeep();
        }
        // de-duplicate instance name: base, base-2, base-3 ...
        int n = 2;
        while(countNamed(w, e.name) > 1){
            String base = stripCounter(Json.str(node.get("name"), "entity"));
            e.name = base + "-" + n++;
        }
        return e;
    }
    /** convenience: spawn at origin */
    public Entity instantiate(World w){ return instantiate(w, null); }

    /** does this prefab define a component of the given type? */
    public boolean hasComponent(String type){
        List<Object> comps = Json.arr(node.get("components"));
        if(comps == null) return false;
        for(Object o : comps){
            Map<String, Object> c = Json.obj(o);
            if(c != null && type.equals(Json.str(c.get("type"), ""))) return true;
        }
        return false;
    }

    /** serialize a live entity back into prefab JSON text (round-trip authoring) */
    public static String toPrefabJson(Entity e){
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("name", e.name);
        if(!e.tag.isEmpty()) root.put("tag", e.tag);
        if(!e.active) root.put("active", false);
        root.put("position", java.util.List.of(e.position.x, e.position.y, e.position.z));
        root.put("scale", java.util.List.of(e.scale.x, e.scale.y, e.scale.z));

        List<Object> comps = new java.util.ArrayList<>();
        for(Component c : e.allComponents()){
            com.hollow.core.JsonW cw = new com.hollow.core.JsonW();
            cw.obj(); c.save(cw); cw.endObj();
            Map<String, Object> cm = Json.parseObj(cw.toString());
            if(cm != null) comps.add(cm);
        }
        root.put("components", comps);
        return Json.pretty(root);
    }

    private static int countNamed(World w, String name){
        int n = 0;
        for(Entity e : w.entities) if(e.name.equals(name)) n++;
        return n;
    }
    private static String stripCounter(String name){
        int dash = name.lastIndexOf('-');
        if(dash > 0){
            try { Integer.parseInt(name.substring(dash + 1)); return name.substring(0, dash); }
            catch(NumberFormatException ignored){ }
        }
        return name;
    }
}
