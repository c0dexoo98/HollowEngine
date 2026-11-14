package com.hollow.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Zone-based CPU profiler (Unreal Insights style, lightweight).
 *  Usage:
 *    int z = Profiler.begin("Physics"); ... Profiler.end(z);
 *    // or try(Profiler.Zone z = Profiler.zone("Anim")){ ... }
 *    Profiler.frame();            // called once per frame; aggregates samples
 *    Profiler.dumpCsv(fs, path);   // export per-zone stats (avg/max/calls per frame)
 *  Thread-safe enough for the single-threaded engine loop (no locks on hot path).
 */
public final class Profiler {
    private Profiler() {}

    public static final class Stat {
        public final String name;
        public long totalNs = 0;       // accumulated over the frame window
        public long calls = 0;
        public long maxNs = 0;         // max single call inside window
        // history
        public double sumPerFrameNs = 0;
        public double sumMaxPerFrameNs = 0;
        public double sumCallsPerFrame = 0;
        public long frames = 0;
        public double peakPerFrameNs = 0;
        Stat(String n){ name = n; }
    }

    private static final Map<String, Stat> stats = new java.util.LinkedHashMap<>();
    private static final List<String> order = new ArrayList<>();

    // hot-path state (single thread)
    private static final int DEPTH = 64;
    private static final long[] startNs = new long[DEPTH];
    private static final String[] startName = new String[DEPTH];
    private static int depth = 0;
    private static boolean enabled = false;
    private static long frameCount = 0;

    public static void setEnabled(boolean on){
        if(on && !enabled){ reset(); }
        enabled = on;
    }
    public static boolean enabled(){ return enabled; }
    public static long frames(){ return frameCount; }

    /** begins a zone; returns zone handle to pass to end() */
    public static int begin(String name){
        if(!enabled || depth >= DEPTH) return -1;
        startNs[depth] = System.nanoTime();
        startName[depth] = name;
        return depth++;
    }

    public static void end(int handle){
        if(handle < 0 || handle >= depth) return;
        long dt = System.nanoTime() - startNs[handle];
        String name = startName[handle];
        // pop back to handle (guard against unbalanced)
        depth = handle;
        Stat s = stats.get(name);
        if(s == null){ s = new Stat(name); stats.put(name, s); order.add(name); }
        s.totalNs += dt;
        s.calls++;
        if(dt > s.maxNs) s.maxNs = dt;
    }

    /** convenience: try-with-resources zone */
    public static Zone zone(String name){ return new Zone(name); }
    public static final class Zone implements AutoCloseable {
        private final int h;
        Zone(String name){ h = begin(name); }
        @Override public void close(){ end(h); }
    }

    /** time a callable (rare paths only — allocation of lambda is fine there) */
    public static void time(String name, Runnable r){
        int h = begin(name);
        try { r.run(); } finally { end(h); }
    }

    /** marks the end of a frame; call once per frame after all systems */
    public static void frame(){
        if(!enabled) return;
        // fold current window into history
        for(String key : order){
            Stat s = stats.get(key);
            if(s.calls > 0){
                s.frames++;                                   // per-frame history (was frozen after first summary)
                s.sumPerFrameNs += s.totalNs;
                s.sumMaxPerFrameNs += s.maxNs;
                s.sumCallsPerFrame += s.calls;
                double perFrame = s.totalNs;
                if(perFrame > s.peakPerFrameNs) s.peakPerFrameNs = perFrame;
                s.totalNs = 0; s.calls = 0; s.maxNs = 0;
            }
        }
        frameCount++;
    }

    public static void reset(){
        stats.clear(); order.clear();
        depth = 0; frameCount = 0;
    }

    /** one summary line per zone, sorted by avg time desc */
    public static List<String> summaryLines(){
        List<String> out = new ArrayList<>();
        if(frameCount == 0) return out;
        List<Stat> list = new ArrayList<>();
        for(String k : order){
            Stat s = stats.get(k);
            if(s.frames == 0 && s.sumCallsPerFrame > 0){ s.frames = 1; }   // legacy zones from before per-frame counting
            list.add(s);
        }
        list.sort((a, b) -> Double.compare(b.sumPerFrameNs / Math.max(1, b.frames), a.sumPerFrameNs / Math.max(1, a.frames)));
        out.add(String.format("%-24s %10s %10s %10s %8s", "zone", "avg ms", "max ms", "peak ms", "calls/f"));
        for(Stat s : list){
            double avgMs = (s.sumPerFrameNs / Math.max(1, s.frames)) / 1e6;
            double maxMs = (s.sumMaxPerFrameNs / Math.max(1, s.frames)) / 1e6;
            double peakMs = s.peakPerFrameNs / 1e6;
            double calls = s.sumCallsPerFrame / Math.max(1, s.frames);
            out.add(String.format("%-24s %10.3f %10.3f %10.3f %8.1f", s.name, avgMs, maxMs, peakMs, calls));
        }
        return out;
    }

    /** CSV export: zone,avg_ms,max_ms,peak_ms,calls_per_frame,frames */
    public static boolean dumpCsv(com.hollow.core.FileSystem fs, String path){
        StringBuilder sb = new StringBuilder("zone,avg_ms,max_ms,peak_ms,calls_per_frame,frames\n");
        for(String k : order){
            Stat s = stats.get(k);
            double f = Math.max(1, s.frames == 0 ? frameCount : s.frames);
            sb.append(k).append(',')
              .append(String.format("%.4f", s.sumPerFrameNs / f / 1e6)).append(',')
              .append(String.format("%.4f", s.sumMaxPerFrameNs / f / 1e6)).append(',')
              .append(String.format("%.4f", s.peakPerFrameNs / 1e6)).append(',')
              .append(String.format("%.2f", s.sumCallsPerFrame / f)).append(',')
              .append(frameCount).append('\n');
        }
        try {
            fs.write(path, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch(Exception e){
            Log.w("Profiler", "csv dump failed: " + e.getMessage());
            return false;
        }
    }

    public static int zoneCount(){ return order.size(); }
}
