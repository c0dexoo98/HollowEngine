package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Noise;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** v2.4 world systems batch — WLD-011..WLD-020 as scene components.
 *  All deterministic (Noise/hash based), headless-safe, JSON persistable. */
public final class WorldSystems {
    private WorldSystems(){}

    // =====================================================================
    // WLD-011 — water domains: river flow + tide
    // =====================================================================
    /** Water domain with directional flow (river) and tide (sinusoidal level).
     *  Physics buoyancy reads surfaceHeight() via EnvironmentPhysics. */
    public static final class WaterDomain extends Component {
        public Vec3 flowDir = new Vec3(0, 0, -1);
        public float flowSpeed = 0.5f;        // m/s surface current
        public boolean tide = false;
        public float tideAmplitude = 0.4f;    // meters
        public float tidePeriodMinutes = 180f;
        public float baseLevel = 0f;          // y of calm surface
        private float clock = 0f;

        public float surfaceHeight(){
            if(!tide) return baseLevel;
            float w = (float)(2.0 * Math.PI * clock / Math.max(1f, tidePeriodMinutes * 60f));
            return baseLevel + tideAmplitude * (float)Math.sin(w);
        }
        /** push vector applied to floating bodies at position p (buoyancy helpers). */
        public Vec3 currentAt(Vec3 p){ return new Vec3(flowDir.x * flowSpeed, 0, flowDir.z * flowSpeed); }

        @Override public void onUpdate(float dt){ clock += dt; }
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","WaterDomain");
            out.kvv("flowDir", flowDir.x, flowDir.y, flowDir.z);
            out.kv("flowSpeed", flowSpeed); out.kv("tide", tide);
            out.kv("tideAmplitude", tideAmplitude); out.kv("tidePeriodMinutes", tidePeriodMinutes);
            out.kv("baseLevel", baseLevel); out.endObj();
        }
        @Override public void load(JsonV n){
            flowDir.set(arr3(n.get("flowDir"), 0f, 0f, -1f));
            flowSpeed = n.flt("flowSpeed", 0.5f); tide = n.bool("tide", false);
            tideAmplitude = n.flt("tideAmplitude", 0.4f); tidePeriodMinutes = n.flt("tidePeriodMinutes", 180f);
            baseLevel = n.flt("baseLevel", 0f);
        }
    }

    // =====================================================================
    // WLD-012 — inter-room portals with exact occlusion culling
    // =====================================================================
    /** Portal pair: looking through portal A shows room B (cell frustum culling).
     *  Renderer consults PortalSystem.visibleCells() before drawing. */
    public static final class Portal extends Component {
        public String targetCell = "";        // scene/cell name behind this portal
        public String targetPortal = "";      // matching portal entity name in target cell
        public float width = 2f, height = 2.4f;
        public boolean open = true;
        public boolean oneWay = false;

        @Override public void save(JsonW out){
            out.obj(); out.kv("type","Portal");
            out.kv("targetCell", targetCell); out.kv("targetPortal", targetPortal);
            out.kv("width", width); out.kv("height", height);
            out.kv("open", open); out.kv("oneWay", oneWay); out.endObj();
        }
        @Override public void load(JsonV n){
            targetCell = n.str("targetCell",""); targetPortal = n.str("targetPortal","");
            width = n.flt("width", 2f); height = n.flt("height", 2.4f);
            open = n.bool("open", true); oneWay = n.bool("oneWay", false);
        }
    }
    /** Static portal graph: cell -> visible cells through open portals. */
    public static final class PortalSystem {
        private final World world;
        public PortalSystem(World w){ world = w; }
        /** cells visible from inside 'from' (portal chains followed up to depth). */
        public List<String> visibleCells(String from, int depth){
            List<String> out = new ArrayList<>();
            collect(from, depth, out);
            return out;
        }
        private void collect(String cell, int depth, List<String> out){
            if(depth <= 0) return;
            for(Entity e : world.entities){
                if(e.destroyed || !e.active) continue;
                Portal p = e.get(Portal.class);
                if(p == null || !p.open || !cellOf(e).equals(cell)) continue;
                if(!out.contains(p.targetCell)){
                    out.add(p.targetCell);
                    collect(p.targetCell, depth - 1, out);
                }
            }
        }
        private String cellOf(Entity e){
            return e.hasMeta("cell") ? String.valueOf(e.getMeta("cell")) : "";
        }
        public static void tagCell(Entity e, String cell){ e.setMeta("cell", cell); }
    }

    // =====================================================================
    // WLD-014 — global wind: base vector + gust/pulse, feeds foliage shader
    // =====================================================================
    public static final class GlobalWind extends Component {
        public Vec3 baseDir = new Vec3(1, 0, 0.3f);
        public float baseStrength = 0.4f;      // 0..1 shader weight
        public float gustStrength = 0.6f;      // extra during gust
        public float gustPeriodSec = 7f;
        public float turbulence = 0.25f;
        /** current shader weight (renderer reads each frame) */
        public float shaderWeight = 0f;
        private float clock = 0f;

        public Vec3 vectorAt(Vec3 p){
            // per-position turbulence so foliage doesn't move in lockstep
            float n = perlin(p.x * 0.08f + clock * 0.5f, p.z * 0.08f);
            float s = baseStrength + gustStrength * gustEnvelope() + turbulence * n;
            s = MathX.clamp(s, 0f, 2f);
            return new Vec3(baseDir.x * s, 0, baseDir.z * s);
        }
        /** 0..1 gust envelope (two sine octaves). */
        public float gustEnvelope(){
            float w = (float)(2.0 * Math.PI * clock / Math.max(0.1f, gustPeriodSec));
            return MathX.clamp(0.5f + 0.5f * (float)(Math.sin(w) + 0.5 * Math.sin(2.31 * w)) * 0.66f, 0f, 1f);
        }
        @Override public void onUpdate(float dt){
            clock += dt;
            shaderWeight = MathX.clamp(baseStrength + gustStrength * gustEnvelope(), 0f, 2f);
        }
        private static final Noise NZ = new Noise(424242);
        private static float perlin(float x, float y){ return NZ.perlin2(x, y); }
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","GlobalWind");
            out.kvv("baseDir", baseDir.x, baseDir.y, baseDir.z);
            out.kv("baseStrength", baseStrength); out.kv("gustStrength", gustStrength);
            out.kv("gustPeriodSec", gustPeriodSec); out.kv("turbulence", turbulence); out.endObj();
        }
        @Override public void load(JsonV n){
            baseDir.set(arr3(n.get("baseDir"), 1f, 0f, 0.3f));
            baseStrength = n.flt("baseStrength", 0.4f); gustStrength = n.flt("gustStrength", 0.6f);
            gustPeriodSec = n.flt("gustPeriodSec", 7f); turbulence = n.flt("turbulence", 0.25f);
        }
    }

    // =====================================================================
    // WLD-015 — rule-based scattering: biome/slope/height driven prop placement
    // =====================================================================
    /** Deterministic scatter: given terrain bounds + rules (height band, slope max,
     *  density per m², poisson-ish jitter), places entities. CLI/test friendly. */
    public static final class ScatterRules {
        public String prefab;                 // entity name template to spawn
        public float minHeight = 0f, maxHeight = 1000f;
        public float maxSlopeDeg = 35f;
        public float densityPer100m2 = 8f;
        public int seed = 1337;
        public float scaleMin = 0.8f, scaleMax = 1.3f;
        public int maxCount = 512;
    }
    public interface HeightProbe {
        float heightAt(float x, float z);
        default float slopeAt(float x, float z){ return 0f; }
    }

    /** scatter props over area using rules; returns spawned entities count. */
    public static int scatter(World w, ScatterRules r, HeightProbe probe, float cx, float cz, float sizeX, float sizeZ){
        com.hollow.core.RandomStream rng = new com.hollow.core.RandomStream(r.seed);
        float area = sizeX * sizeZ;
        int want = (int)Math.min(r.maxCount, area * r.densityPer100m2 / 100f);
        int placed = 0;
        for(int i = 0; i < want * 3 && placed < want; i++){       // rejection sampling
            float x = cx + (rng.nextFloat() - 0.5f) * sizeX;
            float z = cz + (rng.nextFloat() - 0.5f) * sizeZ;
            float h = probe.heightAt(x, z);
            if(h < r.minHeight || h > r.maxHeight) continue;
            float slope = probe.slopeAt(x, z);
            if(slope > r.maxSlopeDeg) continue;
            Entity e = w.spawn(r.prefab);
            e.position.set(x, h, z);
            float s = r.scaleMin + rng.nextFloat() * (r.scaleMax - r.scaleMin);
            e.scale.set(s, s, s);
            e.rotation.set(com.hollow.core.Quat.fromEuler(0, rng.nextFloat() * 360f, 0));
            placed++;
        }
        return placed;
    }

    // =====================================================================
    // WLD-018 — world bounds: clamp physics/bodies inside playfield
    // =====================================================================
    public static final class WorldBounds extends Component {
        public float minX = -200, maxX = 200, minZ = -200, maxZ = 200;
        public float minY = -50, maxY = 500;
        public boolean softWalls = true;      // push back instead of hard clamp
        public float wallPush = 8f;           // m/s inward velocity

        public void clamp(com.hollow.physics.RigidBody body){
            if(body == null || body.entity == null) return;
            Vec3 p = body.entity.position;
            Vec3 v = body.velocity;
            if(softWalls){
                if(p.x < minX && v.x < 0) v.x = wallPush;
                if(p.x > maxX && v.x > 0) v.x = -wallPush;
                if(p.z < minZ && v.z < 0) v.z = wallPush;
                if(p.z > maxZ && v.z > 0) v.z = -wallPush;
            } else {
                p.x = MathX.clamp(p.x, minX, maxX);
                p.z = MathX.clamp(p.z, minZ, maxZ);
            }
            if(p.y < minY){ p.y = minY; if(v.y < 0) v.y = 0; }
            if(p.y > maxY){ p.y = maxY; if(v.y > 0) v.y = 0; }
        }
        public boolean contains(Vec3 p){
            return p.x >= minX && p.x <= maxX && p.z >= minZ && p.z <= maxZ && p.y >= minY && p.y <= maxY;
        }
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","WorldBounds");
            out.kv("minX", minX); out.kv("maxX", maxX);
            out.kv("minZ", minZ); out.kv("maxZ", maxZ);
            out.kv("minY", minY); out.kv("maxY", maxY);
            out.kv("softWalls", softWalls); out.kv("wallPush", wallPush); out.endObj();
        }
        @Override public void load(JsonV n){
            minX = n.flt("minX",-200); maxX = n.flt("maxX",200);
            minZ = n.flt("minZ",-200); maxZ = n.flt("maxZ",200);
            minY = n.flt("minY",-50); maxY = n.flt("maxY",500);
            softWalls = n.bool("softWalls", true); wallPush = n.flt("wallPush", 8f);
        }
    }

    // =====================================================================
    // WLD-019 + WLD-020 — checkpoint/zone triggers with tags
    // =====================================================================
    /** Axis-aligned trigger volume: fires EventBus events on enter/leave.
     *  Zone tags (safe/danger/quest) readable by AI and FlowScript. */
    public static final class TriggerZone extends Component {
        public Vec3 size = new Vec3(4, 3, 4);      // full extents (world scale applied)
        public String onEnterEvent = "";
        public String onLeaveEvent = "";
        public String zoneTag = "";                 // "safe" / "danger" / "quest" / custom
        public boolean once = false;                // fire enter only first time
        public String requireTag = "";              // only trigger for entities with this tag
        private boolean inside = false;
        private boolean firedOnce = false;
        public int occupantCount = 0;

        private boolean contains(World w, Entity e){
            Vec3 c = entity.worldPosition();
            Vec3 s = new Vec3(size.x * entity.worldScale().x * 0.5f,
                              size.y * entity.worldScale().y * 0.5f,
                              size.z * entity.worldScale().z * 0.5f);
            Vec3 p = e.worldPosition();
            return Math.abs(p.x - c.x) <= s.x && Math.abs(p.y - c.y) <= s.y && Math.abs(p.z - c.z) <= s.z;
        }

        @Override public void onUpdate(float dt){
            com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
            World w = app != null ? app.world : null;
            if(w == null) return;
            // player-centric trigger (app.player), plus any tagged entity scan (cheap: player only)
            Entity target = app.player;
            if(target == null || target.destroyed) return;
            if(!requireTag.isEmpty() && !requireTag.equals(target.tag)) return;
            boolean nowIn = contains(w, target);
            if(nowIn && !inside){
                occupantCount++;
                if(!(once && firedOnce) && !onEnterEvent.isEmpty()){
                    firedOnce = true;
                    java.util.Map<String,Object> data = new java.util.LinkedHashMap<>();
                    data.put("zone", entity.name); data.put("tag", zoneTag);
                    w.blackboard.put("__zoneTag", zoneTag);
                    app.svc.events.fire(onEnterEvent, data);
                }
            } else if(!nowIn && inside){
                occupantCount = Math.max(0, occupantCount - 1);
                if(!once && !onLeaveEvent.isEmpty()){
                    java.util.Map<String,Object> data = new java.util.LinkedHashMap<>();
                    data.put("zone", entity.name); data.put("tag", zoneTag);
                    app.svc.events.fire(onLeaveEvent, data);
                }
            }
            inside = nowIn;
        }
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","TriggerZone");
            out.kvv("size", size.x, size.y, size.z);
            out.kv("onEnterEvent", onEnterEvent); out.kv("onLeaveEvent", onLeaveEvent);
            out.kv("zoneTag", zoneTag); out.kv("once", once); out.kv("requireTag", requireTag); out.endObj();
        }
        @Override public void load(JsonV n){
            size.set(arr3(n.get("size"), 4f, 3f, 4f));
            onEnterEvent = n.str("onEnterEvent",""); onLeaveEvent = n.str("onLeaveEvent","");
            zoneTag = n.str("zoneTag",""); once = n.bool("once", false); requireTag = n.str("requireTag","");
        }
    }

    // =====================================================================
    // WLD-017 — audio zones (reverb + music) declared in scene
    // =====================================================================
    public static final class AudioZone extends Component {
        public String musicTrack = "";          // track name played inside ("" = keep current)
        public float reverbWet = -1f;           // -1 = don't override
        public float radius = 8f;
        public float blendMeters = 2f;          // crossfade distance
        public String zoneTag = "";

        public float weightAt(Vec3 p){
            float d = p.dist(entity.worldPosition());
            return 1f - MathX.clamp((d - (radius - blendMeters)) / Math.max(0.01f, blendMeters), 0f, 1f);
        }
        @Override public void save(JsonW out){
            out.obj(); out.kv("type","AudioZone");
            out.kv("musicTrack", musicTrack); out.kv("reverbWet", reverbWet);
            out.kv("radius", radius); out.kv("blendMeters", blendMeters); out.kv("zoneTag", zoneTag); out.endObj();
        }
        @Override public void load(JsonV n){
            musicTrack = n.str("musicTrack",""); reverbWet = n.flt("reverbWet", -1f);
            radius = n.flt("radius", 8f); blendMeters = n.flt("blendMeters", 2f); zoneTag = n.str("zoneTag","");
        }
    }
    /** Resolves dominant audio zone each frame; mirrors into world blackboard so
     *  FlowScript/AI can react (music zone events). */
    public static final class AudioZoneResolver {
        public AudioZone current;
        public float currentWeight = 0f;
        public void update(World w, Vec3 listener){
            AudioZone best = null; float bw = 0f;
            for(Entity e : w.entities){
                if(!e.active || e.destroyed) continue;
                AudioZone z = e.get(AudioZone.class);
                if(z == null) continue;
                float wt = z.weightAt(listener);
                if(wt > bw){ bw = wt; best = z; }
            }
            if(best != current){
                current = best;
                w.blackboard.put("__musicZone", best == null ? "" : best.musicTrack);
                w.blackboard.put("__zoneTag", best == null ? "" : best.zoneTag);
                if(best != null && !best.musicTrack.isEmpty()){
                    java.util.Map<String,Object> data = new java.util.LinkedHashMap<>();
                    data.put("track", best.musicTrack);
                    com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
                    if(app != null && app.svc != null) app.svc.events.fire("music_zone_changed", data);
                }
            }
            currentWeight = bw;
        }
    }

    private static Vec3 arr3(Object o, float dx, float dy, float dz){
        Vec3 v = new Vec3(dx, dy, dz);
        if(o instanceof java.util.List<?> l && l.size() >= 3){
            if(l.get(0) instanceof Number n0) v.x = n0.floatValue();
            if(l.get(1) instanceof Number n1) v.y = n1.floatValue();
            if(l.get(2) instanceof Number n2) v.z = n2.floatValue();
        } else if(o instanceof java.util.Map<?,?> m){
            Object x=m.get("x"), y=m.get("y"), z=m.get("z");
            if(x instanceof Number nx) v.x=nx.floatValue();
            if(y instanceof Number ny) v.y=ny.floatValue();
            if(z instanceof Number nz) v.z=nz.floatValue();
        }
        return v;
    }
}
