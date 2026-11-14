package com.hollow.cli;

import com.hollow.assets.DesktopImageDecoder;
import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.NativeFS;
import com.hollow.game.EngineBootstrap;
import com.hollow.game.EngineServices;
import com.hollow.game.GameApp;
import com.hollow.scene.Entity;

import java.util.List;
import java.util.Map;

/** Headless simulation runner: boots the engine with no GL/audio, ticks N times,
 *  evaluates assertions, prints a report. Exit code 0 = all pass. */
final class HeadlessApp {
    private HeadlessApp() {}

    static void run(String project, int ticks, String assertFile){
        run(project, ticks, assertFile, null);
    }

    static void run(String project, int ticks, String assertFile, String sceneOverride){
        Log.setLevel(Log.Level.WARN);
        DesktopImageDecoder.install();
        NativeFS fs = new NativeFS("projects/" + project);
        EngineServices svc = EngineBootstrap.boot(fs, ".", null);
        svc.config.headless = true;
        svc.time.headless = true;
        if(sceneOverride != null) svc.config.entryScene = sceneOverride;

        TestApp app = new TestApp();
        long t0 = System.currentTimeMillis();
        Log.setCapturing(true); Log.clearCapture();
        app.boot(svc);
        for(int i=0;i<ticks && app.isRunning();i++) app.frame();
        long ms = System.currentTimeMillis() - t0;

        System.out.printf("simulated %d ticks in %d ms (%.1f ticks/s)%n", app.ticksRun, ms, app.ticksRun*1000.0/Math.max(1,ms));
        if(app.currentScenePath != null) System.out.println("scene: " + app.currentScenePath);
        System.out.println("entities: " + (app.world != null ? app.world.count() : 0));
        System.out.println("colliders: " + svc.physics.colliderCount());
        System.out.println("agents: " + svc.ai.agentCount());
        System.out.println("vars: " + svc.vars.size());

        int failures = 0;
        if(assertFile != null){
            try {
                String apath = assertFile;
                if(!fs.exists(apath)){
                    // accept repo-relative paths like projects/<name>/tests.json
                    String prefix = "projects/" + project + "/";
                    if(apath.startsWith(prefix)) apath = apath.substring(prefix.length());
                }
                List<Object> asserts = Json.arr(Json.parse(fs.readText(apath)));
                for(Object o : asserts){
                    Map<String,Object> a = Json.obj(o);
                    String res = check(a, app, svc);
                    if(res == null){
                        System.out.println("PASS  " + Json.write(a));
                    } else {
                        failures++;
                        System.out.println("FAIL  " + Json.write(a) + "  ->  " + res);
                    }
                }
            } catch(Exception e){
                failures++;
                System.out.println("FAIL  assertions unreadable: " + e.getMessage());
            }
        }
        if(failures > 0){
            System.out.println("RESULT: " + failures + " assertion(s) FAILED");
            System.exit(1);
        }
        System.out.println("RESULT: OK");
    }

    private static String check(Map<String,Object> a, TestApp app, EngineServices svc){
        String type = Json.str(a.get("type"), "");
        switch(type){
            case "entity_exists": {
                Entity e = app.world != null ? app.world.find(Json.str(a.get("name"), "")) : null;
                return e != null ? null : "entity not found";
            }
            case "entity_count_at_least": {
                int n = app.world != null ? app.world.count() : 0;
                return n >= Json.int32(a.get("value"), 0) ? null : ("only " + n);
            }
            case "var": {
                String key = Json.str(a.get("key"), "");
                Object v = svc.vars.get(key);
                String op = Json.str(a.get("op"), "==");
                if(v == null) return "var unset";
                double got = v instanceof Number n ? n.doubleValue() : Double.NaN;
                double want = Json.num(a.get("value"), 0);
                boolean ok = switch(op){
                    case "==" -> got == want;
                    case "!=" -> got != want;
                    case ">" -> got > want;
                    case ">=" -> got >= want;
                    case "<" -> got < want;
                    case "<=" -> got <= want;
                    default -> false;
                };
                return ok ? null : ("var " + key + "=" + v);
            }
            case "var_exists": {
                return svc.vars.has(Json.str(a.get("key"), "")) ? null : "var unset";
            }
            case "log_contains": {
                String text = Json.str(a.get("text"), "");
                synchronized(Log.CAPTURE){
                    for(String s : Log.CAPTURE) if(s.contains(text)) return null;
                }
                return "not in logs";
            }
            case "no_error_logs": {
                synchronized(Log.CAPTURE){
                    for(String s : Log.CAPTURE) if(s.startsWith("ERROR")) return "error logged: " + s.substring(0, Math.min(120, s.length()));
                }
                return null;
            }
            case "colliders_at_least": {
                return svc.physics.colliderCount() >= Json.int32(a.get("value"), 1) ? null : ("only " + svc.physics.colliderCount());
            }
            // ---------- v2.0 assertions ----------
            case "body_at_rest": {
                Entity e = app.world != null ? app.world.find(Json.str(a.get("name"), "")) : null;
                if(e == null) return "entity not found";
                com.hollow.physics.RigidBody rb = e.get(com.hollow.physics.RigidBody.class);
                if(rb == null) return "no RigidBody";
                float max = (float)Json.num(a.get("maxSpeed"), 0.15);
                float speed = rb.velocity.len();
                return speed <= max ? null : ("speed=" + speed);
            }
            case "body_below_y": {
                Entity e = app.world != null ? app.world.find(Json.str(a.get("name"), "")) : null;
                if(e == null) return "entity not found";
                float y = (float)Json.num(a.get("y"), 0);
                return e.position.y <= y ? null : ("y=" + e.position.y);
            }
            case "bodies_asleep_at_least": {
                return svc.physics.awakeCount() <= Math.max(0, svc.physics.bodyCount() - Json.int32(a.get("value"), 1))
                    ? null : ("awake=" + svc.physics.awakeCount() + "/" + svc.physics.bodyCount());
            }
            case "eqs_found": {
                String q = Json.str(a.get("query"), "");
                for(Entity e : app.world.entities){
                    com.hollow.ai.Agent ag = e.get(com.hollow.ai.Agent.class);
                    if(ag == null) continue;
                    com.hollow.ai.EQS.Query query = ag.query(q);
                    if(query == null) return "query file not found: " + q;
                    com.hollow.ai.EQS.Result r = com.hollow.ai.EQS.run(query, ag);
                    return r.found ? null : "no valid point";
                }
                return "no agent in scene";
            }
            case "cells_loaded": {
                int want = Json.int32(a.get("value"), 1);
                return svc.streaming.loadedCells >= want ? null : ("loaded=" + svc.streaming.loadedCells);
            }
            case "event_seen": {
                String ev = Json.str(a.get("event"), "");
                synchronized(Log.CAPTURE){
                    for(String s : Log.CAPTURE) if(s.contains(ev)) return null;
                }
                return "event not fired: " + ev;
            }
            default: return "unknown assertion type: " + type;
        }
    }

    static final class TestApp extends GameApp {
        int ticksRun = 0;
        @Override public void onStart(){
            Log.i("HeadlessApp", "start (project=" + svc.config.title + ")");
        }
        @Override public void onFixedUpdate(float dt){
            ticksRun++;
        }
    }
}
