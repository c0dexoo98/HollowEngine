package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.game.StreamingVolume;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.5 world batch — WLD-016..025 (engine-side, deterministic, headless-testable).
 *
 *  WLD-016 InteriorBlend  — interior/exterior environment blending per listener
 *  WLD-021 PrewarmQueue   — background pre-decode of adjacent streaming cells
 *  WLD-022 MemoryBudget   — streaming eviction by memory budget, cheapest-first
 *  WLD-023 WorldMerger    — merge multiple scene chunks into one executable world
 *  WLD-024 WorldSnapshot  — canonical state digest + JSON dump for regression tests
 *  WLD-025 HeadlessWorld  — server-side world facade without renderer/audio deps */
public final class WorldFeatures {
    private WorldFeatures() {}

    // =====================================================================
    // WLD-016 — interior/exterior blend
    // =====================================================================
    /** Declares an interior volume (AABB). While the listener is inside, environment
     *  values (ambient tint, fog density, reverb, sky weight) crossfade to the
     *  interior set; renderer + audio sample `blend` (1 = fully interior). */
    public static final class InteriorBlend extends Component {
        public float[] boundsMin = { -5, 0, -5 };
        public float[] boundsMax = {  5, 3,  5 };
        public float[] interiorAmbient = { 0.10f, 0.09f, 0.12f };
        public float interiorFog = 0.02f;
        public float interiorReverb = 0.6f;
        public float skyWeight = 0.05f;        // 1 = fully open sky
        public float blendTime = 1.2f;         // seconds to reach full interior value
        /** readouts */
        public float blend = 0f;               // 0 exterior .. 1 interior (smoothed)
        public int occupants = 0;

        public boolean contains(Vec3 p){
            return p.x >= boundsMin[0] && p.x <= boundsMax[0]
                && p.y >= boundsMin[1] && p.y <= boundsMax[1]
                && p.z >= boundsMin[2] && p.z <= boundsMax[2];
        }

        @Override public void onUpdate(float dt){
            Entity host = entity != null ? entity : null;
            Vec3 pos = host != null ? host.worldPosition() : null;
            boolean inside = pos != null && contains(pos);
            float target = inside ? 1f : 0f;
            float step = blendTime > 0f ? dt / blendTime : 1f;
            blend = MathX.approach(blend, target, step);
            if(inside) occupants++; else occupants = Math.max(0, occupants - 1);
        }

        /** blended ambient = lerp(exterior, interior, blend) */
        public float[] ambientAt(float[] exteriorAmbient){
            float[] out = new float[3];
            for(int i = 0; i < 3; i++)
                out[i] = MathX.lerp(exteriorAmbient[i], interiorAmbient[i], blend);
            return out;
        }
        public float fogAt(float exteriorFog){ return MathX.lerp(exteriorFog, interiorFog, blend); }
        public float reverbAt(float exteriorReverb){ return MathX.lerp(exteriorReverb, interiorReverb, blend); }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type","InteriorBlend")
              .kvv("boundsMin",boundsMin[0],boundsMin[1],boundsMin[2])
              .kvv("boundsMax",boundsMax[0],boundsMax[1],boundsMax[2])
              .kv("interiorFog",interiorFog).kv("interiorReverb",interiorReverb)
              .kv("skyWeight",skyWeight).kv("blendTime",blendTime); w.endObj();
        }
        @Override public void load(JsonV n){
            com.hollow.core.JsonV min = n.node("boundsMin");
            com.hollow.core.JsonV max = n.node("boundsMax");
            if(min.map != null) boundsMin = new float[]{ min.flt("x",-5), min.flt("y",0), min.flt("z",-5) };
            if(max.map != null) boundsMax = new float[]{ max.flt("x",5), max.flt("y",3), max.flt("z",5) };
            interiorFog = n.flt("interiorFog", 0.02f); interiorReverb = n.flt("interiorReverb", 0.6f);
            skyWeight = n.flt("skyWeight", 0.05f); blendTime = n.flt("blendTime", 1.2f);
        }
    }

    // =====================================================================
    // WLD-021 — prewarm adjacent streaming cells (background JobQueue)
    // =====================================================================
    /** Decodes neighbor cell scene JSON into a byte cache BEFORE the listener gets
     *  close, so the on-screen load becomes a memory append. Uses JobQueue (inline in
     *  deterministic mode, worker threads on device). */
    public static final class PrewarmQueue {
        public final com.hollow.core.JobQueue jobs = com.hollow.core.JobQueue.inline();
        public int radiusCells = 1;             // how many cells beyond loadRadius to prewarm
        public final Map<String, byte[]> cache = new LinkedHashMap<>();
        public int prewarmed = 0, hits = 0, dropped = 0;
        public float prewarmRadiusBoost = 8f;   // effective prewarm range = loadRadius + boost

        /** submit prewarm jobs for every not-loaded cell within boost range */
        public int update(com.hollow.game.StreamingManager sm, World world, Vec3 listener,
                          com.hollow.core.FileSystem fs){
            if(sm == null || world == null || listener == null) return 0;
            int submitted = 0;
            for(Entity e : world.entities){
                StreamingVolume sv = e.get(StreamingVolume.class);
                if(sv == null || sv.loaded || sv.scene.isEmpty()) continue;
                float dist = e.worldPosition().dist(listener);
                if(dist > sv.loadRadius + prewarmRadiusBoost) continue;
                if(cache.containsKey(sv.scene)) continue;
                final String scene = sv.scene;
                jobs.submit(() -> {
                    try {
                        cache.put(scene, fs.read(scene));
                        prewarmed++;
                    } catch(Exception ex){ dropped++; }
                });
                submitted++;
            }
            jobs.waitIdle(100);
            return submitted;
        }

        /** true when the scene bytes are already decoded (fast path) */
        public boolean warm(String scene){ return cache.containsKey(scene); }
        public byte[] take(String scene){
            byte[] b = cache.remove(scene);
            if(b != null) hits++;
            return b;
        }
        public void clear(){ cache.clear(); }
        public String stats(){ return "prewarm[warmed=" + prewarmed + " hits=" + hits + " dropped=" + dropped + "]"; }
    }

    // =====================================================================
    // WLD-022 — memory-budget streaming (evict cheapest first)
    // =====================================================================
    /** Companion to StreamingManager: tracks an estimated byte cost per loaded cell
     *  and forces eviction of the cheapest (farthest-per-byte) cells when the budget
     *  is exceeded — distance streaming alone can blow memory in dense cities. */
    public static final class MemoryBudget {
        public long budgetBytes = 64 * 1024 * 1024;    // 64 MB default
        public int bytesPerEntity = 4096;              // heuristic cost model
        public final Map<String, Long> cellCost = new LinkedHashMap<>();
        public long evictions = 0;

        public void setBudgetMb(int mb){ budgetBytes = (long)mb * 1024 * 1024; }
        public void reportLoad(String cell, int entities){
            cellCost.put(cell, Math.max(1024, (long)entities * bytesPerEntity));
        }
        public void reportUnload(String cell){ cellCost.remove(cell); }
        public long used(){ long s = 0; for(long v : cellCost.values()) s += v; return s; }

        /** cells to evict to fit the budget; cheapest = cost / distance² (highest score
         *  is most expensive to keep far away... we evict farthest & biggest first).
         *  `distances` maps cell -> listener distance. */
        public List<String> evictPlan(Map<String, Float> distances){
            List<String> out = new ArrayList<>();
            long over = used() - budgetBytes;
            if(over <= 0) return out;
            List<Map.Entry<String, Long>> loaded = new ArrayList<>(cellCost.entrySet());
            // eviction score: cost × distance — far + heavy cells go first
            loaded.sort(Comparator.comparingDouble((Map.Entry<String, Long> e) -> {
                float d = Math.max(1f, distances.getOrDefault(e.getKey(), 1e6f));
                return e.getValue() * d;
            }).reversed());
            long freed = 0;
            for(Map.Entry<String, Long> e : loaded){
                if(freed >= over) break;
                out.add(e.getKey()); freed += e.getValue(); evictions++;
            }
            return out;
        }
        public String stats(){
            return "budget[used=" + (used() >> 10) + "KB / " + (budgetBytes >> 20) + "MB cells=" + cellCost.size() + " evictions=" + evictions + "]";
        }
    }

    // =====================================================================
    // WLD-023 — merge multiple scenes into one executable World
    // =====================================================================
    public static final class WorldMerger {
        /** merge scene JSON files into one scene JSON (entities concatenated).
         *  Duplicate entity names get a `sceneName.` prefix. */
        public static String mergeJson(List<String> sceneJsons, com.hollow.core.FileSystem fs){
            List<Object> entities = new ArrayList<>();
            Map<String,Object> environment = null;
            for(String path : sceneJsons){
                Map<String,Object> root;
                try {
                    root = Json.parseObj(fs.readText(path));
                } catch(Exception e){
                    Log.w("WorldMerger", "skip " + path + ": " + e.getMessage());
                    continue;
                }
                String prefix = scenePrefix(path);
                List<Object> list = Json.arr(root.get("entities"));
                if(list != null){
                    for(Object o : list){
                        Map<String,Object> en = Json.obj(o);
                        if(en == null) continue;
                        String name = Json.str(en.get("name"), "");
                        if(!name.isEmpty()) en.put("name", prefix + name);
                        entities.add(en);
                    }
                }
                if(environment == null) environment = Json.obj(root.get("environment"));
            }
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("name", "merged");
            if(environment != null) out.put("environment", environment);
            out.put("entities", entities);
            return Json.write(out);
        }

        /** merge directly into the live world via Scene.spawnFromJson. */
        public static int mergeInto(World world, List<String> sceneJsons, com.hollow.core.FileSystem fs){
            String text = mergeJson(sceneJsons, fs);
            Map<String,Object> root = Json.parseObj(text);
            List<Object> list = Json.arr(root.get("entities"));
            int n = 0;
            if(list != null) for(Object o : list){
                Map<String,Object> en = Json.obj(o);
                if(en != null){ com.hollow.scene.Scene.spawnFromJson(world, en); n++; }
            }
            return n;
        }

        static String scenePrefix(String path){
            String s = path;
            int slash = s.lastIndexOf('/');
            if(slash >= 0) s = s.substring(slash + 1);
            if(s.endsWith(".scene.json")) s = s.substring(0, s.length() - 11);
            else if(s.endsWith(".json")) s = s.substring(0, s.length() - 5);
            return s + ".";
        }
    }

    // =====================================================================
    // WLD-024 — world snapshot for regression tests
    // =====================================================================
    public static final class WorldSnapshot {
        /** canonical per-entity rows: name|tag|active|x|y|z (2 decimals) */
        public static List<String> rows(World w){
            List<String> out = new ArrayList<>();
            List<Entity> sorted = new ArrayList<>(w.entities);
            sorted.sort(Comparator.comparing(e -> e.name));
            for(Entity e : sorted){
                if(e.destroyed) continue;
                Vec3 p = e.worldPosition();
                out.add(e.name + "|" + e.tag + "|" + e.active
                    + "|" + String.format("%.2f", p.x)
                    + "|" + String.format("%.2f", p.y)
                    + "|" + String.format("%.2f", p.z));
            }
            return out;
        }

        /** 64-bit FNV-1a digest of the snapshot — stable across runs. */
        public static long hash(World w){
            long h = 0xCBF29CE484222325L;
            for(String r : rows(w)){
                for(int i = 0; i < r.length(); i++){
                    h ^= r.charAt(i);
                    h *= 0x100000001B3L;
                }
                h ^= '\n'; h *= 0x100000001B3L;
            }
            return h;
        }

        /** full JSON dump (entities + world blackboard + environment) */
        public static String toJson(World w){
            Map<String,Object> root = new LinkedHashMap<>();
            root.put("hash", Double.valueOf(hash(w)));
            root.put("rows", rows(w));
            root.put("blackboard", new LinkedHashMap<>(w.blackboard));
            if(w.environment != null) root.put("environment", w.environment);
            return Json.write(root);
        }

        /** regression check: hash equality, with a helpful diff on mismatch. */
        public static boolean matches(World w, long baseline, StringBuilder diff){
            long h = hash(w);
            if(h == baseline) return true;
            if(diff != null) diff.append("hash ").append(Long.toHexString(baseline))
                .append(" != ").append(Long.toHexString(h)).append('\n');
            return false;
        }
    }

    // =====================================================================
    // WLD-025 — headless world abstraction (server-side tests)
    // =====================================================================
    /** Facade that runs a World with zero renderer/audio/asset dependencies:
     *  fixed-dt ticks, deterministic RNG per tick, spawn/despawn counters and an
     *  event log. Used by dedicated servers and CI smoke tests. */
    public static final class HeadlessWorld {
        public final World world;
        public float fixedDelta = 1f / 60f;
        public long tick = 0;
        public int spawned, destroyed;
        public final List<String> eventLog = new ArrayList<>();
        public int maxEventLog = 256;
        public boolean running = true;

        public HeadlessWorld(String name){
            this.world = World.empty(name);
        }
        public HeadlessWorld(World w){ this.world = w; }

        public Entity spawn(String name, String tag){
            Entity e = world.spawn(name);
            e.tag = tag;
            spawned++;
            log("spawn " + name + " tag=" + tag);
            return e;
        }
        public void despawn(Entity e){ world.destroy(e); destroyed++; log("despawn " + e.name); }

        public void log(String msg){
            eventLog.add("t" + tick + " " + msg);
            while(eventLog.size() > maxEventLog) eventLog.remove(0);
        }

        /** advance exactly n fixed steps (deterministic: same inputs → same hash) */
        public void run(int steps){
            for(int i = 0; i < steps && running; i++){
                world.update(fixedDelta);
                tick++;
            }
        }
        public void stop(){ running = false; }
        public long snapshotHash(){ return WorldSnapshot.hash(world); }
        public String stats(){
            return "headless[" + world.name + " tick=" + tick + " entities=" + world.count()
                + " spawned=" + spawned + " destroyed=" + destroyed + "]";
        }
    }
}
