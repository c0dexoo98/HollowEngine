package com.hollow.cli;

import com.hollow.assets.*;
import com.hollow.core.FileSystem;
import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.NativeFS;
import com.hollow.game.EngineBootstrap;
import com.hollow.game.EngineServices;

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
                if(p.endsWith(".eqs.json")) continue;      // v2.0: EQS files are not behavior trees
                Map<String,Object> bt = safeJson(fs, p);
                if(bt == null) continue;
                String root = Json.str(bt.get("root"), "");
                Map<String,Object> nodes = Json.obj(bt.get("nodes"));
                if(nodes == null || !nodes.containsKey(root)) errors.add(p + ": root node '" + root + "' missing");
            }
            // v2.0: EQS queries lint
            for(String p : fs.walk("ai")){
                if(!p.endsWith(".eqs.json")) continue;
                Map<String,Object> q = safeJson(fs, p);
                if(q == null) continue;
                Map<String,Object> gen = Json.obj(q.get("generator"));
                if(gen.isEmpty()) warnings.add(p + ": no generator (defaults used)");
                String gtype = Json.str(gen.get("type"), "circle");
                if(!List.of("circle","ring","donut").contains(gtype))
                    errors.add(p + ": unknown generator type " + gtype);
                int tests = 0;
                for(Object o : Json.arr(q.get("tests"))){
                    if(!(o instanceof Map)) continue;
                    tests++;
                    String kind = Json.str(((Map<?,?>)o).get("kind"), "");
                    if(!List.of("pathfindable","unblocked","dist_to_target","visible_from_target",
                                "toward_target","away_from_target","random").contains(kind))
                        errors.add(p + ": unknown test kind '" + kind + "'");
                }
                if(tests == 0) warnings.add(p + ": no tests (every candidate passes)");
            }
            // v2.0: blend spaces lint
            for(String p : fs.walk("anim")){
                if(!p.endsWith(".json")) continue;
                Map<String,Object> bs = safeJson(fs, p);
                if(bs == null || !bs.containsKey("samples")) continue;
                List<Object> samples = Json.arr(bs.get("samples"));
                if(samples.isEmpty()) errors.add(p + ": blendspace with no samples");
                for(Object o : samples){
                    if(!(o instanceof Map)) continue;
                    String clip = Json.str(((Map<?,?>)o).get("clip"), "");
                    if(clip.isEmpty()) errors.add(p + ": sample without clip name");
                }
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
        String scene = Main.opt(args, "--scene", null);      // v2.0: override entry scene
        DesktopImageDecoder.install();
        HeadlessApp.run(project, ticks, assertFile, scene);
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

    // ================= v2.0: profile =================
    static void profile(String[] args){
        String project = Main.arg(args, "-p", "project", 1);
        int ticks = Integer.parseInt(Main.opt(args, "--ticks", "600"));
        String csv = Main.opt(args, "--csv", null);
        NativeFS fs = projectFS(project);
        DesktopImageDecoder.install();
        EngineServices svc = EngineBootstrap.boot(fs, ".", null);
        svc.config.headless = true;
        svc.time.headless = true;
        com.hollow.core.Profiler.setEnabled(true);
        ProfileApp app = new ProfileApp();
        app.boot(svc);
        long t0 = System.currentTimeMillis();
        for(int i = 0; i < ticks && app.isRunning(); i++) app.frame();
        long ms = System.currentTimeMillis() - t0;
        System.out.printf("profiled %d ticks in %d ms (%.1f ticks/s, %.3f ms/tick)%n",
            app.ticksRun, ms, app.ticksRun * 1000.0 / Math.max(1, ms), (double)ms / Math.max(1, app.ticksRun));
        for(String line : com.hollow.core.Profiler.summaryLines()) System.out.println(line);
        if(csv != null){
            if(com.hollow.core.Profiler.dumpCsv(fs, csv)) System.out.println("csv written: projects/" + project + "/" + csv);
        }
        com.hollow.core.Profiler.setEnabled(false);
    }
    static final class ProfileApp extends com.hollow.game.GameApp {
        int ticksRun = 0;
        @Override public void onStart(){ }
        @Override public void onFixedUpdate(float dt){ ticksRun++; }
    }

    // ================= v2.0: bench =================
    static void bench(String[] args){
        String project = Main.arg(args, "-p", "project", 1);
        int bodies = Integer.parseInt(Main.opt(args, "--bodies", "200"));
        int ticks = Integer.parseInt(Main.opt(args, "--ticks", "600"));
        NativeFS fs = projectFS(project);
        DesktopImageDecoder.install();
        EngineServices svc = EngineBootstrap.boot(fs, ".", null);
        svc.config.headless = true;
        svc.time.headless = true;
        BenchApp app = new BenchApp();
        app.spawnBodies = bodies;
        app.boot(svc);
        long t0 = System.currentTimeMillis();
        for(int i = 0; i < ticks && app.isRunning(); i++) app.frame();
        long ms = System.currentTimeMillis() - t0;
        System.out.printf("bench: %d dynamic bodies, %d ticks in %d ms (%.1f ticks/s)%n",
            app.spawned, app.ticksRun, ms, app.ticksRun * 1000.0 / Math.max(1, ms));
        System.out.println("awake bodies at end: " + svc.physics.awakeCount() + "/" + svc.physics.bodyCount());
    }
    static final class BenchApp extends com.hollow.game.GameApp {
        int ticksRun = 0;
        int spawnBodies, spawned;
        boolean didSpawn = false;
        @Override public void onStart(){ }
        @Override public void onFixedUpdate(float dt){
            ticksRun++;
            if(!didSpawn && world != null){
                didSpawn = true;
                java.util.Random r = new java.util.Random(42);
                for(int i = 0; i < spawnBodies; i++){
                    com.hollow.scene.Entity e = world.spawn("body" + i);
                    e.position.set(r.nextFloat() * 4 - 2, 3f + r.nextFloat() * 6f, r.nextFloat() * 4 - 2);
                    com.hollow.physics.RigidBody rb = new com.hollow.physics.RigidBody();
                    rb.radius = 0.15f + r.nextFloat() * 0.2f;
                    rb.bounciness = 0.3f;
                    e.add(rb);
                    spawned++;
                }
            }
        }
    }

    // ================= v2.0: net test (loopback) =================
    static void netTest(String[] args){
        int port = Integer.parseInt(Main.opt(args, "--port", "27015"));
        System.out.println("[net] starting server on :" + port);
        com.hollow.net.Session.Server server = new com.hollow.net.Session.Server();
        if(!server.start(port)){ throw new Main.CliError("server failed to start"); }
        // spin the server once so the listener binds + accept thread starts
        server.update(1f / 60f);
        com.hollow.net.Session.Client client = new com.hollow.net.Session.Client();
        System.out.println("[net] connecting client...");
        for(int i = 0; i < 50 && !client.connect("127.0.0.1", port, "tester"); i++){
            server.update(1f / 60f);
            try { Thread.sleep(20); } catch(InterruptedException ignored){}
        }
        if(!client.connected) throw new Main.CliError("client could not connect");
        // simulate: server replicates an entity, client sends input, snapshots flow back
        server.replicate("Player", new com.hollow.core.Vec3(0, 0, 0), 0f);
        long snapBefore = 0;
        int frames = 0;
        for(int i = 0; i < 240; i++){
            float dt = 1f / 60f;
            // move the replicated entity deterministically
            server.replicate("Player", new com.hollow.core.Vec3(i * 0.05f, 0, 0), i * 2f);
            server.update(dt);
            client.update(dt);
            client.sendInput(i % 20 < 10 ? 1f : 0f, 0f);
            frames++;
            if(client.myId != 0 && snapBefore == 0){ snapBefore = 1; System.out.println("[net] welcome received: myId=" + client.myId); }
        }
        float[] sampled = client.sampleEntity("Player");
        if(client.myId == 0) throw new Main.CliError("no WELCOME received");
        if(client.snapshotsReceived == 0) throw new Main.CliError("no snapshots received");
        if(sampled == null) throw new Main.CliError("entity not present in snapshots");
        System.out.println("[net] snapshots=" + client.snapshotsReceived + " (expected >= " + (frames / 4) + ")");
        System.out.println("[net] sampled Player @ t-100ms: x=" + String.format("%.2f", sampled[0]) + " ry=" + String.format("%.2f", sampled[3]));
        System.out.println("[net] server sent=" + server.snapshotsSent + " clients=" + server.clients.size());
        client.disconnect();
        System.out.println("RESULT: net test OK");
    }

    // ================= v2.0: audio-render =================
    static void audioRender(String[] args){
        String project = Main.arg(args, "-p", "project", 1);
        String out = Main.opt(args, "--out", "assets/audio/mix.wav");
        double seconds = Double.parseDouble(Main.opt(args, "--seconds", "5"));
        int sampleRate = Integer.parseInt(Main.opt(args, "--rate", "44100"));
        NativeFS fs = projectFS(project);
        DesktopImageDecoder.install();
        EngineServices svc = EngineBootstrap.boot(fs, ".", null);
        // schedule every audio file in assets/audio/ sequentially (0.5s apart)
        java.util.List<com.hollow.audio.AudioEngine.Cue> cues = new java.util.ArrayList<>();
        try {
            double t = 0;
            for(String p : fs.walk("assets/audio")){
                String lower = p.toLowerCase();
                if(!(lower.endsWith(".wav") || lower.endsWith(".ogg"))) continue;
                com.hollow.audio.AudioEngine.PlayParams pp = new com.hollow.audio.AudioEngine.PlayParams()
                        .bus(com.hollow.audio.AudioEngine.BUS_AMBIENT).volume(0.9f);
                cues.add(new com.hollow.audio.AudioEngine.Cue(p, t, pp));
                t += 0.5;
                if(t >= seconds) break;
            }
        } catch(Exception e){ /* no audio dir */ }
        if(cues.isEmpty()){
            System.out.println("no audio assets under assets/audio/ — nothing to render");
            return;
        }
        short[] pcm = com.hollow.audio.AudioEngine.renderOffline(svc.assets, sampleRate, cues, seconds);
        // write WAV (16-bit PCM stereo)
        byte[] wav = wavBytes(pcm, sampleRate);
        try {
            fs.write(out, wav);
            System.out.printf("rendered %.1fs stereo mix (%d cues) -> projects/%s/%s (%d KB)%n",
                seconds, cues.size(), project, out, wav.length / 1024);
        } catch(Exception e){ throw new Main.CliError("wav write failed: " + e.getMessage()); }
    }

    static byte[] wavBytes(short[] interleaved, int sampleRate){
        int dataLen = interleaved.length * 2;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()); bb.putInt(36 + dataLen); bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes()); bb.putInt(16); bb.putShort((short)1); bb.putShort((short)2);
        bb.putInt(sampleRate); bb.putInt(sampleRate * 4); bb.putShort((short)4); bb.putShort((short)16);
        bb.put("data".getBytes()); bb.putInt(dataLen);
        for(short s : interleaved) bb.putShort(s);
        return bb.array();
    }

    // ================= v2.0: eqs =================
    static void eqsTest(String[] args){
        String project = Main.arg(args, "-p", "project", 1);
        String queryName = Main.opt(args, "--query", null);
        if(queryName == null) throw new Main.CliError("--query <name> required (file ai/<name>.eqs.json)");
        NativeFS fs = projectFS(project);
        DesktopImageDecoder.install();
        EngineServices svc = EngineBootstrap.boot(fs, ".", null);
        svc.config.headless = true;
        svc.time.headless = true;
        EqsApp app = new EqsApp();
        app.queryName = queryName;
        app.boot(svc);
        for(int i = 0; i < 30 && app.isRunning(); i++) app.frame();
        if(!app.found){
            String stats = app.result != null
                ? (" candidates=" + app.result.candidates + " rejected=" + app.result.rejected)
                : " (query missing)";
            throw new Main.CliError("EQS query '" + queryName + "' found no valid point." + stats);
        }
        System.out.printf("EQS '%s': best point (%.2f, %.2f, %.2f) score=%.2f candidates=%d rejected=%d%n",
            queryName, app.result.point.x, app.result.point.y, app.result.point.z,
            app.result.score, app.result.candidates, app.result.rejected);
        System.out.println("RESULT: OK");
    }
    static final class EqsApp extends com.hollow.game.GameApp {
        int ticksRun = 0;
        String queryName;
        com.hollow.ai.EQS.Result result;
        boolean found = false;
        @Override public void onStart(){ }
        @Override public void onFixedUpdate(float dt){
            ticksRun++;
            if(ticksRun == 10 && world != null){
                // run the query from the first agent found (or spawn a probe)
                com.hollow.ai.Agent agent = null;
                for(com.hollow.scene.Entity e : world.entities){
                    com.hollow.ai.Agent a = e.get(com.hollow.ai.Agent.class);
                    if(a != null){ agent = a; break; }
                }
                if(agent == null){
                    com.hollow.scene.Entity probe = world.spawn("eqs_probe");
                    probe.position.set(0, 0, 0);
                    probe.add(new com.hollow.ai.Agent());
                    agent = probe.get(com.hollow.ai.Agent.class);
                }
                com.hollow.ai.EQS.Query q = agent.query(queryName);
                if(q == null) return;
                result = com.hollow.ai.EQS.run(q, agent);
                found = result.found;
            }
        }
    }

    // ================= v2.0: selftest (engine unit checks) =================
    static void selfTest(){
        int failures = 0;
        System.out.println(com.hollow.core.EngineVersion.FULL + " self test");
        // --- math: quat invert/slerp roundtrip ---
        try {
            com.hollow.core.Quat q = com.hollow.core.Quat.fromEuler(20, 35, -10).norm();
            com.hollow.core.Quat qi = q.cpy().invert();
            com.hollow.core.Quat id = qi.cpy().mul(q).norm();   // fresh copy: mul mutates
            if(Math.abs(id.w) < 0.999f) throw new AssertionError("quat invert roundtrip w=" + id.w);
            com.hollow.core.Vec3 v = new com.hollow.core.Vec3(1, 2, 3);
            com.hollow.core.Vec3 vr = q.cpy().rotate(qi.cpy().rotate(v));
            if(Math.abs(vr.x - v.x) > 1e-3f || Math.abs(vr.z - v.z) > 1e-3f) throw new AssertionError("rotate roundtrip " + vr);
            pass("quat invert/rotate roundtrip");
        } catch(Throwable t){ fail("quat", t); failures++; }
        // --- mat4 trs/invert roundtrip ---
        try {
            com.hollow.core.Mat4 m = com.hollow.core.Mat4.trs(
                new com.hollow.core.Vec3(1, 2, 3),
                com.hollow.core.Quat.fromEuler(10, 20, 30),
                new com.hollow.core.Vec3(1, 1, 1));
            com.hollow.core.Mat4 mi = m.cpy().invert();
            com.hollow.core.Vec3 p = m.transformPoint(new com.hollow.core.Vec3(0.5f, -0.5f, 2f));
            com.hollow.core.Vec3 pBack = mi.transformPoint(p);
            if(Math.abs(pBack.x - 0.5f) > 1e-3f || Math.abs(pBack.y + 0.5f) > 1e-3f)
                throw new AssertionError("trs roundtrip " + pBack);
            pass("mat4 trs/invert roundtrip");
        } catch(Throwable t){ fail("mat4", t); failures++; }
        // --- blend space sampling ---
        try {
            com.hollow.anim.BlendSpace bs = new com.hollow.anim.BlendSpace();
            bs.axis1Min = 0; bs.axis1Max = 6;
            com.hollow.anim.BlendSpace.Sample s1 = new com.hollow.anim.BlendSpace.Sample();
            s1.x = 0; s1.clip = "idle";
            com.hollow.anim.BlendSpace.Sample s2 = new com.hollow.anim.BlendSpace.Sample();
            s2.x = 3; s2.clip = "walk";
            com.hollow.anim.BlendSpace.Sample s3 = new com.hollow.anim.BlendSpace.Sample();
            s3.x = 6; s3.clip = "run";
            bs.samples.add(s1); bs.samples.add(s2); bs.samples.add(s3);
            com.hollow.anim.BlendSpace.Weighted w0 = bs.sample(0, 0);
            if(w0.count != 1 || !"idle".equals(w0.clips[0]) || w0.weights[0] < 0.99f)
                throw new AssertionError("sample@0 should be pure idle: count=" + w0.count + " clip=" + w0.clips[0]);
            com.hollow.anim.BlendSpace.Weighted wm = bs.sample(2.5f, 0);
            if(wm.count < 2) throw new AssertionError("sample@2.5 should blend >= 2 clips");
            float sum = 0;
            for(int i = 0; i < wm.count; i++) sum += wm.weights[i];
            if(Math.abs(sum - 1f) > 0.01f) throw new AssertionError("weights sum " + sum);
            com.hollow.anim.BlendSpace.Weighted w6 = bs.sample(9, 0);
            if(!"run".equals(w6.clips[0])) throw new AssertionError("sample@9 clamps to run, got " + w6.clips[0]);
            pass("blendspace 1D sampling + weight normalization");
        } catch(Throwable t){ fail("blendspace", t); failures++; }
        // --- IK two-bone solver ---
        try {
            com.hollow.anim.IK.TwoBoneResult r = com.hollow.anim.IK.solveTwoBone(
                1f, 1f,
                new com.hollow.core.Vec3(0, 0, 0),
                new com.hollow.core.Vec3(0.3f, 1.4f, 0.2f),
                new com.hollow.core.Vec3(1, 0, 0),
                new com.hollow.core.Quat(), new com.hollow.core.Quat(),
                new com.hollow.core.Quat());
            if(!r.valid) throw new AssertionError("solver invalid");
            if(Float.isNaN(r.rotA.w) || Float.isNaN(r.rotB.w)) throw new AssertionError("NaN rotations");
            pass("two-bone IK analytic solve");
        } catch(Throwable t){ fail("ik", t); failures++; }
        // --- reverb: impulse has audible tail, no NaN ---
        try {
            com.hollow.audio.AudioFX.Reverb rev = new com.hollow.audio.AudioFX.Reverb();
            rev.wet = 0.8f; rev.update();
            int sr = 22050;
            float[] block = new float[sr * 2];
            block[0] = 0.9f; block[1] = 0.9f;         // stereo impulse
            rev.process(block, sr);
            boolean nan = false;
            for(float f : block) if(Float.isNaN(f)) { nan = true; break; }
            if(nan) throw new AssertionError("NaN in reverb output");
            float late = 0f;
            for(int i = sr / 2; i < sr; i++) late += Math.abs(block[i * 2]);
            if(late <= 0f) throw new AssertionError("no reverb tail after 250ms");
            pass("freeverb impulse tail (" + String.format("%.3f", late) + " late energy)");
        } catch(Throwable t){ fail("reverb", t); failures++; }
        // --- EQ3: -12dB low shelf cuts 100Hz ---
        try {
            com.hollow.audio.AudioFX.Eq3 eq = new com.hollow.audio.AudioFX.Eq3();
            int sr = 22050;
            float[] flat = new float[sr * 2];
            for(int i = 0; i < sr; i++){
                float s = (float)Math.sin(2 * Math.PI * 100.0 * i / sr) * 0.5f;
                flat[i * 2] = s; flat[i * 2 + 1] = s;
            }
            eq.set(sr, -12f, 0f, 0f);
            eq.process(flat, sr);
            double rms = 0;
            for(int i = sr / 2; i < sr; i++) rms += flat[i * 2] * flat[i * 2];
            rms = Math.sqrt(rms / (sr / 2));
            if(rms > 0.45f) throw new AssertionError("low shelf did not cut (rms=" + rms + ")");
            if(Float.isNaN((float)rms)) throw new AssertionError("NaN from EQ");
            pass("3-band EQ low cut (rms " + String.format("%.3f", rms) + " < 0.45)");
        } catch(Throwable t){ fail("eq3", t); failures++; }
        // --- compressor limits peaks ---
        try {
            com.hollow.audio.AudioFX.Compressor comp = new com.hollow.audio.AudioFX.Compressor();
            comp.threshold = 0.5f; comp.ratio = 8f;
            int sr = 22050;
            float[] block = new float[sr * 2];
            for(int i = 0; i < sr; i++){
                float s = (float)Math.sin(2 * Math.PI * 220.0 * i / sr) * 0.95f;
                block[i * 2] = s; block[i * 2 + 1] = s;
            }
            comp.process(block, sr, sr);
            double peak = 0;
            for(int i = sr / 2; i < sr; i++) peak = Math.max(peak, Math.abs(block[i * 2]));
            if(peak > 0.85f) throw new AssertionError("peak not limited (" + peak + ")");
            pass("compressor limiting (peak " + String.format("%.3f", peak) + " <= 0.85)");
        } catch(Throwable t){ fail("compressor", t); failures++; }
        // --- profiler stats ---
        try {
            com.hollow.core.Profiler.setEnabled(true);
            for(int i = 0; i < 5; i++){
                int z = com.hollow.core.Profiler.begin("zoneA");
                Thread.yield();
                com.hollow.core.Profiler.end(z);
                com.hollow.core.Profiler.frame();
            }
            if(com.hollow.core.Profiler.zoneCount() == 0) throw new AssertionError("no zones recorded");
            List<String> lines = com.hollow.core.Profiler.summaryLines();
            if(lines.size() < 2) throw new AssertionError("summary empty");
            com.hollow.core.Profiler.setEnabled(false);
            pass("profiler zones + summary");
        } catch(Throwable t){ fail("profiler", t); failures++; }
        // --- WAV writer header ---
        try {
            byte[] wav = wavBytes(new short[]{0, 100, -100, 300}, 22050);
            String riff = new String(wav, 0, 4);
            if(!"RIFF".equals(riff)) throw new AssertionError("bad riff magic");
            if(wav.length != 44 + 8) throw new AssertionError("bad wav size " + wav.length);
            pass("WAV writer header");
        } catch(Throwable t){ fail("wav", t); failures++; }
        // ================= v2.1 Batch-1 additions =================
        // --- CVar: register/set/listener/dirty/persistence ---
        try {
            com.hollow.core.CVar v = com.hollow.core.CVar.registerFloat("test.gravity", 9.8f, "test var");
            final float[] seen = { 0 };
            v.onChange(c -> seen[0] = c.getFloat());
            v.set("3.5");
            if(Math.abs(v.getFloat() - 3.5f) > 1e-6f) throw new AssertionError("set/get");
            if(Math.abs(seen[0] - 3.5f) > 1e-6f) throw new AssertionError("listener not fired");
            if(!v.isDirty()) throw new AssertionError("dirty flag");
            com.hollow.core.CVar.save("build/test-cvars.cfg", true);
            v.set("1.0");
            com.hollow.core.CVar.load("build/test-cvars.cfg");
            if(Math.abs(v.getFloat() - 3.5f) > 1e-6f) throw new AssertionError("persist round-trip " + v.getFloat());
            pass("CVar set/listener/persist");
        } catch(Throwable t){ fail("cvar", t); failures++; }
        // --- Console: parse/alias/history/tokenize ---
        try {
            com.hollow.core.Console c = new com.hollow.core.Console(50);
            c.register("add", "a+b", args -> String.valueOf(Integer.parseInt(args[0]) + Integer.parseInt(args[1])));
            c.alias("+", "add");
            if(!"7".equals(c.execute("add 3 4"))) throw new AssertionError("add");
            if(!"10".equals(c.execute("+ 6 4"))) throw new AssertionError("alias dispatch");
            if(!"15".equals(c.execute("+ \"6\" '9'"))) throw new AssertionError("quoted tokens");
            if(c.execute("nosuchcmd") == null || c.lastError == null) throw new AssertionError("unknown cmd error");
            if(c.history().size() != 4) throw new AssertionError("history");
            String[] toks = com.hollow.core.Console.tokenize("spawn \"big lamp\" x=1");
            if(toks.length != 3 || !toks[1].equals("big lamp")) throw new AssertionError("tokenize " + java.util.Arrays.toString(toks));
            pass("console execute/alias/tokenize/history");
        } catch(Throwable t){ fail("console", t); failures++; }
        // --- Pool / RingBuffer / Arena / ReleaseQueue ---
        try {
            com.hollow.core.Pool<StringBuilder> p = new com.hollow.core.Pool<>(StringBuilder::new, sb -> sb.setLength(0), 2, 8);
            StringBuilder a = p.obtain(), b2 = p.obtain();   // idle 2 -> 0, inUse 2
            a.append("x"); p.free(a);                        // idle 1, inUse 1
            if(p.idle() != 1 || p.inUse() != 1) throw new AssertionError("pool counts idle=" + p.idle() + " inUse=" + p.inUse());
            StringBuilder again = p.obtain();                // recycled 'a'
            if(again.length() != 0) throw new AssertionError("reset not applied");
            if(p.idle() != 0 || p.inUse() != 2) throw new AssertionError("pool recycle counts");
            p.free(b2); p.free(again);

            com.hollow.core.RingBuffer<Integer> rb = new com.hollow.core.RingBuffer<>(3, true);
            for(int i=0;i<5;i++) rb.offer(i);           // overwrites oldest
            if(rb.size() != 3 || rb.peek() != 2 || rb.peekLast() != 4) throw new AssertionError("ring " + rb.stats());
            if(rb.poll() != 2 || rb.poll() != 3 || rb.poll() != 4) throw new AssertionError("ring fifo");

            com.hollow.core.ArenaAllocator arena = new com.hollow.core.ArenaAllocator(256);
            int m = arena.mark();
            int addr = arena.alloc(16, 8);
            arena.putInt(addr, 0xCAFEBABE);
            if(arena.getInt(addr) != 0xCAFEBABE) throw new AssertionError("arena int");
            arena.putFloat(addr + 4, 2.5f);
            if(Math.abs(arena.getFloat(addr + 4) - 2.5f) > 1e-6f) throw new AssertionError("arena float");
            arena.rewind(m);
            if(arena.used() != 0) throw new AssertionError("arena rewind");

            final int[] freed = { 0 };
            com.hollow.core.ReleaseQueue.enqueue(() -> freed[0]++);
            com.hollow.core.ReleaseQueue.enqueue(() -> freed[0]++);
            if(com.hollow.core.ReleaseQueue.pending() != 2) throw new AssertionError("release pending");
            com.hollow.core.ReleaseQueue.drain();
            if(freed[0] != 2 || com.hollow.core.ReleaseQueue.pending() != 0) throw new AssertionError("release queue freed=" + freed[0]);
            pass("pool/ring/arena/release-queue");
        } catch(Throwable t){ fail("mem-allocators", t); failures++; }
        // --- MemoryStats + Subsystems + JobQueue + Watchdog + Result ---
        try {
            com.hollow.core.MemoryStats.Snapshot s = com.hollow.core.MemoryStats.snapshot();
            if(s.heapMax <= 0 || s.gcCollections < 0) throw new AssertionError("snapshot");
            if(com.hollow.core.MemoryStats.health().isEmpty()) throw new AssertionError("health");

            com.hollow.core.Subsystems.register(new com.hollow.core.Subsystems.Subsystem(){
                public String name(){ return "test-low"; }
            }, 10);
            com.hollow.core.Subsystems.register(new com.hollow.core.Subsystems.Subsystem(){
                public String name(){ return "test-high"; }
            }, 20);
            com.hollow.core.Subsystems.initAll();
            if(!com.hollow.core.Subsystems.allInitialized()) throw new AssertionError("init all");
            com.hollow.core.Subsystems.shutdownAll();

            com.hollow.core.JobQueue q = new com.hollow.core.JobQueue(4, true); // inline deterministic
            final int[] counter = { 0 };
            q.submit(() -> counter[0]++);
            q.submit(() -> counter[0] += 10);
            if(counter[0] != 11) throw new AssertionError("inline jobs");

            com.hollow.core.Watchdog wd = new com.hollow.core.Watchdog(2f);
            wd.heartbeat(); wd.setEnabled(false); wd.close();

            com.hollow.core.Result<Integer> ok = com.hollow.core.Result.ok(5);
            com.hollow.core.Result<Integer> bad = com.hollow.core.Result.fail("nope");
            if(ok.orElse(0) != 5 || bad.orElse(7) != 7 || bad.isOk()) throw new AssertionError("result");
            pass("memstats/subsystems/jobqueue/watchdog/result");
        } catch(Throwable t){ fail("core-services", t); failures++; }
        // --- Noise / RandomStream determinism ---
        try {
            com.hollow.core.Noise n1 = new com.hollow.core.Noise(7), n2 = new com.hollow.core.Noise(7);
            for(int i=0;i<200;i++){
                float a = n1.perlin2(i * 0.13f, i * 0.07f);
                float b2 = n2.perlin2(i * 0.13f, i * 0.07f);
                if(a != b2) throw new AssertionError("noise not deterministic");
                if(Math.abs(a) > 1.5f) throw new AssertionError("noise out of range " + a);
            }
            com.hollow.core.Noise n3 = new com.hollow.core.Noise(9);
            if(n3.perlin2(1.37f, 2.71f) == n1.perlin2(1.37f, 2.71f)) throw new AssertionError("different seeds equal");
            float fbm = n1.fbm2(0.5f, 0.5f, 4, 2f, 0.5f);
            if(Float.isNaN(fbm)) throw new AssertionError("fbm NaN");

            com.hollow.core.RandomStream r1 = new com.hollow.core.RandomStream(42), r2 = new com.hollow.core.RandomStream(42);
            for(int i=0;i<1000;i++) if(r1.nextInt(1000) != r2.nextInt(1000)) throw new AssertionError("pcg mismatch");
            for(int i=0;i<2000;i++){
                float f = r1.range(5, 9);
                if(f < 5 || f >= 9) throw new AssertionError("range " + f);
            }
            if(r1.weighted(new float[]{0, 1}) != 1) throw new AssertionError("weighted");
            pass("noise/pcg determinism");
        } catch(Throwable t){ fail("noise-rng", t); failures++; }
        // --- Ease / Spline / HalfFloat / Guid / Vec4 / Rect / Plane ---
        try {
            if(com.hollow.core.Ease.byName("cubic_out", 0.5f) <= com.hollow.core.Ease.byName("cubic_out", 0.2f))
                throw new AssertionError("ease monotonic");
            if(Math.abs(com.hollow.core.Ease.bounceOut(1f) - 1f) > 1e-4f) throw new AssertionError("bounce(1)");
            if(Math.abs(com.hollow.core.Ease.damp(0f, 10f, 4f, 0.016f)) <= 0f) throw new AssertionError("damp");
            if(com.hollow.core.Ease.pingPong(1.5f) != 0.5f) throw new AssertionError("pingpong");

            com.hollow.core.Vec3 p0 = new com.hollow.core.Vec3(0,0,0), p1 = new com.hollow.core.Vec3(1,0,0), p2 = new com.hollow.core.Vec3(2,0,0);
            com.hollow.core.Spline sp = com.hollow.core.Spline.catmullRom(p0, p1, p2);
            if(sp.sample(0).dist(p0) > 1e-4f || sp.sample(1).dist(p2) > 1e-4f) throw new AssertionError("spline endpoints");
            if(sp.sample(0.5f).dist(new com.hollow.core.Vec3(1,0,0)) > 0.05f) throw new AssertionError("spline mid " + sp.sample(0.5f));
            com.hollow.core.Spline bz = com.hollow.core.Spline.bezier(p0, new com.hollow.core.Vec3(0,1,0), new com.hollow.core.Vec3(1,1,0), p2);
            if(bz.sample(0).dist(p0) > 1e-4f || bz.sample(1).dist(p2) > 1e-4f) throw new AssertionError("bezier endpoints");
            float len = sp.length();
            if(Math.abs(len - 2f) > 0.2f) throw new AssertionError("spline length " + len);
            if(Math.abs(sp.nearestT(new com.hollow.core.Vec3(1.1f, 0, 0)) - 0.55f) > 0.05f) throw new AssertionError("nearestT");

            for(float f : new float[]{0f, 1f, -1f, 0.25f, 123.5f, -0.0001f, 65504f}){
                float rt = com.hollow.core.HalfFloat.toFloat(com.hollow.core.HalfFloat.toHalf(f));
                float tol = Math.abs(f) > 60000f ? 40f : Math.abs(f) * 0.001f + 1e-6f;
                if(Math.abs(rt - f) > tol) throw new AssertionError("half roundtrip " + f + " -> " + rt);
            }
            if(!com.hollow.core.Guid.isValid(com.hollow.core.Guid.create())) throw new AssertionError("guid format");

            com.hollow.core.Vec4 q4 = new com.hollow.core.Vec4(1,2,3,4).norm();
            if(Math.abs(q4.len() - 1f) > 1e-4f) throw new AssertionError("vec4 norm");

            com.hollow.core.Rect r1 = new com.hollow.core.Rect(0,0,10,10), r2 = new com.hollow.core.Rect(5,5,10,10);
            if(!r1.intersects(r2) || r1.intersection(r2).area() != 25f) throw new AssertionError("rect intersect");
            if(!r1.contains(5,5) || r1.contains(11,5)) throw new AssertionError("rect contains");

            com.hollow.core.Plane pl = com.hollow.core.Plane.fromNormalPoint(new com.hollow.core.Vec3(0,1,0), new com.hollow.core.Vec3(0,2,0));
            if(Math.abs(pl.distance(new com.hollow.core.Vec3(0,5,0)) - 3f) > 1e-4f) throw new AssertionError("plane dist");
            if(pl.boxSide(com.hollow.core.AABB.fromCenterExtents(new com.hollow.core.Vec3(0,4,0), new com.hollow.core.Vec3(1,1,1))) != 1)
                throw new AssertionError("plane boxSide front");
            pass("ease/spline/half/guid/vec4/rect/plane");
        } catch(Throwable t){ fail("math-utils", t); failures++; }
        // --- Prefab: instantiate + de-dup names + round-trip ---
        try {
            com.hollow.scene.World w = com.hollow.scene.World.empty("test");
            com.hollow.scene.Entity src = w.spawn("lamp");
            src.tag = "light";
            src.position.set(1, 2, 3);
            String json = com.hollow.scene.Prefab.toPrefabJson(src);
            com.hollow.scene.Prefab pf = com.hollow.scene.Prefab.fromJson(json);
            com.hollow.scene.Entity e1 = pf.instantiate(w, new com.hollow.core.Vec3(4,4,4));
            com.hollow.scene.Entity e2 = pf.instantiate(w, null);
            if(e1.position.dist(new com.hollow.core.Vec3(4,4,4)) > 1e-4f) throw new AssertionError("prefab pos override");
            if(e1.name.equals(e2.name)) throw new AssertionError("name de-dup " + e1.name + "/" + e2.name);
            if(!e1.tag.equals("light")) throw new AssertionError("prefab tag");
            pass("prefab instantiate/round-trip");
        } catch(Throwable t){ fail("prefab", t); failures++; }
        System.out.println();
        if(failures > 0){
            System.out.println("RESULT: " + failures + " self test(s) FAILED");
            System.exit(1);
        }
        System.out.println("RESULT: all self tests passed");
    }
    private static void pass(String name){ System.out.println("PASS  " + name); }
    private static void fail(String name, Throwable t){
        System.out.println("FAIL  " + name + " -> " + t.getMessage());
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
