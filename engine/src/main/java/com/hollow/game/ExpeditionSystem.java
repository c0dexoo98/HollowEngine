package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v9.0 — expedition system (HollowEngine 9.0 'realm & saga').
 *  Crews travel to ruins in search of artifacts: the journey takes days, the
 *  arrival is settled by crew strength against ruin danger with a
 *  deterministic roll, and success fills the realm's artifact cabinet while
 *  failure costs casualties.
 */
public final class ExpeditionSystem {

    public static final class Ruin {
        public final String id;
        public final String name;
        public final String artifact;
        public final int artifactValue;
        public float danger = 0.5f;                  // 0..1
        public int journeyDays = 4;
        public Ruin(String id, String name, String artifact, int artifactValue){
            this.id = id; this.name = name; this.artifact = artifact; this.artifactValue = artifactValue;
        }
        public Ruin danger(float d){ danger = MathX.clamp(d, 0f, 1f); return this; }
        public Ruin journey(int days){ journeyDays = Math.max(1, days); return this; }
    }

    public static final class Expedition {
        public final int id;
        public final String ruinId;
        public final String leader;
        public final int crew;
        public final int provisions;                 // days of supplies
        public float progress = 0f;
        public boolean resolved = false;
        public Expedition(int id, String ruinId, String leader, int crew, int provisions){
            this.id = id; this.ruinId = ruinId; this.leader = leader; this.crew = crew; this.provisions = provisions;
        }
    }

    public interface Listener { void onExpeditionEvent(String type, int expeditionId, String detail); }
    public Listener listener;

    public final Map<String, Ruin> ruins = new LinkedHashMap<>();
    public final Map<Integer, Expedition> expeditions = new LinkedHashMap<>();
    /** artifact name → recovered count */
    public final Map<String, Integer> artifacts = new LinkedHashMap<>();
    public long seed = 0x9E1D3L;
    public int day = 0;
    private int nextId = 1;

    public Ruin define(Ruin r){ ruins.put(r.id, r); return r; }
    public Ruin ruin(String id){ return ruins.get(id); }

    /** launch an expedition; provisions must cover the journey */
    public Expedition launch(String ruinId, String leader, int crew, int provisions){
        Ruin r = ruins.get(ruinId);
        if(r == null || leader == null || leader.isEmpty() || crew <= 0 || provisions < r.journeyDays) return null;
        Expedition e = new Expedition(nextId++, ruinId, leader, crew, provisions);
        expeditions.put(e.id, e);
        if(listener != null) listener.onExpeditionEvent("launched", e.id, ruinId);
        return e;
    }

    /** daily tick: advance journeys, resolve arrivals */
    public void tickDay(){
        day++;
        for(Expedition e : expeditions.values()){
            if(e.resolved) continue;
            Ruin r = ruins.get(e.ruinId);
            if(r == null){ e.resolved = true; continue; }
            e.progress += 1f;
            if(e.progress < r.journeyDays) continue;

            e.resolved = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (e.id * 104729L) ^ day);
            float crewStrength = Math.min(1f, e.crew / 10f);
            float odds = MathX.clamp(crewStrength - r.danger + 0.4f, 0.05f, 0.95f);
            if(rng.range(0f, 1f) < odds){
                artifacts.put(r.artifact, artifacts.getOrDefault(r.artifact, 0) + 1);
                if(listener != null) listener.onExpeditionEvent("recovered", e.id, r.artifact);
            } else {
                int lost = Math.max(1, e.crew / 2);
                if(listener != null) listener.onExpeditionEvent("casualties", e.id, e.leader + " lost=" + lost);
            }
        }
    }

    /** total catalogue value of recovered artifacts */
    public int cabinetValue(){
        int sum = 0;
        for(Map.Entry<String, Integer> kv : artifacts.entrySet()){
            Ruin r = findRuinByArtifact(kv.getKey());
            if(r != null) sum += r.artifactValue * kv.getValue();
        }
        return sum;
    }

    public int artifactCount(String artifactName){ return artifacts.getOrDefault(artifactName, 0); }

    private Ruin findRuinByArtifact(String artifact){
        for(Ruin r : ruins.values()) if(r.artifact.equals(artifact)) return r;
        return null;
    }

    public int pendingCount(){
        int n = 0;
        for(Expedition e : expeditions.values()) if(!e.resolved) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId);
        w.arr("ruins");
        for(Ruin r : ruins.values())
            w.obj().kv("id", r.id).kv("name", r.name).kv("art", r.artifact).kv("val", r.artifactValue)
              .kv("danger", r.danger).kv("journey", r.journeyDays).endObj();
        w.endArr();
        w.arr("expeditions");
        for(Expedition e : expeditions.values())
            w.obj().kv("id", e.id).kv("ruin", e.ruinId).kv("leader", e.leader).kv("crew", e.crew)
              .kv("prov", e.provisions).kv("prog", e.progress).kv("res", e.resolved).endObj();
        w.endArr();
        w.arr("artifacts");
        for(Map.Entry<String, Integer> kv : artifacts.entrySet())
            w.obj().kv("a", kv.getKey()).kv("n", kv.getValue()).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> rs = n.list("ruins");
            if(rs != null) for(Object o : rs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Ruin r = new Ruin(Json.str(m.get("id"), "?"), Json.str(m.get("name"), "?"),
                    Json.str(m.get("art"), "?"), (int)Json.num(m.get("val"), 0));
                r.danger((float)Json.num(m.get("danger"), r.danger));
                r.journey((int)Json.num(m.get("journey"), r.journeyDays));
                ruins.put(r.id, r);
            }
            List<Object> es = n.list("expeditions");
            if(es != null) for(Object o : es){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Expedition e = new Expedition((int)Json.num(m.get("id"), nextId++), Json.str(m.get("ruin"), "?"),
                    Json.str(m.get("leader"), "?"), (int)Json.num(m.get("crew"), 0), (int)Json.num(m.get("prov"), 0));
                e.progress = (float)Json.num(m.get("prog"), 0f);
                e.resolved = Json.bool(m.get("res"), false);
                expeditions.put(e.id, e);
            }
            List<Object> as = n.list("artifacts");
            if(as != null) for(Object o : as){
                Map<String,Object> m = Json.obj(o);
                if(m != null) artifacts.put(Json.str(m.get("a"), "?"), (int)Json.num(m.get("n"), 0));
            }
        } catch(Exception ignored){ }
    }
}
