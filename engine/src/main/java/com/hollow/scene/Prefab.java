package com.hollow.scene;

import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Prefab (OBJ): a reusable entity template stored as JSON (single entity with components).
 *  Instantiate into any world with an optional position override. Prefabs are the unit of
 *  content reuse — a lamp, an NPC, a trap — shared across scenes.
 *
 *  v2.2 additions:
 *  - OBJ-018 prefab inheritance: {"extends": "prefabs/base.json", ...} — multi-level
 *    parent chains, child overrides parent keys; component lists merge by type with
 *    child fields winning (shallow per-component merge).
 *  - OBJ-025 fragment conversion: {@link #fromEntityTree} serializes a live subtree
 *    into prefab form; {@link #instantiateTree} spawns it back (parent links preserved). */
public final class Prefab {
    private final Map<String, Object> node;
    public final String name;

    private Prefab(Map<String, Object> node, String name){
        this.node = node;
        this.name = name;
    }

    /** parse from JSON text: { "name": "...", "components": [ ... ], "tag": ... }.
     *  Resolves "extends" inheritance chains (OBJ-018). */
    public static Prefab fromJson(String text){
        Map<String, Object> root = Json.parseObj(text);
        if(root == null) throw new IllegalArgumentException("prefab JSON invalid");
        Map<String, Object> merged = resolveInheritance(root, 0);
        String name = Json.str(merged.get("name"), "prefab");
        return new Prefab(merged, name);
    }

    /** multi-level inheritance resolution: merge parent chain into a single node */
    private static Map<String, Object> resolveInheritance(Map<String, Object> node, int depth){
        if(depth > 16) throw new IllegalArgumentException("prefab extends chain too deep (cycle?)");
        String parentPath = Json.str(node.get("extends"), null);
        if(parentPath == null) return node;

        Map<String, Object> parentNode = loadPrefabFile(parentPath);
        Map<String, Object> resolvedParent = resolveInheritance(parentNode, depth + 1);

        // shallow merge: child keys win
        Map<String, Object> merged = new LinkedHashMap<>(resolvedParent);
        for(Map.Entry<String, Object> e : node.entrySet()){
            if(e.getKey().equals("extends")) continue;
            if(e.getKey().equals("components")){
                // components merge by type: parent comps as base, child overrides fields
                List<Object> parentComps = Json.arr(merged.get("components"));
                List<Object> childComps = Json.arr(e.getValue());
                merged.put("components", mergeComponents(parentComps, childComps));
            } else {
                merged.put(e.getKey(), e.getValue());
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadPrefabFile(String path){
        com.hollow.core.FileSystem fs = Scene.currentLoadingFS;
        if(fs == null) throw new IllegalStateException("no FileSystem bound for prefab load: " + path);
        String p = path.startsWith("prefabs/") || path.contains("/") ? path : "prefabs/" + path;
        if(!p.endsWith(".json")) p += ".json";
        Map<String, Object> n = null;
        try {
            n = Json.parseObj(fs.readText(p));
        } catch(Exception e){
            throw new IllegalArgumentException("prefab parent not readable: " + path, e);
        }
        if(n == null) throw new IllegalArgumentException("prefab parent not found: " + path);
        return n;
    }

    /** merge component lists by type (child fields override parent fields) */
    private static List<Object> mergeComponents(List<Object> parent, List<Object> child){
        List<Object> out = new ArrayList<>();
        if(parent != null){
            for(Object po : parent){
                Map<String, Object> pm = Json.obj(po);
                if(pm == null) continue;
                String type = Json.str(pm.get("type"), "");
                Map<String, Object> override = findComponent(child, type);
                if(override != null){
                    Map<String, Object> m = new LinkedHashMap<>(pm);
                    m.putAll(override);           // child wins per-key
                    m.put("type", type);
                    out.add(m);
                } else {
                    out.add(pm);
                }
            }
        }
        if(child != null){
            for(Object co : child){
                Map<String, Object> cm = Json.obj(co);
                if(cm == null) continue;
                String type = Json.str(cm.get("type"), "");
                boolean alreadyMerged = false;
                for(Object oo : out){
                    Map<String, Object> om = Json.obj(oo);
                    if(om != null && type.equals(Json.str(om.get("type"), ""))){ alreadyMerged = true; break; }
                }
                if(!alreadyMerged) out.add(cm);
            }
        }
        return out;
    }
    private static Map<String, Object> findComponent(List<Object> comps, String type){
        if(comps == null || type.isEmpty()) return null;
        for(Object o : comps){
            Map<String, Object> m = Json.obj(o);
            if(m != null && type.equals(Json.str(m.get("type"), ""))) return m;
        }
        return null;
    }

    /** spawn an instance into the world; pos != null overrides stored position */
    public Entity instantiate(World w, Vec3 pos){
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
        return findComponent(Json.arr(node.get("components")), type) != null;
    }

    // ---------------- OBJ-025: fragment <-> prefab conversion ----------------

    /** serialize a live entity subtree into prefab JSON (flat entities + parent links) */
    public static String fromEntityTree(Entity root){
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", root.name);
        List<Object> ents = new ArrayList<>();
        collectTree(root, null, ents);
        out.put("entities", ents);
        return Json.pretty(out);
    }

    private static void collectTree(Entity e, String parentName, List<Object> ents){
        Map<String, Object> en = new LinkedHashMap<>();
        en.put("name", e.name);
        if(parentName != null) en.put("parent", parentName);
        if(!e.tag.isEmpty()) en.put("tag", e.tag);
        if(!e.active) en.put("active", false);
        en.put("position", List.of(e.position.x, e.position.y, e.position.z));
        en.put("scale", List.of(e.scale.x, e.scale.y, e.scale.z));
        // local rotation as euler (pitch/yaw) — matches Scene.save convention
        en.put("rotationEuler", List.of(e.rotation.pitchDeg(), e.rotation.yawDeg(), 0f));
        en.put("layers", e.layers);

        List<Object> comps = new ArrayList<>();
        for(Component c : e.allComponents()){
            com.hollow.core.JsonW cw = new com.hollow.core.JsonW();
            c.save(cw);   // component save() emits its own { } object
            Map<String, Object> cm = Json.parseObj(cw.toString());
            if(cm != null) comps.add(cm);
        }
        en.put("components", comps);
        ents.add(en);
        for(Entity c : e.children()) collectTree(c, e.name, ents);
    }

    /** instantiate a tree-shaped prefab (from fromEntityTree) into a world */
    public Entity instantiateTree(World w, Vec3 worldPos){
        List<Object> ents = Json.arr(node.get("entities"));
        if(ents == null) return instantiate(w, worldPos);   // legacy single-entity prefab
        Map<String, Entity> byName = new LinkedHashMap<>();
        List<Entity> spawned = new ArrayList<>();
        List<Map<String, Object>> spawnedJson = new ArrayList<>();
        for(Object o : ents){
            Map<String, Object> en = Json.obj(o);
            if(en == null) continue;
            Entity e = Scene.spawnFromJson(w, en);
            if(e == null) continue;
            e.layers = Json.int32(en.get("layers"), 0xFFFFFFFF);
            byName.put(e.name, e);
            spawned.add(e);
            spawnedJson.add(en);
        }
        // parent links — spawnedJson[i] is the exact node of spawned[i], so skipped
        // entries in `ents` can no longer shift the pairing [R9]
        for(int i = 0; i < spawned.size(); i++){
            String parent = Json.str(spawnedJson.get(i).get("parent"), null);
            if(parent != null && byName.containsKey(parent)){
                byName.get(parent).addChild(spawned.get(i));
            }
        }
        Entity root = spawned.isEmpty() ? null : spawned.get(0);
        if(root != null && worldPos != null){
            // only the ROOT moves; children keep their local offsets
            Vec3 delta = worldPos.cpy().sub(root.worldPosition());
            root.position.add(delta);
            root.markDirtyDeep();
        }
        return root;
    }

    /** serialize a live single entity back into prefab JSON text (round-trip authoring) */
    public static String toPrefabJson(Entity e){
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", e.name);
        if(!e.tag.isEmpty()) root.put("tag", e.tag);
        if(!e.active) root.put("active", false);
        root.put("position", List.of(e.position.x, e.position.y, e.position.z));
        root.put("scale", List.of(e.scale.x, e.scale.y, e.scale.z));

        List<Object> comps = new ArrayList<>();
        for(Component c : e.allComponents()){
            com.hollow.core.JsonW cw = new com.hollow.core.JsonW();
            c.save(cw);   // component save() emits its own { } object
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
