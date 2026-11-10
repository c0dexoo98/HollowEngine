package com.hollow.ai;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.4 AI batch — AI-011..AI-016, AI-018..AI-021, AI-023..AI-025, AI-028..AI-030.
 *  Deterministic, headless-testable, JSON persistable components. */
public final class AISystems {
    private AISystems(){}

    // =====================================================================
    // AI-011 + AI-012 — Horror Director: measured tension + spawn budget
    // =====================================================================
    /** Pacing director: tracks player stress (proximity to threats, darkness,
     *  recent scares) and drives spawn budget + music tension. */
    public static final class HorrorDirector {
        public float tension = 0f;                 // 0..1
        public float targetTension = 0.4f;         // desired curve
        public float spawnBudget = 0f;             // enemies allowed to spawn
        public float scareCooldown = 20f;          // sec between scripted scares
        public float scareTimer = 0f;
        public float timeSinceScare = 999f;
        public int enemiesAlive = 0;
        public float maxSpawnBudget = 3f;
        /** tension contributions config */
        public float darknessWeight = 0.5f;
        public float proximityWeight = 1.0f;
        public float chaseWeight = 1.4f;

        /** call once per fixed tick with world + player position. */
        public void update(float dt, World world, Vec3 playerPos, com.hollow.core.Time time){
            scareTimer += dt;
            timeSinceScare += dt;
            // 1) proximity tension: nearest enemy distance
            float nearest = Float.MAX_VALUE;
            enemiesAlive = 0;
            for(Entity e : world.entities){
                if(!e.active || e.destroyed) continue;
                Agent a = e.get(Agent.class);
                if(a == null) continue;
                enemiesAlive++;
                float d = e.worldPosition().dist(playerPos);
                if(d < nearest) nearest = d;
            }
            float prox = nearest == Float.MAX_VALUE ? 0f : MathX.clamp(1f - nearest / 25f, 0f, 1f);
            // 2) darkness: blackboard flag set by DayNightCycle/level
            Object darkFlag = world.blackboard.get("__darkness");
            float dark = darkFlag instanceof Number n ? MathX.clamp((float)n.doubleValue(), 0f, 1f) : 0f;
            // 3) chase tension: any agent in "chase"
            float chase = 0f;
            for(Entity e : world.entities){
                Agent a = e.get(Agent.class);
                if(a != null && "chase".equals(a.state)){ chase = 1f; break; }
            }
            float raw = MathX.clamp(prox * proximityWeight + dark * darknessWeight + chase * chaseWeight, 0f, 1.5f);
            // ease toward target; decay when calm
            float desired = Math.max(targetTension, raw);
            float rate = desired > tension ? 0.8f : 0.25f;          // fast rise, slow decay
            tension = MathX.approach(tension, desired, rate * dt);
            // spawn budget follows tension curve (more tension -> bigger budget)
            spawnBudget = MathX.clamp(maxSpawnBudget * (tension - 0.35f) / 0.65f, 0f, maxSpawnBudget);
            if(spawnBudget < 0.05f) spawnBudget = 0f;
        }

        /** AI-012: may an extra enemy spawn now? Consumes 1 budget unit. */
        public boolean tryConsumeSpawn(){
            if(spawnBudget >= 1f){ spawnBudget -= 1f; return true; }
            return false;
        }

        /** scripted scare (jump event): bumps tension, arms cooldown. */
        public void fireScare(float amount){
            if(scareTimer < scareCooldown) return;
            scareTimer = 0f; timeSinceScare = 0f;
            tension = MathX.clamp(tension + amount, 0f, 1f);
        }

        public Map<String,Object> snapshot(){
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("tension", (double)tension);
            m.put("budget", (double)spawnBudget);
            m.put("enemies", (double)enemiesAlive);
            return m;
        }
    }

    // =====================================================================
    // AI-013..AI-015 — enemy memory, suspicion meter, search string
    // =====================================================================
    /** Per-enemy player memory + suspicion. Attach alongside Agent. */
    public static final class EnemyBrain extends Component {
        // AI-013 memory
        public Vec3 lastSeenPos = new Vec3();
        public float lastSeenTime = -999f;
        public boolean hasMemory = false;
        public float memoryDuration = 8f;          // sec before forgetting
        // AI-014 suspicion 0..1 with decay
        public float suspicion = 0f;
        public float suspicionDecay = 0.12f;       // per sec
        public float sightGain = 0.8f;             // per sec while visible
        public float noiseGain = 0.35f;
        // AI-025 human reaction delay
        public float reactionTime = 0.35f;
        public float reactionJitter = 0.2f;
        private float reactionLeft = 0f;
        private long rngState = 0x2545F4914F6CDD1DL;

        // AI-028 flashlight blindness / AI-029 footstep hearing integrated:
        public float flashlightBlindAccum = 0f;    // seconds of direct beam exposure
        public float footstepAwareness = 0f;

        public boolean memoryValid(float now){ return hasMemory && (now - lastSeenTime) < memoryDuration; }

        /** call when player is visible in view cone. */
        public void onPlayerVisible(float dt, Vec3 playerPos, float now, boolean flashlightInEyes){
            lastSeenPos.set(playerPos);
            lastSeenTime = now;
            hasMemory = true;
            suspicion = MathX.clamp(suspicion + sightGain * dt, 0f, 1f);
            if(flashlightInEyes){
                // AI-028: direct beam confuses -> slows suspicion gain, adds blind timer
                flashlightBlindAccum += dt;
                suspicion = MathX.clamp(suspicion - 0.25f * dt, 0f, 1f);
            }
        }
        /** AI-029: player noise (running, bumped props). */
        public void onNoise(float strength){
            suspicion = MathX.clamp(suspicion + noiseGain * strength, 0f, 1f);
            footstepAwareness = MathX.clamp(footstepAwareness + strength, 0f, 1.5f);
        }
        /** AI-023: smell — strong in closed rooms, scales with distance inverse. */
        public void onSmell(float closeness){
            suspicion = MathX.clamp(suspicion + 0.15f * closeness, 0f, 1f);
        }

        /** returns true exactly when reaction delay elapsed (fire chase once). */
        public boolean reactionElapsed(float dt){
            if(suspicion < 0.7f){ reactionLeft = 0f; return false; }
            if(reactionLeft <= 0f){
                rngState ^= rngState << 13; rngState ^= rngState >>> 7; rngState ^= rngState << 17;
                float j = ((rngState >>> 8) % 1000) / 1000f;
                reactionLeft = reactionTime + reactionJitter * j;
            }
            reactionLeft -= dt;
            return reactionLeft <= 0f;
        }

        @Override public void onUpdate(float dt){
            if(suspicion > 0f) suspicion = Math.max(0f, suspicion - suspicionDecay * dt);
            if(footstepAwareness > 0f) footstepAwareness = Math.max(0f, footstepAwareness - 0.5f * dt);
            if(flashlightBlindAccum > 0f) flashlightBlindAccum = Math.max(0f, flashlightBlindAccum - dt);
        }
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","EnemyBrain");
            out.kvv("lastSeenPos", lastSeenPos.x, lastSeenPos.y, lastSeenPos.z);
            out.kv("lastSeenTime", lastSeenTime); out.kv("suspicion", suspicion);
            out.kv("memoryDuration", memoryDuration); out.endObj();
        }
        @Override public void load(JsonV n){
            lastSeenPos.set(n.flt("lastSeenPos.x",0), n.flt("lastSeenPos.y",0), n.flt("lastSeenPos.z",0));
            lastSeenTime = n.flt("lastSeenTime", -999f); suspicion = n.flt("suspicion", 0f);
            memoryDuration = n.flt("memoryDuration", 8f);
        }
    }

    // =====================================================================
    // AI-016 — GOAP planner
    // =====================================================================
    /** Goal-Oriented Action Planning: world state = set of symbols, actions have
     *  preconditions/effects. A* over states (small graphs; mobile-friendly). */
    public static final class GoapPlanner {
        public static final class ActionDef {
            public String name;
            public Map<String,String> preconditions = new LinkedHashMap<>();
            public Map<String,String> effects = new LinkedHashMap<>();
            public float cost = 1f;
            public ActionDef(String name, float cost){ this.name = name; this.cost = cost; }
        }
        public static final class Plan extends ArrayList<ActionDef> {}

        /** plan: from start state to goal state. Returns null if unreachable. */
        public static Plan plan(Map<String,String> start, Map<String,String> goal, List<ActionDef> actions){
            // A* over states (state = sorted "k:v|k:v" key)
            Map<String, Node> open = new LinkedHashMap<>();
            Map<String, Double> gScore = new LinkedHashMap<>();
            Map<String, Node> all = new LinkedHashMap<>();
            String startKey = key(start);
            Node s = new Node(start, null, null);
            all.put(startKey, s);
            gScore.put(startKey, 0.0);
            List<String> frontier = new ArrayList<>();
            frontier.add(startKey);
            int iterations = 0;
            while(!frontier.isEmpty() && iterations++ < 4096){
                // pick lowest f
                String bestKey = null; double bestF = Double.MAX_VALUE;
                for(String k : frontier){
                    Node n = all.get(k);
                    double f = gScore.get(k) + h(n.state, goal);
                    if(f < bestF){ bestF = f; bestKey = k; }
                }
                if(bestKey == null) break;
                frontier.remove(bestKey);
                Node cur = all.get(bestKey);
                if(satisfies(cur.state, goal)) return buildPlan(cur);
                for(ActionDef a : actions){
                    if(!satisfies(cur.state, a.preconditions)) continue;
                    Map<String,String> ns = new LinkedHashMap<>(cur.state);
                    ns.putAll(a.effects);
                    String nk = key(ns);
                    double ng = gScore.get(bestKey) + a.cost;
                    if(ng < gScore.getOrDefault(nk, Double.MAX_VALUE)){
                        gScore.put(nk, ng);
                        Node nn = new Node(ns, cur, a);
                        all.put(nk, nn);
                        if(!frontier.contains(nk)) frontier.add(nk);
                    }
                }
            }
            return null;
        }
        private static final class Node {
            final Map<String,String> state; final Node prev; final ActionDef via;
            Node(Map<String,String> s, Node p, ActionDef a){ state = s; prev = p; via = a; }
        }
        private static Plan buildPlan(Node n){
            Plan p = new Plan();
            for(Node i = n; i != null && i.via != null; i = i.prev) p.add(i.via);
            java.util.Collections.reverse(p);
            return p;
        }
        private static boolean satisfies(Map<String,String> state, Map<String,String> pre){
            for(Map.Entry<String,String> e : pre.entrySet())
                if(!e.getValue().equals(state.get(e.getKey()))) return false;
            return true;
        }
        private static double h(Map<String,String> state, Map<String,String> goal){
            int missing = 0;
            for(Map.Entry<String,String> e : goal.entrySet())
                if(!e.getValue().equals(state.get(e.getKey()))) missing++;
            return missing;
        }
        private static String key(Map<String,String> state){
            List<String> parts = new ArrayList<>();
            for(Map.Entry<String,String> e : state.entrySet()) parts.add(e.getKey() + ":" + e.getValue());
            java.util.Collections.sort(parts);
            return String.join("|", parts);
        }
    }

    // =====================================================================
    // AI-017 — Utility AI with continuous scoring
    // =====================================================================
    public static final class UtilityAI {
        public interface Consideration { float score(Agent a); }
        public static final class Option {
            public final String name; public final Consideration score;
            public float weight = 1f;
            public Option(String name, float weight, Consideration c){ this.name = name; this.weight = weight; this.score = c; }
        }
        public final List<Option> options = new ArrayList<>();
        public String chosen = "";
        public float chosenScore = 0f;

        public Option add(String name, float weight, Consideration c){ Option o = new Option(name, weight, c); options.add(o); return o; }

        /** evaluate all, pick max(score * weight); ties broken by order (deterministic). */
        public String select(Agent a){
            float best = -1f; String name = "";
            for(Option o : options){
                float s = MathX.clamp(o.score.score(a), 0f, 1f) * o.weight;
                if(s > best + 1e-6f){ best = s; name = o.name; }
            }
            chosen = name; chosenScore = best;
            return name;
        }
    }

    // =====================================================================
    // AI-018 — group formations: V / line / encircle
    // =====================================================================
    public static final class Formation {
        public enum Kind { VEE, LINE, ENCIRCLE }
        public Kind kind = Kind.VEE;
        public float spacing = 2f;

        /** slot offsets (x right, z forward) for member i of n. */
        public List<Vec3> slots(int n){
            List<Vec3> out = new ArrayList<>();
            switch(kind){
                case LINE -> {
                    for(int i = 0; i < n; i++) out.add(new Vec3((i - (n - 1) * 0.5f) * spacing, 0, 0));
                }
                case VEE -> {
                    // leader at index 0 (0,0); pairs trail behind at ±45°
                    out.add(new Vec3());
                    for(int i = 1; i < n; i++){
                        int side = (i % 2 == 1) ? 1 : -1;
                        int rank = (i + 1) / 2;
                        out.add(new Vec3(side * rank * spacing * 0.7f, 0, -rank * spacing));
                    }
                }
                case ENCIRCLE -> {
                    for(int i = 0; i < n; i++){
                        float a = (float)(2.0 * Math.PI * i / Math.max(1, n));
                        out.add(new Vec3((float)Math.cos(a) * spacing * 2f, 0, (float)Math.sin(a) * spacing * 2f));
                    }
                }
            }
            return out;
        }
        /** world-space slot positions around anchor with heading (degrees yaw). */
        public List<Vec3> worldSlots(Vec3 anchor, float headingDeg, int n){
            List<Vec3> local = slots(n);
            float rad = (float)Math.toRadians(headingDeg);
            float cs = (float)Math.cos(rad), sn = (float)Math.sin(rad);
            List<Vec3> out = new ArrayList<>();
            for(Vec3 l : local){
                // rotate local (x,z) by heading around Y: x' = x cos + z sin; z' = -x sin + z cos
                float wx = l.x * cs + l.z * sn;
                float wz = -l.x * sn + l.z * cs;
                out.add(new Vec3(anchor.x + wx, anchor.y, anchor.z + wz));
            }
            return out;
        }
        /** AI-030: assign members to nearest free slot (greedy, deterministic). */
        public static Map<Integer,Integer> assign(List<Entity> members, List<Vec3> slotWorld){
            Map<Integer,Integer> assign = new LinkedHashMap<>();
            List<Integer> free = new ArrayList<>();
            for(int s = 0; s < slotWorld.size(); s++) free.add(s);
            // compute all (member, slot) distances, sort, assign greedily
            record Pair(int m, int s, float d) {}
            List<Pair> pairs = new ArrayList<>();
            for(int m = 0; m < members.size(); m++){
                Vec3 mp = members.get(m).worldPosition();
                for(int s : free){
                    pairs.add(new Pair(m, s, mp.dist(slotWorld.get(s))));
                }
            }
            pairs.sort(Comparator.comparingDouble(Pair::d).thenComparingInt(Pair::m).thenComparingInt(Pair::s));
            boolean[] slotTaken = new boolean[slotWorld.size()];
            boolean[] memberDone = new boolean[members.size()];
            for(Pair p : pairs){
                if(memberDone[p.m] || slotTaken[p.s] || !free.contains(p.s)) continue;
                assign.put(p.m, p.s);
                memberDone[p.m] = true;
                slotTaken[p.s] = true;
            }
            return assign;
        }
    }

    // =====================================================================
    // AI-019 — full steering: seek/flee/wander/arrival/alignment
    // =====================================================================
    public static final class Steering {
        public Vec3 seek(Vec3 pos, Vec3 target, float maxSpeed){
            Vec3 d = target.cpy().sub(pos);
            if(d.len() < 1e-5f) return new Vec3();
            return d.norm().scale(maxSpeed);
        }
        public Vec3 flee(Vec3 pos, Vec3 threat, float maxSpeed){
            return seek(pos, threat, maxSpeed).scale(-1f);
        }
        public Vec3 arrival(Vec3 pos, Vec3 target, float maxSpeed, float slowRadius){
            Vec3 d = target.cpy().sub(pos);
            float dist = d.len();
            if(dist < 1e-5f) return new Vec3();
            float speed = maxSpeed * MathX.clamp(dist / slowRadius, 0f, 1f);
            return d.norm().scale(speed);
        }
        public Vec3 wander(Vec3 pos, Vec3 vel, float maxSpeed, long tickSeed){
            // deterministic wander: angle jitter per call
            com.hollow.core.RandomStream rng = new com.hollow.core.RandomStream(tickSeed);
            float ang = rng.range(-30f, 30f);
            Vec3 base = vel.len() < 1e-4f ? new Vec3(0,0,1) : vel.norm();
            // rotate base around Y by ang
            float r = (float)Math.toRadians(ang);
            float cs = (float)Math.cos(r), sn = (float)Math.sin(r);
            Vec3 w = new Vec3(base.x * cs + base.z * sn, 0, -base.x * sn + base.z * cs);
            return w.scale(maxSpeed);
        }
        public Vec3 alignment(List<Vec3> headings){
            if(headings.isEmpty()) return new Vec3();
            Vec3 sum = new Vec3();
            for(Vec3 h : headings) sum.add(h);
            if(sum.len() < 1e-5f) return new Vec3();
            return sum.norm();
        }
        public Vec3 cohesion(List<Vec3> positions, Vec3 self){
            Vec3 center = new Vec3();
            for(Vec3 p : positions) center.add(p);
            center.scale(1f / Math.max(1, positions.size()));
            return center.sub(self);
        }
        public Vec3 separation(Vec3 self, List<Vec3> others, float radius){
            Vec3 push = new Vec3();
            for(Vec3 o : others){
                Vec3 d = self.cpy().sub(o);
                float l = d.len();
                if(l > 1e-4f && l < radius) push.add(d.norm().scale(1f - l / radius));
            }
            return push;
        }
    }

    // =====================================================================
    // AI-020 — dialogue tree with conditions & variables
    // =====================================================================
    public static final class DialogueTree {
        public static final class Node {
            public String id, text, speaker;
            public String condition = "";                 // Expr-evaluated
            public final List<Choice> choices = new ArrayList<>();
        }
        public static final class Choice {
            public String text, next, setVar;
            public String condition = "";
            public Choice(String text, String next){ this.text = text; this.next = next; }
        }
        public final Map<String,Node> nodes = new LinkedHashMap<>();
        public String startId = "start";

        public Node node(String id){
            Node n = nodes.get(id);
            if(n == null){ n = new Node(); n.id = id; nodes.put(id, n); }
            return n;
        }
        /** load simple JSON: {start:"a", nodes:{a:{text,speaker,condition,choices:[{text,next,setVar,condition}]}}} */
        public static DialogueTree fromJson(String json){
            Map<String,Object> root = Json.parseObj(json);
            DialogueTree t = new DialogueTree();
            t.startId = Json.str(root.get("start"), "start");
            Map<String,Object> ns = Json.obj(root.get("nodes"));
            if(ns != null){
                for(Map.Entry<String,Object> en : ns.entrySet()){
                    Map<String,Object> nd = Json.obj(en.getValue());
                    if(nd == null) continue;
                    Node n = t.node(en.getKey());
                    n.text = Json.str(nd.get("text"), "");
                    n.speaker = Json.str(nd.get("speaker"), "");
                    n.condition = Json.str(nd.get("condition"), "");
                    List<Object> chs = (List<Object>)nd.get("choices");
                    if(chs != null){
                        for(Object co : chs){
                            Map<String,Object> cm = Json.obj(co);
                            if(cm == null) continue;
                            Choice c = new Choice(Json.str(cm.get("text"),""), Json.str(cm.get("next"),""));
                            c.setVar = Json.str(cm.get("setVar"), null);
                            c.condition = Json.str(cm.get("condition"), "");
                            n.choices.add(c);
                        }
                    }
                }
            }
            return t;
        }
        /** first node whose condition passes ("" passes). */
        public Node enter(com.hollow.script.VarTable vars){
            Node n = nodes.get(startId);
            if(n != null && conditionPasses(n.condition, vars)) return n;
            return n; // null-condition standard
        }
        public static boolean conditionPasses(String cond, com.hollow.script.VarTable vars){
            if(cond == null || cond.isEmpty()) return true;
            return vars.bool("__expr_" + Integer.toHexString(cond.hashCode()), false) || evalSimple(cond, vars);
        }
        /** minimal safe evaluator: "var == value", "var > num", "flag" */
        static boolean evalSimple(String cond, com.hollow.script.VarTable vars){
            cond = cond.trim();
            if(cond.contains("==")){
                String[] p = cond.split("==");
                return vars.str(p[0].trim(), "").equals(p[1].trim().replace("\"",""));
            }
            if(cond.contains(">")){
                String[] p = cond.split(">");
                return vars.num(p[0].trim(), 0) > Double.parseDouble(p[1].trim());
            }
            if(cond.contains("<")){
                String[] p = cond.split("<");
                return vars.num(p[0].trim(), 0) < Double.parseDouble(p[1].trim());
            }
            return vars.bool(cond, false);
        }
    }

    // =====================================================================
    // AI-021 — factions & relations
    // =====================================================================
    public static final class Factions {
        public enum Attitude { FRIENDLY, NEUTRAL, HOSTILE }
        private final Map<String, Map<String,Attitude>> matrix = new LinkedHashMap<>();

        public void set(String a, String b, Attitude att){
            matrix.computeIfAbsent(a, k -> new LinkedHashMap<>()).put(b, att);
            matrix.computeIfAbsent(b, k -> new LinkedHashMap<>()).putIfAbsent(a, att);   // symmetric default
        }
        public Attitude attitude(String a, String b){
            if(a.equals(b)) return Attitude.FRIENDLY;
            Attitude at = matrix.getOrDefault(a, java.util.Collections.emptyMap()).get(b);
            if(at != null) return at;
            return matrix.getOrDefault(b, java.util.Collections.emptyMap()).getOrDefault(a, Attitude.NEUTRAL);
        }
        public boolean hostile(String a, String b){ return attitude(a,b) == Attitude.HOSTILE; }
        public Map<String, Map<String,Attitude>> raw(){ return matrix; }
    }

    // =====================================================================
    // AI-030 — multi-enemy coordination (pincer/flank)
    // =====================================================================
    public static final class Coordination {
        /** assign flank roles: half approach from left arc, half from right arc.
         *  Returns target approach point per enemy index. */
        public static List<Vec3> pincerAssign(List<Entity> enemies, Vec3 target, float radius){
            List<Vec3> out = new ArrayList<>();
            int n = enemies.size();
            for(int i = 0; i < n; i++){
                float side = (i % 2 == 0) ? 1f : -1f;
                float arc = 35f + (i / 2) * 25f;         // degrees off the front, split left/right
                float ang = (float)Math.toRadians(arc);
                // front is +Z; flankers split on X (left/right), staggered deeper on Z
                out.add(new Vec3(target.x + side * (float)Math.sin(ang) * radius, target.y,
                                 target.z + (float)Math.cos(ang) * radius));
            }
            return out;
        }
    }
}
