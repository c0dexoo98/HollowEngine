package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v11.0 — matchmaker system (HollowEngine 11.0 'hearth & home').
 *  Marriages between named folk: a proposal is accepted when neither is
 *  already wed and compatibility clears the bar; wedded couples grow
 *  children over time at a compatibility-driven deterministic rate.
 */
public final class MatchmakerSystem {

    public static final float MIN_COMPATIBILITY = 0.3f;

    public static final class Couple {
        public final String a;
        public final String b;
        public final float compatibility;            // 0..1
        public int children = 0;
        public Couple(String a, String b, float compatibility){
            this.a = a; this.b = b;
            this.compatibility = MathX.clamp(compatibility, 0f, 1f);
        }
    }

    public interface Listener { void onMatchEvent(String type, String who, int value); }
    public Listener listener;

    public final Map<String, Couple> byName = new LinkedHashMap<>();     // each partner → couple
    public long seed = 0x4EDDL;
    public int day = 0;
    public int childrenBorn = 0;

    /** propose a marriage; accepted when both are single and compatibility clears the bar */
    public boolean propose(String a, String b, float compatibility){
        if(a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)
           || byName.containsKey(a) || byName.containsKey(b)
           || MathX.clamp(compatibility, 0f, 1f) < MIN_COMPATIBILITY) return false;
        Couple c = new Couple(a, b, compatibility);
        byName.put(a, c);
        byName.put(b, c);
        if(listener != null) listener.onMatchEvent("wedding", a + "&" + b, 0);
        return true;
    }

    /** daily tick: wedded couples may welcome a child (deterministic roll) */
    public void tickDay(){
        day++;
        java.util.Set<Couple> seen = new java.util.HashSet<>();
        for(Couple c : byName.values()){
            if(!seen.add(c)) continue;               // each couple appears twice in the map
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 7717L) ^ c.a.hashCode() ^ c.b.hashCode());
            if(rng.range(0f, 1f) < c.compatibility * 0.05f){
                c.children++;
                childrenBorn++;
                if(listener != null) listener.onMatchEvent("birth", c.a + "&" + c.b, c.children);
            }
        }
    }

    /** dissolve a marriage; both partners are single again */
    public boolean divorce(String name){
        Couple c = byName.get(name);
        if(c == null) return false;
        byName.remove(c.a);
        byName.remove(c.b);
        if(listener != null) listener.onMatchEvent("divorced", c.a + "&" + c.b, c.children);
        return true;
    }

    public Couple marriageOf(String name){ return byName.get(name); }
    public int coupleCount(){
        java.util.Set<Couple> seen = new java.util.HashSet<>();
        for(Couple c : byName.values()) seen.add(c);
        return seen.size();
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("born", childrenBorn);
        w.arr("couples");
        java.util.Set<Couple> seen = new java.util.HashSet<>();
        for(Couple c : byName.values()){
            if(!seen.add(c)) continue;
            w.obj().kv("a", c.a).kv("b", c.b).kv("cp", c.compatibility)
              .kv("ch", c.children).endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            childrenBorn = (int)n.flt("born", childrenBorn);
            java.util.List<Object> cs = n.list("couples");
            if(cs == null) return;
            for(Object o : cs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Couple c = new Couple(Json.str(m.get("a"), "?"), Json.str(m.get("b"), "?"),
                    (float)Json.num(m.get("cp"), 0f));
                c.children = (int)Json.num(m.get("ch"), 0);
                byName.put(c.a, c);
                byName.put(c.b, c);
            }
        } catch(Exception ignored){ }
    }
}
