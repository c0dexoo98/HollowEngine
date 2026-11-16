package com.hollow.cli;

import com.hollow.editor.EditorApp;
import com.hollow.editor.EditorModel;
import com.hollow.render.Frame;
import com.hollow.render.Renderer;
import com.hollow.scene.Scene;
import com.hollow.scene.ScriptHost;
import com.hollow.script.Interp;
import com.hollow.script.Parser;
import com.hollow.script.Parser.Program;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** HollowEngine 2 — command line:
 *  hollow new|run|render|script|editor|version|help|selftest */
public final class Main {

    public static void main(String[] args){
        String cmd = args.length == 0 ? "help" : args[0];
        try {
            switch(cmd){
                case "new": exit(new_(args)); break;
                case "run": exit(run(args)); break;
                case "render": exit(render(args)); break;
                case "script": exit(script(args)); break;
                case "editor": exit(editor(args)); break;
                case "selftest": exit(selftest()); break;
                case "version": System.out.println("HollowEngine 2.1.0 'play'"); exit(0); break;
                case "help": help(); exit(0); break;
                default:
                    System.out.println("unknown command '" + cmd + "' — try `hollow help`");
                    exit(2);
            }
        } catch(HeadlessEditor e){
            System.out.println(e.getMessage());
            exit(2);
        } catch(Exception e){
            System.out.println("error: " + e.getMessage());
            exit(1);
        }
    }

    private static void exit(int code){ System.exit(code); }

    private static final class HeadlessEditor extends RuntimeException {
        HeadlessEditor(String msg){ super(msg); }
    }

    private static int argNum(String[] args, String flag, int def){
        for(int i = 0; i + 1 < args.length; i++)
            if(args[i].equals(flag)) return Integer.parseInt(args[i + 1]);
        return def;
    }
    private static String argStr(String[] args, String flag, String def){
        for(int i = 0; i + 1 < args.length; i++)
            if(args[i].equals(flag)) return args[i + 1];
        return def;
    }

    // ------------------------- commands -------------------------

    private static int new_(String[] args){
        Path dir = Path.of(args.length > 1 ? args[1] : "my-game");
        if(Files.exists(dir) && args.length == 2){ System.out.println("directory exists: " + dir); return 1; }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("scene.hscene"), scaffoldScene());
            Files.writeString(dir.resolve("cube.obj"), cubeObj());
            Files.writeString(dir.resolve("spin.hs"), spinScript());
            com.hollow.core.Json.JsonW w = new com.hollow.core.Json.JsonW();
            w.obj().kv("hproject", 2).kv("name", dir.getFileName().toString())
              .kv("entry_scene", "scene.hscene").kv("engine", "2.0.0");
            w.endObj();
            Files.writeString(dir.resolve("project.json"), w.toString());
            System.out.println("created project " + dir.toAbsolutePath());
            System.out.println("next:  hollow render " + dir + "   (frame.png)");
            System.out.println("then:  hollow editor " + dir + "   (needs a display)");
            return 0;
        } catch(Exception e){
            System.out.println("new failed: " + e.getMessage());
            return 1;
        }
    }

    private static Scene loadScene(Path dir) throws Exception{
        Scene s = new Scene();
        s.load(Files.readString(dir.resolve("scene.hscene")));
        return s;
    }

    private static int run(String[] args){
        Path dir = Path.of(args.length > 1 ? args[1] : ".");
        int frames = argNum(args, "--frames", 60);
        try {
            Scene s = loadScene(dir);
            List<String> out = new ArrayList<>();
            ScriptHost host = new ScriptHost(s, out::add, dir);
            int bound = host.start();
            for(int i = 0; i < frames; i++) host.stepWorld(1.0 / 60);
            System.out.println("scene: " + s.name + "  scripts bound: " + bound + "  frames: " + frames);
            for(String line : out) System.out.println("  " + line);
            return 0;
        } catch(Exception e){
            System.out.println("run failed: " + e.getMessage());
            return 1;
        }
    }

    private static int render(String[] args){
        Path dir = Path.of(args.length > 1 ? args[1] : ".");
        int frames = argNum(args, "--frames", 0);
        int w = argNum(args, "--w", 960), h = argNum(args, "--h", 540);
        Path out = dir.resolve(argStr(args, "--out", "frame.png"));
        try {
            Scene s = loadScene(dir);
            List<String> console = new ArrayList<>();
            ScriptHost host = new ScriptHost(s, console::add, dir);
            host.start();
            for(int i = 0; i < frames; i++) host.stepWorld(1.0 / 60);
            Frame f = new Renderer(dir).render(s, w, h);
            f.writePNG(out);
            System.out.println("wrote " + out + " (" + w + "x" + h + ", fill "
                               + Math.round(f.fillRatio() * 100) + "%, "
                               + f.distinctColors() + " colors)");
            return f.fillRatio() > 0.001 ? 0 : 3;
        } catch(Exception e){
            System.out.println("render failed: " + e.getMessage());
            return 1;
        }
    }

    private static int script(String[] args){
        if(args.length < 2){ System.out.println("usage: hollow script <file.hs>"); return 1; }
        try {
            Program p = Parser.parse(Files.readString(Path.of(args[1])));
            Interp interp = new Interp(p);
            interp.runBody();
            if(interp.hasFunc("main")) interp.callFunc("main");
            return 0;
        } catch(Exception e){
            System.out.println("script error: " + e.getMessage());
            return 1;
        }
    }

    private static int editor(String[] args){
        Path dir = Path.of(args.length > 1 ? args[1] : ".");
        if(java.awt.GraphicsEnvironment.isHeadless())
            throw new HeadlessEditor("editor needs a display (run it on your desktop machine); "
                + "headless hosts can use: hollow render/run/script");
        EditorApp.launch(dir);
        return 0;
    }

    // ------------------------- scaffolds -------------------------

    private static String scaffoldScene(){
        return "{\"hscene\": 2, \"name\": \"my-game\", \"clear_color\": \"sky\", \"ambient\": 0.35, "
            + "\"root\": {\"name\": \"root\", \"type\": \"node\", \"position\": [0, 0, 0], \"rotation\": [0, 0, 0], \"scale\": [1, 1, 1], \"visible\": true, "
            + "\"children\": ["
            + "{\"name\": \"hero\", \"type\": \"mesh\", \"position\": [0, 0, 0], \"rotation\": [0, 0, 0], \"scale\": [1, 1, 1], \"visible\": true, \"mesh\": \"cube.obj\", \"color\": \"gold\", \"children\": []}, "
            + "{\"name\": \"camera\", \"type\": \"camera\", \"position\": [0, 2, 6], \"rotation\": [-18, 0, 0], \"scale\": [1, 1, 1], \"visible\": true, \"fov\": 60, \"near\": 0.1, \"far\": 500, \"active\": true, \"children\": []}, "
            + "{\"name\": \"sun\", \"type\": \"light\", \"position\": [0, 0, 0], \"rotation\": [-25, 20, 0], \"scale\": [1, 1, 1], \"visible\": true, \"color\": \"white\", \"intensity\": 1.0, \"children\": []}, "
            + "{\"name\": \"spin\", \"type\": \"script\", \"position\": [0, 0, 0], \"rotation\": [0, 0, 0], \"scale\": [1, 1, 1], \"visible\": true, \"path\": \"spin.hs\", \"children\": []}"
            + "]}}";
    }

    private static String cubeObj(){
        return "# minimal cube for HollowEngine 2\n"
            + "v -1 -1 -1\nv 1 -1 -1\nv 1 1 -1\nv -1 1 -1\n"
            + "v 1 -1 1\nv -1 -1 1\nv -1 1 1\nv 1 1 1\n"
            + "# faces (quads, fan-triangulated by the loader)\n"
            + "f 1 2 3 4\nf 5 6 7 8\nf 6 5 1 4\nf 3 2 5 8\nf 4 3 8 7\nf 2 1 6 5\n";
    }

    private static String spinScript(){
        return String.join("\n",
            "# demo: spin the hero cube and report on start",
            "var speed = 90",
            "",
            "func on_start(node, scene):",
            "    print(\"spin: started, speed =\", speed)",
            "",
            "func on_update(node, scene, delta):",
            "    var hero = scene.find(\"hero\")",
            "    if hero != null:",
            "        hero.rotation_y = hero.rotation_y + speed * delta",
            "");
    }

    private static void help(){
        System.out.println("HollowEngine 2.1.0 'play'");
        System.out.println("  hollow new <dir>            scaffold a project");
        System.out.println("  hollow run <dir> [--frames N]    run scripts headless");
        System.out.println("  hollow render <dir> [--frames N] [--out f.png] [--w 960 --h 540]");
        System.out.println("  hollow script <file.hs>     run a standalone script");
        System.out.println("  hollow editor [dir]         open the editor (needs a display)");
        System.out.println("  hollow selftest             internal battery");
        System.out.println("  hollow version | help");
    }

    // ------------------------- selftest -------------------------

    private static int selftest(){
        int[] fails = { 0 };
        int[] total = { 0 };
        Runnable[] checks = Selftest.battery();
        for(Runnable r : checks){
            try {
                r.run();
                total[0]++;
            } catch(Throwable t){
                fails[0]++;
                total[0]++;
                System.out.println("FAIL selftest: " + t.getClass().getSimpleName()
                                   + (t.getMessage() == null ? "" : " — " + t.getMessage()));
            }
        }
        System.out.println(fails[0] == 0 ? "OK  selftest (" + total[0] + " checks)"
                                         : fails[0] + " OF " + total[0] + " SELFTEST CHECKS FAILED");
        return fails[0] == 0 ? 0 : 1;
    }
}
