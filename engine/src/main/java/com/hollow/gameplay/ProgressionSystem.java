package com.hollow.gameplay;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v5.0 — progression system (XP / levels / skill points, HollowEngine 5.0).
 *  Quadratic-ish XP curve, level-up events granting skill points, skill tree with
 *  prerequisites and per-rank costs, optional hook into AbilitySystem attributes
 *  (each rank can raise an attribute base). Deterministic, JSON-persisted.
 */
public final class ProgressionSystem {

    public static final class Skill {
        public final String id;
        public String desc = "";
        public int maxRank = 1;
        public int costPerRank = 1;
        public String requiresSkill = "";      // prerequisite id (any rank) or ""
        public String attribute = "";          // AbilitySystem attribute to raise per rank
        public float attributeGain = 0f;
        public int rank = 0;

        public Skill(String id){ this.id = id; }
        public Skill ranks(int max){ maxRank = max; return this; }
        public Skill costs(int c){ costPerRank = c; return this; }
        public Skill needs(String id2){ requiresSkill = id2; return this; }
        public Skill raises(String attr, float gain){ attribute = attr; attributeGain = gain; return this; }
        public Skill describes(String d){ desc = d; return this; }
    }

    public interface Sink {
        /** apply a permanent attribute increase (GAS AbilitySystem in real play) */
        void raiseAttribute(String name, float delta);
    }
    public interface Listener { void onProgressEvent(String type, int level, String detail); }
    public Listener listener;
    public Sink sink;

    public int level = 1;
    public long xp = 0;
    public int skillPoints = 0;
    public int spentPoints = 0;
    /** xp required to advance FROM level L: 80 * L^1.5 (deterministic, smooth ramp) */
    public long xpForLevel(int lvl){
        double v = 80.0 * Math.pow(lvl, 1.5);
        return Math.max(50L, Math.round(v / 10.0) * 10);
    }
    public long xpIntoLevel(){ return xp; }
    public long xpToNext(){ return xpForLevel(level) - xp; }

    public final Map<String, Skill> skills = new LinkedHashMap<>();

    public Skill define(Skill s){ skills.put(s.id, s); return s; }
    public Skill skill(String id){ return skills.get(id); }

    /** gain xp; auto level-ups fire events and grant 1 skill point each */
    public boolean addXp(long amount){
        if(amount <= 0) return false;
        xp += amount;
        boolean leveled = false;
        while(xp >= xpForLevel(level) && level < 99){
            xp -= xpForLevel(level);
            level++;
            skillPoints++;
            leveled = true;
            if(listener != null) listener.onProgressEvent("level_up", level, null);
        }
        return leveled;
    }

    public boolean canRankUp(String id){
        Skill s = skills.get(id);
        if(s == null || s.rank >= s.maxRank || !hasPrereq(s)) return false;
        return skillPoints >= s.costPerRank;
    }
    private boolean hasPrereq(Skill s){
        if(s.requiresSkill == null || s.requiresSkill.isEmpty()) return true;
        Skill pre = skills.get(s.requiresSkill);
        return pre != null && pre.rank > 0;
    }

    /** spend points on one rank; raises the linked attribute via the sink */
    public boolean rankUp(String id){
        Skill s = skills.get(id);
        if(s == null || s.rank >= s.maxRank || !hasPrereq(s)) return false;
        if(skillPoints < s.costPerRank) return false;
        skillPoints -= s.costPerRank;
        spentPoints += s.costPerRank;
        s.rank++;
        if(s.attribute != null && !s.attribute.isEmpty() && sink != null && s.attributeGain != 0f)
            sink.raiseAttribute(s.attribute, s.attributeGain);
        if(listener != null) listener.onProgressEvent("ranked", level, id + ":" + s.rank);
        return true;
    }

    /** refund everything (respec): all points back, ranks reset, attributes NOT auto-reverted
     *  — the caller re-syncs attributes from ranks via sink if it wants. */
    public int respec(){
        int refund = spentPoints;
        for(Skill s : skills.values()) s.rank = 0;
        skillPoints += spentPoints;
        spentPoints = 0;
        if(listener != null && refund > 0) listener.onProgressEvent("respec", level, String.valueOf(refund));
        return refund;
    }

    /** re-apply all rank bonuses through the sink (used after load or respec) */
    public void syncAttributes(){
        if(sink == null) return;
        for(Skill s : skills.values())
            if(s.attribute != null && !s.attribute.isEmpty() && s.attributeGain != 0f)
                sink.raiseAttribute(s.attribute, s.attributeGain * s.rank);
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("level", level).kv("xp", xp).kv("sp", skillPoints).kv("spent", spentPoints);
        w.arr("skills");
        for(Skill s : skills.values()){
            w.obj().kv("id", s.id).kv("rank", s.rank)
              .kv("max", s.maxRank).kv("cost", s.costPerRank)
              .kv("req", s.requiresSkill).kv("attr", s.attribute).kv("gain", s.attributeGain)
              .endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            level = Math.max(1, (int)n.flt("level", level));
            xp = (long)n.flt("xp", xp);
            skillPoints = (int)n.flt("sp", skillPoints);
            spentPoints = (int)n.flt("spent", spentPoints);
            List<Object> ss = n.list("skills");
            if(ss != null) for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String id = Json.str(m.get("id"), null);
                if(id == null) continue;
                Skill s = skills.get(id);
                if(s == null){
                    s = new Skill(id);
                    s.maxRank = (int)Json.num(m.get("max"), 1);
                    s.costPerRank = (int)Json.num(m.get("cost"), 1);
                    s.requiresSkill = Json.str(m.get("req"), "");
                    s.attribute = Json.str(m.get("attr"), "");
                    s.attributeGain = (float)Json.num(m.get("gain"), 0f);
                    define(s);
                }
                s.rank = Math.min(s.maxRank, (int)Json.num(m.get("rank"), 0));
            }
        } catch(Exception ignored){ }
    }
}
