package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v13.0 — scribe system (HollowEngine 13.0 'ritual & remedy').
 *  A scriptorium per settlement: scribes copy one book per day while ink
 *  holds out, moths can eat into the finished shelf (deterministic roll),
 *  and copied volumes accumulate for the realm's literacy.
 */
public final class ScribeSystem {

    public static final int MAX_SCRIBES = 5;

    public static final class Library {
        public final String settlementId;
        public final int scribes;
        public int ink = 0;
        public int copied = 0;
        public int mothStrikes = 0;
        public Library(String settlementId, int scribes){
            this.settlementId = settlementId;
            this.scribes = Math.max(1, Math.min(MAX_SCRIBES, scribes));
        }
    }

    public interface Listener { void onScriptoriumEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Library> libraries = new LinkedHashMap<>();
    public long seed = 0x5C81L;
    public int day = 0;

    /** found a scriptorium; one per settlement */
    public boolean found(String settlementId, int scribes){
        if(settlementId == null || settlementId.isEmpty() || libraries.containsKey(settlementId)) return false;
        libraries.put(settlementId, new Library(settlementId, scribes));
        if(listener != null) listener.onScriptoriumEvent("founded", settlementId, scribes);
        return true;
    }

    /** deliver ink pots */
    public boolean deliverInk(String settlementId, int units){
        Library l = libraries.get(settlementId);
        if(l == null || units <= 0) return false;
        l.ink += units;
        return true;
    }

    /** daily tick: copying, moths */
    public void tickDay(){
        day++;
        for(Library l : libraries.values()){
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ l.settlementId.hashCode());
            if(l.copied > 0 && rng.range(0f, 1f) < 0.03f){
                int eaten = Math.max(1, Math.round(l.copied * 0.05f));
                l.copied -= eaten;
                l.mothStrikes++;
                if(listener != null) listener.onScriptoriumEvent("moths", l.settlementId, eaten);
            }
            int pot = Math.min(l.ink, l.scribes);
            if(pot > 0){
                l.ink -= pot;
                l.copied += pot;
                if(listener != null) listener.onScriptoriumEvent("copied", l.settlementId, pot);
            }
        }
    }

    public Library library(String settlementId){ return libraries.get(settlementId); }
    public int libraryCount(){ return libraries.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("libraries");
        for(Library l : libraries.values())
            w.obj().kv("s", l.settlementId).kv("sc", l.scribes).kv("ink", l.ink)
              .kv("cop", l.copied).kv("moth", l.mothStrikes).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ls = n.list("libraries");
            if(ls == null) return;
            for(Object o : ls){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Library l = new Library(Json.str(m.get("s"), "?"), (int)Json.num(m.get("sc"), 1));
                l.ink = (int)Json.num(m.get("ink"), 0);
                l.copied = (int)Json.num(m.get("cop"), 0);
                l.mothStrikes = (int)Json.num(m.get("moth"), 0);
                libraries.put(l.settlementId, l);
            }
        } catch(Exception ignored){ }
    }
}
