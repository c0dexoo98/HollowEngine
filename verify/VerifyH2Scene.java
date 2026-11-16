import com.hollow.core.MathX;
import com.hollow.core.MathX.Vec3;
import com.hollow.scene.CameraNode;
import com.hollow.scene.LightNode;
import com.hollow.scene.MeshNode;
import com.hollow.scene.Node;
import com.hollow.scene.Scene;
import com.hollow.scene.ScriptHost;
import com.hollow.scene.ScriptNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** HollowEngine 2 — scene regression: tree, transforms, IO, scripts. */
public class VerifyH2Scene {
    static int fails = 0, total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++; if(!ok) fails++;
    }

    public static void main(String[] args){
        tree();
        io();
        scripts();
        System.out.println(fails == 0 ? "\nALL " + total + " SCENE TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " SCENE TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void tree(){
        Scene s = new Scene();
        check("sc: createNode types", s.createNode("mesh", "a") instanceof MeshNode
              && s.createNode("camera", "b") instanceof CameraNode
              && s.createNode("light", "c") instanceof LightNode
              && s.createNode("script", "d") instanceof ScriptNode
              && s.createNode("node", "e").type().equals("node"), "");
        try {
            s.createNode("dragon", "f");
            check("sc: unknown type throws", false, "");
        } catch(IllegalArgumentException e){
            check("sc: unknown type throws", true, "");
        }
        Node parent = new Node("p");
        Node mid = new Node("m");
        Node leaf = new Node("l");
        parent.add(mid);
        mid.add(leaf);
        parent.position.set(1, 2, 3);
        mid.position.set(10, 0, 0);
        Vec3 w = leaf.worldPosition();
        check("sc: deep transform composition", Math.abs(w.x - 11) < 1e-4 && Math.abs(w.y - 2) < 1e-4 && Math.abs(w.z - 3) < 1e-4, w.toString());
        Node reparent = new Node("x");
        parent.add(reparent);
        mid.add(reparent);
        check("sc: reparent removes from old parent", parent.childCount() == 1 && mid.childCount() == 2
              && reparent.parent == mid, "");
        check("sc: find deep", parent.find("l") == leaf && parent.find("nope") == null, "");
        final int[] seen = { 0 };
        parent.walk(n -> seen[0]++);
        check("sc: walk visits all", seen[0] == 4, "");
        Node self = new Node("z");
        self.add(self);
        check("sc: self-add ignored", self.childCount() == 0, "");
    }

    static void io(){
        Scene s = new Scene();
        s.name = "rt";
        s.clearColor = "night";
        s.ambient = 0.5f;
        MeshNode m = (MeshNode)s.createNode("mesh", "hero");
        m.meshPath = "cube.obj";
        m.color = "teal";
        m.position.set(1, 2, 3);
        m.rotation.set(10, 20, 30);
        m.scale.set(2, 2, 2);
        s.add(m);
        CameraNode cam = (CameraNode)s.createNode("camera", "cam");
        cam.fov = 75;
        cam.active = true;
        cam.position.set(0, 5, 10);
        s.add(cam);
        LightNode sun = (LightNode)s.createNode("light", "sun");
        sun.color = "orange";
        sun.intensity = 1.5f;
        sun.rotation.set(-40, 15, 0);
        s.add(sun);
        ScriptNode sn = (ScriptNode)s.createNode("script", "ctl");
        sn.path = "ctl.hs";
        s.add(sn);
        Node group = s.createNode("node", "group");
        s.add(group);
        Node inner = s.createNode("mesh", "inner");
        group.add(inner);

        Scene s2 = new Scene();
        s2.load(s.save());
        check("io: scene settings", s2.name.equals("rt") && s2.clearColor.equals("night")
              && Math.abs(s2.ambient - 0.5f) < 1e-5, "");
        MeshNode m2 = (MeshNode)s2.find("hero");
        check("io: mesh props", m2 != null && m2.meshPath.equals("cube.obj") && m2.color.equals("teal")
              && Math.abs(m2.position.z - 3) < 1e-5 && Math.abs(m2.rotation.y - 20) < 1e-5
              && Math.abs(m2.scale.x - 2) < 1e-5, "");
        CameraNode c2 = (CameraNode)s2.find("cam");
        check("io: camera props", c2 != null && Math.abs(c2.fov - 75) < 1e-4 && c2.active, "");
        LightNode l2 = (LightNode)s2.find("sun");
        check("io: light props", l2 != null && l2.color.equals("orange") && Math.abs(l2.intensity - 1.5) < 1e-5
              && Math.abs(l2.rotation.x + 40) < 1e-4, "");
        ScriptNode sc2 = (ScriptNode)s2.find("ctl");
        check("io: script props", sc2 != null && sc2.path.equals("ctl.hs"), "");
        check("io: nested children survive", s2.find("group") != null && s2.find("group").find("inner") != null
              && s2.count() == 5, "");
        check("io: active camera found", s2.activeCamera() == s2.find("cam"), "");

        CameraNode off = (CameraNode)s.createNode("camera", "dark");
        off.active = false;
        s.add(off);
        Scene s3 = new Scene();
        s3.load(s.save());
        check("io: active flag survives, dark camera skipped", s3.activeCamera() == s3.find("cam"), "");
        check("io: spawn idempotent", s3.spawn("mesh", "hero") == s3.find("hero") && s3.count() == 6, "");
        check("io: remove", s3.hCall("remove", java.util.List.of((Object)"hero")).equals(Boolean.TRUE)
              && s3.find("hero") == null && s3.count() == 5, "");
    }

    static void scripts(){
        try {
            Path dir = Files.createTempDirectory("h2scene");
            Files.writeString(dir.resolve("ctl.hs"), String.join("\n",
                "var moved = 0",
                "func on_start(node, scene):",
                "    scene.find(\"hero\").position_x = 5",
                "    print(\"ready\")",
                "",
                "func on_update(node, scene, delta):",
                "    var hero = scene.find(\"hero\")",
                "    hero.rotation_z = hero.rotation_z + 120 * delta",
                "",
                "func on_input(node, scene, key):",
                "    if key == \"up\":",
                "        scene.find(\"hero\").position_y = scene.find(\"hero\").position_y + 1",
                "    elif key == \"spawn\":",
                "        scene.spawn(\"mesh\", \"ghost\")"));
            Scene s = new Scene();
            ScriptNode sn = (ScriptNode)s.createNode("script", "ctl");
            sn.path = "ctl.hs";
            s.add(sn);
            MeshNode hero = (MeshNode)s.createNode("mesh", "hero");
            s.add(hero);
            List<String> out = new ArrayList<>();
            ScriptHost host = new ScriptHost(s, out::add, dir);
            check("sh: on_start bound + ran", host.start() == 1, "");
            check("sh: on_start drove node", Math.abs(hero.position.x - 5) < 1e-5, "");
            check("sh: print reached console", out.contains("ready"), "");
            for(int i = 0; i < 30; i++) host.tickScripts(1.0 / 60);
            check("sh: on_update accumulates", Math.abs(hero.rotation.z - 60) < 1e-3, "" + hero.rotation.z);
            host.input("up");
            check("sh: on_input moved hero", Math.abs(hero.position.y - 1) < 1e-5, "");
            host.input("spawn");
            check("sh: script spawned node", s.find("ghost") != null && s.count() == 3, "");
            check("sh: elapsed accumulates", Math.abs(host.elapsed - 0.5) < 1e-9, "" + host.elapsed);

            // script errors are logged, never thrown
            Files.writeString(dir.resolve("bad.hs"), "func on_start(node, scene):\n    crash_now()\n");
            Scene s2 = new Scene();
            ScriptNode bad = (ScriptNode)s2.createNode("script", "bad");
            bad.path = "bad.hs";
            s2.add(bad);
            ScriptHost host2 = new ScriptHost(s2, out::add, dir);
            boolean threw = false;
            try { host2.start(); } catch(Exception e){ threw = true; }
            check("sh: script error logged not thrown", !threw && out.stream().anyMatch(l -> l.contains("bad.hs")), "");
        } catch(Exception e){
            check("sh: harness", false, e.toString());
        }
    }
}
