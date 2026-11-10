package com.hollow.anim;

import com.hollow.core.Json;
import com.hollow.core.JsonW;
import com.hollow.core.JsonV;
import com.hollow.scene.Component;
import com.hollow.scene.MeshComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Animation state machine over Animator (Agent C implementation).
 *
 *  States loaded from JSON: {name, clip, speed, loop, transitions:[{to, if:{var,op,value}} | {to, event:"x"}]}.
 *  - setState(name) plays the state's clip on the entity's Animator with a 0.2s crossfade.
 *  - Conditions are evaluated against the global game vars (svc.vars) every ~0.1s.
 *  - fire(event) queues an engine-local event; transitions with a matching {event} consume it.
 *  Supported ops: ==, !=, <, >, <=, >= (numeric with epsilon, boolean and string equality).
 */
public final class StateMachine extends Component {
    public static final class State {
        public String name, clip;
        public float speed = 1f;
        public boolean loop = true;
        public List<Object> transitions = new ArrayList<>(); // [{to, if:{var,op,value}} | {to, event}]
    }
    public final Map<String, State> states = new LinkedHashMap<>();
    public String initial = "";
    public String current = "";

    private boolean started;
    private float condTimer;
    private final List<String> pendingEvents = new ArrayList<>();

    public void fire(String event){
        if(event != null && !event.isEmpty()) pendingEvents.add(event);
    }

    public void setState(String name){
        State s = states.get(name);
        if(s == null || entity == null) return;
        current = name;
        Animator a = entity.get(Animator.class);
        if(a != null){
            if(a.modelPath == null || a.modelPath.isEmpty()){
                MeshComponent mc = entity.get(MeshComponent.class);
                if(mc != null && mc.path != null && !mc.path.isEmpty()) a.modelPath = mc.path;
            }
            if(s.clip != null && !s.clip.isEmpty()){
                a.speed = s.speed;
                a.loop = s.loop;
                a.play(s.clip, 0.2f);
            } else {
                a.stop();
            }
        }
    }

    @Override public void onUpdate(float dt){
        if(entity == null) return;
        if(!started){
            if(states.isEmpty()) return;
            started = true;
            String first = !initial.isEmpty() && states.containsKey(initial) ? initial : states.keySet().iterator().next();
            setState(first);
        }
        State cur = states.get(current);
        if(cur == null) return;
        condTimer += dt;
        if(condTimer < 0.1f) return;    // conditions checked every ~0.1s
        condTimer = 0f;
        try {
            for(Object o : cur.transitions){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String to = Json.str(m.get("to"), "");
                if(to.isEmpty() || !states.containsKey(to)) continue;
                String ev = Json.str(m.get("event"), Json.str(m.get("on"), null));
                if(ev != null){
                    if(pendingEvents.contains(ev)){
                        pendingEvents.remove(ev);
                        setState(to);
                        return;
                    }
                    continue;
                }
                if(m.containsKey("if") && evalConditions(m.get("if"))){
                    setState(to);
                    return;
                }
            }
        } catch(Exception e){
            com.hollow.core.Log.w("StateMachine", "transition check failed on " + current + ": " + e);
        } finally {
            pendingEvents.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean evalConditions(Object cond){
        if(cond instanceof Map){
            return evalCondition((Map<String,Object>) cond);
        }
        List<Object> list = Json.arr(cond);
        if(list == null) return false;
        for(Object o : list){
            Map<String,Object> m = Json.obj(o);
            if(m == null || !evalCondition(m)) return false;
        }
        return true;
    }

    private boolean evalCondition(Map<String,Object> c){
        String var = Json.str(c.get("var"), Json.str(c.get("key"), null));
        if(var == null) return false;
        String op = Json.str(c.get("op"), "==");
        Object expected = c.get("value");
        Object actual = null;
        com.hollow.game.EngineServices svc = services();
        if(svc != null && svc.vars != null) actual = svc.vars.get(var);
        return compare(actual, op, expected);
    }

    static boolean compare(Object actual, String op, Object expected){
        if(actual instanceof Number && expected instanceof Number){
            double a = ((Number) actual).doubleValue(), b = ((Number) expected).doubleValue();
            switch(op){
                case "==": return Math.abs(a - b) < 1e-6;
                case "!=": return Math.abs(a - b) >= 1e-6;
                case "<":  return a < b;
                case ">":  return a > b;
                case "<=": return a <= b;
                case ">=": return a >= b;
                default: return false;
            }
        }
        if(actual instanceof Boolean && expected instanceof Boolean){
            boolean a = (Boolean) actual, b = (Boolean) expected;
            switch(op){
                case "==": return a == b;
                case "!=": return a != b;
                default: return false;
            }
        }
        if(actual == null) return op.equals("!=") && expected != null;
        if(op.equals("==")) return String.valueOf(actual).equals(String.valueOf(expected));
        if(op.equals("!=")) return !String.valueOf(actual).equals(String.valueOf(expected));
        if(actual instanceof Number && expected instanceof String){
            try {
                double a = ((Number) actual).doubleValue(), b = Double.parseDouble((String) expected);
                switch(op){
                    case "<": return a < b;
                    case ">": return a > b;
                    case "<=": return a <= b;
                    case ">=": return a >= b;
                    default: return false;
                }
            } catch(NumberFormatException ignored){ return false; }
        }
        return false;
    }

    com.hollow.game.EngineServices services(){
        return com.hollow.game.GameApp.instance != null ? com.hollow.game.GameApp.instance.services() : null;
    }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","StateMachine").kv("initial",initial).kv("state",current);
        w.arr("states");
        for(State s : states.values()){
            w.obj(); w.kv("name",s.name).kv("clip",s.clip).kv("speed",s.speed).kv("loop",s.loop);
            w.arr("transitions");
            for(Object t : s.transitions) w.valueRaw(Json.write(t));
            w.endArr();
            w.endObj();
        }
        w.endArr();
        w.endObj();
    }
    @Override public void load(JsonV n){
        initial = n.str("initial","");
        current = n.str("state", initial);
        states.clear();
        pendingEvents.clear();
        started = false;
        List<Object> sts = n.list("states");
        if(sts == null) return;
        for(Object o : sts){
            Map<String,Object> m = Json.obj(o);
            if(m == null) continue;
            State s = new State();
            s.name = Json.str(m.get("name"), "");
            s.clip = Json.str(m.get("clip"), "");
            s.loop = Json.bool(m.get("loop"), true);
            s.speed = (float) Json.num(m.get("speed"), 1);
            List<Object> tr = Json.arr(m.get("transitions"));
            if(tr != null) s.transitions.addAll(tr);
            if(!s.name.isEmpty()) states.put(s.name, s);
        }
    }
}
