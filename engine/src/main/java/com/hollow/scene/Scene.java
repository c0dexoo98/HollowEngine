package com.hollow.scene;

import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.JsonV;
import com.hollow.core.Vec3;

import java.util.List;
import java.util.Map;

/** Scene (de)serialization: environment + entity tree. */
public final class Scene {

    // ---------------- load ----------------
    /** world currently being populated (for child-component registry lookup) */
    public static World currentLoadingWorld;
    /** file system of the scene being loaded (prefab extends resolution) */
    public static com.hollow.core.FileSystem currentLoadingFS;

    public static World load(String path, com.hollow.core.FileSystem fs){
        try {
            String text = fs.readText(path);
            currentLoadingFS = fs;
            currentLoadingWorld = null;
            World w = loadFromJson(Json.parseObj(text), simpleName(path));
            currentLoadingWorld = null;
            currentLoadingFS = null;
            return w;
        } catch(Exception e){
            Log.e("Scene", "failed to load scene " + path, e);
            World w = World.empty(simpleName(path));
            return w;
        }
    }

    public static World loadFromJson(Map<String,Object> root, String name){
        World w = World.empty(name);
        currentLoadingWorld = w;
        Map<String,Object> env = Json.obj(root.get("environment"));
        if(env != null) w.environment = env;
        w.name = Json.str(root.get("name"), name);

        List<Object> ents = Json.arr(root.get("entities"));
        if(ents == null) return w;

        Map<String, Entity> byName = new java.util.HashMap<>();
        List<Entity> spawned = new java.util.ArrayList<>();
        for(Object o : ents){
            Map<String,Object> en = Json.obj(o);
            if(en == null) continue;
            Entity e = spawnFromJson(w, en);
            if(e != null){ byName.put(e.name, e); spawned.add(e); }
        }
        // second pass: parenting (parallel to spawned — null entries no longer shift indices)
        for(int i = 0; i < spawned.size(); i++){
            Map<String,Object> en = Json.obj(ents.get(i));
            if(en == null) continue;
            String parent = Json.str(en.get("parent"), null);
            if(parent != null && byName.containsKey(parent)){
                byName.get(parent).addChild(spawned.get(i));
            }
        }
        return w;
    }

    @SuppressWarnings("unchecked")
    public static Entity spawnFromJson(World w, Map<String,Object> en){
        Entity e = w.spawn(Json.str(en.get("name"), null));
        e.tag = Json.str(en.get("tag"), "");
        e.active = Json.bool(en.get("active"), true);
        e.position.set(Json.vec3(en.get("position"), new Vec3()));
        e.scale.set(Json.vec3(en.get("scale"), new Vec3(1,1,1)));
        List<Object> rot = Json.arr(en.get("rotationEuler"));
        if(rot != null && rot.size() >= 3){
            e.rotation.set(com.hollow.core.Quat.fromEuler(
                (float)Json.num(rot.get(0),0), (float)Json.num(rot.get(1),0), (float)Json.num(rot.get(2),0)));
        }
        List<Object> comps = Json.arr(en.get("components"));
        if(comps != null){
            for(Object co : comps){
                Map<String,Object> cn = Json.obj(co);
                if(cn == null) continue;
                String type = Json.str(cn.get("type"), "");
                Component c = createComponent(w, type, cn);
                if(c != null) e.add(c);
                else Log.w("Scene", "unknown component type '" + type + "' on " + e.name);
            }
        }
        return e;
    }

    static Component createComponent(World w, String type, Map<String,Object> node){
        World.ComponentFactory f = w.componentRegistry.get(type);
        if(f == null) return null;
        Component c = f.create();
        try { c.load(new JsonV(node)); } catch(Exception ex){ Log.e("Scene", "component load failed: " + type, ex); }
        return c;
    }

    // ---------------- save ----------------
    public static String save(World w){
        StringBuilder b = new StringBuilder(4096);
        b.append('{');
        Json.keyRaw("name", b); Json.valRaw(w.name, b); b.append(',');
        if(w.environment != null){ Json.keyRaw("environment", b); b.append(Json.write(w.environment)); b.append(','); }
        Json.keyRaw("entities", b);
        b.append('[');
        boolean first = true;
        for(Entity e : w.entities){
            if(!first) b.append(',');
            first = false;
            b.append('{');
            Json.keyRaw("name", b); Json.valRaw(e.name, b); b.append(',');
            if(!e.tag.isEmpty()){ Json.keyRaw("tag", b); Json.valRaw(e.tag, b); b.append(','); }
            Json.keyRaw("active", b); b.append(e.active); b.append(',');
            Json.keyRaw("position", b); b.append('[').append(Json.numRaw(e.position.x)).append(',').append(Json.numRaw(e.position.y)).append(',').append(Json.numRaw(e.position.z)).append("],");
            Json.keyRaw("scale", b); b.append('[').append(Json.numRaw(e.scale.x)).append(',').append(Json.numRaw(e.scale.y)).append(',').append(Json.numRaw(e.scale.z)).append("],");
            float[] eu = eulerAngles(e.rotation);
            Json.keyRaw("rotationEuler", b); b.append('[').append(Json.numRaw(eu[0])).append(',').append(Json.numRaw(eu[1])).append(',').append(Json.numRaw(eu[2])).append("],");
            Json.keyRaw("components", b); b.append('[');
            boolean fc = true;
            for(Component c : e.allComponents()){
                if(!fc) b.append(',');
                fc = false;
                com.hollow.core.JsonW cw = new com.hollow.core.JsonW();
                c.save(cw);   // component save() emits its own { } object
                b.append(cw);
            }
            b.append(']');
            b.append('}');
        }
        b.append(']');
        b.append('}');
        return b.toString();
    }

    private static String componentName(Component c){
        String t = c.type();
        return t.isEmpty() ? c.getClass().getSimpleName() : t;
    }

    public static float[] eulerAngles(com.hollow.core.Quat q){
        // to pitch/yaw/roll matching Quat.fromEuler
        float pitch = q.pitchDeg(), yaw = q.yawDeg();
        return new float[]{ pitch, yaw, 0f };
    }

    static String simpleName(String path){
        int a = path.lastIndexOf('/'), b = path.lastIndexOf('.');
        return path.substring(a+1, b>a ? b : path.length());
    }

    /** register the scene-level built-in components (module components register at their bootstrap) */
    public static void registerStandardComponents(World w){
        w.componentRegistry.put("Mesh", MeshComponent::new);
        w.componentRegistry.put("Light", LightComponent::new);
        w.componentRegistry.put("Camera", CameraComponent::new);
        w.componentRegistry.put("Sprite", SpriteComponent::new);
    }
}
