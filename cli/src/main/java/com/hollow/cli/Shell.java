package com.hollow.cli;

import com.hollow.core.*;
import com.hollow.scene.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** `hollow shell` — interactive REPL console for the engine (Godot/Linux style).
 *  Manages a headless World session: engine start/pause/step, world load/unload,
 *  actor spawn/inspect/kill, component add/set/get, CVars, prefabs, batch scripts (.hvefile).
 *  Also usable non-interactively: `hollow shell -c "engine start; actor spawn lamp; engine step 30"`. */
public final class Shell {
    private final Console console = new Console();
    private World world = null;
    private boolean running = false;
    private boolean paused = true;
    private float timeScale = 1f;
    private final Time time = new Time();
    private long steeredTicks = 0;
    private final Watchdog watchdog = new Watchdog(30f);
    private final Map<String, Prefab> prefabs = new LinkedHashMap<>();

    public Shell(){
        registerCommands();
    }

    // ------------------------------------------------------------------ entry
    public static int run(String[] args) throws Exception {
        Shell shell = new Shell();
        // one-shot mode: -c "cmd1; cmd2"
        for(int i=0;i<args.length-1;i++){
            if(args[i].equals("-c")){
                int code = shell.dispatchLine(args[i+1]);
                shell.console.execute("engine stop");
                return code;
            }
        }
        // batch file mode
        for(int i=0;i<args.length-1;i++){
            if(args[i].equals("--file") || args[i].equals("-f")){
                String text = java.nio.file.Files.readString(java.nio.file.Paths.get(args[i+1]));
                int failures = 0;
                for(String line : text.split("\n")){
                    String t = line.trim();
                    if(t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue;
                    int rc = shell.dispatchLine(t); if(rc != 0) failures++;
                }
                shell.console.execute("engine stop");
                return failures == 0 ? 0 : 1;
            }
        }
        shell.banner();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder multi = new StringBuilder(); // trailing ';' continuation
        while(true){
            System.out.print(multi.length() > 0 ? "....> " : "hollow> ");
            System.out.flush();
            String line = in.readLine();
            if(line == null) break; // EOF (piped script)
            if(multi.length() > 0) multi.append(' ').append(line.trim());
            else multi.append(line.trim());
            if(multi.toString().trim().endsWith(";")){
                multi.setLength(multi.length() - 1);
                continue; // wait for the final command
            }
            String chunk = multi.toString().trim();
            multi.setLength(0);
            if(chunk.isEmpty()) continue;
            if(chunk.equals("exit") || chunk.equals("quit")) break;
            shell.dispatchLine(chunk);
        }
        shell.console.execute("engine stop");
        return 0;
    }

    /** execute a `;`-separated line group; returns 0 ok / 1 last error */
    public int dispatchLine(String line){
        int code = 0;
        for(String part : line.split(";")){
            String out = console.execute(part.trim());
            if(out != null && !out.isEmpty()) System.out.println(out);
            if(console.lastError != null) code = 1;
        }
        return code;
    }

    private void banner(){
        System.out.println(EngineVersion.banner());
        System.out.println("type `help` for commands, `exit` to quit. headless session ready.");
    }

    // ------------------------------------------------------------------ commands
    private void registerCommands(){
        console.register("help", "list commands or `help <cmd>`", args -> {
            if(args.length >= 1){
                for(Console.Command c : console.allCommands())
                    if(c.name.equals(args[0])) return c.name + " — " + c.help;
                return "no such command: " + args[0];
            }
            StringBuilder b = new StringBuilder(2048);
            for(Console.Command c : console.allCommands())
                b.append(String.format("  %-14s %s%n", c.name, c.help));
            return b.toString().stripTrailing();
        });
        console.register("echo", "print text", args -> String.join(" ", args));
        console.register("version", "engine version", args -> EngineVersion.FULL);
        console.register("history", "recent command history", args -> {
            List<String> h = console.history();
            StringBuilder b = new StringBuilder();
            int start = Math.max(0, h.size() - 20);
            for(int i=start;i<h.size();i++) b.append(String.format("%4d  %s%n", i+1, h.get(i)));
            return b.toString().stripTrailing();
        });
        console.alias("cls", "clear");
        console.register("clear", "clear history + log capture", args -> { console.clearHistory(); Log.clearCapture(); return null; });

        // ---- CVar ----
        console.register("cvar", "cvar list|get <name>|set <name> <value>|save <file>|load <file>", args -> {
            if(args.length == 0) return "usage: cvar list [filter] | get <name> | set <name> <v> | save <file> | load <file>";
            switch(args[0]){
                case "list": return CVar.table(args.length > 1 ? args[1] : null);
                case "get": {
                    CVar v = requireCVar(arg(args, 1));
                    return v.name + " = " + v.getString() + "   (" + v.type.name().toLowerCase() + ") " + v.help;
                }
                case "set": {
                    String name = arg(args, 1);
                    String val = arg(args, 2);
                    CVar v = CVar.get(name);
                    if(v == null){ // Quake-style: unknown cvars auto-create, typed from the value
                        if(val.equalsIgnoreCase("true") || val.equalsIgnoreCase("false")) v = CVar.registerBool(name, Boolean.parseBoolean(val), "auto-created");
                        else { try { Float.parseFloat(val); v = CVar.registerFloat(name, 0f, "auto-created"); } catch(NumberFormatException e){ v = CVar.registerString(name, val, "auto-created"); } }
                    }
                    v.set(val);
                    return v.name + " = " + v.getString();
                }
                case "save": CVar.save(arg(args, 1), false); return "saved " + arg(args, 1);
                case "load": CVar.load(arg(args, 1)); return "loaded " + arg(args, 1);
                default: return "unknown subcommand: " + args[0];
            }
        });

        // ---- engine lifecycle ----
        console.register("engine", "start|stop|pause|resume|status|step <ticks>|speed <x>", args -> {
            if(args.length == 0) return engineStatus();
            switch(args[0]){
                case "start": {
                    if(world == null) return "no world — `world new <name>` or `world load <scene.json>` first";
                    running = true; paused = false;
                    watchdog.heartbeat();
                    return "engine started (world '" + world.name + "', " + world.count() + " entities)";
                }
                case "stop":
                    running = false; paused = true;
                    ReleaseQueue.drain();
                    return "engine stopped";
                case "pause":  paused = true;  return "paused at tick " + time.tick;
                case "resume": paused = false; return "resumed";
                case "speed":  timeScale = Float.parseFloat(arg(args, 1)); return "timeScale = " + timeScale;
                case "step": {
                    int n = args.length > 1 ? Integer.parseInt(args[1]) : 1;
                    requireRunning();
                    for(int i=0;i<n;i++) stepOnce();
                    return "stepped " + n + " ticks -> tick " + time.tick + ", time " + String.format("%.2f", time.time) + "s";
                }
                case "status": return engineStatus();
                default: return "unknown subcommand: " + args[0];
            }
        });

        // ---- world ----
        console.register("world", "new <name>|load <file>|unload|list|info|save <file>", args -> {
            if(args.length == 0) return world == null ? "no world" : "world '" + world.name + "' entities=" + world.count();
            switch(args[0]){
                case "new": {
                    String name = arg(args, 1);
                    stopIfRunning();
                    world = World.empty(name);
                    return "created empty world '" + name + "'";
                }
                case "load": {
                    String path = arg(args, 1);
                    stopIfRunning();
                    NativeFS fs = new NativeFS(".");
                    if(!fs.exists(path)) return "ERROR: not found: " + path;
                    world = Scene.load(path, fs);
                    return "loaded '" + world.name + "' (" + world.count() + " entities)";
                }
                case "unload": stopIfRunning(); String n = world == null ? "-" : world.name; world = null; return "unloaded '" + n + "'";
                case "list": {
                    List<String> scenes = new NativeFS(".").list("scenes");
                    return scenes.isEmpty() ? "no scenes/ dir or empty" : String.join("\n", scenes);
                }
                case "info": {
                    requireWorld();
                    StringBuilder b = new StringBuilder(256);
                    b.append("world '").append(world.name).append("': ").append(world.count()).append(" entities, ")
                     .append(world.componentRegistry.size()).append(" component types registered").append('\n');
                    Map<String,Integer> byType = new LinkedHashMap<>();
                    for(Entity e : world.entities) for(Component c : e.allComponents())
                        byType.merge(c.type(), 1, Integer::sum);
                    for(Map.Entry<String,Integer> en : byType.entrySet()) b.append("  ").append(en.getKey()).append(" x").append(en.getValue()).append('\n');
                    return b.toString().stripTrailing();
                }
                case "save": {
                    requireWorld();
                    String path = arg(args, 1);
                    java.nio.file.Files.writeString(java.nio.file.Paths.get(path), Scene.save(world));
                    return "saved " + path;
                }
                default: return "unknown subcommand: " + args[0];
            }
        });

        // ---- actors (entities) ----
        console.register("actor", "spawn <name>|list|inspect <id>|kill <id>|move <id> x y z|tag <id> <tag>|find <name>", args -> {
            if(args.length == 0) return "usage: actor spawn|list|inspect|kill|move|tag|find";
            requireWorld();
            switch(args[0]){
                case "spawn": {
                    String name = arg(args, 1);
                    Entity e = world.spawn(name);
                    return "spawned " + e;
                }
                case "list": {
                    StringBuilder b = new StringBuilder(1024);
                    b.append(String.format("%-4s %-18s %-10s %-8s %s%n", "id", "name", "tag", "active", "components"));
                    for(Entity e : world.entities){
                        StringBuilder cs = new StringBuilder();
                        for(Component c : e.allComponents()) cs.append(c.type()).append(' ');
                        b.append(String.format("%-4d %-18s %-10s %-8s %s%n", e.id, e.name, e.tag.isEmpty() ? "-" : e.tag, e.active ? "yes" : "NO", cs.toString().trim()));
                    }
                    return b.toString().stripTrailing();
                }
                case "inspect": {
                    Entity e = find(arg(args, 1));
                    StringBuilder b = new StringBuilder(512);
                    b.append(e).append("  tag=").append(e.tag.isEmpty() ? "-" : e.tag)
                     .append("  active=").append(e.active)
                     .append("  parent=").append(e.parent() == null ? "-" : e.parent().name).append('\n');
                    b.append("  pos ").append(e.position).append("  scale ").append(e.scale).append('\n');
                    for(Component c : e.allComponents()){
                        com.hollow.core.JsonW cw = new com.hollow.core.JsonW();
                        cw.obj(); c.save(cw); cw.endObj();
                        b.append("  [").append(c.type()).append("] ").append(cw).append('\n');
                    }
                    return b.toString().stripTrailing();
                }
                case "kill": {
                    Entity e = find(arg(args, 1));
                    world.destroy(e);
                    return "destroyed " + e;
                }
                case "move": {
                    Entity e = find(arg(args, 1));
                    e.position.set(Float.parseFloat(arg(args, 2)), Float.parseFloat(arg(args, 3)), Float.parseFloat(arg(args, 4)));
                    e.markDirtyDeep();
                    return e + " -> " + e.position;
                }
                case "tag": {
                    Entity e = find(arg(args, 1));
                    e.tag = arg(args, 2);
                    return e + " tag='" + e.tag + "'";
                }
                case "find": {
                    Entity e = world.find(arg(args, 1));
                    return e == null ? "not found" : e.toString();
                }
                default: return "unknown subcommand: " + args[0];
            }
        });

        // ---- components ----
        console.register("component", "list|add <id> <Type>|remove <id> <Type>|set <id> <Type> <json>|get <id> <Type>", args -> {
            if(args.length == 0) return "usage: component list|add|remove|set|get";
            requireWorld();
            switch(args[0]){
                case "list": {
                    StringBuilder b = new StringBuilder();
                    for(String t : world.componentRegistry.keySet()) b.append(t).append(' ');
                    return "registered types: " + b.toString().trim();
                }
                case "add": {
                    Entity e = find(arg(args, 1));
                    String type = arg(args, 2);
                    World.ComponentFactory f = world.componentRegistry.get(type);
                    if(f == null) return "ERROR: unknown component type '" + type + "'";
                    Component c = f.create();
                    if(args.length > 3) c.load(new JsonV(Json.parseObj(joinArgs(args, 3))));
                    e.add(c);
                    return "added " + type + " to " + e;
                }
                case "remove": {
                    Entity e = find(arg(args, 1));
                    String type = arg(args, 2);
                    Component c = e.get(type);
                    if(c == null) return "no such component: " + type;
                    c.onDetach();
                    e.components.remove(type);
                    return "removed " + type + " from " + e;
                }
                case "set": {
                    Entity e = find(arg(args, 1));
                    String type = arg(args, 2);
                    Component c = e.get(type);
                    if(c == null) return "no such component: " + type;
                    c.load(new JsonV(Json.parseObj(joinArgs(args, 3))));
                    return "patched " + type + " on " + e;
                }
                case "get": {
                    Entity e = find(arg(args, 1));
                    String type = arg(args, 2);
                    Component c = e.get(type);
                    if(c == null) return "no such component: " + type;
                    com.hollow.core.JsonW cw = new com.hollow.core.JsonW();
                    cw.obj(); c.save(cw); cw.endObj();
                    return cw.toString();
                }
                default: return "unknown subcommand: " + args[0];
            }
        });

        // ---- prefabs ----
        console.register("prefab", "load <file>|save <id> <file>|spawn <name> [x y z]", args -> {
            if(args.length == 0) return "usage: prefab load|save|spawn";
            requireWorld();
            switch(args[0]){
                case "load": {
                    String path = arg(args, 1);
                    Prefab p = Prefab.fromJson(java.nio.file.Files.readString(java.nio.file.Paths.get(path)));
                    prefabs.put(p.name, p);
                    return "prefab '" + p.name + "' loaded from " + path;
                }
                case "save": {
                    Entity e = find(arg(args, 1));
                    String path = arg(args, 2);
                    java.nio.file.Files.writeString(java.nio.file.Paths.get(path), Prefab.toPrefabJson(e));
                    return "prefab saved: " + path;
                }
                case "spawn": {
                    String name = arg(args, 1);
                    Prefab p = prefabs.get(name);
                    if(p == null) return "no prefab loaded named '" + name + "'";
                    com.hollow.core.Vec3 pos = null;
                    if(args.length >= 5)
                        pos = new com.hollow.core.Vec3(Float.parseFloat(args[2]), Float.parseFloat(args[3]), Float.parseFloat(args[4]));
                    Entity e = p.instantiate(world, pos);
                    return "spawned " + e + " from prefab '" + p.name + "'";
                }
                default: return "unknown subcommand: " + args[0];
            }
        });

        // ---- memory / diagnostics ----
        console.register("mem", "memory report", args -> MemoryStats.report().stripTrailing());
        console.register("subsys", "subsystem registry status", args -> Subsystems.statusTable().stripTrailing());
        console.register("gc", "request GC + report", args -> { System.gc(); return "GC requested\n" + MemoryStats.report().stripTrailing(); });
        console.register("guid", "new stable id", args -> Guid.create());
        console.register("pool", "pool stats demo", args -> {
            Pool<Object> p = new Pool<>(Object::new, 4);
            Object a = p.obtain(); Object b = p.obtain(); p.free(a); p.free(b);
            return p.stats();
        });
        console.register("wait", "wait <ticks> — step until tick reaches value", args -> {
            requireWorld(); requireRunning();
            int target = Integer.parseInt(arg(args, 1));
            while(time.tick < target) stepOnce();
            return "reached tick " + time.tick;
        });
        console.register("batch", "batch <file.hvefile> — run command script", args -> {
            String path = arg(args, 1);
            String text = java.nio.file.Files.readString(java.nio.file.Paths.get(path));
            int n = console.executeScript(text);
            return "executed " + n + " lines from " + path;
        });
        console.register("screenshot", "screenshot [--out file] — headless world snapshot (JSON, not pixels; see Batch 2 GPU)", args -> {
            requireWorld();
            String out = args.length >= 2 && args[0].equals("--out") ? args[1] : "screenshots/world-t" + time.tick + ".json";
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(out).toAbsolutePath().getParent());
            java.nio.file.Files.writeString(java.nio.file.Paths.get(out), Scene.save(world));
            return "snapshot written: " + out;
        });

        // aliases for muscle memory
        console.alias("ls", "actor");
        console.alias("e", "engine");
        console.alias("w", "world");
        console.alias("a", "actor");
        console.alias("c", "component");
    }

    // ------------------------------------------------------------------ simulation
    private void stepOnce(){
        float dt = time.fixedDelta * timeScale;
        time.deltaTime = dt;
        time.tick++;
        time.frame++;
        time.time += dt;
        world.update(dt);
        steeredTicks++;
        if(steeredTicks % 60 == 0) watchdog.heartbeat();
    }

    private String engineStatus(){
        StringBuilder b = new StringBuilder(192);
        b.append("engine: ").append(!running ? "stopped" : (paused ? "paused" : "running"))
         .append("   world: ").append(world == null ? "-" : world.name)
         .append("   entities: ").append(world == null ? 0 : world.count())
         .append("   tick: ").append(time.tick)
         .append("   time: ").append(String.format("%.2fs", time.time))
         .append("   speed: ").append(timeScale).append('x');
        return b.toString();
    }

    // ------------------------------------------------------------------ helpers
    private void requireWorld(){ if(world == null) throw new IllegalStateException("no world — run `world new <name>` or `world load <file>`"); }
    private void requireRunning(){ if(!running) throw new IllegalStateException("engine not started — `engine start` first"); }
    private void stopIfRunning(){ running = false; paused = true; }
    private CVar requireCVar(String name){
        CVar v = CVar.get(name);
        if(v == null) throw new IllegalStateException("no cvar '" + name + "' (cvar list)");
        return v;
    }
    private Entity find(String idOrName){
        Entity e = null;
        try { int id = Integer.parseInt(idOrName);
            for(Entity en : world.entities) if(en.id == id){ e = en; break; }
        } catch(NumberFormatException ignored){ }
        if(e == null) e = world.find(idOrName);
        if(e == null) throw new IllegalStateException("no entity '" + idOrName + "'");
        return e;
    }
    private static String arg(String[] args, int idx){
        if(args.length <= idx) throw new IllegalArgumentException("missing argument " + (idx+1));
        return args[idx];
    }
    private static String joinArgs(String[] args, int from){
        return String.join(" ", java.util.Arrays.copyOfRange(args, from, args.length));
    }
}
