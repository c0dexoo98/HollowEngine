package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v7.0 — diplomacy system (HollowEngine 7.0 'civilization').
 *  Faction-pair relations (-100..100) with drift, treaties (TRADE / PEACE /
 *  NONAGGRESSION / ALLIANCE) that require a relation threshold to sign, war
 *  declarations that break treaties. Wire to FactionSystem for reputation.
 */
public final class DiplomacySystem {

    public enum Treaty { TRADE, PEACE, NONAGGRESSION, ALLIANCE }

    public static final class Relation {
        public final String key;                    // "a|b" sorted
        public int value = 0;                       // -100 hostile .. 100 friendly
        public boolean atWar = false;
        public final List<Treaty> treaties = new ArrayList<>();
        public Relation(String key){ this.key = key; }
    }

    public interface Listener { void onDiplomacyEvent(String type, String a, String b, String detail); }
    public Listener listener;

    public final Map<String, Relation> relations = new LinkedHashMap<>();
    public int day = 0;
    /** relation needed to sign each treaty */
    public int threshold(Treaty t){
        switch(t){
            case TRADE: return 10;
            case PEACE: return -20;
            case NONAGGRESSION: return 0;
            case ALLIANCE: return 60;
            default: return 0;
        }
    }

    private static String keyOf(String a, String b){
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    public Relation relation(String a, String b){
        String k = keyOf(a, b);
        Relation r = relations.get(k);
        if(r == null){ r = new Relation(k); relations.put(k, r); }
        return r;
    }

    /** shift a relation (symmetric), clamped -100..100 */
    public void shift(String a, String b, int delta){
        Relation r = relation(a, b);
        r.value = MathX.clamp(r.value + delta, -100, 100);
    }

    /** propose a treaty: needs the relation threshold, no war */
    public boolean propose(String a, String b, Treaty t){
        Relation r = relation(a, b);
        if(r.atWar || r.value < threshold(t) || r.treaties.contains(t)) return false;
        r.treaties.add(t);
        if(listener != null) listener.onDiplomacyEvent("treaty", a, b, t.name());
        return true;
    }

    public void declareWar(String a, String b){
        Relation r = relation(a, b);
        if(r.atWar) return;
        r.atWar = true;
        if(!r.treaties.isEmpty()){
            if(listener != null) listener.onDiplomacyEvent("treaties_broken", a, b, r.treaties.size() + "");
            r.treaties.clear();
        }
        r.value = Math.min(r.value, -40);
        if(listener != null) listener.onDiplomacyEvent("war", a, b, "" + r.value);
    }

    /** sue for peace: relation must be above -80; war ends, cold peace remains */
    public boolean makePeace(String a, String b){
        Relation r = relation(a, b);
        if(!r.atWar || r.value < -80) return false;
        r.atWar = false;
        r.value = Math.max(r.value, -20);
        if(listener != null) listener.onDiplomacyEvent("peace", a, b, "" + r.value);
        return true;
    }

    public boolean atWar(String a, String b){ return relation(a, b).atWar; }
    public boolean hasTreaty(String a, String b, Treaty t){ return relation(a, b).treaties.contains(t); }

    /** daily drift: treaties warm relations, wars cool them, everything else decays to neutral */
    public void tickDay(){
        day++;
        for(Relation r : relations.values()){
            int drift = 0;
            if(r.treaties.contains(Treaty.ALLIANCE)) drift = 2;
            else if(r.treaties.contains(Treaty.TRADE) || r.treaties.contains(Treaty.NONAGGRESSION)) drift = 1;
            else if(r.atWar) drift = -1;
            else drift = r.value > 0 ? -1 : 1;       // decay toward 0
            r.value = MathX.clamp(r.value + drift, -100, 100);
        }
    }

    public int treatyCount(){
        int n = 0;
        for(Relation r : relations.values()) n += r.treaties.size();
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("relations");
        for(Relation r : relations.values()){
            w.obj().kv("key", r.key).kv("val", r.value).kv("war", r.atWar);
            w.arr("treaties");
            for(Treaty t : r.treaties) w.val(t.name());
            w.endArr();
            w.endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            List<Object> rs = n.list("relations");
            if(rs == null) return;
            for(Object o : rs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String k = Json.str(m.get("key"), null);
                if(k == null) continue;
                Relation r = relation(k.split("\\|")[0], k.split("\\|")[1]);
                r.value = MathX.clamp((int)Json.num(m.get("val"), r.value), -100, 100);
                r.atWar = Json.bool(m.get("war"), r.atWar);
                List<Object> ts = Json.arr(m.get("treaties"));
                if(ts != null) for(Object t : ts){
                    try { r.treaties.add(Treaty.valueOf(String.valueOf(t))); } catch(Exception ignored){ }
                }
            }
        } catch(Exception ignored){ }
    }
}
