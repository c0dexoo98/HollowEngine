package com.hollow.platform;

import com.hollow.core.Log;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * v3.0.0 PLATFORM: plugin system — jar-based extensions with lifecycle, ordering
 * and dependency declarations, no engine rebuild needed.
 *
 * A plugin is any jar on the classpath/plugins dir containing a class that
 * implements HollowPlugin (declared via META-INF/services or auto-scanned).
 * Plugins get: onLoad (register console commands / recipes / quest hooks),
 * per-tick update, and onUnload cleanup. Load order honors dependencies.
 */
public final class PluginHost {

    /** the plugin contract */
    public interface HollowPlugin {
        /** unique id, e.g. "com.example.speedrun" */
        String id();
        /** human name for logs/UI */
        default String displayName(){ return id(); }
        /** plugin ids that must load before this one */
        default String[] dependsOn(){ return new String[0]; }
        /** plugin version */
        default String version(){ return "1.0"; }
        /** called once when loaded */
        default void onLoad(Context ctx) throws Exception { }
        /** fixed-tick update (may be every tick; keep cheap) */
        default void onUpdate(Context ctx, float dt) { }
        /** called on unload/shutdown */
        default void onUnload() { }
    }

    /** what the host hands to plugins */
    public interface Context {
        com.hollow.core.Console console();
        com.hollow.script.EventBus events();
        Map<String,Object> state();      // shared blackboard
    }

    public static final class Loaded {
        public final HollowPlugin plugin;
        public boolean enabled = true;
        public long ticks = 0;
        Loaded(HollowPlugin plugin){ this.plugin = plugin; }
    }

    public final Map<String, Loaded> loaded = new LinkedHashMap<>();
    private final Context ctx;
    private final List<Runnable> pendingConsoleUnregisters = new ArrayList<>();

    public PluginHost(Context ctx){ this.ctx = ctx; }

    /** register an already-constructed plugin (classpath embedding) */
    public boolean register(HollowPlugin p){
        if(p == null || p.id() == null || loaded.containsKey(p.id())) return false;
        try {
            p.onLoad(ctx);
            loaded.put(p.id(), new Loaded(p));
            Log.i("plugin", "plugin '" + p.displayName() + "' v" + p.version() + " loaded");
            return true;
        } catch(Throwable t){
            Log.e("plugin", "plugin '" + p.id() + "' failed to load: " + t);
            return false;
        }
    }

    /** load plugins from a directory of jars (ServiceLoader discovery) */
    public int loadDir(File dir){
        if(dir == null || !dir.isDirectory()) return 0;
        File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
        if(jars == null || jars.length == 0) return 0;
        List<HollowPlugin> discovered = new ArrayList<>();
        try {
            URL[] urls = new URL[jars.length];
            for(int i = 0; i < jars.length; i++) urls[i] = jars[i].toURI().toURL();
            URLClassLoader cl = new URLClassLoader(urls, getClass().getClassLoader());
            ServiceLoader<HollowPlugin> sl = ServiceLoader.load(HollowPlugin.class, cl);
            for(HollowPlugin p : sl) discovered.add(p);
        } catch(Throwable t){
            Log.e("plugin", "plugin dir scan failed: " + t);
        }
        discovered.sort(Comparator.comparingInt(p -> 0));   // stable; deps resolved below
        int count = 0;
        for(HollowPlugin p : dependencyOrder(discovered)) if(register(p)) count++;
        return count;
    }

    /** topological-ish ordering by dependsOn (missing deps -> load anyway, warn) */
    public static List<HollowPlugin> dependencyOrder(List<HollowPlugin> plugins){
        List<HollowPlugin> out = new ArrayList<>();
        List<HollowPlugin> remaining = new ArrayList<>(plugins);
        while(!remaining.isEmpty()){
            boolean progressed = false;
            for(int i = 0; i < remaining.size(); i++){
                HollowPlugin p = remaining.get(i);
                boolean depsMet = true;
                for(String dep : p.dependsOn()){
                    boolean have = out.stream().anyMatch(o -> o.id().equals(dep)) || plugins.stream().noneMatch(o -> o.id().equals(dep));
                    if(!have){ depsMet = false; break; }
                }
                if(depsMet){
                    out.add(p);
                    remaining.remove(i);
                    progressed = true;
                    break;
                }
            }
            if(!progressed){   // cycle: flush in declaration order
                out.addAll(remaining);
                break;
            }
        }
        return out;
    }

    /** fixed-tick broadcast to enabled plugins */
    public void update(float dt){
        for(Loaded l : loaded.values()){
            if(!l.enabled) continue;
            try {
                l.plugin.onUpdate(ctx, dt);
                l.ticks++;
            } catch(Throwable t){
                Log.e("plugin", "plugin '" + l.plugin.id() + "' update error: " + t);
                l.enabled = false;   // quarantine a crashing plugin
            }
        }
    }

    public boolean setEnabled(String id, boolean enabled){
        Loaded l = loaded.get(id);
        if(l == null) return false;
        l.enabled = enabled;
        return true;
    }

    /** unload one plugin */
    public boolean unload(String id){
        Loaded l = loaded.remove(id);
        if(l == null) return false;
        try { l.plugin.onUnload(); } catch(Throwable t){ Log.e("plugin", "unload: " + t); }
        return true;
    }

    /** unload everything (shutdown) */
    public void unloadAll(){
        for(String id : new ArrayList<>(loaded.keySet())) unload(id);
    }

    public int enabledCount(){
        int c = 0;
        for(Loaded l : loaded.values()) if(l.enabled) c++;
        return c;
    }
}
