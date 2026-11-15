package com.hollow.cli;

import com.hollow.core.Json;
import com.hollow.core.JsonV;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.0.0 CLI 'edit' — headless scene editor.
 *
 * Scene JSON is the source of truth; this command mutates it with small,
 * scriptable operations (place/move/rotate/light/delete/list). No GUI needed —
 * the same ops work from CI, a REPL or a build script:
 *
 *   edit -p demo-fps list
 *   edit -p demo-fps place Enemy1 -t 12 0 6 --components '[{"type":"CapsuleCollider","radius":0.4,"height":1.7}]'
 *   edit -p demo-fps move Enemy1 -t 12 0 8
 *   edit -p demo-fps rotate Player -y 90
 *   edit -p demo-fps light Lamp1 -t 3 2.5 3 -c 1 0.85 0.6 -i 12
 *   edit -p demo-fps delete Enemy1
 */
final class SceneEdit {

    private SceneEdit(){ }

    static int run(String[] args){
        String project = Main.arg(args, "-p", "project", 1);
        if(project == null){ System.err.println("edit: -p <project> required"); return 2; }
        List<String> rest = new ArrayList<>();
        for(int i = 1; i < args.length; i++){   // args[0] == "edit"
            if(args[i].equals("-p") && i + 1 < args.length) i++;
            else rest.add(args[i]);
        }
        if(rest.isEmpty()) { help(); return 2; }
        String op = rest.get(0);
        String sceneFile = firstScene(project);
        if(sceneFile == null){ System.err.println("edit: no scene in project " + project); return 2; }
        setScenePath(sceneFile);
        try {
            Map<String,Object> root = Json.parseObj(new String(Files.readAllBytes(new File(sceneFile).toPath()), "UTF-8"));
            List<Object> entities = (List<Object>) root.get("entities");
            if(entities == null){ entities = new ArrayList<>(); root.put("entities", entities); }
            switch(op){
                case "list": return list(entities);
                case "place": return place(entities, rest, root);
                case "move": return transform(entities, rest, false);
                case "rotate": return transform(entities, rest, true);
                case "light": return light(entities, rest);
                case "delete": return delete(entities, rest);
                default:
                    System.err.println("edit: unknown op '" + op + "'");
                    help();
                    return 2;
            }
        } catch(Exception e){
            System.err.println("edit failed: " + e);
            e.printStackTrace();
            return 1;
        }
    }

    private static String firstScene(String project){
        File dir = new File("projects/" + project + "/scenes");
        if(!dir.isDirectory()) dir = new File(project + "/scenes");
        if(!dir.isDirectory()) return null;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        return files != null && files.length > 0 ? files[0].getPath() : null;
    }

    private static void help(){
        System.out.println("usage: edit -p <project> <op> ...\n" +
            "  list\n" +
            "  place <name> -t x y z [--tag t] [--components json]\n" +
            "  move   <name> -t x y z\n" +
            "  rotate <name> -x deg] [-y deg] [-z deg]     (sets rotation array)\n" +
            "  light  <name> -t x y z -c r g b -i intensity [--spot]\n" +
            "  delete <name>");
    }

    private static Map<String,Object> findEntity(List<Object> entities, String name){
        for(Object o : entities){
            Map<String,Object> e = (Map<String,Object>) o;
            if(name.equals(e.get("name"))) return e;
        }
        return null;
    }

    private static float[] xyz(String[] raw, int from){
        // expects three consecutive numbers starting at raw[from] (already split)
        return null;
    }

    private static List<String> after(List<String> rest, int skip){
        return rest.subList(Math.min(skip, rest.size()), rest.size());
    }

    private static String flag(List<String> a, String name){
        for(int i = 0; i < a.size() - 1; i++) if(a.get(i).equals(name)) return a.get(i + 1);
        return null;
    }

    private static float[] flagVec(List<String> a, String name){
        for(int i = 0; i < a.size() - 1; i++){
            if(a.get(i).equals(name)){
                // accept both "-t 5,0,7" (single arg) and "-t 5 0 7" (three args)
                String v = a.get(i + 1);
                if(v.contains(",")){
                    String[] parts = v.split(",");
                    float[] out = new float[Math.min(3, parts.length)];
                    for(int k = 0; k < out.length; k++) out[k] = Float.parseFloat(parts[k].trim());
                    return out;
                }
                int take = Math.min(3, a.size() - i - 1);
                float[] out = new float[take];
                try {
                    for(int k = 0; k < take; k++) out[k] = Float.parseFloat(a.get(i + 1 + k));
                } catch(NumberFormatException e){
                    if(take == 1) return null;   // single non-numeric value
                    float[] one = new float[1];
                    try { one[0] = Float.parseFloat(v); } catch(NumberFormatException e2){ return null; }
                    return one;
                }
                return out;
            }
        }
        return null;
    }

    private static List<Float> vec(float... v){
        List<Float> l = new ArrayList<>();
        for(float f : v) l.add(f);
        return l;
    }

    private static int list(List<Object> entities){
        System.out.println(entities.size() + " entities:");
        for(Object o : entities){
            Map<String,Object> e = (Map<String,Object>) o;
            Object pos = e.get("position");
            int nc = e.get("components") instanceof List ? ((List<?>)e.get("components")).size() : 0;
            System.out.printf("  %-18s tag=%-8s pos=%-22s components=%d%n",
                e.get("name"), e.getOrDefault("tag", "-"), pos == null ? "-" : pos.toString(), nc);
        }
        return 0;
    }

    private static int place(List<Object> entities, List<String> rest, Map<String,Object> root) throws Exception {
        if(rest.size() < 2){ System.err.println("place: need a name"); return 2; }
        String name = rest.get(1);
        if(findEntity(entities, name) != null){ System.err.println("place: entity '" + name + "' already exists"); return 1; }
        List<String> a = after(rest, 2);
        float[] t = flagVec(a, "-t");
        if(t == null || t.length < 3){ System.err.println("place: -t x y z required"); return 2; }
        Map<String,Object> e = new LinkedHashMap<>();
        e.put("name", name);
        String tag = flag(a, "--tag");
        if(tag != null) e.put("tag", tag);
        e.put("position", vec(t[0], t[1], t[2]));
        String compJson = flag(a, "--components");
        if(compJson != null){
            Object comps = Json.parse(compJson);
            e.put("components", comps);
        }
        entities.add(e);
        return save(root, entities, "placed '" + name + "'");
    }

    private static int transform(List<Object> entities, List<String> rest, boolean rotate){
        if(rest.size() < 2){ System.err.println((rotate ? "rotate" : "move") + ": need a name"); return 2; }
        String name = rest.get(1);
        Map<String,Object> e = findEntity(entities, name);
        if(e == null){ System.err.println("no entity '" + name + "'"); return 1; }
        List<String> a = after(rest, 2);
        if(rotate){
            float[] x = flagVec(a, "-x"), y = flagVec(a, "-y"), z = flagVec(a, "-z");
            List<Object> cur = e.get("rotation") instanceof List ? (List<Object>) e.get("rotation") : null;
            float rx = cur != null && cur.size() > 0 && x == null ? ((Number)cur.get(0)).floatValue() : x != null ? x[0] : 0f;
            float ry = cur != null && cur.size() > 1 && y == null ? ((Number)cur.get(1)).floatValue() : y != null ? y[0] : 0f;
            float rz = cur != null && cur.size() > 2 && z == null ? ((Number)cur.get(2)).floatValue() : z != null ? z[0] : 0f;
            e.put("rotation", vec(rx, ry, rz));
            return save(null, entities, "rotated '" + name + "'");
        }
        float[] t = flagVec(a, "-t");
        if(t == null || t.length < 3){ System.err.println("move: -t x y z required"); return 2; }
        e.put("position", vec(t[0], t[1], t[2]));
        return save(null, entities, "moved '" + name + "'");
    }

    private static int light(List<Object> entities, List<String> rest) throws Exception {
        if(rest.size() < 2){ System.err.println("light: need a name"); return 2; }
        String name = rest.get(1);
        Map<String,Object> e = findEntity(entities, name);
        List<String> a = after(rest, 2);
        float[] t = flagVec(a, "-t");
        float[] c = flagVec(a, "-c");
        String inten = flag(a, "-i");
        if(e == null){
            // create light entity
            if(t == null || t.length < 3){ System.err.println("light: -t x y z required for a new light"); return 2; }
            e = new LinkedHashMap<>();
            e.put("name", name);
            e.put("tag", "light");
            e.put("position", vec(t[0], t[1], t[2]));
            Map<String,Object> lc = new LinkedHashMap<>();
            lc.put("type", a.contains("--spot") ? "spot" : "point");
            if(c != null && c.length >= 3){
                List<Float> col = vec(c[0], c[1], c[2]);
                lc.put("color", col);
            }
            if(inten != null) lc.put("intensity", Float.parseFloat(inten));
            List<Object> comps = new ArrayList<>();
            comps.add(lc);
            e.put("components", comps);
            entities.add(e);
        } else {
            // edit existing light component
            List<Object> comps = (List<Object>) e.get("components");
            Map<String,Object> light = null;
            for(Object o : comps){
                Map<String,Object> cm = (Map<String,Object>) o;
                Object ty = cm.get("type");
                if(ty instanceof String && (((String)ty).equalsIgnoreCase("point") || ((String)ty).equalsIgnoreCase("spot") || ((String)ty).equalsIgnoreCase("directional"))){
                    light = cm; break;
                }
            }
            if(light == null){
                light = new LinkedHashMap<>();
                light.put("type", "point");
                comps.add(light);
            }
            if(t != null && t.length >= 3) e.put("position", vec(t[0], t[1], t[2]));
            if(c != null && c.length >= 3) light.put("color", vec(c[0], c[1], c[2]));
            if(inten != null) light.put("intensity", Float.parseFloat(inten));
        }
        return save(null, entities, "light '" + name + "' set");
    }

    private static int delete(List<Object> entities, List<String> rest){
        if(rest.size() < 2){ System.err.println("delete: need a name"); return 2; }
        String name = rest.get(1);
        boolean removed = entities.removeIf(o -> name.equals(((Map<String,Object>) o).get("name")));
        if(!removed){ System.err.println("no entity '" + name + "'"); return 1; }
        return save(null, entities, "deleted '" + name + "'");
    }

    private static String currentScenePath = null;

    private static int save(Map<String,Object> rootForPlace, List<Object> entities, String msg){
        // entities are mutated in place inside root; rootForPlace is only used the first time
        try {
            if(currentScenePath == null) return 1;
            // re-read the file root each save: entities list object is the SAME list we mutated,
            // so serializing its owner map is correct when rootForPlace != null; otherwise read-modify-write
            Map<String,Object> root = rootForPlace;
            if(root == null){
                root = Json.parseObj(new String(Files.readAllBytes(new File(currentScenePath).toPath()), "UTF-8"));
                root.put("entities", entities);
            }
            Files.write(new File(currentScenePath).toPath(), Json.pretty(root).getBytes("UTF-8"));
            System.out.println("edit: " + msg + " -> " + currentScenePath);
            return 0;
        } catch(Exception e){
            System.err.println("edit save failed: " + e);
            return 1;
        }
    }

    /** called once by run() before ops so save() knows the target */
    static void setScenePath(String p){ currentScenePath = p; }
}
