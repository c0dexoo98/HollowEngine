package com.hollow.script;

import java.util.LinkedHashMap;
import java.util.Map;

/** Typed variable table (global game state or per-entity). Thread-confined to engine thread. */
public final class VarTable {
    private final Map<String,Object> vars = new LinkedHashMap<>();

    public void set(String k, Object v){ if(v == null) vars.remove(k); else vars.put(k, v); }
    public Object get(String k){ return vars.get(k); }

    public double num(String k, double def){
        Object v = vars.get(k);
        return v instanceof Number n ? n.doubleValue() : def;
    }
    public boolean bool(String k, boolean def){
        Object v = vars.get(k);
        if(v instanceof Boolean b) return b;
        if(v instanceof Number n) return n.doubleValue() != 0;
        if(v instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }
    public String str(String k, String def){
        Object v = vars.get(k);
        return v == null ? def : String.valueOf(v);
    }
    public boolean has(String k){ return vars.containsKey(k); }
    public void add(String k, double delta){ set(k, num(k, 0) + delta); }
    public void toggle(String k){ set(k, !bool(k, false)); }

    public Map<String,Object> snapshot(){ return new LinkedHashMap<>(vars); }
    @SuppressWarnings("unchecked")
    public void restore(Map<String,Object> saved){
        vars.clear();
        if(saved != null) vars.putAll(saved);
    }
    public int size(){ return vars.size(); }
    public Iterable<String> keys(){ return vars.keySet(); }
}
