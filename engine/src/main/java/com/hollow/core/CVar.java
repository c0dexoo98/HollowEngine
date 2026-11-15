package com.hollow.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Console variables (CVar) — Unreal/Quake style tunables.
 *  Types: INT / FLOAT / BOOL / STRING. Registered once, settable at runtime,
 *  with optional change callbacks and optional persistence to a config file. */
public final class CVar {
    public enum Type { INT, FLOAT, BOOL, STRING }

    public final String name;
    public final Type type;
    public final String help;
    private int iv; private float fv; private boolean bv; private String sv;
    private final int minI, maxI; private final float minF, maxF;    private final List<java.util.function.Consumer<CVar>> listeners = new ArrayList<>();
    private boolean dirty = false; // changed since last save

    private static final Map<String, CVar> REGISTRY = new LinkedHashMap<>();

    private CVar(String name, Type type, String help){
        this(name, type, help, Integer.MIN_VALUE, Integer.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE);
    }
    private CVar(String name, Type type, String help, int minI, int maxI, float minF, float maxF){
        this.name = name; this.type = type; this.help = help;
        this.minI = minI; this.maxI = maxI; this.minF = minF; this.maxF = maxF;
    }

    // ---------------- registration ----------------
    public static CVar registerInt(String name, int def, String help){ return register(name, Type.INT, def, 0f, null, help); }
    public static CVar registerInt(String name, int def, int min, int max, String help){
        CVar existing = REGISTRY.get(name);
        if(existing != null) return existing; // idempotent registration
        CVar v = new CVar(name, Type.INT, help, min, max, -Float.MAX_VALUE, Float.MAX_VALUE);
        v.iv = Math.max(min, Math.min(max, def));   // N7: the bounded overload dropped the default (always started at 0)
        v.bv = v.iv != 0;
        synchronized(CVar.class){ REGISTRY.put(name, v); }
        return v;
    }
    public static CVar registerFloat(String name, float def, String help){ return register(name, Type.FLOAT, 0, def, null, help); }
    public static CVar registerBool(String name, boolean def, String help){ return register(name, Type.BOOL, def?1:0, 0f, null, help); }
    public static CVar registerString(String name, String def, String help){ return register(name, Type.STRING, 0, 0f, def, help); }

    private static synchronized CVar register(String name, Type type, int iv, float fv, String sv, String help){
        CVar existing = REGISTRY.get(name);
        if(existing != null) return existing; // idempotent registration
        CVar v = new CVar(name, type, help);
        v.iv = iv; v.fv = fv; v.sv = sv;
        v.bv = iv != 0;
        REGISTRY.put(name, v);
        return v;
    }

    public static CVar get(String name){ return REGISTRY.get(name); }
    public static boolean exists(String name){ return REGISTRY.containsKey(name); }
    public static List<CVar> all(){ List<CVar> out = new ArrayList<>(REGISTRY.values()); Collections.sort(out, (a,b)->a.name.compareTo(b.name)); return out; }
    public static int count(){ return REGISTRY.size(); }

    // ---------------- getters ----------------
    public int getInt(){ return type == Type.BOOL ? (bv?1:0) : iv; }
    public float getFloat(){ return type == Type.INT ? iv : fv; }
    public boolean getBool(){ return type == Type.INT ? iv != 0 : bv; }
    public String getString(){ return switch(type){ case STRING -> sv == null ? "" : sv; case BOOL -> bv ? "true" : "false"; case INT -> Integer.toString(iv); case FLOAT -> Float.toString(fv); }; }
    public Object value(){ return switch(type){ case STRING -> sv; case BOOL -> bv; case INT -> iv; case FLOAT -> fv; }; }
    public boolean isDirty(){ return dirty; }
    public void clearDirty(){ dirty = false; }

    // ---------------- setters ----------------
    public void set(String value){
        try {
            switch(type){
                case INT -> setInt(Integer.parseInt(value.trim()));
                case FLOAT -> setFloat(Float.parseFloat(value.trim()));
                case BOOL -> setBool(value.trim().equals("1") || value.trim().equalsIgnoreCase("true") || value.trim().equalsIgnoreCase("yes") || value.trim().equalsIgnoreCase("on"));
                case STRING -> setString(value);
            }
        } catch(NumberFormatException e){ Log.w("CVar", "bad value '" + value + "' for " + name + " (" + type + ")"); }
    }
    public void setInt(int v){
        if(type != Type.INT && type != Type.BOOL) return;
        int c = Math.max(minI, Math.min(maxI, v));
        boolean changed = (type == Type.INT ? iv : (bv?1:0)) != c;
        if(type == Type.INT) iv = c; else bv = c != 0;
        if(changed){ dirty = true; fire(); }
    }
    public void setFloat(float v){
        if(type != Type.FLOAT) return;
        float c = Math.max(minF, Math.min(maxF, v));
        if(c != fv){ fv = c; dirty = true; fire(); }
    }
    public void setBool(boolean v){
        if(type == Type.BOOL){ if(v != bv){ bv = v; dirty = true; fire(); } }
        else if(type == Type.INT) setInt(v ? 1 : 0);
    }
    public void setString(String v){
        if(type != Type.STRING) return;
        if(!java.util.Objects.equals(sv, v)){ sv = v; dirty = true; fire(); }
    }

    // ---------------- change notifications ----------------
    public CVar onChange(java.util.function.Consumer<CVar> fn){ listeners.add(fn); return this; }
    private void fire(){ for(java.util.function.Consumer<CVar> fn : listeners){ try { fn.accept(this); } catch(Exception e){ Log.e("CVar", "listener failed for " + name, e); } } }

    // ---------------- persistence ----------------
    /** save all dirty (or all with all=true) CVars as `name = value` lines */
    public static synchronized void save(String path, boolean all) throws IOException {
        StringBuilder b = new StringBuilder(512);
        b.append("# HollowEngine CVar config\n");
        for(CVar v : REGISTRY.values()){
            if(!all && !v.dirty) continue;
            b.append(v.name).append(" = ").append(v.getString()).append('\n');
        }
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if(p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
        java.nio.file.Files.writeString(p, b.toString());
        for(CVar v : REGISTRY.values()) v.clearDirty();
    }
    /** load and apply; unknown names are registered as STRING (so files round-trip) */
    public static synchronized void load(String path) throws IOException {
        java.nio.file.Path p = java.nio.file.Paths.get(path);
        if(!java.nio.file.Files.exists(p)) return;
        String text = java.nio.file.Files.readString(p);
        for(String line : text.split("\n")){
            line = line.trim();
            if(line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            int eq = line.indexOf('=');
            if(eq <= 0) continue;
            String name = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            CVar v = REGISTRY.get(name);
            if(v == null) v = registerString(name, val, "loaded from config");
            else v.set(val);
        }
    }
    /** console table dump: name, value, type, help */
    public static synchronized String table(String filter){
        StringBuilder b = new StringBuilder(1024);
        String f = filter == null ? "" : filter.toLowerCase();
        for(CVar v : all()){
            if(!f.isEmpty() && !v.name.toLowerCase().contains(f) && !v.help.toLowerCase().contains(f)) continue;
            b.append(String.format("%-34s = %-14s %-6s %s%n", v.name, v.getString(), v.type.name().toLowerCase(), v.help));
        }
        return b.toString();
    }
}
