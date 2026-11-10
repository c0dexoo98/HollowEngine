package com.hollow.script;

import com.hollow.core.Json;
import com.hollow.core.JsonW;
import com.hollow.core.JsonV;
import com.hollow.scene.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** FlowScript: data-driven gameplay graphs (Blueprint-lite).
 *  graphs: [{on:{event, zone?, name?, key?, t?, repeat?}, steps:[...]}]
 *  Trigger kinds: on_start, on_update_every {t}, on_timer {t, repeat}, on_trigger_enter/exit {zone},
 *  on_interact, on_message {name}, on_var {key}, on_anim_end, on_damage, on_death,
 *  plus any custom EventBus event name.
 *  API is FINAL. */
public final class ScriptComponent extends Component {
    public List<Object> graphs = new ArrayList<>();   // [{on:{...}, steps:[...]}]

    transient StepRunner runner;
    transient boolean started = false;
    transient boolean detached = false;
    transient final List<GraphBinding> bindings = new ArrayList<>();
    /** keeps references so lambdas stay reachable; onDetach deactivates them via flag */
    transient final List<Object> listeners = new ArrayList<>();

    static final class GraphBinding {
        Map<String, Object> graph;
        Map<String, Object> on;
        String base;         // normalized trigger name (no "on_" prefix)
        float period;        // update_every / timer period
        boolean repeat;
        float elapsed;
        boolean fired;
    }

    @Override public void onAttach(){
        runner = new FlowRunner();
        detached = false;
        bindings.clear();
        listeners.clear();
        com.hollow.script.EventBus ev = events();
        for(Object o : graphs){
            Map<String, Object> g = Json.obj(o);
            if(g == null) continue;
            Map<String, Object> on = Json.obj(g.get("on"));
            if(on == null) continue;
            String evName = Json.str(on.get("event"), "");
            String base = evName.startsWith("on_") ? evName.substring(3) : evName;
            GraphBinding b = new GraphBinding();
            b.graph = g;
            b.on = on;
            b.base = base;
            if(base.equals("update_every") || base.equals("timer")){
                b.period = (float) Json.num(on.get("t"), Json.num(on.get("seconds"), 0));
                b.repeat = Json.bool(on.get("repeat"), base.equals("update_every"));
            }
            bindings.add(b);
            // subscribe bus triggers (start / update_every / timer are internal)
            if(ev != null && !base.equals("start") && !base.equals("update_every") && !base.equals("timer") && !evName.isEmpty()){
                final GraphBinding fb = b;
                EventBus.Listener l = (name, data) -> {
                    if(detached) return;
                    if(!filterMatches(fb.on, data)) return;
                    run(fb);
                };
                ev.listen(base, l);
                listeners.add(l);
            }
        }
    }

    @Override public void onUpdate(float dt){
        if(detached || runner == null) return;
        // on_start fires on the first logic tick after the world loads
        if(!started){
            started = true;
            for(GraphBinding b : bindings) if(b.base.equals("start")) run(b);
        }
        try { runner.update(dt); }
        catch(Exception e){ com.hollow.core.Log.w("Script", "runner update failed on " + entityName() + ": " + e); }
        // timers
        for(GraphBinding b : bindings){
            if(b.base.equals("update_every")){
                b.elapsed += dt;
                if(b.period > 0 && b.elapsed >= b.period){ b.elapsed = 0; run(b); }
            } else if(b.base.equals("timer")){
                if(b.fired && !b.repeat) continue;
                b.elapsed += dt;
                if(b.period > 0 && b.elapsed >= b.period){ b.elapsed = 0; b.fired = true; run(b); }
            }
        }
    }

    @Override public void onDetach(){
        detached = true;   // subscribed listeners become no-ops (EventBus has no unlisten API)
        listeners.clear();
        if(runner instanceof FlowRunner fr) fr.cancel();
    }

    /** fire a custom message into this script ("on_message {name}") */
    public void message(String name, Map<String, Object> data){
        if(detached || runner == null) return;
        for(GraphBinding b : bindings){
            if(!b.base.equals("message")) continue;
            Object want = b.on.get("name");
            if(want != null && !String.valueOf(want).equals(name)) continue;
            run(b);
        }
    }

    /** test hook: run the steps of the first graph matching a trigger base */
    public boolean fireTrigger(String base){
        if(detached || runner == null) return false;
        boolean any = false;
        for(GraphBinding b : bindings){
            if(b.base.equals(base)){ run(b); any = true; }
        }
        return any;
    }

    private void run(GraphBinding b){
        if(runner != null && entity != null && !entity.destroyed){
            try { runner.runSteps(b.graph.get("steps"), entity); }
            catch(Exception e){ com.hollow.core.Log.w("Script", "graph start failed on " + entityName() + ": " + e); }
        }
    }

    /** on-map filters: zone / name / key / entity must all match event data (when present).
     *  "@self" resolves to this entity's name. */
    boolean filterMatches(Map<String, Object> on, Map<String, Object> data){
        String selfName = entity != null ? entity.name : null;
        for(String k : new String[]{"zone", "name", "key", "entity", "tag"}){
            Object want = on.get(k);
            if(want == null) continue;
            if(data == null) return false;
            Object got = data.get(k);
            if(got == null) return false;
            String w = String.valueOf(want);
            if(w.equals("@self")){
                if(selfName == null || !selfName.equals(String.valueOf(got))) return false;
            } else if(!w.equals(String.valueOf(got))) return false;
        }
        return true;
    }

    private EventBus events(){
        return com.hollow.game.GameApp.instance != null && com.hollow.game.GameApp.instance.services() != null
                ? com.hollow.game.GameApp.instance.services().events : null;
    }

    private String entityName(){ return entity != null ? entity.name : "?"; }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Script");
        if(!graphs.isEmpty()) w.kv("graphs", Json.write(graphs));
        w.endObj();
    }
    @Override public void load(JsonV n){
        List<Object> g = n.list("graphs");
        if(g != null) graphs = g;
        // inline shortcut: {"type":"Script","on":{...},"steps":[...]}
        if(n.has("on") && n.has("steps")){
            Map<String,Object> inline = new java.util.LinkedHashMap<>();
            inline.put("on", n.get("on"));
            inline.put("steps", n.get("steps"));
            Object els = n.get("else");
            if(els != null) inline.put("else", els);
            graphs.add(inline);
        }
    }
}
