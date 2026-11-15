package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/** v7.0 — territory control system (HollowEngine 7.0 'civilization').
 *  Regions with a control race between factions: presence pushes a faction's
 *  control up and rivals' down, the owner is whoever leads by a margin, and a
 *  capture fires when a faction crosses the takeover threshold. Deterministic.
 */
public final class TerritorySystem {

    public static final class Region {
        public final String id;
        public String name;
        public Vec3 center = new Vec3();
        public float radius = 50f;
        public final Map<String, Float> control = new LinkedHashMap<>();   // factionId → 0..100
        public String owner = "";                   // current leading faction
        public Region(String id, String name){ this.id = id; this.name = name; }
        public Region at(float x, float y, float z){ center.set(x, y, z); return this; }
        public Region range(float r){ radius = r; return this; }
    }

    public interface Listener { void onTerritoryEvent(String type, String regionId, String faction); }
    public Listener listener;

    public final Map<String, Region> regions = new LinkedHashMap<>();
    public int day = 0;
    public float captureThreshold = 60f;            // control needed to take ownership
    public float decayPerDay = 2f;                  // rivals' control bleeds to the owner
    public float leadMargin = 15f;                  // control lead required to flip ownership

    public Region define(Region r){ regions.put(r.id, r); return r; }
    public Region region(String id){ return regions.get(id); }

    /** a faction is present in a region: grows its control, drains rivals */
    public void pushPresence(String regionId, String factionId, float strength){
        Region r = regions.get(regionId);
        if(r == null || strength <= 0f) return;
        float cur = r.control.getOrDefault(factionId, 0f);
        r.control.put(factionId, MathX.clamp(cur + strength, 0f, 100f));
        for(Map.Entry<String, Float> kv : r.control.entrySet())
            if(!kv.getKey().equals(factionId))
                r.control.put(kv.getKey(), MathX.clamp(kv.getValue() - strength * 0.6f, 0f, 100f));
    }

    public float controlOf(String regionId, String factionId){
        Region r = regions.get(regionId);
        return r == null ? 0f : r.control.getOrDefault(factionId, 0f);
    }

    public String ownerOf(String regionId){
        Region r = regions.get(regionId);
        return r == null ? "" : r.owner;
    }

    /** daily tick: passive bleed of non-owner control + ownership resolution */
    public void tickDay(){
        day++;
        for(Region r : regions.values()){
            String leader = "";
            float best = 0f, second = 0f;
            for(Map.Entry<String, Float> kv : r.control.entrySet()){
                // non-owner control decays toward 0
                if(!kv.getKey().equals(r.owner))
                    kv.setValue(Math.max(0f, kv.getValue() - decayPerDay));
                if(kv.getValue() > best){ second = best; best = kv.getValue(); leader = kv.getKey(); }
                else if(kv.getValue() > second) second = kv.getValue();
            }
            // capture: a faction crosses the threshold and leads by a margin
            if(leader != null && !leader.isEmpty() && best >= captureThreshold
               && best - second >= leadMargin && !leader.equals(r.owner)){
                r.owner = leader;
                if(listener != null) listener.onTerritoryEvent("captured", r.id, leader);
            } else if(r.owner != null && !r.owner.isEmpty()){
                Float own = r.control.get(r.owner);
                if(own == null || own < captureThreshold * 0.5f){
                    r.owner = "";
                    if(listener != null) listener.onTerritoryEvent("lost", r.id, "");
                }
            }
        }
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("regions");
        for(Region r : regions.values()){
            w.obj().kv("id", r.id).kv("name", r.name).kv("owner", r.owner)
              .kvv("center", r.center.x, r.center.y, r.center.z).kv("radius", r.radius);
            w.arr("control");
            for(Map.Entry<String, Float> kv : r.control.entrySet())
                w.obj().kv("f", kv.getKey()).kv("c", kv.getValue()).endObj();
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
            java.util.List<Object> rs = n.list("regions");
            if(rs == null) return;
            for(Object o : rs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String id = Json.str(m.get("id"), null);
                if(id == null) continue;
                Region r = regions.get(id);
                if(r == null) r = define(new Region(id, Json.str(m.get("name"), id)));
                r.owner = Json.str(m.get("owner"), r.owner);
                r.radius = (float)Json.num(m.get("radius"), r.radius);
                java.util.List<Object> c = Json.arr(m.get("center"));
                if(c != null && c.size() >= 3)
                    r.center.set((float)Json.num(c.get(0), 0), (float)Json.num(c.get(1), 0), (float)Json.num(c.get(2), 0));
                java.util.List<Object> cs = Json.arr(m.get("control"));
                if(cs != null) for(Object co : cs){
                    Map<String,Object> cm = Json.obj(co);
                    if(cm != null) r.control.put(Json.str(cm.get("f"), ""), (float)Json.num(cm.get("c"), 0f));
                }
            }
        } catch(Exception ignored){ }
    }
}
