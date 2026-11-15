package com.hollow.gameplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.0.0 GAMEPLAY: quest system — objectives, chains, flags, rewards, journal.
 *
 * A Quest = ordered objectives; each objective completes when its condition
 * holds (progress-counted: 3/5 wolves). Quest states: AVAILABLE -> ACTIVE ->
 * COMPLETED / FAILED. Rewards are opaque entries (item grants, xp, gold) the
 * game layer interprets. QuestLog tracks all known quests + a global flag
 * store for conditions ("met_blacksmith", "has_key").
 */
public final class QuestSystem {

    public static final String AVAILABLE = "available", ACTIVE = "active",
                               COMPLETED = "completed", FAILED = "failed";

    public interface ProgressCheck { /** current progress count for the objective */ int probe(Quest q, Objective o); }
    public interface Reward { void grant(Quest q); }

    public static final class Objective {
        public final String id;
        public String description = "";
        public int required = 1;              // target count
        public int progress = 0;              // current count
        public boolean optional = false;      // skip for completion (bonus)
        public transient ProgressCheck check; // polled by update()

        public Objective(String id, String description, int required){
            this.id = id; this.description = description; this.required = required;
        }
        public boolean isComplete(){ return progress >= required; }
    }

    public static final class Quest {
        public final String id;
        public String title = "";
        public String description = "";
        public String state = AVAILABLE;
        public final List<Objective> objectives = new ArrayList<>();
        public final List<Reward> rewards = new ArrayList<>();
        /** prerequisite flags (all must be set) before this quest can start */
        public final List<String> requiresFlags = new ArrayList<>();
        public transient String nextQuestId;   // chain: auto-offer on completion

        public Quest(String id, String title){ this.id = id; this.title = title; }

        public Quest objective(String oid, String desc, int required, ProgressCheck check){
            Objective o = new Objective(oid, desc, required);
            o.check = check;
            objectives.add(o);
            return this;
        }

        public Quest reward(Reward r){ rewards.add(r); return this; }

        public boolean allRequiredComplete(){
            if(objectives.isEmpty()) return false;   // no objectives = never auto-completes (explicit complete() only)
            for(Objective o : objectives){
                if(!o.optional && !o.isComplete()) return false;
            }
            return true;
        }
        public boolean anyFailedObjectiveFailed(){ return false; }   // failure via explicit fail()
    }

    // ------------------------------------------------------------

    public final Map<String, Quest> quests = new LinkedHashMap<>();
    public final Map<String, Boolean> flags = new LinkedHashMap<>();
    /** fired once per event name; game layer hooks UI toasts here */
    public interface QuestEvent { void onEvent(String type, Quest q); }
    public final List<QuestEvent> listeners = new ArrayList<>();

    public Quest add(Quest q){
        quests.put(q.id, q);
        return q;
    }

    public void setFlag(String f, boolean v){ flags.put(f, v); }
    public boolean flag(String f){ return Boolean.TRUE.equals(flags.get(f)); }

    public Quest quest(String id){ return quests.get(id); }

    /** quests whose flags are satisfied and not yet started */
    public List<Quest> available(){
        List<Quest> out = new ArrayList<>();
        for(Quest q : quests.values()){
            if(!AVAILABLE.equals(q.state)) continue;
            boolean ok = true;
            for(String f : q.requiresFlags) if(!flag(f)){ ok = false; break; }
            if(ok) out.add(q);
        }
        return out;
    }

    /** start a quest (must be AVAILABLE and flag-gated) */
    public boolean start(String questId){
        Quest q = quests.get(questId);
        if(q == null || !AVAILABLE.equals(q.state)) return false;
        for(String f : q.requiresFlags) if(!flag(f)) return false;
        q.state = ACTIVE;
        fire("started", q);
        return true;
    }

    /** advance objectives via their probes; completes the quest when all required objectives pass */
    public void update(){
        for(Quest q : quests.values()){
            if(!ACTIVE.equals(q.state)) continue;
            for(Objective o : q.objectives){
                if(o.check != null && !o.isComplete()){
                    int before = o.progress;
                    o.progress = Math.min(o.required, o.check.probe(q, o));
                    if(before < o.required && o.isComplete()) fire("objective", q);
                }
            }
            if(q.allRequiredComplete()) complete(q.id);
        }
    }

    /** manual progress bump (game layer counted the kill itself) */
    public boolean progress(String questId, String objectiveId, int amount){
        Quest q = quests.get(questId);
        if(q == null || !ACTIVE.equals(q.state)) return false;
        for(Objective o : q.objectives){
            if(o.id.equals(objectiveId)){
                o.progress = Math.min(o.required, o.progress + amount);
                if(o.isComplete()) fire("objective", q);
                if(q.allRequiredComplete()) complete(questId);
                return true;
            }
        }
        return false;
    }

    public boolean complete(String questId){
        Quest q = quests.get(questId);
        if(q == null || !ACTIVE.equals(q.state)) return false;
        q.state = COMPLETED;
        for(Reward r : q.rewards) r.grant(q);
        fire("completed", q);
        if(q.nextQuestId != null) start(q.nextQuestId);
        return true;
    }

    public boolean fail(String questId){
        Quest q = quests.get(questId);
        if(q == null || !ACTIVE.equals(q.state)) return false;
        q.state = FAILED;
        fire("failed", q);
        return true;
    }

    /** journal snapshot: active quests + objective lines for UI/save */
    public List<Map<String,Object>> journal(){
        List<Map<String,Object>> out = new ArrayList<>();
        for(Quest q : quests.values()){
            if(!ACTIVE.equals(q.state)) continue;
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", q.id); m.put("title", q.title);
            List<Map<String,Object>> objs = new ArrayList<>();
            for(Objective o : q.objectives){
                Map<String,Object> om = new LinkedHashMap<>();
                om.put("id", o.id); om.put("desc", o.description);
                om.put("progress", o.progress); om.put("required", o.required);
                om.put("done", o.isComplete());
                objs.add(om);
            }
            m.put("objectives", objs);
            out.add(m);
        }
        return out;
    }

    private void fire(String type, Quest q){
        for(QuestEvent l : listeners) l.onEvent(type, q);
    }
}
