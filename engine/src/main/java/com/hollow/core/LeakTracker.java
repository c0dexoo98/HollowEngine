package com.hollow.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Leak tracker (MEM-020): GPU objects, file handles and native-backed engine
 *  resources register here at creation and report at dispose. On shutdown
 *  {@link #reportLeaks()} lists everything still alive so the console shows
 *  exactly what leaked (and where it was allocated). */
public final class LeakTracker {
    private LeakTracker(){}

    public interface TrackedResource { void onDispose(); }

    private static final class Entry {
        final String type, where;
        final TrackedResource resource;
        Entry(String type, String where, TrackedResource r){ this.type = type; this.where = where; this.resource = r; }
    }

    private static final Map<Integer, Entry> LIVE = new ConcurrentHashMap<>();
    private static int nextId = 1;
    private static long totalCreated = 0, totalDisposed = 0;

    /** register a resource; returns its tracking id */
    public static synchronized int register(String type, String where, TrackedResource r){
        int id = nextId++;
        LIVE.put(id, new Entry(type, where, r));
        totalCreated++;
        return id;
    }

    /** mark disposed — removes from the live set */
    public static synchronized void dispose(int id){
        if(LIVE.remove(id) != null) totalDisposed++;
    }

    public static synchronized int liveCount(){ return LIVE.size(); }
    public static long totalCreated(){ return totalCreated; }
    public static long totalDisposed(){ return totalDisposed; }

    /** multi-line leak report for shutdown (empty string = nothing leaked) */
    public static synchronized String reportLeaks(){
        if(LIVE.isEmpty()) return "";
        StringBuilder b = new StringBuilder(256);
        b.append("LEAKS: ").append(LIVE.size()).append(" resource(s) alive at shutdown:\n");
        int shown = 0;
        for(Map.Entry<Integer, Entry> e : LIVE.entrySet()){
            if(shown++ >= 32){ b.append("  ... and ").append(LIVE.size() - 32).append(" more\n"); break; }
            b.append(String.format("  #%d %-18s %s%n", e.getKey(), e.getValue().type, e.getValue().where));
        }
        return b.toString();
    }

    /** per-type counts for the mem console command */
    public static synchronized Map<String, Integer> byType(){
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for(Entry e : LIVE.values()) out.merge(e.type, 1, Integer::sum);
        return out;
    }

    /** test hook: forget everything (doesn't dispose resources) */
    public static synchronized void resetForTest(){ LIVE.clear(); }
}
