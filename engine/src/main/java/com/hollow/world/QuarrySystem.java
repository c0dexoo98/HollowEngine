package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v8.0 — quarry / resource node system (HollowEngine 8.0 'kingdom').
 *  Extractable nodes (mine, quarry, fishery...) with finite reserves that
 *  deplete under work and regenerate over time; depleted nodes go idle until
 *  they recover. Daily extraction totals are exposed for wiring into
 *  SettlementSystem stockpiles. Deterministic, JSON-persisted.
 */
public final class QuarrySystem {

    public static final class Node {
        public final String id;
        public String resource;                      // "stone", "iron", "fish"...
        public int workers = 0;
        public float yieldPerWorker = 1f;
        public float reserves = 100f;
        public float maxReserves = 100f;
        public float regenPerDay = 2f;
        public float minReserve = 5f;                // reactivation threshold
        public boolean depleted = false;
        public float lastExtract = 0f;
        public Node(String id, String resource){ this.id = id; this.resource = resource; }
        public Node workers(int n){ workers = n; return this; }
        public Node yield(float y){ yieldPerWorker = y; return this; }
        public Node reserve(float cur, float max){ reserves = cur; maxReserves = max; return this; }
        public Node regen(float r){ regenPerDay = r; return this; }
    }

    public interface Listener { void onQuarryEvent(String type, String nodeId, float amount); }
    public Listener listener;

    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public int day = 0;
    public float lastExtractTotal = 0f;

    public Node define(Node n){ nodes.put(n.id, n); return n; }
    public Node node(String id){ return nodes.get(id); }

    /** daily extraction pass; returns total extracted (unitless resource) */
    public float tickDay(){
        day++;
        float total = 0f;
        for(Node n : nodes.values()){
            n.lastExtract = 0f;
            if(!n.depleted){
                float want = n.workers * n.yieldPerWorker;
                float got = Math.min(want, n.reserves);
                n.reserves -= got;
                n.lastExtract = got;
                total += got;
                if(n.reserves <= 0.0001f){
                    n.depleted = true;
                    if(listener != null) listener.onQuarryEvent("depleted", n.id, got);
                }
            } else if(n.reserves >= n.minReserve){
                n.depleted = false;                  // back to work once it recovered
                if(listener != null) listener.onQuarryEvent("recovered", n.id, n.reserves);
            }
            n.reserves = Math.min(n.maxReserves, n.reserves + n.regenPerDay);
        }
        lastExtractTotal = total;
        return total;
    }

    /** extraction output by resource for yesterday's tick */
    public Map<String, Float> extractionByResource(){
        Map<String, Float> out = new LinkedHashMap<>();
        for(Node n : nodes.values())
            out.put(n.resource, out.getOrDefault(n.resource, 0f) + n.lastExtract);
        return out;
    }

    public int depletedCount(){
        int n = 0;
        for(Node node : nodes.values()) if(node.depleted) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("nodes");
        for(Node n : nodes.values()){
            w.obj().kv("id", n.id).kv("res", n.resource).kv("w", n.workers)
              .kv("ypw", n.yieldPerWorker).kv("reserves", n.reserves).kv("max", n.maxReserves)
              .kv("regen", n.regenPerDay).kv("min", n.minReserve)
              .kv("dep", n.depleted).endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            List<Object> ns = n.list("nodes");
            if(ns == null) return;
            for(Object o : ns){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String id = Json.str(m.get("id"), null);
                if(id == null) continue;
                Node node = nodes.get(id);
                if(node == null) node = define(new Node(id, Json.str(m.get("res"), "stone")));
                node.workers = (int)Json.num(m.get("w"), node.workers);
                node.yieldPerWorker = (float)Json.num(m.get("ypw"), node.yieldPerWorker);
                node.reserves = (float)Json.num(m.get("reserves"), node.reserves);
                node.maxReserves = (float)Json.num(m.get("max"), node.maxReserves);
                node.regenPerDay = (float)Json.num(m.get("regen"), node.regenPerDay);
                node.minReserve = (float)Json.num(m.get("min"), node.minReserve);
                node.depleted = Json.bool(m.get("dep"), node.depleted);
            }
        } catch(Exception ignored){ }
    }
}
