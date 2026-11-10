package com.hollow.debug;

import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.4 DBG/SCR batch — DBG-008..020 / SCR-007..015 (engine-side parts).
 *  All deterministic and headless-testable. */
public final class DebugSystems {
    private DebugSystems(){}

    // =====================================================================
    // DBG-008 + DBG-009 — in-game console panel data + FPS graph
    // =====================================================================
    public static final class ConsolePanel {
        public final List<String> lines = new ArrayList<>();
        public int maxLines = 200;
        public boolean visible = false;
        public boolean attached = false;

        /** install a wrapping sink that mirrors logs into this panel. */
        public void attach(){
            if(attached) return;
            com.hollow.core.Log.setSink((lvl, tag, msg) -> push(lvl + "[" + tag + "] " + msg));
            attached = true;
        }
        public void detach(){ attached = false; }
        public void push(String line){
            lines.add(line);
            if(lines.size() > maxLines) lines.remove(0);
        }
        public List<String> tail(int n){
            int from = Math.max(0, lines.size() - n);
            return lines.subList(from, lines.size());
        }
    }

    public static final class FpsGraph {
        private final float[] history;
        private int cursor = 0; private long frames = 0;
        public float min = Float.MAX_VALUE, max = 0f, avg = 0f;

        public FpsGraph(int samples){ history = new float[Math.max(8, samples)]; }
        public void push(float frameMs){
            history[cursor] = frameMs;
            cursor = (cursor + 1) % history.length;
            frames++;
            float sum = 0f; min = Float.MAX_VALUE; max = 0f;
            int n = Math.min((int)frames, history.length);
            for(int i = 0; i < n; i++){
                float v = history[i];
                if(v <= 0) continue;
                sum += v;
                if(v < min) min = v;
                if(v > max) max = v;
            }
            avg = n > 0 ? sum / n : 0f;
        }
        public float[] bars(){
            float[] out = new float[history.length];
            for(int i = 0; i < history.length; i++) out[i] = history[(cursor + i) % history.length];
            return out;
        }
        public int sampleCount(){ return history.length; }
        public boolean spikeLast(){ return history[(cursor - 1 + history.length) % history.length] > avg * 1.5f; }
    }

    // =====================================================================
    // DBG-011 — pause + frame stepping
    // =====================================================================
    public static final class FrameStepper {
        public boolean paused = false;
        public int stepFrames = 0;
        public int framesRunSincePause = 0;

        public void pause(){ paused = true; stepFrames = 0; }
        public void resume(){ paused = false; }
        public void step(int n){ stepFrames = n; framesRunSincePause = 0; }
        public boolean allowFrame(){
            if(!paused) return true;
            if(stepFrames > 0){
                stepFrames--; framesRunSincePause++;
                if(stepFrames == 0) paused = true;
                return true;
            }
            return false;
        }
    }

    // =====================================================================
    // DBG-014 — per-system stats table
    // =====================================================================
    public static final class SystemStats {
        public static final class Row {
            public final String system; public double ms; public int calls;
            public Row(String s){ system = s; }
        }
        private final Map<String,Row> rows = new LinkedHashMap<>();
        public Row row(String sys){ return rows.computeIfAbsent(sys, Row::new); }
        public void add(String sys, double ms){ Row r = row(sys); r.ms += ms; r.calls++; }
        public void reset(){ rows.clear(); }
        public List<Row> sortedByCost(){
            List<Row> out = new ArrayList<>(rows.values());
            out.sort(Comparator.comparingDouble((Row r) -> r.ms).reversed());
            return out;
        }
        public String table(){
            StringBuilder b = new StringBuilder();
            b.append(String.format("%-14s %10s %8s%n", "system", "ms", "calls"));
            for(Row r : sortedByCost()) b.append(String.format("%-14s %10.2f %8d%n", r.system, r.ms, r.calls));
            return b.toString();
        }
        public int size(){ return rows.size(); }
    }

    // =====================================================================
    // DBG-015/016 — project asserts (JSON) + script test hook
    // =====================================================================
    public static final class ProjectAsserts {
        public static final class Result { public String name; public boolean pass; public String message; }
        public static List<Result> run(List<Object> defs, com.hollow.script.VarTable vars){
            List<Result> out = new ArrayList<>();
            for(Object o : defs){
                Map<String,Object> d = Json.obj(o);
                if(d == null) continue;
                Result r = new Result();
                r.name = Json.str(d.get("name"), Json.str(d.get("type"), "assert"));
                String type = Json.str(d.get("type"), "");
                switch(type){
                    case "script" -> {
                        String expr = Json.str(d.get("expr"), "true");
                        r.pass = evalExpr(expr, vars);
                        if(!r.pass) r.message = "expr false: " + expr;
                    }
                    case "var" -> {
                        String key = Json.str(d.get("key"), "");
                        double want = Json.num(d.get("value"), 0);
                        double got = vars.num(key, Double.NaN);
                        r.pass = got == want;
                        if(!r.pass) r.message = key + "=" + got + " want " + want;
                    }
                    default -> { r.pass = true; r.message = "skipped (unknown type " + type + ")"; }
                }
                out.add(r);
            }
            return out;
        }
        static boolean evalExpr(String expr, com.hollow.script.VarTable vars){
            String[] ops = { ">=", "<=", "==", "!=", ">", "<" };
            for(String op : ops){
                if(expr.contains(op)){
                    String[] p = expr.split(java.util.regex.Pattern.quote(op));
                    double a = vars.num(p[0].trim(), Double.NaN);
                    double b;
                    String rs = p[1].trim();
                    if(rs.startsWith("var:")) b = vars.num(rs.substring(4), 0);
                    else { try { b = Double.parseDouble(rs); } catch(Exception e){ b = vars.num(rs, Double.NaN); } }
                    return switch(op){
                        case ">=" -> a >= b; case "<=" -> a <= b;
                        case "==" -> a == b; case "!=" -> a != b;
                        case ">" -> a > b; case "<" -> a < b;
                        default -> false;
                    };
                }
            }
            return vars.bool(expr.trim(), false);
        }
    }

    // =====================================================================
    // DBG-018 — live EventBus monitor with filters
    // =====================================================================
    public static final class EventMonitor {
        public static final class Ev { public final String name; public final long frame; public final int dataCount;
            Ev(String n, long f, int d){ name = n; frame = f; dataCount = d; } }
        public final List<Ev> feed = new ArrayList<>();
        public final List<String> include = new ArrayList<>();
        public final List<String> exclude = new ArrayList<>();
        public int maxFeed = 256;
        public long eventsSeen = 0, eventsFiltered = 0;

        public void record(String name, long frame, Map<String,Object> data){
            eventsSeen++;
            if(!include.isEmpty() && include.stream().noneMatch(name::startsWith)){ eventsFiltered++; return; }
            for(String ex : exclude) if(name.startsWith(ex)){ eventsFiltered++; return; }
            feed.add(new Ev(name, frame, data == null ? 0 : data.size()));
            while(feed.size() > maxFeed) feed.remove(0);
        }
        public List<Ev> recent(int n){
            int from = Math.max(0, feed.size() - n);
            return feed.subList(from, feed.size());
        }
        public List<Map.Entry<String,Integer>> topEvents(int n){
            Map<String,Integer> counts = new LinkedHashMap<>();
            for(Ev e : feed) counts.merge(e.name, 1, Integer::sum);
            List<Map.Entry<String,Integer>> out = new ArrayList<>(counts.entrySet());
            out.sort(Comparator.comparingInt((Map.Entry<String,Integer> e) -> -e.getValue()));
            return out.subList(0, Math.min(n, out.size()));
        }
    }

    // =====================================================================
    // DBG-019 + DBG-020 — bug report bundle + local telemetry
    // =====================================================================
    public static final class Telemetry {
        public long sessionStartMs;
        public long frames;
        public long errors;
        public long warnings;
        public double maxFrameMs = 0;
        public final Map<String,Long> counters = new LinkedHashMap<>();

        public Telemetry(){ sessionStartMs = System.currentTimeMillis(); }
        public void frame(float ms){ frames++; if(ms > maxFrameMs) maxFrameMs = ms; }
        public void error(){ errors++; }
        public void warn(){ warnings++; }
        public void count(String key){ counters.merge(key, 1L, Long::sum); }
        public Map<String,Object> report(){
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("uptimeMin", (double)((System.currentTimeMillis() - sessionStartMs) / 60000));
            m.put("frames", (double)frames);
            m.put("errors", (double)errors);
            m.put("warnings", (double)warnings);
            m.put("maxFrameMs", maxFrameMs);
            m.put("counters", new LinkedHashMap<>(counters));
            return m;
        }
        public String bugReport(List<String> lastLogs){
            StringBuilder b = new StringBuilder();
            b.append("== HollowEngine bug report ==\n");
            for(Map.Entry<String,Object> e : report().entrySet()) b.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            b.append("\n-- last logs --\n");
            for(String l : lastLogs) b.append(l).append('\n');
            return b.toString();
        }
    }

    // =====================================================================
    // SCR-007 — script hot reload (stamp-based)
    // =====================================================================
    public static final class HotReload {
        public static final class Watched { public String path; public long stamp; public long size; }
        private final Map<String,Watched> watched = new LinkedHashMap<>();
        public int reloads = 0;

        public void watch(String path, long stamp, long size){
            Watched w = new Watched(); w.path = path; w.stamp = stamp; w.size = size;
            watched.put(path, w);
        }
        public List<String> poll(Map<String,long[]> current){
            List<String> changed = new ArrayList<>();
            for(Map.Entry<String,long[]> e : current.entrySet()){
                Watched w = watched.get(e.getKey());
                if(w == null){ watch(e.getKey(), e.getValue()[0], e.getValue()[1]); continue; }
                if(w.stamp != e.getValue()[0] || w.size != e.getValue()[1]){
                    w.stamp = e.getValue()[0]; w.size = e.getValue()[1];
                    changed.add(e.getKey());
                    reloads++;
                }
            }
            return changed;
        }
        public int watchedCount(){ return watched.size(); }
    }

    // =====================================================================
    // SCR-009 — richer stdlib for Expr (math/time/string helpers)
    // =====================================================================
    public static final class ScriptStd {
        public static double call(String fn, double a, double b){
            return switch(fn){
                case "abs" -> Math.abs(a);
                case "min" -> Math.min(a, b);
                case "max" -> Math.max(a, b);
                case "sqrt" -> Math.sqrt(Math.max(0, a));
                case "pow" -> Math.pow(a, b);
                case "sin" -> Math.sin(Math.toRadians(a));
                case "cos" -> Math.cos(Math.toRadians(a));
                case "floor" -> Math.floor(a);
                case "ceil" -> Math.ceil(a);
                case "round" -> Math.round(a);
                default -> 0;
            };
        }
        public static String str(String fn, String s){
            return switch(fn){
                case "upper" -> s.toUpperCase();
                case "lower" -> s.toLowerCase();
                case "trim" -> s.trim();
                default -> s;
            };
        }
        public static double timeSeconds(long tick, float fixedDt){ return tick * fixedDt; }
    }

    // =====================================================================
    // SCR-010 — coroutine/sleep for scripts
    // =====================================================================
    public static final class ScriptCoroutines {
        public static final class Co {
            public final String id; public float waitLeft; public int steps;
            public final Object state;
            public Co(String id, float wait, int steps, Object state){ this.id = id; this.waitLeft = wait; this.steps = steps; this.state = state; }
        }
        public final List<Co> active = new ArrayList<>();
        public int completed = 0;

        public void launch(String id, float delay, int steps, Object state){
            active.add(new Co(id, delay, steps, state));
        }
        public List<String> update(float dt){
            List<String> ready = new ArrayList<>();
            for(int i = active.size()-1; i >= 0; i--){
                Co c = active.get(i);
                if(c.waitLeft > 0f){ c.waitLeft -= dt; continue; }
                ready.add(c.id);
                if(c.steps > 0){
                    c.steps--;
                    if(c.steps == 0){ active.remove(i); completed++; }
                } else {
                    active.remove(i); completed++;
                }
            }
            return ready;
        }
        public int activeCount(){ return active.size(); }
    }

    // =====================================================================
    // SCR-013/014 — sandbox permission flags + per-script profiler
    // =====================================================================
    public static final class Sandbox {
        public boolean allowFs = false;
        public boolean allowNet = false;
        public boolean allowAudio = true;
        public boolean allowUi = true;
        public int maxStepsPerTick = 2048;
        public boolean allowed(String api){
            return switch(api){
                case "fs" -> allowFs; case "net" -> allowNet;
                case "audio" -> allowAudio; case "ui" -> allowUi;
                default -> false;
            };
        }
    }

    public static final class ScriptProfiler {
        public static final class Entry { public String file; public long calls; public double totalMs;
            public Entry(String f){ file = f; } }
        private final Map<String,Entry> entries = new LinkedHashMap<>();
        public void end(String file, double ms){
            Entry e = entries.computeIfAbsent(file, Entry::new);
            e.calls++; e.totalMs += ms;
        }
        public List<Entry> hottest(){
            List<Entry> out = new ArrayList<>(entries.values());
            out.sort(Comparator.comparingDouble((Entry e) -> e.totalMs).reversed());
            return out;
        }
        public String report(){
            StringBuilder b = new StringBuilder();
            for(Entry e : hottest())
                b.append(String.format("%-24s %8.2f ms  %6d calls  %6.3f ms/call%n",
                    e.file, e.totalMs, e.calls, e.totalMs / Math.max(1, e.calls)));
            return b.toString();
        }
        public int size(){ return entries.size(); }
    }

    // =====================================================================
    // SCR-015 — auto API doc generation
    // =====================================================================
    public static final class ApiDocs {
        public static String generate(Map<String,String> actions, Map<String,String> checks, List<String> stdFns){
            StringBuilder b = new StringBuilder();
            b.append("# FlowScript API\n\n## actions\n");
            List<String> akeys = new ArrayList<>(actions.keySet());
            Collections.sort(akeys);
            for(String k : akeys) b.append("- `").append(k).append("` — ").append(actions.get(k)).append('\n');
            b.append("\n## conditions\n");
            List<String> ckeys = new ArrayList<>(checks.keySet());
            Collections.sort(ckeys);
            for(String k : ckeys) b.append("- `").append(k).append("` — ").append(checks.get(k)).append('\n');
            b.append("\n## stdlib\n");
            for(String f : stdFns) b.append("- `").append(f).append("()`\n");
            return b.toString();
        }
    }
}
