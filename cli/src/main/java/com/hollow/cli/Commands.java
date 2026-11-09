package com.hollow.cli;

import com.hollow.assets.*;
import com.hollow.core.FileSystem;
import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.NativeFS;

import java.io.File;
import java.util.*;

/** All hollow CLI commands. */
public final class Commands {
    private Commands() {}

    // ================= new =================
    static void newProject(String name){
        if(name == null || name.isBlank()) throw new Main.CliError("project name required");
        File dir = new File("projects/" + name);
        if(dir.exists()) throw new Main.CliError("project exists: " + dir);
        for(String d : List.of("scenes","raw","raw/sprites","assets","scripts","ai","ui","strings","saves"))
            new File(dir, d).mkdirs();
        Map<String,Object> pj = linked(
            "title", name,
            "appId", "com.hollow.game." + name.toLowerCase().replaceAll("[^a-z0-9]",""),
            "version", "1.0.0", "versionCode", 1,
            "entryScene", "scenes/main.scene.json",
            "orientation", "sensorLandscape",
            "quality", "HIGH");
        write(dir, "project.json", Json.pretty(pj));
        write(dir, "input.json", Json.pretty(linked(
            "keymap", linked("w","move_forward","s","move_back","a","move_left","d","move_right",
                             "space","jump","e","interact","f","flashlight","shift","run","mouse_left","attack"))));
        write(dir, "strings/en.json", Json.pretty(linked("hello","Hello","press_e","Press E to interact")));
        write(dir, "strings/fa.json", Json.pretty(linked("hello","سلام","press_e","برای تعامل E را بزن")));
        // starter scene
        Map<String,Object> scene = starterScene(name);
        write(dir, "scenes/main.scene.json", Json.pretty(scene));
        // generate starter meshes
        try {
            NativeFS fs = new NativeFS(dir.getPath());
            HvmGen.plane(20, 8).save("assets/models/floor.hvm", fs);
            HvmGen.box(1, 1, 1).save("assets/models/cube.hvm", fs);
        } catch(Exception e){ throw new Main.CliError("mesh gen failed: " + e.getMessage()); }
        System.out.println("created project: " + dir.getPath());
        System.out.println("next: hollow validate -p " + name);
    }

    private static Map<String,Object> starterScene(String name){
        List<Object> entities = new ArrayList<>();
        entities.add(linked(
            "name","MainCamera","tag","camera",
            "position", List.of(0.0, 2.0, 6.0),
            "components", List.of(linked("type","Camera","fov",60.0,"active",true))));
        entities.add(linked(
            "name","Sun","tag","light",
            "position", List.of(0.0, 10.0, 0.0),
            "components", List.of(linked("type","Light","kind","directional","intensity",1.2,
                "color", List.of(1.0,0.95,0.85), "castShadow", true))));
        entities.add(linked(
            "name","Floor",
            "position", List.of(0.0, 0.0, 0.0),
            "components", List.of(linked("type","Mesh","path","assets/models/floor.hvm","castShadow",true))));
        return linked(
            "name", name,
            "environment", linked(
                "sky", linked("topColor", List.of(0.05,0.08,0.15), "horizonColor", List.of(0.2,0.25,0.35)),
                "ambient", List.of(0.12,0.13,0.18),
                "fog", linked("color", List.of(0.05,0.06,0.1), "density", 0.02)),
            "entities", entities);
    }

    // ================= gen =================
    static void gen(String[] args){
        String kind = Main.arg(args, "-k", "kind", 1).toLowerCase();
        String out = Main.opt(args, "-o", "assets/models/" + kind + ".hvm");
        float a = Float.parseFloat(Main.opt(args, "-a", "1"));
        float b = Float.parseFloat(Main.opt(args, "-b", "1"));
        int seg = Integer.parseInt(Main.opt(args, "-seg", "24"));
        MeshData m = switch(kind){
            case "box" -> HvmGen.box(a, Float.parseFloat(Main.opt(args, "-h", String.valueOf(a))), b);
            case "plane" -> HvmGen.plane(a, seg);
            case "sphere" -> HvmGen.sphere(a, seg/2, seg);
            case "capsule" -> HvmGen.capsule(a, Float.parseFloat(Main.opt(args, "-h", String.valueOf(2*a))), seg);
            case "cylinder" -> HvmGen.cylinder(a, Float.parseFloat(Main.opt(args, "-h", String.valueOf(2*a))), seg);
            default -> throw new Main.CliError("unknown primitive: " + kind + " (box|plane|sphere|capsule|cylinder)");
        };
        try {
            NativeFS fs = new NativeFS(".");
            m.save(out, fs);
        } catch(Exception e){ throw new Main.CliError("save failed: " + e.getMessage()); }
        System.out.printf("wrote %s  (%d verts, %d tris)%n", out, m.vertexCount(), m.triangleCount());
    }

    // ================= import =================
    static void doImport(String project){
        NativeFS fs = projectFS(project);
        DesktopImageDecoder.install();
        try {
            List<String> raw = fs.walk("raw");
            if(raw.isEmpty()){ System.out.println("no raw assets"); return; }
            List<Map<String,Object>> manifestItems = new ArrayList<>();
            int converted = 0, copied = 0, skipped = 0;
            for(String src : raw){
                String dst = src.replaceFirst("^raw/", "assets/");
                String lower = src.toLowerCase();
                if(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")){
                    dst = dst.replaceAll("\\.(png|jpg|jpeg)$", ".hvt");
                    ImageData img = ImageDecoder.get().decodeFile(src, fs);
                    long srcSize = fs.read(src).length;
                    if(fs.exists(dst) && sameSize(fs, src, srcSize)){ skipped++; }
                    else { img.save(dst, fs); converted++; }
                    manifestItems.add(linked("src",src,"dst",dst,"type","texture","w",img.width,"h",img.height));
                } else if(lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".hvm") || lower.endsWith(".hvt")
                       || lower.endsWith(".gltf") || lower.endsWith(".glb")){
                    if(!fs.exists(dst)){
                        fs.write(dst, fs.read(src));
                        copied++;
                    } else skipped++;
                    manifestItems.add(linked("src",src,"dst",dst,"type",type(lower)));
                } else {
                    Log.w("import", "skipped unknown asset type: " + src);
                }
            }
            Map<String,Object> manifest = linked("project", project, "imported", manifestItems);
            fs.write("manifest.json", Json.pretty(manifest).getBytes("UTF-8"));
            System.out.printf("import complete: %d converted, %d copied, %d skipped%n", converted, copied, skipped);
        } catch(Exception e){ throw new Main.CliError("import failed: " + e.getMessage()); }
    }
    private static String type(String lower){
        if(lower.endsWith(".wav")) return "audio";
        if(lower.endsWith(".ogg")) return "audio";
        if(lower.endsWith(".gltf")||lower.endsWith(".glb")) return "model";
        return "binary";
    }
    private static boolean sameSize(NativeFS fs, String src, long size){
        try { return fs.read(src).length == size; } catch(Exception e){ return false; }
    }

    // ================= atlas =================
    static void atlas(String project){
        NativeFS fs = projectFS(project);
        DesktopImageDecoder.install();
        try {
            List<String> pngs = new ArrayList<>();
            if(fs.isDirectory("raw/sprites")) pngs.addAll(fs.walk("raw/sprites"));
            if(pngs.isEmpty()){ System.out.println("no sprites in raw/sprites/"); return; }
            List<AtlasPacker.Item> items = new ArrayList<>();
            for(String p : pngs){
                if(!(p.toLowerCase().endsWith(".png"))) continue;
                ImageData img = ImageDecoder.get().decodeFile(p, fs);
                String name = p.substring(p.lastIndexOf('/')+1).replaceAll("\\.[^.]+$", "");
                items.add(new AtlasPacker.Item(name, img));
            }
            AtlasPacker.Result r = AtlasPacker.pack(items, 4096);
            if(r.canvas == null) throw new Main.CliError("atlas packing failed");
            r.canvas.save("assets/atlas/main.hvt", fs);
            Map<String,Object> atlasJson = new LinkedHashMap<>();
            atlasJson.put("texture", "assets/atlas/main.hvt");
            atlasJson.put("width", r.width);
            atlasJson.put("height", r.height);
            Map<String,Object> sprites = new LinkedHashMap<>();
            for(AtlasPacker.Item it : r.placed){
                sprites.put(it.name, List.of(it.x, it.y, it.image.width, it.image.height));
            }
            atlasJson.put("sprites", sprites);
            atlasJson.put("dropped", r.dropped);
            fs.write("assets/atlas/main.atlas", Json.pretty(atlasJson).getBytes("UTF-8"));
            System.out.printf("atlas: %d sprites -> %dx%d (%d dropped)%n", r.placed.size(), r.width, r.height, r.dropped);
        } catch(Exception e){ throw new Main.CliError("atlas failed: " + e.getMessage()); }
    }

    // ================= validate =================
    static void validate(String project){
        NativeFS fs = projectFS(project);
        DesktopImageDecoder.install();
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        try {
            // 1. JSON files parse
            for(String p : fs.walk(".")){
                if(!p.endsWith(".json") || p.startsWith("saves/") || p.equals("manifest.json")) continue;
                try {
                    Object root = Json.parse(fs.readText(p));
                    if(root instanceof Map && ((Map<?,?>)root).isEmpty() && !p.equals("input.json")) {
                        warnings.add("empty JSON object: " + p);
                    }
                }
                catch(Exception e){ errors.add("bad JSON: " + p + " — " + e.getMessage()); }
            }
            // 2. project.json
            if(!fs.exists("project.json")) errors.add("missing project.json");
            // 3. scenes: entity/component/refs
            for(String p : fs.walk("scenes")){
                Map<String,Object> sc = safeJson(fs, p);
                if(sc == null) continue;
                List<Object> ents = Json.arr(sc.get("entities"));
                if(ents == null || ents.isEmpty()) warnings.add("scene has no entities: " + p);
                if(ents != null) for(Object eo : ents){
                    Map<String,Object> en = Json.obj(eo);
                    if(en == null) continue;
                    List<Object> comps = Json.arr(en.get("components"));
                    if(comps == null) continue;
                    for(Object co : comps){
                        Map<String,Object> cm = Json.obj(co);
                        if(cm == null) continue;
                        String type = Json.str(cm.get("type"), "");
                        if(type.isEmpty()) errors.add(p + ": component without type");
                        if(type.equals("Mesh") || type.equals("Animator")){
                            String path = Json.str(cm.get("path"), Json.str(cm.get("model"), ""));
                            if(!path.isEmpty() && !fs.exists(path)) errors.add(p + ": missing asset " + path);
                        }
                    }
                }
            }
            // 4. scripts/ai/ui/strings exist parse (already checked); AI trees root valid
            for(String p : fs.walk("ai")){
                Map<String,Object> bt = safeJson(fs, p);
                if(bt == null) continue;
                String root = Json.str(bt.get("root"), "");
                Map<String,Object> nodes = Json.obj(bt.get("nodes"));
                if(nodes == null || !nodes.containsKey(root)) errors.add(p + ": root node '" + root + "' missing");
            }
            // 5. audio assets referenced by scenes exist
            // 6. entry scene
            Map<String,Object> pj = safeJson(fs, "project.json");
            if(pj != null){
                String entry = Json.str(pj.get("entryScene"), "");
                if(!entry.isEmpty() && !fs.exists(entry)) errors.add("entryScene missing: " + entry);
            }
        } catch(Exception e){ errors.add("walk failed: " + e.getMessage()); }

        for(String w : warnings) System.out.println("WARN  " + w);
        for(String e : errors) System.out.println("ERROR " + e);
        System.out.printf("%nvalidation: %d errors, %d warnings%n", errors.size(), warnings.size());
        if(!errors.isEmpty()) throw new Main.CliError("validation failed");
    }

    // ================= tree =================
    static void tree(String project, String sceneName){
        NativeFS fs = projectFS(project);
        DesktopImageDecoder.install();
        String scenePath = sceneName;
        try {
            if(scenePath == null){
                Map<String,Object> pj = safeJson(fs, "project.json");
                scenePath = pj != null ? Json.str(pj.get("entryScene"), "scenes/main.scene.json") : "scenes/main.scene.json";
            }
            Map<String,Object> sc = safeJson(fs, scenePath);
            if(sc == null) throw new Main.CliError("cannot read scene: " + scenePath);
            System.out.println(scenePath);
            List<Object> ents = Json.arr(sc.get("entities"));
            if(ents == null) return;
            for(Object eo : ents){
                Map<String,Object> en = Json.obj(eo);
                if(en == null) continue;
                StringBuilder sb = new StringBuilder(" ├─ " + Json.str(en.get("name"), "?"));
                String tag = Json.str(en.get("tag"), "");
                if(!tag.isEmpty()) sb.append(" [").append(tag).append("]");
                List<Object> pos = Json.arr(en.get("position"));
                if(pos != null && pos.size() >= 3)
                    sb.append(String.format("  pos=(%.1f, %.1f, %.1f)", Json.num(pos.get(0),0), Json.num(pos.get(1),0), Json.num(pos.get(2),0)));
                System.out.println(sb);
                List<Object> comps = Json.arr(en.get("components"));
                if(comps != null) for(Object co : comps){
                    Map<String,Object> cm = Json.obj(co);
                    if(cm == null) continue;
                    System.out.println(" │    • " + Json.str(cm.get("type"), "?"));
                }
            }
        } catch(Main.CliError e){ throw e; }
        catch(Exception e){ throw new Main.CliError("tree failed: " + e.getMessage()); }
    }

    // ================= run (headless) =================
    static void run(String[] args){
        String project = Main.arg(args, "-p", "project", 1);
        int ticks = Integer.parseInt(Main.opt(args, "--ticks", "300"));
        String assertFile = Main.opt(args, "--assert", null);
        DesktopImageDecoder.install();
        HeadlessApp.run(project, ticks, assertFile);
    }

    // ================= build =================
    static void build(String[] args){
        String mode = Main.arg(args, "-t", "target", 1);
        if(!mode.equals("apk") && !mode.equals("android")) throw new Main.CliError("build target: apk");
        String project = Main.arg(args, "-p", "project", 2);
        buildApk(project);
    }

    static void buildApk(String project){
        NativeFS fs = projectFS(project);
        Map<String,Object> pj = safeJson(fs, "project.json");
        if(pj == null) throw new Main.CliError("project.json missing");
        String appId = Json.str(pj.get("appId"), "com.hollow.game." + project);
        String title = Json.str(pj.get("title"), project);
        String version = Json.str(pj.get("version"), "1.0.0");
        int versionCode = Json.int32(pj.get("versionCode"), 1);
        String orientation = Json.str(pj.get("orientation"), "sensorLandscape");

        // ensure keystore
        File ks = new File("tools/keys/hollow.jks");
        if(!ks.exists()) genKeystore();

        // stage assets
        try {
            File stage = new File("runtime-android/src/main/assets/project");
            if(stage.exists()) deleteR(stage);
            File projDir = new File("projects/" + project);
            copyR(projDir, stage);
            System.out.println("staged project assets");
        } catch(Exception e){ throw new Main.CliError("staging failed: " + e.getMessage()); }

        // gradle build
        String gradlew = System.getenv("HE_GRADLE_BIN") != null ? System.getenv("HE_GRADLE_BIN") : (new File("gradlew").exists() ? "./gradlew" : "gradle");
        String extra = System.getenv().getOrDefault("HE_GRADLE_ARGS", "");
        int rc = runProc(gradlew + " :runtime-android:assembleRelease " +
            "-Phe.appId=" + appId +
            " -Phe.appLabel=" + shellQuote(title) +
            " -Phe.versionName=" + version +
            " -Phe.versionCode=" + versionCode +
            " -Phe.orientation=" + orientation + " " + extra);
        if(rc != 0) throw new Main.CliError("gradle build failed (rc=" + rc + ") — SDK available? set ANDROID_SDK_ROOT");

        // copy apk
        try {
            new File("dist").mkdirs();
            File apk = new File("runtime-android/build/outputs/apk/release/runtime-android-release.apk");
            if(!apk.exists()) throw new Main.CliError("apk not found after build");
            String out = "dist/" + project + "-" + version + ".apk";
            java.nio.file.Files.copy(apk.toPath(), new File(out).toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            byte[] bytes = java.nio.file.Files.readAllBytes(new File(out).toPath());
            String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            System.out.println("APK: " + out + "  (" + bytes.length/1024/1024 + " MB)");
            System.out.println("SHA256: " + sha);
        } catch(Main.CliError e){ throw e; }
        catch(Exception e){ throw new Main.CliError("apk copy failed: " + e.getMessage()); }
    }

    static void genKeystore(){
        try {
            new File("tools/keys").mkdirs();
            String keytool = System.getenv("JAVA_HOME") == null ? "keytool" : System.getenv("JAVA_HOME") + "/bin/keytool";
            int rc = runProc(keytool + " -genkeypair -keystore tools/keys/hollow.jks -alias hollow " +
                "-storepass hollowengine -keypass hollowengine -keyalg RSA -keysize 2048 -validity 10950 " +
                "-dname 'CN=HollowEngine, O=Hollow, C=IR'");
            if(rc != 0) throw new Main.CliError("keytool failed — set JAVA_HOME to a JDK");
            System.out.println("generated release keystore tools/keys/hollow.jks");
        } catch(Main.CliError e){ throw e; }
        catch(Exception e){ throw new Main.CliError("keystore: " + e.getMessage()); }
    }

    // ================= clean =================
    static void clean(String project){
        NativeFS fs = projectFS(project);
        try {
            if(fs.exists("manifest.json")) fs.delete("manifest.json");
            for(String p : fs.walk("assets")) if(p.endsWith(".hvt")) fs.delete(p);
        } catch(Exception ignored){}
        System.out.println("cleaned " + project);
    }

    // ================= helpers =================
    static NativeFS projectFS(String project){
        File dir = new File("projects/" + project);
        if(!dir.isDirectory()) throw new Main.CliError("project not found: projects/" + project);
        return new NativeFS(dir.getPath());
    }
    static Map<String,Object> safeJson(NativeFS fs, String path){
        try { return Json.parseObj(fs.readText(path)); }
        catch(Exception e){ return null; }
    }
    static void write(File dir, String name, String content){
        try {
            File f = new File(dir, name);
            f.getParentFile().mkdirs();
            java.nio.file.Files.writeString(f.toPath(), content);
        } catch(Exception e){ throw new RuntimeException(e); }
    }
    static Map<String,Object> linked(Object... kv){
        Map<String,Object> m = new LinkedHashMap<>();
        for(int i=0;i<kv.length;i+=2) m.put((String)kv[i], kv[i+1]);
        return m;
    }
    static void deleteR(File f){
        if(f.isDirectory()) for(File c : f.listFiles()) deleteR(c);
        f.delete();
    }
    static void copyR(File src, File dst) throws Exception {
        if(src.isDirectory()){
            dst.mkdirs();
            for(File c : src.listFiles()) copyR(c, new File(dst, c.getName()));
        } else {
            java.nio.file.Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static String shellQuote(String s){ return "'" + s.replace("'","'\\''") + "'"; }
    static int runProc(String cmd){
        try {
            Process p = new ProcessBuilder("/bin/sh", "-c", cmd).inheritIO().start();
            return p.waitFor();
        } catch(Exception e){ throw new RuntimeException(e); }
    }
}
