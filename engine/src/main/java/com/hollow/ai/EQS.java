package com.hollow.ai;

import com.hollow.core.Json;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.game.GameApp;
import com.hollow.physics.PhysicsWorld;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Environment Query System (HollowEngine 2.0 — UE EQS equivalent).
 *  Data-driven queries over a grid of candidate points with scoring tests:
 *  <pre>
 *  { "generator": {"type":"circle","radius":8,"spacing":1.0},     // circle | ring | donut
 *    "tests":[
 *      {"kind":"pathfindable"},                                   // NavGrid reachable
 *      {"kind":"dist_to_target","min":3,"max":10},                // hard range filter
 *      {"kind":"visible_from_target","weight":1.5},               // LOS bonus
 *      {"kind":"toward_target","weight":1.0},                     // prefer moving closer
 *      {"kind":"away_from_target","weight":1.0},
 *      {"kind":"random","weight":0.4} ]}
 *  </pre>
 *  Hard filters (no weight) reject candidates; weighted tests accumulate score.
 *  Best point is written to the agent's blackboard via the eqs_move action.
 */
public final class EQS {

    public static final class Query {
        public String generatorType = "circle";
        public float radius = 8f;
        public float spacing = 1.2f;
        public float innerRadius = 0f;        // ring/donut inner
        public final List<Test> tests = new ArrayList<>();
    }

    public static final class Test {
        public String kind;
        public float weight = 0f;             // 0 = hard filter
        public float min = Float.NaN, max = Float.NaN;
    }

    public static Query parse(Map<String,Object> root){
        Query q = new Query();
        Map<String,Object> gen = Json.obj(root.get("generator"));
        if(!gen.isEmpty()){
            q.generatorType = Json.str(gen.get("type"), "circle");
            q.radius = (float)Json.num(gen.get("radius"), 8);
            q.spacing = (float)Json.num(gen.get("spacing"), 1.2);
            q.innerRadius = (float)Json.num(gen.get("inner"), 0);
        }
        for(Object o : Json.arr(root.get("tests"))){
            if(!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>)o;
            Test t = new Test();
            t.kind = Json.str(m.get("kind"), "");
            t.weight = (float)Json.num(m.get("weight"), 0);
            if(m.containsKey("min")) t.min = (float)Json.num(m.get("min"), 0);
            if(m.containsKey("max")) t.max = (float)Json.num(m.get("max"), 0);
            if(!t.kind.isEmpty()) q.tests.add(t);
        }
        return q;
    }

    public static final class Result {
        public boolean found;
        public final Vec3 point = new Vec3();
        public float score;
        public int candidates, rejected;
    }

    private static final Vec3 tmpClose = new Vec3();

    /** run the query for an agent; uses NavGrid + Perception + physics raycast */
    public static Result run(Query q, Agent agent){
        Result res = new Result();
        Entity self = agent.entity;
        if(self == null || q == null) return res;
        GameApp app = GameApp.instance;
        if(app == null || app.services() == null) return res;
        World world = app.world;
        PhysicsWorld phys = app.services().physics;

        Vec3 origin = self.worldPosition();
        com.hollow.physics.NavGrid nav = phys.navGrid;

        // generate candidates
        List<Vec3> candidates = new ArrayList<>();
        float spacing = Math.max(0.3f, q.spacing);
        int steps = Math.max(1, (int)(q.radius / spacing));
        if("circle".equals(q.generatorType) || "donut".equals(q.generatorType)){
            for(int ix = -steps; ix <= steps; ix++){
                for(int iz = -steps; iz <= steps; iz++){
                    float x = origin.x + ix * spacing;
                    float z = origin.z + iz * spacing;
                    float d = (float)Math.sqrt((x - origin.x) * (x - origin.x) + (z - origin.z) * (z - origin.z));
                    if(d > q.radius) continue;
                    if("donut".equals(q.generatorType) && d < Math.max(spacing, q.innerRadius)) continue;
                    candidates.add(new Vec3(x, origin.y, z));
                }
            }
        } else if("ring".equals(q.generatorType)){
            int n = Math.max(6, (int)(2f * (float)Math.PI * q.radius / spacing));
            for(int i = 0; i < n; i++){
                float a = (float)(Math.PI * 2.0 * i / n);
                candidates.add(new Vec3(origin.x + (float)Math.cos(a) * q.radius, origin.y, origin.z + (float)Math.sin(a) * q.radius));
            }
        }
        res.candidates = candidates.size();
        if(candidates.isEmpty()) return res;

        // target context
        Entity target = null;
        Perception p = agent.perception();
        if(p != null) target = p.visibleTarget();

        long seed = agent.seed();
        MathX.Rng rng = new MathX.Rng(seed ^ 0x5DEECE66DL);

        float bestScore = Float.NEGATIVE_INFINITY;
        Vec3 bestPoint = null;
        for(Vec3 c : candidates){
            float score = 1f;
            boolean reject = false;
            for(Test t : q.tests){
                float v = 0f;
                switch(t.kind){
                    case "pathfindable": {
                        if(nav == null){ reject = true; break; }
                        List<Vec3> path = nav.findPath(origin, c);
                        if(path == null || path.isEmpty()){ reject = true; break; }
                        v = 1f;
                        break;
                    }
                    case "unblocked": {
                        if(phys == null){ reject = true; break; }
                        // pass 1: lift candidates onto low walkable surfaces they penetrate
                        for(PhysicsWorld.StaticShape s : phys.staticShapes()){
                            if(s.isTrigger || s.entity == null) continue;
                            if(c.x < s.bounds.min.x - 0.3f || c.x > s.bounds.max.x + 0.3f) continue;
                            if(c.z < s.bounds.min.z - 0.3f || c.z > s.bounds.max.z + 0.3f) continue;
                            float top = s.bounds.max.y;
                            if(top <= c.y + 1.2f && c.y >= s.bounds.min.y - 0.3f && c.y < top){
                                c.y = top + 0.02f;                      // stand on the surface
                            }
                        }
                        // pass 2: reject on obstacles rising above the candidate
                        for(PhysicsWorld.StaticShape s : phys.staticShapes()){
                            if(s.isTrigger || s.entity == null) continue;
                            if(s.bounds.max.y < c.y + 0.5f) continue;   // low: walkable
                            if(c.y > s.bounds.max.y - 0.3f) continue;   // standing on top
                            float d;
                            if(s.kind == PhysicsWorld.KIND_BOX){
                                PhysicsWorld.clampToBox(c, s.bounds.min, s.bounds.max, tmpClose);
                                d = c.dist(tmpClose);
                            } else if(s.kind == PhysicsWorld.KIND_SPHERE){
                                d = c.dist(s.center) - s.radius;
                            } else {
                                PhysicsWorld.closestOnSeg(s.capA, s.capB, c, tmpClose);
                                d = c.dist(tmpClose) - s.radius;
                            }
                            if(d <= 0.35f){ reject = true; break; }
                        }
                        if(!reject) v = 1f;                             // survived the filter
                        break;
                    }
                    case "dist_to_target": {
                        if(target == null){ v = t.min != t.min ? 1f : 0f; }
                        else {
                            float d = c.dist(target.worldPosition());
                            if(t.min == t.min && d < t.min){ reject = true; break; }
                            if(t.max != t.max){ /* no max */ }
                            else if(d > t.max){ reject = true; break; }
                            v = 1f;
                        }
                        break;
                    }
                    case "visible_from_target": {
                        if(target == null || phys == null){ v = 0f; break; }
                        PhysicsWorld.Hit hit = phys.raycast(target.worldPosition(),
                            c.cpy().sub(target.worldPosition()).norm(), c.dist(target.worldPosition()));
                        v = hit.hit ? 0f : 1f;
                        break;
                    }
                    case "toward_target": {
                        if(target == null){ v = 0f; break; }
                        float cur = origin.dist(target.worldPosition());
                        float now = c.dist(target.worldPosition());
                        v = cur - now;                     // positive when closer
                        break;
                    }
                    case "away_from_target": {
                        if(target == null){ v = 0f; break; }
                        float cur2 = origin.dist(target.worldPosition());
                        float now2 = c.dist(target.worldPosition());
                        v = now2 - cur2;
                        break;
                    }
                    case "random": {
                        v = rng.nextFloat() * 2f - 1f;
                        break;
                    }
                    default: v = 0f;
                }
                if(reject) break;
                if(t.weight == 0f){
                    // hard filter: value must be positive to keep
                    if(v <= 0f){ reject = true; break; }
                } else {
                    score += v * t.weight;
                }
            }
            if(reject){ res.rejected++; continue; }
            if(score > bestScore){
                bestScore = score;
                bestPoint = c;
            }
        }
        if(bestPoint != null){
            res.found = true;
            res.point.set(bestPoint);
            res.score = bestScore;
        }
        return res;
    }
}
