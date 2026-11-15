package com.hollow.gameplay;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v7.0 — skill tree system (HollowEngine 7.0 'civilization').
 *  DAG of skill nodes with tiers and prerequisites: spend skill points to unlock
 *  nodes, query aggregated stat bonuses (e.g. +10% damage), respec to refund
 *  everything. Wire to ProgressionSystem for point income. JSON-persisted.
 */
public final class SkillTreeSystem {

    public static final class Node {
        public final String id;
        public String name;
        public int tier = 0;
        public int cost = 1;
        public final List<String> requires = new ArrayList<>();
        public final Map<String, Float> effects = new LinkedHashMap<>();
        public boolean unlocked = false;
        public Node(String id, String name){ this.id = id; this.name = name; }
        public Node tier(int t){ this.tier = t; return this; }
        public Node costs(int c){ this.cost = c; return this; }
        public Node requires(String... ids){ for(String r : ids) requires.add(r); return this; }
        public Node effect(String stat, float value){ effects.put(stat, value); return this; }
    }

    public interface Listener { void onSkillEvent(String type, String nodeId, int pointsLeft); }
    public Listener listener;

    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public int points = 0;
    public int spentPoints = 0;
    public int respecCost = 5;                     // gold/points charge for full respec

    public Node define(Node n){ nodes.put(n.id, n); return n; }
    public Node node(String id){ return nodes.get(id); }

    public void grant(int n){
        points += n;
        if(listener != null) listener.onSkillEvent("granted", "", points);
    }

    public boolean canUnlock(String id){
        Node n = nodes.get(id);
        if(n == null || n.unlocked || points < n.cost) return false;
        for(String r : n.requires)
            if(nodes.get(r) == null || !nodes.get(r).unlocked) return false;
        return true;
    }

    /** unlock a node: prerequisites owned + points available */
    public boolean unlock(String id){
        if(!canUnlock(id)) return false;
        Node n = nodes.get(id);
        n.unlocked = true;
        points -= n.cost;
        spentPoints += n.cost;
        if(listener != null) listener.onSkillEvent("unlocked", id, points);
        return true;
    }

    /** aggregated bonus of one stat over all unlocked nodes */
    public float bonus(String stat){
        float sum = 0f;
        for(Node n : nodes.values())
            if(n.unlocked && n.effects.containsKey(stat)) sum += n.effects.get(stat);
        return sum;
    }

    /** full respec: refunds every spent point (minus nothing), clears unlocks */
    public int respec(){
        int refunded = spentPoints;
        points += spentPoints;
        spentPoints = 0;
        for(Node n : nodes.values()) n.unlocked = false;
        if(listener != null) listener.onSkillEvent("respec", "", points);
        return refunded;
    }

    public int unlockedCount(){
        int n = 0;
        for(Node node : nodes.values()) if(node.unlocked) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("points", points).kv("spent", spentPoints);
        w.arr("unlocked");
        for(Node n : nodes.values())
            if(n.unlocked) w.obj().kv("id", n.id).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            points = (int)n.flt("points", points);
            spentPoints = (int)n.flt("spent", spentPoints);
            List<Object> us = n.list("unlocked");
            if(us == null) return;
            for(Object o : us){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Node node = nodes.get(Json.str(m.get("id"), ""));
                if(node != null && !node.unlocked){
                    node.unlocked = true;
                }
            }
        } catch(Exception ignored){ }
    }
}
