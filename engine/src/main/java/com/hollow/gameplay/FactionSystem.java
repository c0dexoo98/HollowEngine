package com.hollow.gameplay;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v5.0 — faction system (relations + reputation, HollowEngine 5.0).
 *  Factions with a relations matrix (ALLY / FRIENDLY / NEUTRAL / HOSTILE / WAR),
 *  per-faction reputation scale (-100..100) shifted by world events, automatic
 *  relation re-derivation from reputation thresholds, hostility queries for AI
 *  targeting and JSON persistence.
 */
public final class FactionSystem {

    public enum Relation { ALLY, FRIENDLY, NEUTRAL, HOSTILE, WAR }

    public static final class Faction {
        public final String id;
        public String name;
        public int reputation = 0;             // player-facing standing -100..100
        public Relation playerRelation = Relation.NEUTRAL;
        public Faction(String id, String name){ this.id = id; this.name = name; }
    }

    public interface Listener { void onFactionEvent(String type, String factionId, int reputation); }
    public Listener listener;

    public final Map<String, Faction> factions = new LinkedHashMap<>();
    /** directed hostility overrides: factionId -> set of factionIds at WAR regardless of reputation */
    public final Map<String, List<String>> wars = new LinkedHashMap<>();
    /** threshold deltas applied between two factions when the player's standing shifts */
    public static final float ALLY_SPILL = 0.15f;      // allies gain 15% of a rep gain
    public static final float ENEMY_SPILL = -0.25f;    // enemies lose 25% of a rep gain

    public Faction define(String id, String name){
        Faction f = new Faction(id, name);
        factions.put(id, f);
        return f;
    }
    public Faction faction(String id){ return factions.get(id); }

    public void declareWar(String a, String b){
        addDir(a, b); addDir(b, a);
        syncRelations();
    }
    public void makePeace(String a, String b){
        removeDir(a, b); removeDir(b, a);
        syncRelations();
    }
    private void addDir(String a, String b){
        List<String> l = wars.get(a);
        if(l == null){ l = new ArrayList<>(); wars.put(a, l); }
        if(!l.contains(b)) l.add(b);
    }
    private void removeDir(String a, String b){
        List<String> l = wars.get(a);
        if(l != null) l.remove(b);
    }

    /** shift player reputation with a faction; spills to allies/enemies, re-derives relations */
    public int shiftReputation(String id, float delta){
        Faction f = factions.get(id);
        if(f == null || delta == 0f) return f != null ? f.reputation : 0;
        applyRep(f, delta);
        // spill: positive changes help the player's allies and hurt their enemies (and vice versa)
        for(Faction other : factions.values()){
            if(other.id.equals(id)) continue;
            if(relation(other.id, id) == Relation.ALLY) applyRep(other, delta * ALLY_SPILL);
            else if(relation(other.id, id) == Relation.WAR) applyRep(other, delta * ENEMY_SPILL);
        }
        syncRelations();
        return f.reputation;
    }
    private void applyRep(Faction f, float delta){
        f.reputation = Math.round(Math.max(-100f, Math.min(100f, f.reputation + delta)));
        if(listener != null) listener.onFactionEvent("reputation", f.id, f.reputation);
    }

    /** derive playerRelation from reputation (explicit wars stay WAR) */
    public void syncRelations(){
        for(Faction f : factions.values()){
            if(wars.get(f.id) != null && wars.get(f.id).contains("player")){
                f.playerRelation = Relation.WAR;
                continue;
            }
            int r = f.reputation;
            f.playerRelation = r >= 60 ? Relation.ALLY
                             : r >= 25 ? Relation.FRIENDLY
                             : r > -25 ? Relation.NEUTRAL
                             : r > -60 ? Relation.HOSTILE
                             : Relation.WAR;
        }
    }

    /** faction-vs-faction hostility (AI targeting): explicit wars first, then relation fallback */
    public Relation relation(String a, String b){
        List<String> wa = wars.get(a);
        if(wa != null && wa.contains(b)) return Relation.WAR;
        List<String> wb = wars.get(b);
        if(wb != null && wb.contains(a)) return Relation.WAR;
        Faction fa = factions.get(a), fb = factions.get(b);
        if(fa == null || fb == null) return Relation.NEUTRAL;
        int sum = fa.reputation + fb.reputation;
        if(sum >= 120) return Relation.ALLY;
        if(sum >= 50) return Relation.FRIENDLY;
        if(sum > -50) return Relation.NEUTRAL;
        if(sum > -120) return Relation.HOSTILE;
        return Relation.WAR;
    }

    public boolean isAtWar(String a, String b){ return relation(a, b) == Relation.WAR; }
    public boolean isHostileToPlayer(String id){
        Faction f = factions.get(id);
        return f != null && (f.playerRelation == Relation.HOSTILE || f.playerRelation == Relation.WAR);
    }
    public boolean playerAllyOf(String id){
        Faction f = factions.get(id);
        return f != null && (f.playerRelation == Relation.ALLY || f.playerRelation == Relation.FRIENDLY);
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj();
        w.arr("factions");
        for(Faction f : factions.values()){
            w.obj().kv("id", f.id).kv("name", f.name).kv("rep", f.reputation).endObj();
        }
        w.endArr();
        w.arr("wars");
        for(Map.Entry<String, List<String>> kv : wars.entrySet()){
            for(String other : kv.getValue())
                if(kv.getKey().compareTo(other) < 0)     // store each pair once
                    w.obj().kv("a", kv.getKey()).kv("b", other).endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            List<Object> fs = n.list("factions");
            if(fs != null) for(Object o : fs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String id = Json.str(m.get("id"), null);
                if(id == null) continue;
                Faction f = factions.get(id);
                if(f == null) f = define(id, Json.str(m.get("name"), id));
                f.reputation = (int)Json.num(m.get("rep"), f.reputation);
            }
            List<Object> ws = n.list("wars");
            if(ws != null) for(Object o : ws){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String a = Json.str(m.get("a"), ""), b = Json.str(m.get("b"), "");
                if(!a.isEmpty() && !b.isEmpty()){ addDir(a, b); addDir(b, a); }
            }
            syncRelations();
        } catch(Exception ignored){ }
    }
}
