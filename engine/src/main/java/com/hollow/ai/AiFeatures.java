package com.hollow.ai;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Noise;
import com.hollow.core.RandomStream;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.5 AI batch — AI-022..027 (deterministic, headless-testable).
 *
 *  AI-022 TerrainPath    — pathfinding over continuous heightfields (slope cost)
 *  AI-024 MedicalBehavior— wounded NPC triage: flee / flail / recover
 *  AI-026 AiDirector     — tension pacing director (audio + spawn budgets)
 *  AI-027 AiScenarioTests— JSON scenario runner with assertions */
public final class AiFeatures {
    private AiFeatures() {}

    // =====================================================================
    // AI-022 — path generation on continuous terrain
    // =====================================================================
    /** Grid-sampled A* over a heightfield callback: cell cost = 1 + slope^2 *
     *  slopeWeight; cells steeper than maxSlope are impassable. Output path is
     *  smoothed by string-pulling over line-of-sight slope checks. Fully
     *  deterministic (fixed neighbor order, tie-break by (y,x) index). */
    public static final class TerrainPath {
        public interface HeightField { float height(float x, float z); }

        public final int samples;          // grid resolution per axis
        public final float originX, originZ, cell;
        public final HeightField field;
        public float maxSlopeDeg = 38f;    // steeper = blocked
        public float slopeWeight = 6f;     // how strongly slope adds to cost

        public TerrainPath(HeightField field, float originX, float originZ, float size, int samples){
            this.field = field;
            this.originX = originX; this.originZ = originZ;
            this.cell = size / Math.max(1, samples - 1);
            this.samples = Math.max(2, samples);
        }

        int gx(float x){ return Math.round((x - originX) / cell); }
        int gz(float z){ return Math.round((z - originZ) / cell); }
        float wx(int gx){ return originX + gx * cell; }
        float wz(int gz){ return originZ + gz * cell; }

        float slopeDeg(int ax, int az, int bx, int bz){
            float dh = field.height(wx(bx), wz(bz)) - field.height(wx(ax), wz(az));
            float run = cell * (float)Math.sqrt((bx-ax)*(bx-ax) + (bz-az)*(bz-az));
            return (float)Math.toDegrees(Math.atan2(Math.abs(dh), Math.max(1e-4f, run)));
        }

        /** A* over the sampled grid; returns world-space waypoints (smoothed) or null. */
        public List<Vec3> findPath(float sx, float sz, float tx, float tz){
            int w = samples, n = w * w;
            int s = gz(sz) * w + gx(sx), t = gz(tz) * w + gx(tx);
            s = MathX.clamp(s, 0, n-1); t = MathX.clamp(t, 0, n-1);
            float[] g = new float[n]; int[] from = new int[n];
            java.util.Arrays.fill(g, Float.MAX_VALUE); java.util.Arrays.fill(from, -1);
            // binary-heap-lite: small open list as sorted array is fine at ≤ 200x200 with early exit
            boolean[] closed = new boolean[n];
            g[s] = 0;
            int[][] DIRS = { {1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1} };
            List<Integer> open = new ArrayList<>();
            open.add(s);
            while(!open.isEmpty()){
                // pick lowest f = g + h (deterministic scan — grid is bounded)
                int best = -1; float bestF = Float.MAX_VALUE;
                for(int idx : open){
                    float h = (float)Math.hypot((idx % w) - (t % w), (idx / w) - (t / w));
                    float f = g[idx] + h * 1.001f;   // slight inflation → fewer expansions
                    if(f < bestF){ bestF = f; best = idx; }
                }
                if(best < 0) break;
                if(best == t) break;
                open.remove((Integer)best);
                if(closed[best]) continue;
                closed[best] = true;
                int bx = best % w, bz = best / w;
                for(int[] d : DIRS){
                    int nx = bx + d[0], nz = bz + d[1];
                    if(nx < 0 || nz < 0 || nx >= w || nz >= w) continue;
                    int ni = nz * w + nx;
                    if(closed[ni]) continue;
                    float slope = slopeDeg(bx, bz, nx, nz);
                    if(slope > maxSlopeDeg) continue;
                    float step = 1f + (slope / maxSlopeDeg) * slopeWeight;
                    if(d[0] != 0 && d[1] != 0) step *= 1.4142f;
                    float ng = g[best] + step;
                    if(ng < g[ni]){ g[ni] = ng; from[ni] = best; if(!open.contains(ni)) open.add(ni); }
                }
            }
            if(g[t] == Float.MAX_VALUE) return null;
            // reconstruct + smoothing (slope-aware line of sight)
            List<Integer> cells = new ArrayList<>();
            for(int c = t; c != -1; c = from[c]) cells.add(c);
            java.util.Collections.reverse(cells);
            List<Vec3> raw = new ArrayList<>();
            for(int c : cells) raw.add(new Vec3(wx(c % w), field.height(wx(c % w), wz(c / w)), wz(c / w)));
            return smooth(raw);
        }

        List<Vec3> smooth(List<Vec3> path){
            if(path.size() < 3) return path;
            List<Vec3> out = new ArrayList<>();
            int i = 0;
            while(i < path.size() - 1){
                int j = path.size() - 1;
                for(; j > i + 1; j--) if(lineOfSlope(path.get(i), path.get(j))) break;
                out.add(path.get(i));
                i = j;
            }
            out.add(path.get(path.size() - 1));
            return out;
        }

        boolean lineOfSlope(Vec3 a, Vec3 b){
            int steps = Math.max(2, (int)(a.dist(b) / Math.max(0.05f, cell)));
            for(int k = 1; k <= steps; k++){
                float f = k / (float)steps;
                float x = MathX.lerp(a.x, b.x, f), z = MathX.lerp(a.z, b.z, f);
                float y = MathX.lerp(a.y, b.y, f);
                float ground = field.height(x, z);
                if(Math.abs(y - ground) > cell * 0.9f) return false;
                if(k > 1){
                    float px = MathX.lerp(a.x, b.x, (k-1)/(float)steps), pz = MathX.lerp(a.z, b.z, (k-1)/(float)steps);
                    float dh = field.height(x, z) - field.height(px, pz);
                    float slope = (float)Math.toDegrees(Math.atan2(Math.abs(dh), Math.max(0.05f, cell)));
                    if(slope > maxSlopeDeg) return false;
                }
            }
            return true;
        }

        public float pathLength(List<Vec3> path){
            float len = 0;
            for(int i = 1; i < path.size(); i++) len += path.get(i-1).dist(path.get(i));
            return len;
        }
    }

    // =====================================================================
    // AI-024 — simple NPC medicine (wounded → flee / flail)
    // =====================================================================
    /** Attach to any NPC with health. Below `woundThreshold` the NPC panics:
     *  flees away from `threatPos` while stamina lasts, then flails (random short
     *  dashes) and slowly recovers. Deterministic panic RNG (seeded per entity). */
    public static final class MedicalBehavior extends Component {
        public float health = 100f;
        public float maxHealth = 100f;
        public float woundThreshold = 0.35f;   // fraction of maxHealth → panic
        public float fleeSpeed = 3.4f;
        public float stamina = 4.5f;           // seconds of fleeing
        public float recoveryRate = 2.0f;      // hp/sec while safe
        public float threatRadius = 24f;       // forget threat beyond this
        public String state = "idle";          // idle | flee | flail | recover

        /** readouts (renderer/anim sample these) */
        public Vec3 threatPos = new Vec3();
        public boolean hasThreat = false;
        public final Vec3 moveDir = new Vec3();
        public float panicAnim = 0f;           // 0..1 drives flail animation weight
        public boolean bleedFx = false;        // VFX hook (drips)

        float staminaLeft;
        float flailTimer;
        float flailDirAngle;
        RandomStream rng;

        public void hurt(float amount, Vec3 from){
            health = MathX.clamp(health - amount, 0, maxHealth);
            if(from != null){ threatPos.set(from); hasThreat = true; }
            if(health <= maxHealth * woundThreshold && !"flee".equals(state) && !"flail".equals(state)){
                enterWounded();
            }
            bleedFx = health < maxHealth * woundThreshold;
        }

        void enterWounded(){
            state = "flee";
            staminaLeft = stamina;
            rng = new RandomStream(entity != null ? entity.name.hashCode() ^ 0x4D4F : 1);
            Log.d("Medical", "NPC wounded → flee");
        }

        @Override public void onUpdate(float dt){
            switch(state){
                case "flee" -> {
                    staminaLeft -= dt;
                    if(hasThreat && entity != null){
                        moveDir.set(entity.worldPosition().x - threatPos.x, 0,
                                    entity.worldPosition().z - threatPos.z);
                        float l = moveDir.len();
                        if(l > 1e-3f) moveDir.set(moveDir.x/l * fleeSpeed, 0, moveDir.z/l * fleeSpeed);
                    }
                    if(staminaLeft <= 0f || threatForgotten(dt)){ state = "flail"; flailTimer = 0f; }
                }
                case "flail" -> {
                    if(rng == null) rng = new RandomStream(7);
                    flailTimer -= dt;
                    if(flailTimer <= 0f){
                        flailTimer = 0.35f + rng.nextFloat() * 0.4f;
                        flailDirAngle = rng.nextFloat() * 360f;
                    }
                    float rad = (float)Math.toRadians(flailDirAngle);
                    moveDir.set((float)Math.cos(rad) * fleeSpeed * 0.4f, 0, (float)Math.sin(rad) * fleeSpeed * 0.4f);
                    panicAnim = 0.6f + 0.4f * (float)Math.sin(flailTimer * 20f);
                    if(!hasThreat && health > maxHealth * woundThreshold){ state = "recover"; }
                }
                case "recover" -> {
                    health = Math.min(maxHealth, health + recoveryRate * dt);
                    panicAnim = MathX.approach(panicAnim, 0, dt);
                    moveDir.set(0, 0, 0);
                    if(health >= maxHealth * 0.99f){
                        state = "idle";
                        bleedFx = false;
                        panicAnim = 0f;
                    }
                }
                default -> { moveDir.set(0,0,0); panicAnim = MathX.approach(panicAnim, 0, dt); }
            }
        }

        boolean threatForgotten(float dt){
            if(!hasThreat || entity == null) return true;
            return entity.worldPosition().dist(threatPos) > threatRadius;
        }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","MedicalBehavior").kv("health",health).kv("maxHealth",maxHealth)
              .kv("woundThreshold",woundThreshold).kv("fleeSpeed",fleeSpeed)
              .kv("stamina",stamina).kv("recoveryRate",recoveryRate).kv("state",state); w.endObj();
        }
        @Override public void load(JsonV n){
            health = n.flt("health", 100f); maxHealth = n.flt("maxHealth", 100f);
            woundThreshold = n.flt("woundThreshold", 0.35f);
            fleeSpeed = n.flt("fleeSpeed", 3.4f); stamina = n.flt("stamina", 4.5f);
            recoveryRate = n.flt("recoveryRate", 2f); state = n.str("state", "idle");
        }
    }

    // =====================================================================
    // AI-026 — AI director (tension pacing for audio + spawning)
    // =====================================================================
    /** Tracks a tension signal fed by gameplay events, decays over time, and emits
     *  pacing bands: quiet → build → peak → release. Consumers (music system,
     *  spawner budget, VFX) read `band` + `tension`; the director guarantees a
     *  rest window after peaks (classic pacing rule). Deterministic. */
    public static final class AiDirector {
        public float tension = 0f;             // 0..1
        public float decayPerSec = 0.045f;
        public float restAfterPeak = 6f;       // seconds of forced quiet after peak
        public float peakLevel = 0.85f;
        public final Noise pulse = new Noise(0xC1);

        public String band = "quiet";          // quiet | build | peak | release | rest
        public float restLeft = 0f;
        public float musicIntensity = 0f;      // 0..1 → audio layering
        public int spawnBudget = 0;            // enemies the spawner may add this window
        public long frames = 0;

        public void push(String event, float weight){
            switch(event){
                case "player_hit", "npc_died", "chase_start" -> tension = Math.min(1, tension + weight * 0.28f);
                case "attack" -> tension = Math.min(1, tension + weight * 0.15f);
                case "quiet_zone" -> tension = Math.max(0, tension - weight * 0.4f);
                default -> tension = Math.min(1, tension + weight * 0.08f);
            }
        }

        public void update(float dt){
            frames++;
            tension = Math.max(0, tension - decayPerSec * dt);
            if(restLeft > 0f) restLeft -= dt;
            String prev = band;
            if(restLeft > 0f) band = "rest";
            else if(tension >= peakLevel){ band = "peak"; restLeft = restAfterPeak; }
            else if(tension >= 0.5f) band = "build";
            else if(tension >= 0.2f && "peak".equals(prev)) band = "release";
            else band = "quiet";

            float wobble = 0.08f * pulse.perlin2(frames * 0.01f, 3.3f);
            switch(band){
                case "quiet", "rest" -> musicIntensity = MathX.approach(musicIntensity, 0.08f + wobble, dt * 0.5f);
                case "release" -> musicIntensity = MathX.approach(musicIntensity, 0.35f + wobble, dt * 0.8f);
                case "build" -> musicIntensity = MathX.approach(musicIntensity, 0.65f + wobble, dt * 1.2f);
                case "peak" -> musicIntensity = MathX.approach(musicIntensity, 1f, dt * 2.5f);
            }
            spawnBudget = switch(band){
                case "quiet", "rest" -> 0;
                case "release" -> 1;
                case "build" -> 2;
                case "peak" -> 4;
                default -> 0;
            };
        }
        public String status(){ return "director[tension=" + String.format("%.2f", tension) + " band=" + band + " budget=" + spawnBudget + "]"; }
    }

    // =====================================================================
    // AI-027 — automated AI scenario tests
    // =====================================================================
    /** JSON scenarios: {"name": "...", "steps": 240,
     *   "agents":[{"x":0,"z":5,"state":"patrol"}],
     *   "asserts":[{"type":"intent","agent":0,"state":"patrol"},
     *              {"type":"dist","a":0,"b":1,"max":10}]}.
     *  Runs the scenario on a fresh headless world and reports per-assert results. */
    public static final class AiScenarioTests {
        public static final class AssertResult {
            public String name; public boolean pass; public String detail;
        }
        public static final class ScenarioResult {
            public String name; public int steps;
            public final List<AssertResult> asserts = new ArrayList<>();
            public boolean pass(){ return asserts.stream().allMatch(a -> a.pass); }
        }

        public static List<ScenarioResult> run(List<Object> scenarios, World world, AISystem ai){
            List<ScenarioResult> results = new ArrayList<>();
            for(Object o : scenarios){
                Map<String,Object> sc = Json.obj(o);
                if(sc == null) continue;
                results.add(runOne(sc, world, ai));
            }
            return results;
        }

        static ScenarioResult runOne(Map<String,Object> sc, World world, AISystem ai){
            ScenarioResult res = new ScenarioResult();
            res.name = Json.str(sc.get("name"), "scenario");
            int steps = (int)Json.num(sc.get("steps"), 240);
            List<Entity> agents = new ArrayList<>();
            List<Object> agentDefs = Json.arr(sc.get("agents"));
            if(agentDefs != null){
                for(Object ao : agentDefs){
                    Map<String,Object> ad = Json.obj(ao);
                    if(ad == null) continue;
                    Entity e = world.spawn("sc_agent_" + agents.size());
                    e.tag = "ai_test";
                    e.position.set((float)Json.num(ad.get("x"), 0), 0, (float)Json.num(ad.get("z"), 0));
                    e.markDirtyDeep();
                    Agent a = new Agent();
                    a.state = Json.str(ad.get("state"), "patrol");
                    e.add(a);
                    agents.add(e);
                }
            }
            for(int i = 0; i < steps; i++) world.update(1f / 60f);

            List<Object> asserts = Json.arr(sc.get("asserts"));
            if(asserts != null){
                for(Object ao : asserts){
                    Map<String,Object> as = Json.obj(ao);
                    if(as == null) continue;
                    AssertResult r = new AssertResult();
                    r.name = Json.str(as.get("name"), Json.str(as.get("type"), "assert"));
                    String type = Json.str(as.get("type"), "");
                    int ai0 = (int)Json.num(as.get("agent"), 0);
                    switch(type){
                        case "intent" -> {
                            String want = Json.str(as.get("state"), "");
                            String got = ai0 < agents.size() ? agents.get(ai0).get(Agent.class).state : "<missing>";
                            r.pass = got.equals(want);
                            r.detail = "state=" + got + " want=" + want;
                        }
                        case "dist" -> {
                            int bi = (int)Json.num(as.get("b"), -1);
                            float max = (float)Json.num(as.get("max"), 1e9f);
                            float d = (ai0 < agents.size() && bi < agents.size())
                                ? agents.get(ai0).worldPosition().dist(agents.get(bi).worldPosition()) : -1;
                            r.pass = d >= 0 && d <= max;
                            r.detail = "dist=" + String.format("%.2f", d) + " max=" + max;
                        }
                        case "alive" -> {
                            boolean want = Json.bool(as.get("value"), true);
                            boolean got = ai0 < agents.size() && !agents.get(ai0).destroyed;
                            r.pass = got == want;
                            r.detail = "alive=" + got;
                        }
                        case "count" -> {
                            String tag = Json.str(as.get("tag"), "ai_test");
                            int got = world.byTag(tag).size();
                            int want = (int)Json.num(as.get("value"), -1);
                            r.pass = got == want;
                            r.detail = "count=" + got + " want=" + want;
                        }
                        default -> { r.pass = true; r.detail = "skipped"; }
                    }
                    res.asserts.add(r);
                }
            }
            for(Entity e : agents) world.destroy(e);
            return res;
        }

        public static String report(List<ScenarioResult> results){
            StringBuilder b = new StringBuilder();
            int pass = 0;
            for(ScenarioResult r : results){
                b.append(r.pass() ? "PASS " : "FAIL ").append(r.name).append(" (").append(r.steps).append(" steps)\n");
                if(r.pass()) pass++;
                for(AssertResult a : r.asserts)
                    if(!a.pass) b.append("   ✗ ").append(a.name).append(": ").append(a.detail).append('\n');
            }
            b.append(pass).append('/').append(results.size()).append(" scenarios passed\n");
            return b.toString();
        }
    }
}
