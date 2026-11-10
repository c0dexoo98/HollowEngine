package com.hollow.ai;

import com.hollow.core.Json;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Behavior tree engine (Agent C). JSON format (docs/SPEC.md):
 * <pre>
 * { "root": "seq1", "nodes": { "seq1": {"type":"sequence", "children":["sel1","act_attack"]},
 *   "sel1": {"type":"selector", "children":["cond_seen","act_patrol"]},
 *   "cond_seen": {"type":"condition","check":"seen"},
 *   "act_patrol": {"type":"action","do":"patrol"},
 *   "w": {"type":"wait","seconds":2} } }
 * </pre>
 * Node types: selector (reactive), sequence (stateful), parallel(successAll|any),
 * inverter, repeat(n|forever), wait(seconds or min/max), condition, action,
 * set_bb {key,value}, fire_event {name}.
 * Status codes: SUCCESS / FAILURE / RUNNING. Node params may live under "params" {...}
 * or inline on the node (e.g. {"type":"condition","check":"target_in_range","range":3}).
 */
public final class BehaviorTree {
    public static final int SUCCESS = 1, FAILURE = 2, RUNNING = 3;

    public String root = "";
    public final Map<String, BTNode> nodes = new LinkedHashMap<>();

    public static final class BTNode {
        public String id, type, check, action, key, event, policy;
        public Object value;
        public float seconds = -1f, min = -1f, max = -1f;
        public int repeat = -1;
        public boolean forever;
        public final List<String> children = new ArrayList<>();
        public final Map<String,Object> params = new LinkedHashMap<>();
        public Map<String,Object> raw = new LinkedHashMap<>();
    }

    // per-node runtime state
    private static final class NS {
        int childIdx;
        float elapsed;
        float waitTarget = -1f;
        int count;
        int[] order;                    // random_selector shuffle order
    }
    private final Map<String, NS> state = new HashMap<>();
    private long salt;

    public static BehaviorTree parse(Map<String,Object> json){
        BehaviorTree t = new BehaviorTree();
        if(json == null) return t;
        t.root = Json.str(json.get("root"), "");
        Map<String,Object> nds = Json.obj(json.get("nodes"));
        if(nds == null) return t;
        for(Map.Entry<String,Object> e : nds.entrySet()){
            Map<String,Object> m = Json.obj(e.getValue());
            if(m == null) continue;
            BTNode n = new BTNode();
            n.id = e.getKey();
            n.type = Json.str(m.get("type"), "");
            n.check = Json.str(m.get("check"), null);
            n.action = Json.str(m.get("do"), Json.str(m.get("action"), null));
            n.key = Json.str(m.get("key"), null);
            n.event = Json.str(m.get("name"), Json.str(m.get("event"), null));
            n.policy = Json.str(m.get("policy"), "all");
            n.value = m.get("value");
            n.seconds = (float) Json.num(m.get("seconds"), -1);
            if(m.containsKey("min")) n.min = (float) Json.num(m.get("min"), -1);
            if(m.containsKey("max")) n.max = (float) Json.num(m.get("max"), -1);
            n.repeat = Json.int32(m.get("n"), Json.int32(m.get("repeat"), -1));
            n.forever = Json.bool(m.get("forever"), false);
            Map<String,Object> pr = Json.obj(m.get("params"));
            if(pr != null) n.params.putAll(pr);
            List<Object> ch = Json.arr(m.get("children"));
            if(ch != null) for(Object c : ch){ String s = Json.str(c, null); if(s != null) n.children.add(s); }
            n.raw = m;
            t.nodes.put(n.id, n);
        }
        return t;
    }

    public void reset(){ state.clear(); }

    /** tick from the root; called once per agent update */
    public int tick(Agent agent, float dt){
        salt++;
        if(root == null || !nodes.containsKey(root)) return FAILURE;
        return run(root, agent, dt);
    }

    /** run one node to completion synchronously (scripts/debug); RUNNING counts as false */
    public boolean execute(String id, Agent agent){
        if(id == null || !nodes.containsKey(id)) return false;
        return run(id, agent, 0f) == SUCCESS;
    }

    private NS ns(String id){ return state.computeIfAbsent(id, k -> new NS()); }

    private void resetSub(String id){
        NS s = state.remove(id);
        BTNode n = nodes.get(id);
        if(s == null || n == null) return;
        for(String c : n.children) resetSub(c);
    }

    private int run(String id, Agent a, float dt){
        BTNode n = nodes.get(id);
        if(n == null) return FAILURE;
        try {
            switch(n.type){
                case "selector": return runSelector(n, a, dt);
                case "random_selector": return runRandomSelector(n, a, dt);
                case "sequence": return runSequence(n, a, dt);
                case "parallel": return runParallel(n, a, dt);
                case "inverter": {
                    if(n.children.isEmpty()) return FAILURE;
                    int r = run(n.children.get(0), a, dt);
                    if(r == SUCCESS) return FAILURE;
                    if(r == FAILURE) return SUCCESS;
                    return RUNNING;
                }
                case "force_success": {
                    if(n.children.isEmpty()) return FAILURE;
                    int r = run(n.children.get(0), a, dt);
                    return r == RUNNING ? RUNNING : SUCCESS;
                }
                case "force_failure": {
                    if(n.children.isEmpty()) return SUCCESS;
                    int r = run(n.children.get(0), a, dt);
                    return r == RUNNING ? RUNNING : FAILURE;
                }
                case "cooldown": {
                    NS s = ns(n.id);
                    if(s.elapsed > 0f){
                        s.elapsed -= dt;
                        return FAILURE;
                    }
                    int r = run(n.children.get(0), a, dt);
                    if(r == SUCCESS){
                        s.elapsed = n.seconds >= 0f ? n.seconds : 1f;
                        resetSub(n.children.get(0));
                    }
                    return r;
                }
                case "time_limit": {
                    NS s = ns(n.id);
                    s.elapsed += dt;
                    float lim = n.seconds >= 0f ? n.seconds : 5f;
                    if(s.elapsed > lim){
                        s.elapsed = 0f;
                        resetSub(n.children.get(0));
                        return FAILURE;
                    }
                    int r = run(n.children.get(0), a, dt);
                    if(r != RUNNING) s.elapsed = 0f;
                    return r;
                }
                case "service": {
                    // runs child action every N seconds without blocking (returns child status)
                    NS s = ns(n.id);
                    s.elapsed += dt;
                    float period = n.seconds >= 0f ? n.seconds : 1f;
                    if(s.elapsed >= period){
                        s.elapsed = 0f;
                        String name = n.action;
                        if(name != null){
                            Agent.Action act = a.actions.get(name);
                            if(act != null){
                                a.nodeParams = n.params;
                                a.nodeRaw = n.raw;
                                act.run(a, n.params);
                            }
                        }
                    }
                    return n.children.isEmpty() ? SUCCESS : run(n.children.get(0), a, dt);
                }
                case "repeat": return runRepeat(n, a, dt);
                case "wait": return runWait(n, a, dt);
                case "condition": {
                    if(n.check == null) return FAILURE;
                    Agent.Check c = a.checks.get(n.check);
                    if(c == null) return FAILURE;
                    a.nodeParams = n.params;
                    a.nodeRaw = n.raw;
                    return c.test(a) ? SUCCESS : FAILURE;
                }
                case "action": {
                    String name = n.action != null ? n.action : n.check;
                    if(name == null) return FAILURE;
                    Agent.Action act = a.actions.get(name);
                    if(act == null){
                        com.hollow.core.Log.w("BehaviorTree", "unknown action '" + name + "' in tree");
                        return FAILURE;
                    }
                    a.nodeParams = n.params;
                    a.nodeRaw = n.raw;
                    a.actionRunning = false;
                    boolean ok = act.run(a, n.params);
                    return a.actionRunning ? RUNNING : (ok ? SUCCESS : FAILURE);
                }
                case "set_bb": {
                    if(n.key != null) a.bb.set(n.key, n.value);
                    return SUCCESS;
                }
                case "fire_event": {
                    String ev = n.event != null ? n.event : n.key;
                    if(ev != null){
                        Map<String,Object> data = new LinkedHashMap<>();
                        data.put("agent", a.entity != null ? a.entity.name : "?");
                        if(n.value != null) data.put("data", n.value);
                        a.fireEvent(ev, data);
                    }
                    return SUCCESS;
                }
                default:
                    com.hollow.core.Log.w("BehaviorTree", "unknown node type '" + n.type + "'");
                    return FAILURE;
            }
        } catch(Exception e){
            com.hollow.core.Log.e("BehaviorTree", "node '" + id + "' failed: " + e);
            return FAILURE;
        }
    }

    /** reactive selector: re-evaluated from the first child every tick (preemption) */
    private int runSelector(BTNode n, Agent a, float dt){
        NS s = ns(n.id);
        int prevRunning = s.childIdx;
        for(int i = 0; i < n.children.size(); i++){
            int r = run(n.children.get(i), a, dt);
            if(r == RUNNING){
                if(prevRunning >= 0 && prevRunning != i) resetSub(n.children.get(prevRunning));
                s.childIdx = i;
                return RUNNING;
            }
            if(r == SUCCESS){
                if(prevRunning >= 0 && prevRunning != i) resetSub(n.children.get(prevRunning));
                s.childIdx = i;
                return SUCCESS;
            }
        }
        s.childIdx = -1;
        return FAILURE;
    }

    /** shuffled priority selector — reshuffles once per entry (memory-of-choice semantics) */
    private int runRandomSelector(BTNode n, Agent a, float dt){
        NS s = ns(n.id);
        if(s.count == 0 || s.childIdx < 0){
            // fresh entry: build a shuffled order
            if(s.order == null || s.order.length != n.children.size()){
                s.order = new int[n.children.size()];
            }
            for(int i = 0; i < s.order.length; i++) s.order[i] = i;
            long seed = a.seed() ^ n.id.hashCode() ^ (salt * 0x9E3779B97F4A7C15L) ^ (a.entity != null ? a.entity.id * 7919L : 0L);
            MathX.Rng rng = new MathX.Rng(seed);
            for(int i = s.order.length - 1; i > 0; i--){
                int j = rng.nextInt(i + 1);
                int tmp = s.order[i]; s.order[i] = s.order[j]; s.order[j] = tmp;
            }
            s.childIdx = 0;
            s.count = 1;
        }
        int prevRunning = s.childIdx;
        while(s.childIdx < n.children.size()){
            String child = n.children.get(s.order[s.childIdx]);
            int r = run(child, a, dt);
            if(r == RUNNING){
                if(prevRunning >= 0 && prevRunning != s.childIdx) resetSub(n.children.get(s.order[prevRunning]));
                return RUNNING;
            }
            if(r == SUCCESS){
                if(prevRunning >= 0 && prevRunning != s.childIdx) resetSub(n.children.get(s.order[prevRunning]));
                s.childIdx = -1;
                s.count = 0;
                return SUCCESS;
            }
            s.childIdx++;
        }
        s.childIdx = -1;
        s.count = 0;
        return FAILURE;
    }

    /** stateful sequence: remembers the running child across ticks */
    private int runSequence(BTNode n, Agent a, float dt){
        NS s = ns(n.id);
        if(s.childIdx >= n.children.size()) s.childIdx = 0;
        while(s.childIdx < n.children.size()){
            String child = n.children.get(s.childIdx);
            int r = run(child, a, dt);
            if(r == RUNNING) return RUNNING;
            if(r == FAILURE){
                resetSub(child);
                s.childIdx = 0;
                return FAILURE;
            }
            resetSub(child);
            s.childIdx++;
        }
        s.childIdx = 0;
        return SUCCESS;
    }

    private int runParallel(BTNode n, Agent a, float dt){
        if(n.children.isEmpty()) return FAILURE;
        boolean anySuccess = false, anyFailure = false, anyRunning = false;
        int succ = 0, fail = 0;
        for(String c : n.children){
            int r = run(c, a, dt);
            if(r == RUNNING) anyRunning = true;
            else if(r == SUCCESS){ anySuccess = true; succ++; }
            else { anyFailure = true; fail++; }
        }
        boolean anyPolicy = "any".equalsIgnoreCase(n.policy);
        if(anyPolicy){
            if(anySuccess){ for(String c : n.children) resetSub(c); return SUCCESS; }
            if(!anyRunning && fail == n.children.size()) return FAILURE;
            return RUNNING;
        }
        if(anyFailure){ for(String c : n.children) resetSub(c); return FAILURE; }
        if(!anyRunning && succ == n.children.size()) return SUCCESS;
        return RUNNING;
    }

    private int runRepeat(BTNode n, Agent a, float dt){
        if(n.children.isEmpty()) return FAILURE;
        NS s = ns(n.id);
        int times = n.forever ? Integer.MAX_VALUE : Math.max(1, n.repeat);
        for(int guard = 0; guard < Math.min(times, 64); guard++){
            int r = run(n.children.get(0), a, dt);
            if(r == RUNNING) return RUNNING;
            s.count++;
            resetSub(n.children.get(0));
            if(!n.forever && s.count >= times){
                s.count = 0;
                return SUCCESS;
            }
        }
        return RUNNING;
    }

    private int runWait(BTNode n, Agent a, float dt){
        NS s = ns(n.id);
        if(s.waitTarget < 0f){
            if(n.seconds >= 0f) s.waitTarget = n.seconds;
            else if(n.min >= 0f || n.max >= 0f){
                float lo = Math.max(0f, n.min);
                float hi = Math.max(lo, n.max);
                long seed = a.seed() ^ n.id.hashCode() ^ (salt * 0x9E3779B97F4A7C15L);
                s.waitTarget = new MathX.Rng(seed).range(lo, hi);
            } else s.waitTarget = 1f;
        }
        s.elapsed += dt;
        if(s.elapsed >= s.waitTarget){
            s.elapsed = 0f;
            s.waitTarget = -1f;
            return SUCCESS;
        }
        return RUNNING;
    }
}
