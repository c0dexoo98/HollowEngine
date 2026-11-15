package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v9.0 — rumor system (HollowEngine 9.0 'realm & saga').
 *  Stories travel mouth to mouth: every hop distorts the truth by a
 *  deterministic roll, each NPC remembers the version they heard, and a
 *  chain of hops can be simulated to know what the far end of the realm
 *  actually believes.
 */
public final class RumorSystem {

    public static final class Rumor {
        public final int id;
        public final String subject;
        public final float truth;                    // 0..1 at the origin
        public final int originDay;
        public float distortionPerHop = 0.12f;
        public Rumor(int id, String subject, float truth, int originDay){
            this.id = id; this.subject = subject; this.truth = truth; this.originDay = originDay;
        }
    }

    public interface Listener { void onRumorEvent(String type, int rumorId, String detail); }
    public Listener listener;

    public final Map<Integer, Rumor> rumors = new LinkedHashMap<>();
    /** npc → rumorId → the truth level of the version they heard */
    public final Map<String, Map<Integer, Float>> heard = new LinkedHashMap<>();
    public long seed = 0x9CE2L;
    public int day = 0;
    private int nextId = 1;

    /** plant a rumor at its source; returns the rumor */
    public Rumor spread(String subject, float truth, String originNpc){
        if(subject == null || subject.isEmpty()) return null;
        Rumor r = new Rumor(nextId++, subject, MathX.clamp(truth, 0f, 1f), day);
        rumors.put(r.id, r);
        hear(originNpc, r.id, r.truth);
        if(listener != null) listener.onRumorEvent("planted", r.id, originNpc);
        return r;
    }

    /** an npc hears a version of a rumor at some truth level */
    public void hear(String npc, int rumorId, float truthLevel){
        if(npc == null || npc.isEmpty() || !rumors.containsKey(rumorId)) return;
        heard.computeIfAbsent(npc, k -> new LinkedHashMap<>()).put(rumorId, MathX.clamp(truthLevel, 0f, 1f));
    }

    /** what does this npc currently believe about the rumor? */
    public float truthOf(String npc, int rumorId){
        Map<Integer, Float> m = heard.get(npc);
        return m == null ? -1f : m.getOrDefault(rumorId, -1f);
    }

    /** simulate n mouth-to-mouth hops from an origin version; each hop distorts
     *  by distortionPerHop scaled by a deterministic roll, and the last listener remembers it */
    public float hopChain(String originNpc, int rumorId, int hops){
        Rumor r = rumors.get(rumorId);
        if(r == null) return -1f;
        Float cur = heard.get(originNpc) == null ? null : heard.get(originNpc).get(rumorId);
        if(cur == null) cur = r.truth;
        MathX.Rng rng = new MathX.Rng(seed ^ (rumorId * 7919L) ^ (day * 101L));
        String carrier = originNpc;
        for(int i = 0; i < hops; i++){
            cur = Math.max(0f, cur - r.distortionPerHop * (0.6f + rng.range(0f, 0.8f)));
            carrier = "listener" + i;
            hear(carrier, rumorId, cur);
        }
        if(listener != null) listener.onRumorEvent("hops", rumorId, hops + "");
        return cur;
    }

    /** rumors an npc has heard */
    public List<Integer> knownBy(String npc){
        Map<Integer, Float> m = heard.get(npc);
        return m == null ? new ArrayList<>() : new ArrayList<>(m.keySet());
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId);
        w.arr("rumors");
        for(Rumor r : rumors.values())
            w.obj().kv("id", r.id).kv("s", r.subject).kv("t", r.truth)
              .kv("d", r.originDay).kv("dist", r.distortionPerHop).endObj();
        w.endArr();
        w.arr("heard");
        for(Map.Entry<String, Map<Integer, Float>> kv : heard.entrySet()){
            w.obj().kv("npc", kv.getKey());
            w.arr("mem");
            for(Map.Entry<Integer, Float> mem : kv.getValue().entrySet())
                w.obj().kv("r", mem.getKey()).kv("t", mem.getValue()).endObj();
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
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> rs = n.list("rumors");
            if(rs != null) for(Object o : rs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Rumor r = new Rumor(nextIdFor(m.get("id")), Json.str(m.get("s"), "?"),
                    (float)Json.num(m.get("t"), 0f), (int)Json.num(m.get("d"), 0));
                r.distortionPerHop = (float)Json.num(m.get("dist"), r.distortionPerHop);
                rumors.put(r.id, r);
            }
            List<Object> hs = n.list("heard");
            if(hs != null) for(Object o : hs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String npc = Json.str(m.get("npc"), null);
                if(npc == null) continue;
                List<Object> mem = Json.arr(m.get("mem"));
                if(mem == null) continue;
                for(Object eo : mem){
                    Map<String,Object> em = Json.obj(eo);
                    if(em == null) continue;
                    hear(npc, (int)Json.num(em.get("r"), 0), (float)Json.num(em.get("t"), 0f));
                }
            }
        } catch(Exception ignored){ }
    }
}
