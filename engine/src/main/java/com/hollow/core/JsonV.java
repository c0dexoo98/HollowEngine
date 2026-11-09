package com.hollow.core;

import com.hollow.core.Json;

import java.util.List;
import java.util.Map;

/** Thin typed wrapper over a parsed JSON node (Map/List tree). */
public final class JsonV {
    public final Map<String,Object> map;

    public JsonV(){ this.map = new java.util.LinkedHashMap<>(); }
    @SuppressWarnings("unchecked")
    public JsonV(Object o){ this.map = o instanceof Map ? (Map<String,Object>)o : new java.util.LinkedHashMap<>(); }
    public static JsonV parse(String s){ return new JsonV(Json.parse(s)); }

    public boolean has(String k){ return map.containsKey(k); }
    public Object get(String k){ return map.get(k); }
    public JsonV node(String k){ return new JsonV(map.get(k)); }
    public List<Object> list(String k){ return Json.arr(map.get(k)); }
    public String str(String k, String def){ return Json.str(map.get(k), def); }
    public double num(String k, double def){ return Json.num(map.get(k), def); }
    public float flt(String k, float def){ return (float) num(k, def); }
    public int int32(String k, int def){ return Json.int32(map.get(k), def); }
    public boolean bool(String k, boolean def){ return Json.bool(map.get(k), def); }
}
