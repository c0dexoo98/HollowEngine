package com.hollow.cli;

import com.hollow.editor.EditorModel;
import com.hollow.core.Json;
import com.hollow.core.MathX;
import com.hollow.core.MathX.Mat4;
import com.hollow.core.MathX.Vec3;
import com.hollow.render.Colors;
import com.hollow.render.Mesh;
import com.hollow.render.Rasterizer;
import com.hollow.render.Frame;
import com.hollow.render.Renderer;
import com.hollow.scene.CameraNode;
import com.hollow.scene.LightNode;
import com.hollow.scene.MeshNode;
import com.hollow.scene.Node;
import com.hollow.scene.Scene;
import com.hollow.scene.ScriptHost;
import com.hollow.scene.ScriptNode;
import com.hollow.script.Interp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** HollowEngine 2 — selftest battery: one assertion per Runnable;
 *  a check fails by throwing. Covers every subsystem end to end. */
final class Selftest {

    private Selftest(){ }

    static Runnable[] battery(){
        List<Runnable> b = new ArrayList<>();

        // --- core: json ---
        b.add(() -> {
            String j = "{\"a\": 1, \"b\": [true, \"two\", null], \"c\": {\"d\": 2.5}}";
            var m = Json.parseObj(j);
            if(Json.num(m.get("a"), -1) != 1) throw new AssertionError("json a");
            var arr = Json.arr(m.get("b"));
            if(arr == null || arr.size() != 3 || !Json.bool(arr.get(0), false)) throw new AssertionError("json b");
            var c = Json.obj(m.get("c"));
            if(c == null || Json.num(c.get("d"), 0) != 2.5) throw new AssertionError("json c");
            var w = new Json.JsonW();
            w.obj().kv("x", 1).arr("y").item("z").endArr().endObj();
            if(!w.toString().contains("\"y\": [\"z\"]")) throw new AssertionError("jsonw: " + w);
        });
        b.add(() -> {
            // roundtrip: parse(save(x)) == x
            var w = new Json.JsonW();
            w.obj().kv("name", "voi\"d").arr("list").item(1.5).item(2).endArr().kv("flag", true).kv("none", null).endObj();
            var m = Json.parseObj(w.toString());
            if(!Json.str(m.get("name"), "").equals("voi\"d")) throw new AssertionError("roundtrip str");
            if(Json.arr(m.get("list")).size() != 2 || Json.num(Json.arr(m.get("list")).get(1), 0) != 2) throw new AssertionError("roundtrip list");
            if(!Json.bool(m.get("flag"), false) || m.get("none") != null) throw new AssertionError("roundtrip misc");
        });

        // --- core: math ---
        b.add(() -> {
            Mat4 t = new Mat4().identity().translate(1, 2, 3);
            Vec3 p = t.transformPoint(new Vec3(0, 0, 0));
            if(Math.abs(p.x - 1) > 1e-5 || Math.abs(p.y - 2) > 1e-5 || Math.abs(p.z - 3) > 1e-5)
                throw new AssertionError("translate");
        });
        b.add(() -> {
            // parent(1,0,0) ∘ child(0,2,0) → child world (1,2,0)
            Node parent = new Node("p");
            parent.position.set(1, 0, 0);
            Node child = new Node("c");
            child.position.set(0, 2, 0);
            parent.add(child);
            Vec3 w = child.worldPosition();
            if(Math.abs(w.x - 1) > 1e-5 || Math.abs(w.y - 2) > 1e-5) throw new AssertionError("hierarchy: " + w);
        });
        b.add(() -> {
            Mat4 ry = new Mat4().identity().rotateY(90);
            Vec3 f = ry.transformDir(new Vec3(0, 0, -1));
            if(Math.abs(f.x + 1) > 1e-4 || Math.abs(f.z) > 1e-4) throw new AssertionError("rotateY: " + f);
        });
        b.add(() -> {
            Mat4 proj = new Mat4().identity().perspective(60, 16f / 9f, 0.1f, 100f);
            Vec3 c = proj.transformPoint(new Vec3(0, 0, -5));
            // 5 units in front of the near plane, far=100 → NDC z close to 1, centered
            if(Math.abs(c.x) > 1e-4 || Math.abs(c.y) > 1e-4) throw new AssertionError("perspective xy: " + c);
            if(!(c.z > 0.8 && c.z < 1.0)) throw new AssertionError("perspective z: " + c);
        });
        b.add(() -> {
            MathX.Rng a = new MathX.Rng(42), c = new MathX.Rng(42);
            for(int i = 0; i < 10; i++)
                if(a.nextDouble() != c.nextDouble()) throw new AssertionError("rng deterministic");
            MathX.Rng s = new MathX.Rng(7);
            for(int i = 0; i < 100; i++){
                double v = s.range(-2, 2);
                if(v < -2 || v >= 2) throw new AssertionError("rng range");
            }
        });

        // --- script: lexer/parser/interp ---
        b.add(() -> {
            Interp i = Interp.fromSource("var a = 2\nvar b = a * 3\nprint(a + b)");
            var out = new ArrayList<String>();
            i.setOut(out::add);
            i.runBody();
            if(!out.toString().contains("8")) throw new AssertionError("interp top-level: " + out);
        });
        b.add(() -> {
            Interp i = Interp.fromSource(String.join("\n",
                "func fib(n):",
                "    if n < 2:",
                "        return n",
                "    return fib(n - 1) + fib(n - 2)"));
            if((double)i.callFunc("fib", 10.0) != 55.0) throw new AssertionError("fib(10)");
        });
        b.add(() -> {
            Interp i = Interp.fromSource(String.join("\n",
                "var total = 0",
                "for x in range(1, 11):",
                "    total = total + x",
                "if total == 55:",
                "    total = total * 2",
                "elif total == 50:",
                "    total = 0"));
            i.runBody();
            if(!i.hasGlobal("total")) throw new AssertionError("for/if");
            if((double)i.global("total") != 110.0) throw new AssertionError("total=" + i.global("total"));
        });
        b.add(() -> {
            Interp i = Interp.fromSource(String.join("\n",
                "var items = [\"a\", \"b\"]",
                "var m = {color: \"red\", size: 3}",
                "items.append(\"c\")",
                "func main():",
                "    return len(items) + m.size + len(m.color)"));
            i.runBody();
            if((double)i.callFunc("main") != 9.0) throw new AssertionError("list/map: " + i.callFunc("main"));
        });
        b.add(() -> {
            // while + break + string ops + not
            Interp i = Interp.fromSource(String.join("\n",
                "var i = 0",
                "while true:",
                "    i = i + 1",
                "    if i >= 5:",
                "        break",
                "var s = \"ab\" + \"cd\"",
                "func main():",
                "    if not (len(s) == 4):",
                "        return -1",
                "    return i * len(s.upper())"));
            i.runBody();
            if((double)i.callFunc("main") != 20.0) throw new AssertionError("while/break/str");
        });
        b.add(() -> {
            // native surface: dot get/set on a node
            Node n = new Node("cube");
            n.rotation.y = 10;
            Interp i = Interp.fromSource(String.join("\n",
                "func on_update(node, scene, delta):",
                "    node.rotation_y = node.rotation_y + 90 * delta",
                "    return node.rotation_y"));
            Object r = i.callFunc("on_update", n, new Scene(), 0.5);
            if(Math.abs((double)r - 55.0) > 1e-4) throw new AssertionError("native dot: " + r);
        });
        b.add(() -> {
            // deterministic rand
            Interp a = Interp.fromSource("var x = rand(100)");
            Interp c = Interp.fromSource("var x = rand(100)");
            a.setSeed(99);
            c.setSeed(99);
            a.runBody();
            c.runBody();
            if(!a.global("x").equals(c.global("x"))) throw new AssertionError("rand deterministic");
        });
        b.add(() -> {
            // errors are ScriptError, not crashes
            try {
                Interp i = Interp.fromSource("var a = 1\nprint(missing)");
                i.runBody();
                throw new AssertionError("expected error");
            } catch(Interp.ScriptError e){
                if(!e.getMessage().contains("missing")) throw new AssertionError("err msg: " + e.getMessage());
            }
        });

        // --- scene: IO + scripts ---
        b.add(() -> {
            Scene s = new Scene();
            MeshNode m = (MeshNode)s.createNode("mesh", "hero");
            m.meshPath = "cube.obj";
            m.color = "gold";
            m.position.set(0, 1, 0);
            s.add(m);
            CameraNode cam = (CameraNode)s.createNode("camera", "cam");
            cam.position.set(0, 2, 6);
            cam.rotation.x = -18;
            s.add(cam);
            String json = s.save();
            Scene s2 = new Scene();
            s2.load(json);
            if(s2.find("hero") == null || s2.count() != 2) throw new AssertionError("save/load count");
            MeshNode m2 = (MeshNode)s2.find("hero");
            if(!m2.meshPath.equals("cube.obj") || !m2.color.equals("gold")
               || Math.abs(m2.position.y - 1) > 1e-5) throw new AssertionError("save/load props");
        });
        b.add(() -> {
            // script host: on_start + on_update drive the node
            try {
                Path tmp = java.nio.file.Files.createTempFile("spin", ".hs");
                java.nio.file.Files.writeString(tmp, String.join("\n",
                    "func on_start(node, scene):",
                    "    scene.find(\"hero\").rotation_y = 10",
                    "",
                    "func on_update(node, scene, delta):",
                    "    scene.find(\"hero\").rotation_y = scene.find(\"hero\").rotation_y + 60 * delta"));
                Scene s = new Scene();
                ScriptNode sn = (ScriptNode)s.createNode("script", "spin");
                sn.path = tmp.toString();
                s.add(sn);
                MeshNode hero = (MeshNode)s.createNode("mesh", "hero");
                s.add(hero);
                ScriptHost host = new ScriptHost(s, l -> { });
                host.start();
                for(int i = 0; i < 60; i++) host.tickScripts(1.0 / 60);
                double ry = hero.rotation.y;
                if(Math.abs(ry - 70) > 1e-3) throw new AssertionError("script drove node: " + ry);
                java.nio.file.Files.deleteIfExists(tmp);
            } catch(java.io.IOException e){
                throw new AssertionError(e);
            }
        });
        b.add(() -> {
            // scene.spawn from a script + input dispatch
            try {
                Path tmp = java.nio.file.Files.createTempFile("spawn", ".hs");
                java.nio.file.Files.writeString(tmp, String.join("\n",
                    "func on_input(node, scene, key):",
                    "    if key == \"space\":",
                    "        scene.spawn(\"mesh\", \"spawned\")",
                    "",
                    "func on_start(node, scene):",
                    "    print(\"ctl ready\")"));
                Scene s = new Scene();
                ScriptNode sn = (ScriptNode)s.createNode("script", "ctl");
                sn.path = tmp.toString();
                s.add(sn);
                ScriptHost host = new ScriptHost(s, l -> { });
                host.start();
                host.input("space");
                if(s.find("spawned") == null || s.count() != 2) throw new AssertionError("scene.spawn: " + s.count());
                java.nio.file.Files.deleteIfExists(tmp);
            } catch(java.io.IOException e){
                throw new AssertionError(e);
            }
        });

        // --- render ---
        b.add(() -> {
            Mesh m = Mesh.cube();
            if(m.vertexCount() != 24 || m.triangleCount() != 12) throw new AssertionError("cube: " + m.vertexCount() + "/" + m.triangleCount());
        });
        b.add(() -> {
            Mesh m = Mesh.fromObj("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
            if(m.vertexCount() != 3 || m.triangleCount() != 1) throw new AssertionError("obj parse");
        });
        b.add(() -> {
            Frame f = new Frame(64, 64);
            f.clear(0xFF87CEEB);
            // camera at +Z looking down -Z: view maps the cube to view-z = -5
            Mat4 view = new Mat4().identity().translate(0, 0, -5);
            Mat4 proj = new Mat4().identity().perspective(60, 1, 0.1f, 100f);
            Rasterizer.drawMesh(f, Mesh.cube(), new Mat4().identity(), view, proj,
                new Vec3(0, 0, 5), new Rasterizer.Light(new Vec3(-0.3f, -1, -0.2f).norm(), 0xFFFFFFFF, 1f),
                Colors.parse("gold"), 0.3f);
            if(f.fillRatio() < 0.05) throw new AssertionError("cube rasterized: " + f.fillRatio());
            if(f.distinctColors() < 3) throw new AssertionError("shading: " + f.distinctColors());
        });
        b.add(() -> {
            // z-buffer: near quad wins over far quad
            Frame f = new Frame(32, 32);
            f.clear(0);
            Mat4 view = new Mat4().identity();
            Mat4 proj = new Mat4().identity().perspective(60, 1, 0.1f, 100f);
            Vec3 eye = new Vec3(0, 0, 0);
            Rasterizer.Light l = new Rasterizer.Light(new Vec3(0, -1, 0), 0xFFFFFF, 0f);
            Mesh quad = Mesh.fromObj("v -5 -5 -2\nv 5 -5 -2\nv 5 5 -2\nv -5 5 -2\nf 1 2 3 4\n");
            Mesh nearQ = Mesh.fromObj("v -2 -2 -1\nv 2 -2 -1\nv 2 2 -1\nv -2 2 -1\nf 1 2 3 4\n");
            Rasterizer.drawMesh(f, quad, new Mat4().identity(), view, proj, eye, l, Colors.parse("red"), 1f);
            Rasterizer.drawMesh(f, nearQ, new Mat4().identity(), view, proj, eye, l, Colors.parse("green"), 1f);
            // center pixel must be the near quad's color family (green dominates red)
            int mid = f.pixels[16 * 32 + 16];
            int g = (mid >> 8) & 0xFF, r = (mid >> 16) & 0xFF;
            if(g <= r) throw new AssertionError("zbuffer: mid=" + Integer.toHexString(mid));
        });
        b.add(() -> {
            // full scene render: camera sees the cube, frame is non-blank + PNG
            Scene s = new Scene();
            MeshNode hero = (MeshNode)s.createNode("mesh", "hero");
            hero.position.set(0, 0, 0);
            s.add(hero);
            CameraNode cam = (CameraNode)s.createNode("camera", "cam");
            cam.position.set(0, 0, 6);
            s.add(cam);
            LightNode sun = (LightNode)s.createNode("light", "sun");
            sun.rotation.x = -25;
            s.add(sun);
            Frame f = new Renderer(Path.of(".")).render(s, 160, 90);
            if(f.fillRatio() < 0.02) throw new AssertionError("scene render fill: " + f.fillRatio());
            try {
                Path png = java.nio.file.Files.createTempFile("frame", ".png");
                boolean wrote = f.writePNG(png);
                if(!wrote || java.nio.file.Files.size(png) <= 0) throw new AssertionError("png not written");
                java.nio.file.Files.deleteIfExists(png);
            } catch(java.io.IOException e){
                throw new AssertionError(e);
            }
        });

        // --- editor model ---
        b.add(() -> {
            EditorModel em = new EditorModel(Path.of("."));
            em.addNode("mesh", "hero");
            em.addNode("camera", "cam");
            if(em.findNode("hero") == null || em.scene.count() != 2) throw new AssertionError("editor add");
            if(!em.properties("hero").containsKey("mesh") || !em.properties("cam").containsKey("fov"))
                throw new AssertionError("editor schema");
            if(!em.setProperty("hero", "position_y", 3.5) || Math.abs(em.findNode("hero").position.y - 3.5) > 1e-5)
                throw new AssertionError("editor setProperty");
            if(em.setProperty("hero", "fov", 10.0)) throw new AssertionError("cross-type prop accepted");
        });
        b.add(() -> {
            EditorModel em = new EditorModel(Path.of("."));
            em.addNode("mesh", "hero");
            em.renameNode("hero", "player");
            if(em.findNode("hero") != null || em.findNode("player") == null) throw new AssertionError("rename");
            em.select("player");
            if(!em.selected.equals("player")) throw new AssertionError("select");
            em.deleteNode("player");
            if(em.scene.count() != 0) throw new AssertionError("delete");
        });
        b.add(() -> {
            // editor save/open roundtrip through the same model
            try {
                Path dir = java.nio.file.Files.createTempDirectory("h2proj");
                EditorModel em = new EditorModel(dir);
                em.addNode("mesh", "hero");
                em.setProperty("hero", "color", "teal");
                if(!em.saveScene(dir.resolve("scene.hscene"))) throw new AssertionError("editor save");
                EditorModel em2 = new EditorModel(dir);
                if(!em2.openScene(dir.resolve("scene.hscene"))) throw new AssertionError("editor open");
                if(!"teal".equals(em2.properties("hero").get("color"))) throw new AssertionError("editor roundtrip");
                dir.toFile().deleteOnExit();
            } catch(java.io.IOException e){
                throw new AssertionError(e);
            }
        });

        // --- classes (HollowScript OOP) ---
        b.add(() -> {
            Interp i = Interp.fromSource(String.join("\n",
                "class Enemy:",
                "    var hp = 10",
                "    func _init(start):",
                "        self.hp = start",
                "    func hit(d):",
                "        self.hp = self.hp - d",
                "        return self.hp",
                "class Boss extends Enemy:",
                "    var armor = 2",
                "var e = Enemy.new(30)",
                "var b = Boss.new(50)"));
            i.runBody();
            Interp.ScriptObject e = (Interp.ScriptObject)i.global("e");
            Interp.ScriptObject boss = (Interp.ScriptObject)i.global("b");
            if(e == null || boss == null) throw new AssertionError("instantiate");
            if((double)e.hGet("hp") != 30 || (double)boss.hGet("armor") != 2) throw new AssertionError("fields");
            if((double)boss.callMethod("hit", java.util.List.of((Object)5.0), 0) != 45) throw new AssertionError("inherited method");
            if(!Interp.typeName(boss).equals("Boss")) throw new AssertionError("typename: " + Interp.typeName(boss));
        });

        // --- physics end to end ---
        b.add(() -> {
            com.hollow.physics.Physics p = new com.hollow.physics.Physics();
            Scene s = new Scene();
            MeshNode ground = (MeshNode)s.createNode("mesh", "ground");
            ground.position.set(0, -1.05f, 0);
            ground.scale.set(40, 0.1f, 40);
            s.add(ground);
            MeshNode box = (MeshNode)s.createNode("mesh", "box");
            box.dynamic = true;
            box.position.set(0, 3, 0);
            s.add(box);
            LightNode sun = (LightNode)s.createNode("light", "sun");
            s.add(sun);
            float minY = Float.MAX_VALUE;
            for(int i = 0; i < 300; i++){
                p.step(s, 1.0 / 60);
                minY = Math.min(minY, box.position.y);
            }
            // rests at -1.05 + 1.0 + 0.1 = 0.05; sun (a light) is not solid
            if(!(box.position.y > -0.2f && box.position.y < 0.15f)) throw new AssertionError("rest: " + box.position.y);
            if(minY < -0.35f) throw new AssertionError("tunneled: " + minY);
        });

        // --- editor undo/redo + play + orbit + pick ---
        b.add(() -> {
            EditorModel em = new EditorModel(Path.of("."));
            em.addNode("mesh", "hero");
            em.setProperty("hero", "color", "gold");
            if(!em.canUndo()) throw new AssertionError("undo stack");
            // stack: [pre-add(empty), pre-color(hero/silver)] — undo twice to reach empty
            if(!em.undo() || !"silver".equals(em.properties("hero").get("color"))) throw new AssertionError("undo1");
            if(!em.undo() || em.findNode("hero") != null) throw new AssertionError("undo2");
            if(!em.redo() || em.findNode("hero") == null) throw new AssertionError("redo1");
            if(!em.redo() || !"gold".equals(em.properties("hero").get("color"))) throw new AssertionError("redo2");
        });
        b.add(() -> {
            try {
                Path dir = java.nio.file.Files.createTempDirectory("h2play");
                java.nio.file.Files.writeString(dir.resolve("spin.hs"), String.join("\n",
                    "var ticks = 0",
                    "func on_update(node, scene, delta):",
                    "    ticks = ticks + 1",
                    "    scene.find(\"hero\").rotation_y = ticks",
                    "",
                    "func on_collision(node, scene, a, b, axis):",
                    "    print(\"bump\", a.name, b.name, axis)"));
                EditorModel em = new EditorModel(dir);
                MeshNode hero = (MeshNode)em.addNode("mesh", "hero");
                hero.dynamic = true;
                Node ctl = em.addNode("script", "ctl");
                ((com.hollow.scene.ScriptNode)ctl).path = "spin.hs";
                em.addNode("mesh", "ground");
                em.playStart();
                for(int i = 0; i < 30; i++) em.playTick(1.0 / 30);
                if(em.findNode("hero").rotation.y < 25) throw new AssertionError("play drove node: " + em.findNode("hero").rotation.y);
                em.playInput("space");
                em.playStop();
                if(em.isPlaying()) throw new AssertionError("stop");
            } catch(java.io.IOException e){
                throw new AssertionError(e);
            }
        });
        b.add(() -> {
            EditorModel em = new EditorModel(Path.of("."));
            em.useOrbitCamera = true;
            em.addNode("mesh", "hero");
            Frame f1 = em.renderPreview(160, 90);
            em.orbit(90, 10);
            em.zoom(1.3f);
            Frame f2 = em.renderPreview(160, 90);
            if(f1.fillRatio() <= 0 || f2.fillRatio() <= 0) throw new AssertionError("orbit renders blank");
            // drag moves the selected node on the ground plane
            em.select("hero");
            float x0 = em.findNode("hero").position.x;
            em.beginDrag();
            em.dragSelected(20, 0, 480, 270);
            if(em.findNode("hero").position.x == x0) throw new AssertionError("drag moved nothing");
        });
        b.add(() -> {
            // planar shadows darken the ground under a floating box
            Scene s = new Scene();
            MeshNode ground = (MeshNode)s.createNode("mesh", "ground");
            ground.position.set(0, -1.05f, 0);
            ground.scale.set(30, 0.1f, 30);
            ground.color = "grass";
            s.add(ground);
            MeshNode box = (MeshNode)s.createNode("mesh", "box");
            box.position.set(1, 1.2f, 0);
            box.color = "gold";
            s.add(box);
            CameraNode cam = (CameraNode)s.createNode("camera", "cam");
            cam.position.set(0, 3, 9);
            cam.rotation.x = -14;
            s.add(cam);
            LightNode sun = (LightNode)s.createNode("light", "sun");
            sun.rotation.set(-45, 30, 0);
            s.add(sun);
            s.shadowPlaneY = -1.0f;
            Frame f = new com.hollow.render.Renderer(Path.of(".")).render(s, 320, 180);
            int shadowPixels = 0;
            int gl = 0x4C + 0x9F + 0x4C;
            for(int p : f.pixels){
                int lum = ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
                if(p != f.background && lum < gl * 0.6) shadowPixels++;
            }
            if(shadowPixels < 100) throw new AssertionError("shadows: " + shadowPixels);
        });

        return b.toArray(new Runnable[0]);
    }
}
