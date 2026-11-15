package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v10.0 — spy network system (HollowEngine 10.0 'crown & coin').
 *  Informants planted in settlements: intel quality is the best live
 *  informant's reliability, plot uncovering is a deterministic roll against
 *  it, and a failed uncovering can burn the informant.
 */
public final class SpyNetworkSystem {

    public static final class Informant {
        public final String name;
        public final String settlementId;
        public float reliability;                    // 0..1
        public int uncovered = 0;
        public boolean burned = false;
        public Informant(String name, String settlementId, float reliability){
            this.name = name; this.settlementId = settlementId;
            this.reliability = MathX.clamp(reliability, 0f, 1f);
        }
    }

    public interface Listener { void onSpyEvent(String type, String name, String detail); }
    public Listener listener;

    public final Map<String, Informant> informants = new LinkedHashMap<>();
    public long seed = 0x57791L;
    public int day = 0;

    /** plant an informant; refuses duplicates and empty names */
    public boolean recruit(String name, String settlementId, float reliability){
        if(name == null || name.isEmpty() || settlementId == null || settlementId.isEmpty()
           || informants.containsKey(name)) return false;
        informants.put(name, new Informant(name, settlementId, reliability));
        if(listener != null) listener.onSpyEvent("recruited", name, settlementId);
        return true;
    }

    /** best live intel quality in a settlement; 0 when nobody is planted */
    public float intel(String settlementId){
        float best = 0f;
        for(Informant i : informants.values())
            if(!i.burned && i.settlementId.equals(settlementId)) best = Math.max(best, i.reliability);
        return best;
    }

    /** try to uncover a plot with the best informant available; deterministic */
    public boolean uncoverPlot(String plotName, String settlementId){
        Informant spy = bestLive(settlementId);
        if(spy == null || plotName == null || plotName.isEmpty()) return false;
        MathX.Rng rng = new MathX.Rng(seed ^ plotName.hashCode() ^ (day * 7907L));
        if(rng.range(0f, 1f) < spy.reliability){
            spy.uncovered++;
            if(listener != null) listener.onSpyEvent("uncovered", spy.name, plotName);
            return true;
        }
        MathX.Rng rng2 = new MathX.Rng(seed ^ (plotName.hashCode() * 31L) ^ day);
        if(rng2.range(0f, 1f) < 0.25f){
            spy.burned = true;
            if(listener != null) listener.onSpyEvent("burned", spy.name, plotName);
        }
        return false;
    }

    /** retire an informant by hand */
    public boolean burn(String name){
        Informant i = informants.get(name);
        if(i == null || i.burned) return false;
        i.burned = true;
        if(listener != null) listener.onSpyEvent("burned", name, "manual");
        return true;
    }

    private Informant bestLive(String settlementId){
        Informant best = null;
        for(Informant i : informants.values())
            if(!i.burned && i.settlementId.equals(settlementId)
               && (best == null || i.reliability > best.reliability)) best = i;
        return best;
    }

    public int liveCount(){
        int n = 0;
        for(Informant i : informants.values()) if(!i.burned) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("informants");
        for(Informant i : informants.values())
            w.obj().kv("n", i.name).kv("s", i.settlementId).kv("rel", i.reliability)
              .kv("unc", i.uncovered).kv("bur", i.burned).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            List<Object> is = n.list("informants");
            if(is == null) return;
            for(Object o : is){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Informant i = new Informant(Json.str(m.get("n"), "?"), Json.str(m.get("s"), "?"),
                    (float)Json.num(m.get("rel"), 0f));
                i.uncovered = (int)Json.num(m.get("unc"), 0);
                i.burned = Json.bool(m.get("bur"), false);
                informants.put(i.name, i);
            }
        } catch(Exception ignored){ }
    }
}
