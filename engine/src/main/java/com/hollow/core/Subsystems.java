package com.hollow.core;

import java.util.ArrayList;
import java.util.List;

/** Ordered subsystem registry (CORE): modules register with a name + init/shutdown order;
 *  the registry guarantees init() ascending and shutdown() descending, with timing + error isolation. */
public final class Subsystems {

    public interface Subsystem {
        String name();
        /** @return error message or null on success */
        default String init(){ return null; }
        default void shutdown(){}
        default String status(){ return "ok"; }
    }

    private static final class Entry {
        final Subsystem s; final int order; boolean initialized = false;
        Entry(Subsystem s, int order){ this.s = s; this.order = order; }
    }

    private static final List<Entry> REGISTRY = new ArrayList<>();
    private static boolean initializedAll = false;

    public static synchronized void register(Subsystem s, int order){
        for(Entry e : REGISTRY) if(e.s.name().equals(s.name())) return; // idempotent
        REGISTRY.add(new Entry(s, order));
        REGISTRY.sort((a, b) -> Integer.compare(a.order, b.order));
    }
    public static synchronized void register(Subsystem s){ register(s, 100); }

    public static synchronized Result<String> initAll(){
        if(initializedAll) return Result.ok("already initialized");
        for(Entry e : REGISTRY){
            if(e.initialized) continue;
            long t0 = System.nanoTime();
            try {
                String err = e.s.init();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if(err != null){
                    Log.e("Subsystems", "init FAILED for " + e.s.name() + ": " + err);
                    return Result.fail(e.s.name() + ": " + err);
                }
                e.initialized = true;
                Log.d("Subsystems", "init " + e.s.name() + " in " + ms + " ms");
            } catch(Throwable t){
                Log.e("Subsystems", "init threw for " + e.s.name(), t);
                return Result.fail(e.s.name() + ": " + t);
            }
        }
        initializedAll = true;
        return Result.ok("initialized " + REGISTRY.size() + " subsystems");
    }

    public static synchronized void shutdownAll(){
        for(int i = REGISTRY.size() - 1; i >= 0; i--){
            Entry e = REGISTRY.get(i);
            if(!e.initialized) continue;
            try { e.s.shutdown(); } catch(Throwable t){ Log.e("Subsystems", "shutdown failed for " + e.s.name(), t); }
            e.initialized = false;
        }
        initializedAll = false;
    }

    public static synchronized String statusTable(){
        StringBuilder b = new StringBuilder(256);
        b.append(String.format("%-3s %-24s %-6s %s%n", "#", "subsystem", "state", "status"));
        int i = 1;
        for(Entry e : REGISTRY){
            String status;
            try { status = e.s.status(); } catch(Throwable t){ status = "<error>"; }
            b.append(String.format("%-3d %-24s %-6s %s%n", i++, e.s.name(), e.initialized ? "on" : "off", status));
        }
        return b.toString();
    }
    public static synchronized int count(){ return REGISTRY.size(); }
    public static synchronized boolean allInitialized(){ return initializedAll; }
}
