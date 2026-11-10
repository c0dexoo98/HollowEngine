package com.hollow.core;

import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime memory telemetry (MEM): heap usage, GC pressure, allocation counters,
 *  registered pools/arenas. Powers the `mem` console command and profile reports. */
public final class MemoryStats {

    public static final class Snapshot {
        public long heapUsed, heapMax, heapCommitted;
        public long gcCollections, gcTimeMs;
        public long allocBytes;                 // engine-tracked allocations
        public int poolsTracked;
        public String topPool;
        public long nonHeapUsed;
        public int liveThreads;
        public float gcPressure;
    }

    public interface Tracked { String memStats(); }

    private static final Map<String, Tracked> TRACKED = new LinkedHashMap<>();
    private static long allocBytes = 0;
    private static long lastGcCount = -1, lastGcTime = -1;
    private static float gcPressure = 0f; // 0..1 smoothed GC activity estimate

    private MemoryStats(){}

    // ---------------- tracking ----------------
    public static synchronized void track(String name, Tracked t){ TRACKED.put(name, t); }
    public static synchronized void untrack(String name){ TRACKED.remove(name); }
    public static synchronized void noteAlloc(long bytes){ allocBytes += bytes; }
    public static void resetAllocCounter(){ allocBytes = 0; }

    // ---------------- sampling ----------------
    public static Snapshot snapshot(){
        Snapshot s = new Snapshot();
        Runtime rt = Runtime.getRuntime();
        s.heapUsed = rt.totalMemory() - rt.freeMemory();
        s.heapMax = rt.maxMemory();
        s.heapCommitted = rt.totalMemory();
        java.lang.management.MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
        s.nonHeapUsed = mx.getNonHeapMemoryUsage().getUsed();
        s.liveThreads = ManagementFactory.getThreadMXBean().getThreadCount();

        long gc = 0, t = 0;
        for(GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()){
            long c = b.getCollectionCount(); long tm = b.getCollectionTime();
            if(c > 0) gc += c;
            if(tm > 0) t += tm;
        }
        s.gcCollections = gc; s.gcTimeMs = t;
        if(lastGcCount >= 0 && gc > lastGcCount){
            float delta = (gc - lastGcCount) * 0.25f;         // collections since last sample
            float dtWeight = lastGcTime >= 0 ? Math.min(1f, (t - lastGcTime) / 100f) : 0.5f;
            gcPressure = Math.min(1f, gcPressure * 0.7f + delta * 0.1f + dtWeight * 0.05f);
        }
        lastGcCount = gc; lastGcTime = t;
        s.gcPressure = gcPressure;
        s.allocBytes = allocBytes;
        synchronized(MemoryStats.class){ s.poolsTracked = TRACKED.size(); }
        return s;
    }

    public static float gcPressure(){ return gcPressure; }

    /** multi-line human report for console/logs */
    public static String report(){
        Snapshot s = snapshot();
        StringBuilder b = new StringBuilder(512);
        b.append(String.format("heap: %.1f / %.1f MB (%.0f%%)%n", s.heapUsed / 1048576f, s.heapMax / 1048576f, 100f * s.heapUsed / Math.max(1, s.heapMax)));
        b.append(String.format("non-heap: %.1f MB   threads: %d%n", s.nonHeapUsed / 1048576f, s.liveThreads));
        b.append(String.format("gc: %d collections, %d ms total, pressure %.2f%n", s.gcCollections, s.gcTimeMs, s.gcPressure));
        b.append(String.format("engine allocs: %.2f MB tracked, %d subsystems%n", s.allocBytes / 1048576f, s.poolsTracked));
        synchronized(MemoryStats.class){
            for(Map.Entry<String, Tracked> e : TRACKED.entrySet()){
                try { b.append(String.format("  %-22s %s%n", e.getKey(), e.getValue().memStats())); }
                catch(Exception ex){ b.append("  ").append(e.getKey()).append(" <error>").append('\n'); }
            }
        }
        return b.toString();
    }

    /** advice string: OK / WARN / HIGH based on heap ratio + GC pressure */
    public static String health(){
        Snapshot s = snapshot();
        float ratio = (float) s.heapUsed / Math.max(1, s.heapMax);
        if(ratio > 0.9f || gcPressure > 0.8f) return "HIGH";
        if(ratio > 0.75f || gcPressure > 0.5f) return "WARN";
        return "OK";
    }
}
