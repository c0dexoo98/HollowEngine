package com.hollow.cli;

import com.hollow.core.FileSystem;
import com.hollow.core.EngineVersion;
import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.NativeFS;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** `hollow` — the Linux CLI (Godot-style). All commands: hollow <cmd> --help */
public final class Main {
    public static void main(String[] args){
        if(args.length == 0){ help(); return; }
        String cmd = args[0];
        try {
            switch(cmd){
                case "version": version(); break;
                case "help": case "--help": case "-h": help(); break;
                case "new": Commands.newProject(arg(args, "-n", "name", 1)); break;
                case "gen": Commands.gen(args); break;
                case "import": Commands.doImport(arg(args, "-p", "project", 1)); break;
                case "atlas": Commands.atlas(arg(args, "-p", "project", 1)); break;
                case "validate": Commands.validate(arg(args, "-p", "project", 1)); break;
                case "tree": Commands.tree(arg(args, "-p", "project", 1), opt(args, "-s", null)); break;
                case "run": Commands.run(args); break;
                case "build": Commands.build(args); break;
                case "clean": Commands.clean(arg(args, "-p", "project", 1)); break;
                // v2.0 commands
                case "profile": Commands.profile(args); break;
                case "bench": Commands.bench(args); break;
                case "net": Commands.netTest(args); break;
                case "audio-render": Commands.audioRender(args); break;
                case "eqs": Commands.eqsTest(args); break;
                case "pose": PoseCmd.run(args); break;
                case "selftest": Commands.selfTest(); break;
                // v2.1 — interactive console (Batch 1)
                case "shell": case "repl": System.exit(Shell.run(args)); break;
                // v2.5 — EXT batch
                case "export": Commands.exportCmd(args); break;
                case "lsp": Commands.lspServer(args); break;
                case "lint": Commands.lintScript(args); break;
                case "debug": Commands.debugScript(args); break;
                default:
                    System.err.println("unknown command: " + cmd);
                    help();
                    System.exit(2);
            }
        } catch(CliError e){
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        } catch(Exception e){
            Log.e("CLI", "command failed: " + cmd, e);
            System.err.println("FATAL: " + e);
            System.exit(1);
        }
    }

    static String arg(String[] args, String flag, String name, Integer pos){
        for(int i=1;i<args.length-1;i++){
            if(args[i].equals(flag)) return args[i+1];
        }
        if(pos != null){
            // positional fallback
            List<String> positional = new ArrayList<>();
            for(int i=1;i<args.length;i++){
                if(args[i].startsWith("-")) { i++; continue; }
                positional.add(args[i]);
            }
            if(positional.size() >= pos) return positional.get(pos-1);
        }
        throw new CliError("missing --" + name + " (or positional argument)");
    }
    static String opt(String[] args, String flag, String def){
        for(int i=1;i<args.length-1;i++) if(args[i].equals(flag)) return args[i+1];
        return def;
    }
    static boolean hasFlag(String[] args, String flag){
        for(int i=1;i<args.length;i++) if(args[i].equals(flag)) return true;
        return false;
    }

    static void version(){
        System.out.println(EngineVersion.FULL);
        System.out.println("Gradle modules: :engine :cli :runtime-android");
        System.out.println("feature matrix: 570 items — docs/FEATURES.md");
    }

    static void help(){
        System.out.println("""
            HollowEngine CLI — build any game from the Linux terminal
            
            commands:
              version                              show engine version
              new -n <name>                        scaffold a game project in projects/<name>/
              gen box|plane|sphere|capsule|cylinder|terrain  -o models/x.hvm [opts]
              import -p <project>                  convert raw/ assets -> assets/ (.hvt, manifest)
              atlas -p <project>                   pack raw/sprites/*.png -> assets/atlas/main.atlas
              validate -p <project>                full project lint (JSON, refs, components, scripts)
              tree -p <project> [-s scene]         print scene entity tree
              run -p <project> [--ticks N] [--assert file]   headless simulation + assertions
              profile -p <project> [--ticks N] [--csv out.csv]   CPU zone profiler
              bench -p <project> [--bodies N]      physics/AI throughput benchmark
              audio-render -p <project> --out mix.wav [--seconds 5]  offline mixdown (full DSP chain)
              eqs -p <project> --query <name>      evaluate an EQS query from the entry scene
              net test                             in-process server/client loopback test
              build apk -p <project>               produce signed APK in dist/
              clean -p <project>                   remove build outputs
              shell                                interactive REPL console (world/actor/cvar/batch)
              shell -c "cmd1; cmd2"                run one shell line non-interactively
              shell -f session.hvefile             run a .hvefile batch script
              
            v2.5:
              export itch -p <project> [--apk dist/app.apk]   itch.io zip + page templates
              export ci -p <project>                          GitHub Actions workflow
              export blender [--out tools/blender]            Blender .hvm export addon
              export jar                                      pack CLI into dist/hollow.jar
              lint <script.flow.json> [--strict]              FlowScript linter
              lsp                                             run LSP server on stdio (VSCode)
              debug <script.flow.json> [--bp N] [--step]      script debugger (breakpoints/step)
            
            project layout: projects/<name>/{project.json, scenes/, raw/, assets/, scripts/, ai/, ui/, strings/, input.json}
            """);
    }

    static final class CliError extends RuntimeException { CliError(String m){ super(m); } }
}
