package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.List;

/** v9.0 — succession system (HollowEngine 9.0 'realm & saga').
 *  Rulers reign with a legitimacy score that drifts with stability; naming an
 *  heir secures the succession. A ruler who dies or is deposed hands the crown
 *  to the named heir (stronger start) or throws the realm into a weak interim.
 */
public final class SuccessionSystem {

    public static final class Ruler {
        public final String name;
        public int legitimacy;                       // 0..100
        public final int reignStartDay;
        public Ruler(String name, int legitimacy, int reignStartDay){
            this.name = name; this.legitimacy = legitimacy; this.reignStartDay = reignStartDay;
        }
    }

    public interface Listener { void onSuccessionEvent(String type, String rulerName, int legitimacy); }
    public Listener listener;

    public Ruler current = null;
    public String heirName = "";
    public final List<String> lineage = new ArrayList<>();
    public int day = 0;
    public int stabilityDrift = 1;                   // legitimacy gained per day when stable
    public int uprisingThreshold = 20;

    /** crown a new ruler directly (coup / conquest); legitimacy 60 */
    public Ruler crown(String name){
        if(name == null || name.isEmpty()) return null;
        if(current != null) lineage.add(current.name);
        heirName = "";
        current = new Ruler(name, 60, day);
        if(listener != null) listener.onSuccessionEvent("crowned", name, current.legitimacy);
        return current;
    }

    /** designate an heir — a named heir inherits with +25 legitimacy */
    public boolean nameHeir(String name){
        if(name == null || name.isEmpty() || current == null || name.equals(current.name)) return false;
        heirName = name;
        if(listener != null) listener.onSuccessionEvent("heir", name, 0);
        return true;
    }

    /** daily tick: stable rule accumulates legitimacy, weak rule risks uprising */
    public void tickDay(){
        day++;
        if(current == null) return;
        if(current.legitimacy >= uprisingThreshold)
            current.legitimacy = Math.min(100, current.legitimacy + stabilityDrift);
        if(current.legitimacy < uprisingThreshold && listener != null)
            listener.onSuccessionEvent("uprising_risk", current.name, current.legitimacy);
    }

    /** the ruler dies/deposed — the realm passes to the heir if one was named */
    public boolean depose(){
        if(current == null) return false;
        lineage.add(current.name);
        String old = current.name;
        if(!heirName.isEmpty()){
            current = new Ruler(heirName, 40 + 25, day);      // named heir: strong claim
            heirName = "";
            if(listener != null) listener.onSuccessionEvent("succeeded", current.name, current.legitimacy);
        } else {
            current = null;                                    // interregnum
            if(listener != null) listener.onSuccessionEvent("interregnum", old, 0);
        }
        return true;
    }

    /** interim rule during an interregnum: legitimacy 25 for whoever seizes the crown */
    public Ruler seizeThrone(String name){
        if(current != null || name == null || name.isEmpty()) return null;
        current = new Ruler(name, 25, day);
        if(listener != null) listener.onSuccessionEvent("usurped", name, current.legitimacy);
        return current;
    }

    public boolean hasRuler(){ return current != null; }
    public int legitimacy(){ return current == null ? 0 : current.legitimacy; }
    public int reignDays(){ return current == null ? 0 : day - current.reignStartDay; }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("heir", heirName).kv("drift", stabilityDrift);
        if(current != null)
            w.kv("ruler", current.name).kv("legit", current.legitimacy).kv("reign0", current.reignStartDay);
        w.arr("lineage");
        for(String l : lineage) w.obj().kv("n", l).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            heirName = n.str("heir", "");
            stabilityDrift = (int)n.flt("drift", stabilityDrift);
            String ruler = n.str("ruler", "");
            current = ruler == null || ruler.isEmpty() ? null
                : new Ruler(ruler, (int)n.flt("legit", 60), (int)n.flt("reign0", day));
            List<Object> ls = n.list("lineage");
            if(ls != null) for(Object o : ls){
                java.util.Map<String,Object> m = Json.obj(o);
                if(m != null) lineage.add(Json.str(m.get("n"), "?"));
            }
        } catch(Exception ignored){ }
    }
}
