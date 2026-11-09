package com.hollow.ai;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.physics.CharacterController;
import com.hollow.physics.PhysicsWorld;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** AI agent driven by a behavior tree + navigation + standard checks/actions. */
public final class Agent extends Component {
    public String treePath = "";             // ai/*.bt.json
    public float speed = 2.2f;
    public float chaseSpeed = 4.2f;
    public float attackRange = 1.6f;
    public boolean waypointLoop = true;
    public String state = "patrol";

    /** runtime blackboard (entity-local) */
    public final com.hollow.script.VarTable bb = new com.hollow.script.VarTable();

    // set by BehaviorTree while running a node
    public Map<String,Object> nodeParams = new java.util.HashMap<>();
    public Map<String,Object> nodeRaw = new java.util.HashMap<>();
    public boolean actionRunning = false;

    public interface Check { boolean test(Agent a); }
    public interface Action { boolean run(Agent a, Map<String,Object> params); }
    final Map<String, Check> checks = new java.util.HashMap<>();
    final Map<String, Action> actions = new java.util.HashMap<>();

    BehaviorTree tree;
    final List<Entity> waypoints = new ArrayList<>();
    int waypointIndex = 0;
    Perception perception;
    CharacterController controller;
    PhysicsWorld physics;
    com.hollow.script.EventBus events;

    // movement scratch
    final Vec3 moveTarget = new Vec3();
    boolean hasMoveTarget = false;
    float stuckTimer = 0f;
    final Vec3 lastPos = new Vec3();
    float repathTimer = 0f;
    List<Vec3> currentPath = null;
    int pathIndex = 0;
    float stateTimer = 0f;

    public void registerCheck(String name, Check c){ checks.put(name, c); }
    public void registerAction(String name, Action a){ actions.put(name, a); }
    public long seed(){
        com.hollow.game.EngineServices svc = services();
        return (svc != null ? svc.frameSeed : 0x1234L) ^ (entity != null ? entity.id * 0x9E3779B9L : 0);
    }
    public void fireEvent(String name, Map<String,Object> data){
        com.hollow.script.EventBus bus = events;
        if(bus == null) bus = eventBus();
        if(bus != null) bus.fire(name, data);
    }
    public Perception perception(){ return perception; }

    private com.hollow.game.EngineServices services(){
        return com.hollow.game.GameApp.instance != null ? com.hollow.game.GameApp.instance.services() : null;
    }
    private com.hollow.script.EventBus eventBus(){
        com.hollow.game.EngineServices svc = services();
        return svc != null ? svc.events : null;
    }
    private com.hollow.assets.AssetDB assets(){
        com.hollow.game.EngineServices svc = services();
        return svc != null ? svc.assets : null;
    }

    @Override public void onAttach(){
        controller = entity.get(CharacterController.class);
        if(controller == null) controller = new CharacterController(); // kinematic fallback (no physics)
        perception = entity.get(Perception.class);
        com.hollow.game.EngineServices svc = services();
        if(svc != null){
            physics = svc.physics;
            events = svc.events;
        }
        registerStandard();
        loadTree();
        gatherWaypoints();
        bb.set("ai_state", state);
    }

    private void loadTree(){
        if(treePath == null || treePath.isEmpty()) return;
        try {
            String path = treePath.startsWith("ai/") ? treePath : "ai/" + treePath;
            if(!path.endsWith(".bt.json")) path += ".bt.json";
            com.hollow.core.FileSystem fs = assets() != null ? assets().fs : null;
            if(fs != null && fs.exists(path)){
                tree = BehaviorTree.parse(Json.parseObj(fs.readText(path)));
            } else {
                Log.w("Agent", "tree file not found: " + path + " on " + entity.name);
            }
        } catch(Exception e){
            Log.e("Agent", "tree load failed: " + treePath, e);
        }
    }

    private void gatherWaypoints(){
        waypoints.clear();
        if(entity == null) return;
        int i = 1;
        while(true){
            Entity wp = entity.findChild("wp" + i);
            if(wp == null) break;
            waypoints.add(wp);
            i++;
        }
        if(waypoints.isEmpty() && entity.parent() != null){
            // siblings tagged "<parent>_wp" or "waypoint"
            for(Entity c : entity.parent().children()){
                if(c != entity && (c.tag.equals("waypoint") || c.tag.equals(entity.name + "_wp"))) waypoints.add(c);
            }
        }
    }

    private void registerStandard(){
        checks.put("seen", a -> a.perception != null && a.perception.hasTarget());
        checks.put("target_in_range", a -> {
            if(a.perception == null || !a.perception.hasTarget()) return false;
            Object r = a.nodeParams.get("range");
            float range = r instanceof Number n ? n.floatValue() : a.attackRange;
            return a.entity != null && a.perception.visibleTarget() != null &&
                   a.entity.position.dist(a.perception.visibleTarget().position) <= range;
        });
        checks.put("lost_target", a -> a.perception != null && !a.perception.hasTarget()
                && a.perception.lastKnownTargetPos.len2() > 1e-4f);
        checks.put("chance", a -> {
            Object p = a.nodeParams.get("p");
            float prob = p instanceof Number n ? n.floatValue() : 0.5f;
            return new MathX.Rng(a.seed()).nextFloat() < prob;
        });
        checks.put("bb_true", a -> a.bb.bool(nodeStr(a, "key"), false));

        actions.put("patrol", (a, p) -> actPatrol(a, p));
        actions.put("chase", (a, p) -> actChase(a, p));
        actions.put("search", (a, p) -> actSearch(a, p));
        actions.put("attack", (a, p) -> {
            Map<String,Object> d = new java.util.HashMap<>();
            d.put("attacker", a.entity != null ? a.entity.name : "?");
            a.fireEvent(str(a.nodeParams.get("event"), "attack"), d);
            a.setState("attack");
            return true;
        });
        actions.put("move_to", (a, p) -> {
            Vec3 target = targetFromParams(a);
            if(target == null) return true;
            return a.moveTo(target, a.speed);
        });
        actions.put("flee", (a, p) -> {
            if(a.perception == null) return true;
            Vec3 away = a.entity.position.cpy().sub(a.perception.lastKnownTargetPos);
            if(away.len2() < 1e-4f) away.set(0,0,1);
            away.norm();
            Vec3 dest = a.entity.position.cpy().addScaled(away, 5f);
            return a.moveTo(dest, a.chaseSpeed);
        });
        actions.put("play_anim", (a, p) -> {
            Animator0 anim = animator(a);
            if(anim != null) anim.play(str(a.nodeParams.get("clip"), ""));
            return true;
        });
        actions.put("stop", (a, p) -> { a.hasMoveTarget = false; return true; });
    }
    private interface Animator0 { void play(String clip); }
    private static Animator0 animator(Agent a){
        com.hollow.anim.Animator an = a.entity != null ? a.entity.get(com.hollow.anim.Animator.class) : null;
        return an == null ? null : an::play;
    }
    private static String nodeStr(Agent a, String k){
        Object v = a.nodeParams.get(k);
        return v instanceof String s ? s : null;
    }
    private static String str(Object o, String def){ return o instanceof String s ? s : def; }

    private static boolean actPatrol(Agent a, Map<String,Object> p){
        a.setState("patrol");
        if(a.waypoints.isEmpty()) return true;
        Entity wp = a.waypoints.get(Math.min(a.waypointIndex, a.waypoints.size()-1));
        float dist = a.entity.position.dist(wp.position);
        if(dist < 0.6f){
            a.waypointIndex++;
            if(a.waypointIndex >= a.waypoints.size()){
                if(a.waypointLoop) a.waypointIndex = 0;
                else { a.hasMoveTarget = false; return true; }
            }
            wp = a.waypoints.get(a.waypointIndex);
        }
        return a.moveTo(wp.position, a.speed);
    }
    private static boolean actChase(Agent a, Map<String,Object> p){
        a.setState("chase");
        if(a.perception == null) return true;
        Vec3 target = a.perception.hasTarget() && a.perception.visibleTarget() != null
                ? a.perception.visibleTarget().position
                : a.perception.lastKnownTargetPos;
        float dist = a.entity.position.dist(target);
        if(dist <= a.attackRange) { a.hasMoveTarget = false; return true; }
        return a.moveTo(target, a.chaseSpeed);
    }
    private static boolean actSearch(Agent a, Map<String,Object> p){
        a.setState("search");
        a.stateTimer += 1f/60f;
        if(a.stateTimer > 2f || !a.hasMoveTarget){
            a.stateTimer = 0;
            MathX.Rng rng = new MathX.Rng(a.seed() ^ (long)(a.stateTimer * 1000));
            Vec3 base = a.perception != null ? a.perception.lastKnownTargetPos : a.entity.position;
            Vec3 jitter = rng.insideUnitSphere().scale(3f);
            jitter.y = 0;
            a.moveTo(base.cpy().add(jitter), a.speed);
        }
        return false; // keep searching (RUNNING)
    }

    private Vec3 targetFromParams(Agent a){
        Object px = a.nodeParams.get("x");
        if(px instanceof Number){
            return new Vec3(((Number)px).floatValue(),
                    (float)Json.num(a.nodeParams.get("y"), 0),
                    (float)Json.num(a.nodeParams.get("z"), 0));
        }
        Object entName = a.nodeParams.get("entity");
        if(entName instanceof String s && entity != null && entity.parent() != null){
            Entity t = entity.parent().findChild(s);
            if(t == null && entity.parent().parent() != null) t = entity.parent().parent().findChild(s);
            if(t != null) return t.position;
        }
        return null;
    }

    /** steer toward target (with navgrid path when available); returns false while en route */
    public boolean moveTo(Vec3 target, float spd){
        if(entity == null) return true;
        moveTarget.set(target);
        hasMoveTarget = true;
        float flatDist = flatDist(entity.position, target);
        if(flatDist < 0.25f){ hasMoveTarget = false; return true; }

        Vec3 dir;
        repathTimer -= 1f/60f;
        if(physics != null && physics.navGrid != null && flatDist > 2f){
            if(currentPath == null || pathIndex >= currentPath.size() || repathTimer <= 0f){
                currentPath = physics.navGrid.findPath(entity.position, target);
                pathIndex = 0;
                repathTimer = 0.5f;
            }
            if(currentPath != null && pathIndex < currentPath.size()){
                Vec3 wp = currentPath.get(pathIndex);
                if(flatDist(entity.position, wp) < 0.5f) pathIndex++;
                dir = flatDir(entity.position, wp);
            } else dir = flatDir(entity.position, target);
        } else {
            currentPath = null;
            dir = flatDir(entity.position, target);
        }
        if(dir.len2() < 1e-8f){ hasMoveTarget = false; return true; }
        dir.scale(spd / 60f); // per fixed tick

        // stuck detection
        stuckTimer += 1f/60f;
        if(stuckTimer > 0.5f){
            if(entity.position.dist2(lastPos) < 0.0004f){
                // nudge sideways
                Vec3 side = new Vec3(-dir.z, 0, dir.x).norm().scale(spd/60f * 2f);
                dir.add(side);
            }
            lastPos.set(entity.position);
            stuckTimer = 0;
        }

        applyMove(dir);
        return false;
    }
    private void applyMove(Vec3 d){
        if(controller != null && controller.entity != null && physics != null){
            controller.move(d);
        } else if(entity != null){
            entity.position.add(d);
            entity.markDirtyDeep();
        }
        // face movement direction
        if(d.len2() > 1e-10f && entity != null){
            float yaw = (float)Math.toDegrees(Math.atan2(-d.x, -d.z));
            float pitch = entity.rotation.pitchDeg();
            entity.rotation.slerp(com.hollow.core.Quat.fromEuler(pitch, yaw, 0), 0.25f);
            entity.markDirtyDeep();
        }
    }
    private static float flatDist(Vec3 a, Vec3 b){
        float dx=a.x-b.x, dz=a.z-b.z; return (float)Math.sqrt(dx*dx+dz*dz);
    }
    private static Vec3 flatDir(Vec3 from, Vec3 to){
        return new Vec3(to.x-from.x, 0, to.z-from.z).norm();
    }

    public void setState(String s){
        if(!state.equals(s)){
            state = s;
            bb.set("ai_state", s);
            stateTimer = 0;
        }
    }

    /** called by AISystem each fixed tick */
    public void tick(float dt){
        if(entity == null || !entity.active || entity.destroyed) return;
        bb.set("target_dist", perception != null && perception.hasTarget() && perception.visibleTarget() != null
                ? entity.position.dist(perception.visibleTarget().position) : -1f);
        bb.set("seen_time", perception != null ? perception.targetVisibleFor() : 0f);
        if(tree != null){
            actionRunning = false;
            tree.tick(this, dt);
        } else if(!waypoints.isEmpty()){
            actPatrol(this, java.util.Collections.emptyMap());
        }
    }

    @Override public void onUpdate(float dt){ /* ticked by AISystem to control order */ }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Agent").kv("tree",treePath).kv("speed",speed)
          .kv("chaseSpeed",chaseSpeed).kv("attackRange",attackRange)
          .kv("waypointLoop",waypointLoop).kv("state",state); w.endObj();
    }
    @Override public void load(JsonV n){
        treePath = n.str("tree", n.str("treePath", ""));
        speed = n.flt("speed",2.2f); chaseSpeed = n.flt("chaseSpeed",4.2f);
        attackRange = n.flt("attackRange",1.6f);
        waypointLoop = n.bool("waypointLoop", true);
        state = n.str("state","patrol");
    }
}
