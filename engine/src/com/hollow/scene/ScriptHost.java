package com.hollow.scene;

import com.hollow.script.Interp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** HollowEngine 2 — binds every ScriptNode in a scene to an
 *  interpreter and dispatches engine events (on_start / on_update /
 *  on_input). Scripts receive (node, scene) — the same Native
 *  surfaces the editor inspects. Deterministic: no wall clock. */
public final class ScriptHost {

    public final Scene scene;
    public final Consumer<String> out;
    public final java.nio.file.Path baseDir;
    public double elapsed = 0;
    /** HUD lines produced by scripts this frame (hud() builtin) */
    public final java.util.List<String> hudLines = new java.util.ArrayList<>();

    public ScriptHost(Scene scene, Consumer<String> out){
        this(scene, out, java.nio.file.Path.of("."));
    }

    /** baseDir: relative script paths resolve against the project directory */
    public ScriptHost(Scene scene, Consumer<String> out, java.nio.file.Path baseDir){
        this.scene = scene;
        this.out = out;
        this.baseDir = baseDir;
    }

    private java.nio.file.Path resolve(String p){
        java.nio.file.Path pp = java.nio.file.Path.of(p);
        return pp.isAbsolute() ? pp : baseDir.resolve(pp);
    }

    /** load + bind all script nodes; calls on_start(node, scene) in tree order */
    public int start(){
        elapsed = 0;
        int[] bound = new int[1];
        scene.root.walk(n -> {
            if(n instanceof ScriptNode){
                Interp interp = interpFor((ScriptNode)n);
                if(interp != null){
                    try {
                        if(interp.hasFunc("on_start")) interp.callFunc("on_start", n, scene);
                        bound[0]++;
                    } catch(Exception e){
                        out.accept("[script " + ((ScriptNode)n).path + "] " + e.getMessage());
                    }
                }
            }
        });
        return bound[0];
    }

    /** advance the world: node.update + on_update(node, scene, delta) */
    public int tickScripts(double delta){
        elapsed += delta;
        int[] ran = new int[1];
        scene.root.walk(n -> {
            if(n instanceof ScriptNode){
                Interp interp = interpFor((ScriptNode)n);
                if(interp != null && interp.hasFunc("on_update")){
                    try {
                        interp.callFunc("on_update", n, scene, delta);
                        ran[0]++;
                    } catch(Exception e){
                        out.accept("[script " + ((ScriptNode)n).path + "] " + e.getMessage());
                    }
                }
            }
        });
        return ran[0];
    }

    /** deliver an input key to every script with on_input(node, scene, key) */
    public int input(String key){
        int[] ran = new int[1];
        scene.root.walk(n -> {
            if(n instanceof ScriptNode){
                Interp interp = interpFor((ScriptNode)n);
                if(interp != null && interp.hasFunc("on_input")){
                    try {
                        interp.callFunc("on_input", n, scene, key);
                        ran[0]++;
                    } catch(Exception e){
                        out.accept("[script " + ((ScriptNode)n).path + "] " + e.getMessage());
                    }
                }
            }
        });
        return ran[0];
    }

    /** one game-frame entry point: physics → collisions → scripts.
     *  Scripts get on_collision(node, other, scene, axis) for each
     *  new contact, then on_update(node, scene, delta). HUD lines
     *  accumulate during the frame via the hud() builtin. */
    public void stepWorld(double delta){
        hudLines.clear();
        com.hollow.physics.Physics physics = new com.hollow.physics.Physics();
        java.util.List<com.hollow.physics.Physics.Collision> hits = physics.step(scene, delta);
        if(!hits.isEmpty()){
            scene.root.walk(n -> {
                if(n instanceof ScriptNode){
                    Interp interp = interpFor((ScriptNode)n);
                    if(interp != null && interp.hasFunc("on_collision")){
                        for(com.hollow.physics.Physics.Collision c : hits){
                            try {
                                interp.callFunc("on_collision", n, scene, c.a, c.b, c.axis);
                            } catch(Exception e){
                                out.accept("[script " + ((ScriptNode)n).path + "] " + e.getMessage());
                            }
                        }
                    }
                }
            });
        }
        tickScripts(delta);
    }

    /** test/tooling access to a bound interpreter (null if the script failed to load) */
    public Interp interpOf(ScriptNode sn){ return interpFor(sn); }

    /** one interpreter per script node per host (null caches load failures) */
    private Interp interpFor(ScriptNode sn){
        if(cache.containsKey(sn)) return cache.get(sn);
        try {
            Interp interp = Interp.fromSource(Files.readString(resolve(sn.path)));
            interp.setOut(out);
            interp.setHud(line -> hudLines.add(line));
            interp.runBody();
            cache.put(sn, interp);
            return interp;
        } catch(Exception e){
            out.accept("[script " + sn.path + "] " + e.getMessage());
            cache.put(sn, null);
            return null;
        }
    }
    private final java.util.Map<ScriptNode, Interp> cache = new java.util.HashMap<>();
}
