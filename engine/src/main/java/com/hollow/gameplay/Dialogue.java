package com.hollow.gameplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.0.0 GAMEPLAY: branching dialogue — nodes, choices, conditions, effects.
 *
 * A Dialogue is a tree of nodes. Each node has text (localized key or literal)
 * and choices; each choice may carry a condition predicate (shown/enabled)
 * and an effect runnable (set quest flag, give item, start combat...).
 * The game layer steps it: begin() -> current node -> pick(choiceIndex) -> next.
 * Runs anywhere (no engine coupling) — save-safe via node ids.
 */
public final class Dialogue {

    public static final class Node {
        public final String id;
        public String speaker = "";
        public String text = "";
        public final List<Choice> choices = new ArrayList<>();
        /** when set, dialogue ends after showing this node (no choices) */
        public boolean endsDialogue;

        public Node(String id){ this.id = id; }
        public Node(String id, String speaker, String text){
            this.id = id; this.speaker = speaker; this.text = text;
        }
        public Node choice(String label, String nextId){ return choice(label, nextId, null, null); }
        public Node choice(String label, String nextId, Condition cond, Effect fx){
            choices.add(new Choice(label, nextId, cond, fx));
            return this;
        }
    }

    public interface Condition { boolean ok(Dialogue d); }
    public interface Effect { void run(Dialogue d); }

    public static final class Choice {
        public final String label;
        public final String nextId;      // null = end dialogue
        public final Condition condition;
        public final Effect effect;
        Choice(String label, String nextId, Condition condition, Effect effect){
            this.label = label; this.nextId = nextId;
            this.condition = condition; this.effect = effect;
        }
    }

    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public String startNodeId;
    public String currentNodeId;
    public boolean active = false;

    /** blackboard: shared game state conditions read / effects write */
    public final Map<String, Object> vars = new LinkedHashMap<>();

    // visit tracking (once-only choices etc.)
    public final List<String> visitedNodes = new ArrayList<>();

    public Dialogue(){ }

    public Node add(Node n){
        nodes.put(n.id, n);
        if(startNodeId == null) startNodeId = n.id;
        return n;
    }

    /** start the conversation; returns the first node (or null when empty) */
    public Node begin(){
        currentNodeId = startNodeId;
        active = currentNodeId != null;
        if(active) markVisited(currentNodeId);
        return active ? nodes.get(currentNodeId) : null;
    }

    public Node current(){ return currentNodeId == null ? null : nodes.get(currentNodeId); }

    /** choices visible under current vars (conditions evaluated, hidden ones filtered) */
    public List<Choice> visibleChoices(){
        List<Choice> out = new ArrayList<>();
        Node n = current();
        if(n == null) return out;
        for(Choice c : n.choices){
            if(c.condition == null || c.condition.ok(this)) out.add(c);
        }
        return out;
    }

    /** pick a choice among visibleChoices(); returns the next node or null (dialogue over) */
    public Node pick(int visibleIndex){
        List<Choice> visible = visibleChoices();
        if(visibleIndex < 0 || visibleIndex >= visible.size()) return null;
        Choice c = visible.get(visibleIndex);
        if(c.effect != null) c.effect.run(this);
        if(c.nextId == null || !nodes.containsKey(c.nextId)){
            active = false;
            currentNodeId = null;
            return null;
        }
        currentNodeId = c.nextId;
        markVisited(currentNodeId);
        return nodes.get(currentNodeId);
    }

    public boolean visited(String nodeId){
        return visitedNodes.contains(nodeId);
    }

    private void markVisited(String id){
        if(!visitedNodes.contains(id)) visitedNodes.add(id);
    }

    // convenience var helpers for conditions/effects
    public void setVar(String k, Object v){ vars.put(k, v); }
    public Object getVar(String k){ return vars.get(k); }
    public boolean flag(String k){ Object v = vars.get(k); return v instanceof Boolean && (Boolean)v; }
    public int intVar(String k, int def){ Object v = vars.get(k); return v instanceof Integer ? (Integer)v : def; }
}
