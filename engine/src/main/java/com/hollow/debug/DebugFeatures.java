package com.hollow.debug;

import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.MemoryStats;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.5 debug batch — DBG-010/012/013/017.
 *  Deterministic, headless-testable. */
public final class DebugFeatures {
    private DebugFeatures() {}

    // =====================================================================
    // DBG-010 — in-game entity inspector (select / edit)
    // =====================================================================
    /** Picks an entity (by name or camera ray proxy = nearest to a screen point),
     *  lists editable properties, applies edits with an undo stack and an audit log. */
    public static final class EntityInspector {
        public static final class Edit {
            public final String entity, property; public final Object before, after;
            public final long tick;
            Edit(String e, String p, Object b, Object a, long t){ entity = e; property = p; before = b; after = a; tick = t; }
        }
        public World world = null;
        public Entity selected = null;
        public final List<Edit> undoStack = new ArrayList<>();
        public final List<String> audit = new ArrayList<>();
        public int maxUndo = 32;
        public long tick = 0;
        public int edits = 0, undos = 0;

        public Entity select(String name){
            selected = world != null ? world.find(name) : null;
            audit("select " + (selected != null ? selected.name : "<none>"));
            return selected;
        }

        /** camera-proxy pick: nearest entity to a world-space point within radius. */
        public Entity pickAt(com.hollow.core.Vec3 point, float maxDist){
            if(world == null || point == null) return null;
            Entity best = null; float bestD = maxDist;
            for(Entity e : world.entities){
                if(e.destroyed) continue;
                float d = e.worldPosition().dist(point);
                if(d < bestD){ bestD = d; best = e; }
            }
            selected = best;
            audit("pick " + (best != null ? best.name : "<miss>"));
            return best;
        }

        public List<String> describeSelected(){
            List<String> out = new ArrayList<>();
            if(selected == null) return out;
            out.add(selected.name + " [" + selected.tag + "] active=" + selected.active);
            for(Map.Entry<String, com.hollow.scene.Component> c : selected.components.entrySet()){
                out.add("  " + c.getKey());
            }
            return out;
        }

        /** generic property setter: supports "active", "tag", and component meta
         *  keys ("meta:key"); unknown targets are reported false. */
        public boolean setProperty(String property, Object value){
            if(selected == null) return false;
            Object before;
            switch(property){
                case "active" -> before = selected.active;
                case "tag" -> before = selected.tag;
                default -> {
                    if(property.startsWith("meta:")){
                        String k = property.substring(5);
                        before = selected.getMeta(k);
                        applyEdit(property, before, value);
                        selected.setMeta(k, value);
                        edits++;
                        return true;
                    }
                    audit("unsupported property " + property);
                    return false;
                }
            }
            applyEdit(property, before, value);
            if(property.equals("active")) selected.active = Json.bool(value, true);
            else if(property.equals("tag")) selected.tag = String.valueOf(value);
            edits++;
            return true;
        }

        void applyEdit(String prop, Object before, Object after){
            undoStack.add(new Edit(selected.name, prop, before, after, tick));
            while(undoStack.size() > maxUndo) undoStack.remove(0);
            audit("set " + selected.name + "." + prop + " = " + after);
        }

        /** undo the most recent edit. */
        public boolean undo(){
            if(undoStack.isEmpty() || world == null) return false;
            Edit e = undoStack.remove(undoStack.size() - 1);
            Entity ent = world.find(e.entity);
            if(ent == null) return false;
            switch(e.property){
                case "active" -> ent.active = Json.bool(e.before, true);
                case "tag" -> ent.tag = String.valueOf(e.before);
                default -> { if(e.property.startsWith("meta:")) ent.setMeta(e.property.substring(5), e.before); }
            }
            undos++;
            audit("undo " + e.entity + "." + e.property);
            return true;
        }

        void audit(String line){
            audit.add("t" + tick + " " + line);
            while(audit.size() > 128) audit.remove(0);
        }
    }

    // =====================================================================
    // DBG-012 — input vortex replay (last 60 seconds)
    // =====================================================================
    /** Ring buffer of per-frame input snapshots; on demand the last N seconds are
     *  replayed frame-by-frame into a consumer (deterministic bug reproduction). */
    public static final class InputReplay {
        public static final class InputFrame {
            public final long tick;
            public final Map<String, Float> axes = new LinkedHashMap<>();
            public final List<String> buttonsDown = new ArrayList<>();
            public final List<String> buttonsUp = new ArrayList<>();
            public final Vec2Look look = new Vec2Look();
            InputFrame(long t){ tick = t; }
        }
        public static final class Vec2Look { public float dx, dy; }

        public interface Consumer { void applyFrame(InputFrame f); }

        private final java.util.ArrayDeque<InputFrame> ring = new java.util.ArrayDeque<>();
        private final List<InputFrame> recording = new ArrayList<>();
        public int capacityFrames = 60 * 60;            // 60s @ 60Hz
        public long tick = 0;
        public boolean recording_ = false;
        public long recordedFrames = 0, replayedFrames = 0;

        /** capture the current frame's input (platform layer calls once per frame). */
        public void record(com.hollow.game.InputActions in, List<String> down, List<String> up){
            InputFrame f = new InputFrame(tick);
            if(in != null){
                for(Map.Entry<String, com.hollow.game.InputActions.Action> e : in.all().entrySet()){
                    float v = e.getValue().value;
                    if(Math.abs(v) > 0.001f) f.axes.put(e.getKey(), v);
                }
                if(in.touchLook.x != 0 || in.touchLook.y != 0){ f.look.dx = in.touchLook.x; f.look.dy = in.touchLook.y; }
            }
            if(down != null) f.buttonsDown.addAll(down);
            if(up != null) f.buttonsUp.addAll(up);
            ring.addLast(f);
            while(ring.size() > capacityFrames) ring.removeFirst();
            recordedFrames++;
            if(recording_) recording.add(f);
        }

        /** begin a standalone clip (hard disk-ish "save vortex") */
        public void startClip(){ recording.clear(); recording_ = true; }
        public List<InputFrame> endClip(){ recording_ = false; return new ArrayList<>(recording); }

        /** replay the last `seconds` of buffered input into `consumer`. */
        public int replayLast(float seconds, float fixedDelta, Consumer consumer){
            int frames = Math.round(seconds / Math.max(0.0001f, fixedDelta));
            List<InputFrame> batch = new ArrayList<>(ring);
            int from = Math.max(0, batch.size() - frames);
            for(int i = from; i < batch.size(); i++){
                consumer.applyFrame(batch.get(i));
                replayedFrames++;
            }
            return batch.size() - from;
        }

        public int buffered(){ return ring.size(); }
        public void clear(){ ring.clear(); recording.clear(); }
        public String status(){
            return "replay[buffered=" + buffered() + "/" + capacityFrames + " recorded=" + recordedFrames
                + " replayed=" + replayedFrames + " clip=" + recording.size() + "]";
        }
    }

    // =====================================================================
    // DBG-013 — render cost heat map (colored overlay)
    // =====================================================================
    /** Screen partitioned into a grid; renderer hooks push per-cell GPU/CPU ms;
     *  heat map normalizes to a blue→red ramp and can emit an ASCII overlay. */
    public static final class RenderHeatMap {
        public final int cols, rows;
        public final float[] cellMs;
        public float maxMs = 1f;
        public double totalMs = 0;
        public int pushes = 0;

        public RenderHeatMap(int cols, int rows){
            this.cols = Math.max(1, cols); this.rows = Math.max(1, rows);
            cellMs = new float[this.cols * this.rows];
        }

        public void push(int col, int row, float ms){
            if(col < 0 || row < 0 || col >= cols || row >= rows) return;
            cellMs[row * cols + col] += ms;
            totalMs += ms;
            pushes++;
        }

        /** recomputes the normalization bound (call once per frame before sampling). */
        public void normalize(){
            maxMs = 0.001f;
            for(float v : cellMs) if(v > maxMs) maxMs = v;
        }
        public void endFrame(){ java.util.Arrays.fill(cellMs, 0f); totalMs = 0; }

        /** heat 0..1 for a cell */
        public float heat(int col, int row){
            return MathX.clamp(cellMs[row * cols + col] / maxMs, 0, 1);
        }

        /** ramp: 0=blue .34=green .67=yellow 1=red (debug overlay classic) */
        public static int colorRamp(float h){
            h = MathX.clamp(h, 0, 1);
            int r, g, b;
            if(h < 0.34f){ float t = h / 0.34f; r = (int)(t * 0); g = (int)(80 + t * 120); b = (int)(255 - t * 175); }
            else if(h < 0.67f){ float t = (h - 0.34f) / 0.33f; r = (int)(t * 255); g = (int)(200 - t * 40); b = (int)(80 - t * 80); }
            else { float t = (h - 0.67f) / 0.33f; r = 255; g = (int)(160 - t * 160); b = (int)(0); }
            return 0xFF000000 | (MathX.clamp(r, 0, 255) << 16) | (MathX.clamp(g, 0, 255) << 8) | MathX.clamp(b, 0, 255);
        }

        /** ASCII visualization for CLI (each cell = one char). */
        public String ascii(){
            StringBuilder b = new StringBuilder();
            for(int r = 0; r < rows; r++){
                for(int c = 0; c < cols; c++){
                    float h = heat(c, r);
                    b.append(h < 0.02f ? '.' : h < 0.2f ? ':' : h < 0.45f ? 'o' : h < 0.75f ? 'O' : '@');
                }
                b.append('\n');
            }
            return b.toString();
        }
    }

    // =====================================================================
    // DBG-017 — long-term memory leak monitor (weekly report)
    // =====================================================================
    /** Samples heap usage on a cadence, keeps a rolling window, computes a smoothed
     *  growth trend, and raises a leak suspicion when growth is monotonic across
     *  windows. Emits human-readable periodic reports. */
    public static final class LeakMonitor {
        public static final class Sample {
            public final long wallClock; public final long heapBytes; public final long liveEntities;
            public final long tick;
            Sample(long t, long heap, long ent, long tick){ wallClock = t; heapBytes = heap; liveEntities = ent; this.tick = tick; }
        }
        public final List<Sample> window = new ArrayList<>();
        public int maxSamples = 7 * 24 * 60;            // one week at 1-min cadence
        public float sampleIntervalSec = 60f;
        private float timer = 0f;
        public long samples = 0;
        public boolean alert = false;
        public String alertReason = "";
        public double trendBytesPerHour = 0;

        /** sim clock poll (GameApp frame); uses MemoryStats + world counts. */
        public void poll(float dt, World world, long simTick, long wallNow){
            timer += dt;
            if(timer < sampleIntervalSec) return;
            timer = 0f;
            long heap = MemoryStats.snapshot().heapUsed;
            long ent = world != null ? world.count() : 0;
            window.add(new Sample(wallNow, heap, ent, simTick));
            samples++;
            while(window.size() > maxSamples) window.remove(0);
            recomputeTrend();
        }

        void recomputeTrend(){
            if(window.size() < 10){ alert = false; return; }
            // least-squares slope over heap vs wall-clock
            int n = window.size();
            double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
            long x0 = window.get(0).wallClock;
            for(Sample s : window){
                double x = (s.wallClock - x0) / 1000.0;   // seconds
                double y = s.heapBytes;
                sumX += x; sumY += y; sumXY += x * y; sumXX += x * x;
            }
            double denom = n * sumXX - sumX * sumX;
            double slope = Math.abs(denom) < 1e-9 ? 0 : (n * sumXY - sumX * sumY) / denom;   // bytes/sec
            trendBytesPerHour = slope * 3600.0;
            // suspicion: sustained growth over >= 1h window AND heap grew > 8MB
            double hours = ((window.get(n-1).wallClock - x0) / 1000.0) / 3600.0;
            double grown = (double)window.get(n-1).heapBytes - window.get(0).heapBytes;
            if(hours >= 1.0 && trendBytesPerHour > 2 * 1024 * 1024 && grown > 8 * 1024 * 1024){
                alert = true;
                alertReason = String.format("heap growing %.1f MB/h over %.1f h (+%.0f MB)",
                    trendBytesPerHour / 1048576.0, hours, grown / 1048576.0);
            } else {
                alert = false;
                alertReason = "";
            }
        }

        /** weekly report (deterministic text). */
        public String weeklyReport(){
            StringBuilder b = new StringBuilder("== memory leak monitor ==\n");
            b.append("samples: ").append(samples).append(" (window ").append(window.size()).append(")\n");
            if(!window.isEmpty()){
                Sample first = window.get(0), last = window.get(window.size() - 1);
                b.append(String.format("heap: %d MB -> %d MB%n", first.heapBytes >> 20, last.heapBytes >> 20));
                b.append(String.format("entities: %d -> %d%n", first.liveEntities, last.liveEntities));
            }
            b.append(String.format("trend: %+.2f MB/hour%n", trendBytesPerHour / 1048576.0));
            b.append("suspicion: ").append(alert ? "YES — " + alertReason : "none").append('\n');
            return b.toString();
        }
    }
}
