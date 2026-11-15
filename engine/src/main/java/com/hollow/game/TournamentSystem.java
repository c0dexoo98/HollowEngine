package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v9.0 — tournament system (HollowEngine 9.0 'realm & saga').
 *  Knights enroll, a single-elimination bracket is drawn by a deterministic
 *  seed, each bout is settled by skill plus a dice roll, and the champion
 *  takes the prize and the fame. ChronicleSystem is a natural listener.
 */
public final class TournamentSystem {

    public static final class Knight {
        public final String name;
        public float skill = 0.5f;                   // 0..1
        public Knight(String name, float skill){ this.name = name; this.skill = MathX.clamp(skill, 0f, 1f); }
    }

    public static final class Tournament {
        public final int id;
        public final String name;
        public final int prize;
        public final int fameReward;
        public final List<String> enrolled = new ArrayList<>();
        public String champion = "";
        public boolean done = false;
        public Tournament(int id, String name, int prize, int fameReward){
            this.id = id; this.name = name; this.prize = prize; this.fameReward = fameReward;
        }
    }

    public interface Listener { void onTourneyEvent(String type, int tourneyId, String detail); }
    public Listener listener;

    public final Map<String, Knight> knights = new LinkedHashMap<>();
    public final Map<Integer, Tournament> tournaments = new LinkedHashMap<>();
    public final Map<String, Integer> titles = new LinkedHashMap<>();      // knight → championships
    public long seed = 0x70C4L;
    private int nextId = 1;

    public Knight define(Knight k){ knights.put(k.name, k); return k; }
    public Knight knight(String name){ return knights.get(name); }

    public Tournament schedule(String name, int prize, int fameReward){
        if(name == null || name.isEmpty() || prize <= 0) return null;
        Tournament t = new Tournament(nextId++, name, prize, fameReward);
        tournaments.put(t.id, t);
        if(listener != null) listener.onTourneyEvent("scheduled", t.id, name);
        return t;
    }

    public boolean enroll(int tourneyId, String knightName){
        Tournament t = tournaments.get(tourneyId);
        if(t == null || t.done || !knights.containsKey(knightName) || t.enrolled.contains(knightName)) return false;
        t.enrolled.add(knightName);
        return true;
    }

    /** run the bracket; needs at least 2 knights. Returns the champion name or "" */
    public String run(int tourneyId, long seedRoll){
        Tournament t = tournaments.get(tourneyId);
        if(t == null || t.done || t.enrolled.size() < 2) return "";
        t.done = true;
        MathX.Rng rng = new MathX.Rng(seedRoll ^ (t.id * 31337L));

        List<String> field = new ArrayList<>(t.enrolled);
        // deterministic shuffle of the draw order
        for(int i = field.size() - 1; i > 0; i--){
            int j = rng.nextInt(i + 1);
            String tmp = field.get(i); field.set(i, field.get(j)); field.set(j, tmp);
        }
        while(field.size() > 1){
            List<String> next = new ArrayList<>();
            for(int i = 0; i + 1 < field.size(); i += 2){
                String a = field.get(i), b = field.get(i + 1);
                float sa = knights.get(a).skill + rng.range(-0.25f, 0.25f);
                float sb = knights.get(b).skill + rng.range(-0.25f, 0.25f);
                next.add(sa >= sb ? a : b);
            }
            if(field.size() % 2 == 1) next.add(field.get(field.size() - 1));   // bye round
            field = next;
        }
        String champion = field.get(0);
        t.champion = champion;
        titles.put(champion, titles.getOrDefault(champion, 0) + 1);
        if(listener != null) listener.onTourneyEvent("champion", t.id, champion);
        return champion;
    }

    public int titlesOf(String knight){ return titles.getOrDefault(knight, 0); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("nextId", nextId);
        w.arr("knights");
        for(Knight k : knights.values())
            w.obj().kv("n", k.name).kv("sk", k.skill).endObj();
        w.endArr();
        w.arr("tournaments");
        for(Tournament t : tournaments.values()){
            w.obj().kv("id", t.id).kv("name", t.name).kv("prize", t.prize)
              .kv("fame", t.fameReward).kv("champ", t.champion).kv("done", t.done);
            w.arr("en");
            for(String e : t.enrolled) w.obj().kv("k", e).endObj();
            w.endArr();
            w.endObj();
        }
        w.endArr();
        w.arr("titles");
        for(Map.Entry<String, Integer> kv : titles.entrySet())
            w.obj().kv("k", kv.getKey()).kv("n", kv.getValue()).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> ks = n.list("knights");
            if(ks != null) for(Object o : ks){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                define(new Knight(Json.str(m.get("n"), "?"), (float)Json.num(m.get("sk"), 0.5f)));
            }
            List<Object> ts = n.list("tournaments");
            if(ts != null) for(Object o : ts){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Tournament t = new Tournament((int)Json.num(m.get("id"), nextId++), Json.str(m.get("name"), "?"),
                    (int)Json.num(m.get("prize"), 0), (int)Json.num(m.get("fame"), 0));
                t.champion = Json.str(m.get("champ"), "");
                t.done = Json.bool(m.get("done"), false);
                List<Object> es = Json.arr(m.get("en"));
                if(es != null) for(Object eo : es){
                    Map<String,Object> em = Json.obj(eo);
                    if(em != null) t.enrolled.add(Json.str(em.get("k"), "?"));
                }
                tournaments.put(t.id, t);
            }
            List<Object> xs = n.list("titles");
            if(xs != null) for(Object o : xs){
                Map<String,Object> m = Json.obj(o);
                if(m != null) titles.put(Json.str(m.get("k"), ""), (int)Json.num(m.get("n"), 0));
            }
        } catch(Exception ignored){ }
    }
}
