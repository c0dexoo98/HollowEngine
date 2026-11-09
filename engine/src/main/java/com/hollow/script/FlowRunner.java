package com.hollow.script;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.game.EngineServices;
import com.hollow.game.GameApp;
import com.hollow.physics.CharacterController;
import com.hollow.scene.CameraComponent;
import com.hollow.scene.Entity;
import com.hollow.scene.LightComponent;
import com.hollow.scene.Scene;
import com.hollow.scene.World;
import com.hollow.ui.UIManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Concrete FlowScript interpreter (coroutine-like, thread-free).
 *  Step lists run sequentially; `wait` schedules a continuation that FlowRunner.update(dt)
 *  resumes when its timer elapses. if/else/sequence splice sub-lists via a frame stack.
 *  Any step error → Log.w + continue (games never crash on script bugs). */
public final class FlowRunner extends StepRunner {
    public static final int MAX_STEPS_PER_RESUME = 2048;

    static final class Frame {
        final List<Object> steps;
        int pc = 0;
        Frame(List<Object> steps){ this.steps = steps; }
    }

    static final class Task {
        Entity self;
        final ArrayList<Frame> stack = new ArrayList<>();
        float delay = 0f;      // seconds remaining before resume
        boolean alive = true;
    }

    private final ArrayList<Task> tasks = new ArrayList<>();
    private int rngCallsAtFrameStart = 0;

    public boolean update(float dt){
        Expr.rngCalls = rngCallsAtFrameStart;
        for(int i = 0; i < tasks.size(); i++){
            Task t = tasks.get(i);
            if(!t.alive){ tasks.remove(i); i--; continue; }
            if(t.self != null && t.self.destroyed){ t.alive = false; tasks.remove(i); i--; continue; }
            if(t.delay > 0f){
                t.delay -= dt;
                if(t.delay > 0f) continue;
            }
            resume(t);
            if(!t.alive){ tasks.remove(i); i--; }
        }
        rngCallsAtFrameStart = Expr.rngCalls;
        return !tasks.isEmpty();
    }

    public void runSteps(Object steps, Entity self){
        List<Object> list = Json.arr(steps);
        if(list == null || list.isEmpty()) return;
        Task t = new Task();
        t.self = self;
        t.stack.add(new Frame(list));
        tasks.add(t);
        resume(t);
        if(!t.alive) tasks.remove(t);
    }

    /** drop all pending continuations (used on detach/world change) */
    public void cancel(){
        tasks.clear();
    }

    public int pendingTasks(){ return tasks.size(); }

    // ---------------- interpreter core ----------------

    private void resume(Task t){
        int budget = MAX_STEPS_PER_RESUME;
        while(t.alive && !t.stack.isEmpty() && budget-- > 0){
            Frame f = t.stack.get(t.stack.size() - 1);
            if(f.pc >= f.steps.size()){
                t.stack.remove(t.stack.size() - 1);
                continue;
            }
            Object o = f.steps.get(f.pc++);
            Map<String, Object> step = Json.obj(o);
            if(step == null) continue;
            String action = Json.str(step.get("do"), "");
            boolean proceed;
            try {
                proceed = doStep(action, step, t);
            } catch(Exception e){
                Log.w("Script", "step '" + action + "' failed on " + (t.self != null ? t.self.name : "?") + ": " + e);
                proceed = true;
            }
            if(!proceed) return;   // paused on a wait
        }
    }

    /** returns false when the task pauses (wait) */
    private boolean doStep(String action, Map<String, Object> step, Task t){
        Entity self = t.self;
        switch(action){
            // ---- variables / flow ----
            case "set_var": {
                Object v = step.get("value");
                if(v instanceof String s && s.startsWith("=")) v = Expr.eval(s.substring(1), self);
                String key = varKeyOrNull(step);
                if(key != null && vars() != null) vars().set(key, v);
                fireVarChanged(step);
                return true;
            }
            case "add_var": {
                double delta = Json.num(step.get("delta"), 0);
                String k = varKeyOrNull(step);
                if(k != null && vars() != null) vars().add(k, delta);
                fireVarChanged(step);
                return true;
            }
            case "if": {
                String cond = Json.str(step.get("cond"), Json.str(step.get("condition"), "false"));
                boolean ok = Expr.evalBool(cond, self);
                Object branch = ok ? step.get("then") : step.get("else");
                List<Object> list = Json.arr(branch);
                if(list != null && !list.isEmpty()) t.stack.add(new Frame(list));
                return true;
            }
            case "sequence": {
                List<Object> list = Json.arr(step.get("steps"));
                if(list != null && !list.isEmpty()) t.stack.add(new Frame(list));
                return true;
            }
            case "wait": {
                t.delay = Math.max(0f, (float) Json.num(step.get("t"), 0));
                return false;
            }
            case "wait_random": {
                float a = (float) Json.num(step.get("min"), 0);
                float b = (float) Json.num(step.get("max"), 1);
                EngineServices s = svc();
                long seed = (s != null ? s.frameSeed : 1L) ^ (self != null ? self.id : 0) ^ 0x5DEECE66DL;
                t.delay = Math.max(0f, new MathX.Rng(seed).range(Math.min(a, b), Math.max(a, b)));
                return false;
            }

            // ---- animation ----
            case "play_anim": {
                Entity e = resolveTarget(step.get("target"), self);
                com.hollow.anim.Animator an = e != null ? e.get(com.hollow.anim.Animator.class) : null;
                String clip = Json.str(step.get("clip"), "");
                if(an != null && !clip.isEmpty()) an.play(clip);
                else Log.w("Script", "play_anim: no Animator/clip on " + (e != null ? e.name : "?"));
                return true;
            }
            case "stop_anim": {
                Entity e = resolveTarget(step.get("target"), self);
                com.hollow.anim.Animator an = e != null ? e.get(com.hollow.anim.Animator.class) : null;
                if(an != null) an.stop();
                return true;
            }

            // ---- transform ----
            case "teleport": {
                Entity e = resolveTarget(firstOf(step, "target", "entity"), self);
                if(e == null) return true;
                Vec3 pos = resolvePoint(firstOf(step, "to", "point", "at"), self);
                if(pos == null) return true;
                CharacterController cc = e.get(CharacterController.class);
                if(cc != null) cc.teleport(pos);
                else { e.position.set(pos); e.markDirtyDeep(); }
                return true;
            }
            case "move_by": {
                Entity e = resolveTarget(step.get("target"), self);
                Vec3 v = Json.vec3(step.get("v"), new Vec3());
                if(e == null || v.len2() == 0) return true;
                CharacterController cc = e.get(CharacterController.class);
                if(cc != null) cc.move(new Vec3(v.x, v.y, v.z));
                else { e.position.add(v); e.markDirtyDeep(); }
                return true;
            }
            case "look_at": {
                Entity e = resolveTarget(step.get("target"), self);
                Entity at = resolveTarget(firstOf(step, "at", "to"), self);
                if(e != null && at != null && at != e) e.lookAt(at.worldPosition());
                return true;
            }
            case "spawn": {
                World w = world();
                if(w == null) return true;
                Entity spawned = null;
                Map<String, Object> embedded = Json.obj(step.get("entity"));
                if(embedded != null){
                    spawned = Scene.spawnFromJson(w, embedded);
                } else {
                    String prefab = Json.str(step.get("prefab"), null);
                    EngineServices s = svc();
                    if(prefab != null && s != null && s.fs != null){
                        String path = prefab.contains("/") ? prefab : "prefabs/" + prefab + (prefab.endsWith(".json") ? "" : ".json");
                        try {
                            Map<String, Object> def = Json.parseObj(s.fs.readText(path));
                            spawned = Scene.spawnFromJson(w, def);
                        } catch(Exception ex){
                            Log.w("Script", "spawn: prefab '" + prefab + "' unreadable: " + ex.getMessage());
                        }
                    }
                }
                if(spawned != null){
                    Vec3 pos = resolvePoint(step.get("at"), self);
                    if(pos != null){ spawned.position.set(pos); spawned.markDirtyDeep(); }
                }
                return true;
            }
            case "destroy": {
                Entity e = resolveTarget(step.get("target"), self);
                World w = world();
                if(e != null && w != null) w.destroy(e);
                return true;
            }
            case "enable": case "disable": {
                Entity e = resolveTarget(step.get("target"), self);
                if(e != null) e.active = action.equals("enable");
                return true;
            }

            // ---- presentation ----
            case "show_toast": {
                UIManager ui = ui();
                if(ui != null){
                    String text = resolveText(Json.str(step.get("text"), ""));
                    float dur = (float) Json.num(step.get("duration"), 5);
                    ui.toasts().showToast(text, dur);
                }
                return true;
            }
            case "fade": {
                GameApp app = GameApp.instance;
                if(app == null) return true;
                String mode = Json.str(step.get("mode"), Json.str(step.get("in_out"), "out"));
                float speed = (float) Json.num(step.get("speed"), Json.num(step.get("t"), 2));
                if(mode.equalsIgnoreCase("in")) app.fadeIn(speed);
                else app.fadeOut(speed);
                return true;
            }
            case "camera_shake": {
                World w = world();
                if(w == null) return true;
                float amp = (float) Json.num(step.get("amp"), 0.2f);
                float dur = (float) Json.num(step.get("t"), 0.3f);
                Object prev = w.blackboard.get("__shake");
                double prevAmp = prev instanceof Map ? Json.num(((Map<?, ?>) prev).get("amp"), 0) : 0;
                double prevLeft = prev instanceof Map ? Json.num(((Map<?, ?>) prev).get("left"), 0) : 0;
                Map<String, Object> shake = new LinkedHashMap<>();
                shake.put("amp", (double) Math.max(amp, (float) prevAmp));
                shake.put("t", (double) dur);
                shake.put("left", (double) Math.max(dur, (float) prevLeft));
                w.blackboard.put("__shake", shake);
                return true;
            }
            case "set_light": {
                Entity e = resolveTarget(step.get("target"), self);
                LightComponent l = e != null ? e.get(LightComponent.class) : null;
                if(l == null) return true;
                World w = world();
                String memoKey = "light_prev_" + e.id;
                if(step.containsKey("on")){
                    boolean on = Json.bool(step.get("on"), true);
                    if(w != null){
                        if(on){
                            Object prev = w.blackboard.remove(memoKey);
                            if(prev instanceof Number n && l.intensity <= 0.0001f) l.intensity = n.floatValue();
                        } else if(l.intensity > 0.0001f){
                            w.blackboard.put(memoKey, (double) l.intensity);
                            l.intensity = 0f;
                        }
                    }
                }
                List<Object> col = Json.arr(step.get("color"));
                if(col != null && col.size() >= 3){
                    l.color.set((float) Json.num(col.get(0), 1), (float) Json.num(col.get(1), 1), (float) Json.num(col.get(2), 1), 1);
                }
                if(step.containsKey("intensity")) l.intensity = (float) Json.num(step.get("intensity"), l.intensity);
                return true;
            }
            case "set_active_camera": {
                Entity e = resolveTarget(step.get("entity"), self);
                GameApp app = GameApp.instance;
                if(e != null && app != null){
                    app.activeCamera = e;
                    CameraComponent cam = e.get(CameraComponent.class);
                    if(cam != null) cam.active = true;
                }
                return true;
            }

            // ---- inventory / quests / persistence ----
            case "give_item": {
                EngineServices s = svc();
                String key = Json.str(step.get("key"), null);
                if(s != null && s.save != null && key != null && !s.save.inventory.contains(key)) s.save.inventory.add(key);
                return true;
            }
            case "remove_item": {
                EngineServices s = svc();
                String key = Json.str(step.get("key"), null);
                if(s != null && s.save != null) s.save.inventory.remove(key);
                return true;
            }
            case "quest": {
                String id = Json.str(step.get("id"), "");
                Object state = step.get("state");
                if(!id.isEmpty() && vars() != null) vars().set("quest_" + id, state != null ? state : "active");
                return true;
            }
            case "save": {
                EngineServices s = svc();
                GameApp app = GameApp.instance;
                if(s != null && s.save != null && app != null)
                    s.save.save((int) Json.num(step.get("slot"), s.save.activeSlot), app.world, app.player);
                return true;
            }
            case "load": {
                EngineServices s = svc();
                GameApp app = GameApp.instance;
                if(s != null && s.save != null && app != null)
                    s.save.load((int) Json.num(step.get("slot"), s.save.activeSlot), app.world);
                return true;
            }
            case "load_scene": {
                GameApp app = GameApp.instance;
                String path = Json.str(step.get("path"), "");
                if(app != null && !path.isEmpty()) app.loadScene(path, null);
                return true;
            }
            case "cinematic": {
                EngineServices s = svc();
                String path = Json.str(step.get("path"), "");
                if(s != null && s.sequencer != null && !path.isEmpty()){
                    s.sequencer.load(path, s.fs);
                    s.sequencer.play(world(), null);
                }
                return true;
            }

            // ---- audio ----
            case "play_sound": {
                EngineServices s = svc();
                String asset = Json.str(step.get("asset"), "");
                if(s != null && s.audio != null && !asset.isEmpty())
                    s.audio.play(asset, new com.hollow.audio.AudioEngine.PlayParams()
                            .bus((int) Json.num(step.get("bus"), com.hollow.audio.AudioEngine.BUS_SFX))
                            .volume((float) Json.num(step.get("volume"), 1)));
                return true;
            }
            case "play_music": {
                EngineServices s = svc();
                String asset = Json.str(step.get("asset"), "");
                if(s != null && s.audio != null && !asset.isEmpty())
                    s.audio.play(asset, new com.hollow.audio.AudioEngine.PlayParams()
                            .bus(com.hollow.audio.AudioEngine.BUS_MUSIC)
                            .volume((float) Json.num(step.get("volume"), 1)).loop());
                return true;
            }
            case "stop_music": {
                EngineServices s = svc();
                if(s != null && s.audio != null) s.audio.stopBus(com.hollow.audio.AudioEngine.BUS_MUSIC);
                return true;
            }

            // ---- events / misc ----
            case "message": {
                EngineServices s = svc();
                String name = Json.str(step.get("name"), "");
                Map<String, Object> data = Json.obj(step.get("data"));
                Map<String, Object> payload = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
                if(!name.isEmpty()) payload.put("name", name);
                if(s != null && s.events != null) s.events.fire("message", payload);
                return true;
            }
            case "log": {
                Log.i("Script", resolveText(Json.str(step.get("text"), Json.str(step.get("msg"), ""))));
                return true;
            }
            case "fire": {
                EngineServices s = svc();
                String ev = Json.str(step.get("event"), "");
                if(s != null && s.events != null && !ev.isEmpty())
                    s.events.fire(ev, Json.obj(step.get("data")));
                return true;
            }
            case "debug_draw": {
                World w = world();
                if(w != null){
                    List<Object> lines = Json.arr(w.blackboard.get("__debugLines"));
                    if(lines == null){ lines = new ArrayList<>(); w.blackboard.put("__debugLines", lines); }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("a", step.get("a"));
                    entry.put("b", step.get("b"));
                    entry.put("color", step.get("color"));
                    lines.add(entry);
                    if(lines.size() > 512) lines.remove(0);
                }
                return true;
            }
            default:
                Log.w("Script", "unknown step '" + action + "' (skipped)");
                return true;
        }
    }

    // ---------------- helpers ----------------

    private static Object firstOf(Map<String, Object> step, String... keys){
        for(String k : keys){ Object v = step.get(k); if(v != null) return v; }
        return null;
    }

    private static String varKeyOrNull(Map<String, Object> step){
        return Json.str(step.get("name"), Json.str(step.get("key"), null));
    }

    private void fireVarChanged(Map<String, Object> step){
        EngineServices s = svc();
        String k = varKeyOrNull(step);
        if(s != null && s.events != null && k != null){
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("key", k);
            Object v = vars() != null ? vars().get(k) : null;
            if(v != null) d.put("value", v);
            s.events.fire("var_changed", d);
        }
    }

    EngineServices svc(){
        GameApp app = GameApp.instance;
        return app != null ? app.services() : null;
    }
    World world(){
        GameApp app = GameApp.instance;
        return app != null ? app.world : null;
    }
    com.hollow.script.VarTable vars(){
        EngineServices s = svc();
        return s != null ? s.vars : null;
    }
    UIManager ui(){
        EngineServices s = svc();
        return s != null ? s.ui : null;
    }

    /** "@i18n:key" → localization table, otherwise literal */
    String resolveText(String s){
        if(s == null) return "";
        if(s.startsWith("@i18n:")){
            EngineServices sv = svc();
            String key = s.substring(6);
            return sv != null && sv.i18n != null ? sv.i18n.get(key, key) : key;
        }
        return s;
    }

    /** "target" may be an entity name, "@self", "@player", "@camera" (or null → self) */
    Entity resolveTarget(Object spec, Entity self){
        if(spec == null) return self;
        String s = String.valueOf(spec);
        World w = world();
        switch(s){
            case "@self": return self;
            case "@player": {
                GameApp app = GameApp.instance;
                if(app != null && app.player != null) return app.player;
                if(w != null){
                    List<Entity> ps = w.byTag("player");
                    for(Entity e : ps) if(e.active && !e.destroyed) return e;
                }
                return null;
            }
            case "@camera": {
                GameApp app = GameApp.instance;
                return app != null ? app.activeCamera : null;
            }
            default: return w != null ? w.find(s) : null;
        }
    }

    /** step point parameter: [x,y,z] array, entity name, "@self"/"@player" → that entity position */
    Vec3 resolvePoint(Object spec, Entity self){
        if(spec == null) return null;
        if(spec instanceof List) return Json.vec3(spec, null);
        Entity e = resolveTarget(spec, self);
        return e != null ? e.worldPosition() : null;
    }
}
