import com.hollow.editor.EditorModel;
import com.hollow.render.Frame;
import com.hollow.scene.CameraNode;
import com.hollow.scene.LightNode;
import com.hollow.scene.MeshNode;
import com.hollow.scene.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** HollowEngine 2 — editor model regression: headless editor state machine. */
public class VerifyH2Editor {
    static int fails = 0, total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++; if(!ok) fails++;
    }

    public static void main(String[] args){
        treeOps();
        inspector();
        filesAndRun();
        System.out.println(fails == 0 ? "\nALL " + total + " EDITOR TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " EDITOR TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void treeOps(){
        EditorModel em = new EditorModel(Path.of("."));
        check("ed: add nodes", em.addNode("mesh", "hero") instanceof MeshNode
              && em.addNode("camera", "cam") instanceof CameraNode
              && em.addNode("light", "sun") instanceof LightNode
              && em.scene.count() == 3, "");
        check("ed: duplicate name refused", em.addNode("mesh", "hero") == null
              && em.scene.count() == 3 && em.console.get(em.console.size() - 1).contains("already exists"), "");
        em.select("hero");
        check("ed: select", em.selected.equals("hero"), "");
        check("ed: rename rebinds selection", em.renameNode("hero", "player") && em.selected.equals("player")
              && em.findNode("player") != null && em.findNode("hero") == null, "");
        check("ed: rename to taken name refused", !em.renameNode("player", "cam"), "");
        check("ed: delete", em.deleteNode("player") && em.scene.count() == 2 && em.selected.isEmpty(), "");
        check("ed: delete missing refused", !em.deleteNode("ghost"), "");
        check("ed: root protected from delete", !em.deleteNode("root") && em.scene.count() == 2, "");
    }

    static void inspector(){
        EditorModel em = new EditorModel(Path.of("."));
        em.addNode("mesh", "hero");
        var props = em.properties("hero");
        check("in: mesh schema", props.containsKey("type") && props.containsKey("name") && props.containsKey("visible")
              && props.containsKey("position_x") && props.containsKey("rotation_z") && props.containsKey("scale_y")
              && props.containsKey("mesh") && props.containsKey("color"), "");
        em.addNode("camera", "cam");
        em.addNode("light", "sun");
        check("in: camera schema", em.properties("cam").containsKey("fov") && em.properties("cam").containsKey("active"), "");
        check("in: light schema", em.properties("sun").containsKey("color") && em.properties("sun").containsKey("intensity"), "");
        check("in: missing node → empty schema", em.properties("nope").isEmpty(), "");

        check("in: set numeric", em.setProperty("hero", "position_x", 2.5)
              && Math.abs(em.findNode("hero").position.x - 2.5f) < 1e-5, "");
        check("in: set numeric as string", em.setProperty("hero", "rotation_y", "45")
              && Math.abs(em.findNode("hero").rotation.y - 45) < 1e-4, "");
        check("in: set string", em.setProperty("hero", "color", "purple")
              && ((MeshNode)em.findNode("hero")).color.equals("purple"), "");
        check("in: set bool", em.setProperty("hero", "visible", "false") && !em.findNode("hero").visible, "");
        check("in: set via rename", em.setProperty("hero", "name", "cube2") && em.findNode("cube2") != null, "");
        check("in: cross-type set refused", !em.setProperty("hero", "fov", 30.0), "");
        check("in: unknown key refused", !em.setProperty("hero", "nope", 1), "");
        check("in: bad number refused", !em.setProperty("hero", "position_x", "abc"), "");
        em.setProperty("cam", "fov", 90);
        check("in: camera fov set", Math.abs(((CameraNode)em.findNode("cam")).fov - 90) < 1e-4, "");
    }

    static void filesAndRun(){
        try {
            Path dir = Files.createTempDirectory("h2ed");
            Files.writeString(dir.resolve("spin.hs"), String.join("\n",
                "var spins = 0",
                "func on_start(node, scene):",
                "    print(\"editor run\")",
                "",
                "func on_update(node, scene, delta):",
                "    spins = spins + 1",
                "",
                "func on_input(node, scene, key):",
                "    if key == \"jump\":",
                "        print(\"jumped\")"));
            EditorModel em = new EditorModel(dir);
            em.addNode("mesh", "hero");
            Node spin = em.addNode("script", "spin");
            ((com.hollow.scene.ScriptNode)spin).path = "spin.hs";
            if(!em.saveScene(dir.resolve("scene.hscene"))) throw new AssertionError("save");

            EditorModel em2 = new EditorModel(dir);
            check("ed: open roundtrip", em2.openScene(dir.resolve("scene.hscene")) && em2.scene.count() == 2
                  && em2.findNode("hero") != null && em2.findNode("spin") != null, "");
            check("ed: project files dock", em2.projectFiles().contains("scene.hscene")
                  && em2.projectFiles().contains("spin.hs"), "");

            List<String> inputs = new ArrayList<>(List.of("jump"));
            String log = em2.run(60, 1.0 / 60, inputs);
            check("ed: run binds + ticks + inputs", log.contains("editor run") && log.contains("jumped")
                  && log.contains("60 frames done"), "");
            em2.saveProject();
            var proj = em2.loadProject(dir.resolve("project.json"));
            check("ed: project manifest", "2.0.0".equals(proj.get("engine")) && "scene.hscene".equals(proj.get("entry_scene")), "");

            Frame f = em2.renderPreview(240, 135);
            check("ed: preview renders", f.fillRatio() >= 0, "w=" + f.w);
            check("ed: editor console grows", em2.console.size() > 3, "" + em2.console.size());
        } catch(Exception e){
            check("ed: harness", false, e.toString());
        }
    }
}
