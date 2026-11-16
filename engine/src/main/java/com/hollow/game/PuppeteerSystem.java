package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v14.0 — puppeteer system (HollowEngine 14.0 'watches & works').
 *  A traveling troupe: plays are commissioned and rehearsed for their
 *  stated days, then staged on demand — each performance rolls the
 *  crowd once (75% applause, else a flop); the repertoire ledger keeps
 *  every play with its score.
 */
public final class PuppeteerSystem {

    public static final class Play {
        public final int id;
        public final String name;
        public final int rehearseDays;
        public int daysRehearsed = 0;
        public int performances = 0;
        public int applause = 0;
        public Play(int id, String name, int rehearseDays){
            this.id = id; this.name = name;
            this.rehearseDays = Math.max(1, rehearseDays);
        }
        public boolean ready(){ return daysRehearsed >= rehearseDays; }
    }

    public interface Listener { void onStageEvent(String type, int playId, String detail); }
    public Listener listener;

    public final Map<Integer, Play> plays = new LinkedHashMap<>();
    public long seed = 0x9A9L;
    public int day = 0;
    private int nextId = 1;

    /** commission a play; name must be fresh and days at least one */
    public Play commission(String name, int rehearseDays){
        if(name == null || name.isEmpty() || rehearseDays < 1) return null;
        for(Play p : plays.values()) if(p.name.equals(name)) return null;
        Play p = new Play(nextId++, name, rehearseDays);
        plays.put(p.id, p);
        if(listener != null) listener.onStageEvent("commissioned", p.id, name);
        return p;
    }

    /** daily tick: in-progress plays rehearse */
    public void tickDay(){
        day++;
        for(Play p : plays.values())
            if(!p.ready()) p.daysRehearsed++;
    }

    /** stage a play; the crowd rolls once — applause or flop */
    public boolean perform(int playId){
        Play p = plays.get(playId);
        if(p == null || !p.ready()) return false;
        p.performances++;
        MathX.Rng rng = new MathX.Rng(seed ^ (p.id * 7919L) ^ (day * 131L));
        if(rng.range(0f, 1f) < 0.75f){
            p.applause++;
            if(listener != null) listener.onStageEvent("applause", p.id, p.name);
        } else if(listener != null){
            listener.onStageEvent("flop", p.id, p.name);
        }
        return true;
    }

    public Play play(int id){ return plays.get(id); }
    public int playCount(){ return plays.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId);
        w.arr("plays");
        for(Play p : plays.values())
            w.obj().kv("id", p.id).kv("n", p.name).kv("rd", p.rehearseDays)
              .kv("dr", p.daysRehearsed).kv("perf", p.performances).kv("ap", p.applause).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> ps = n.list("plays");
            if(ps == null) return;
            for(Object o : ps){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Play p = new Play(nextIdFor(m.get("id")),
                    Json.str(m.get("n"), "?"), (int)Json.num(m.get("rd"), 1));
                p.daysRehearsed = (int)Json.num(m.get("dr"), 0);
                p.performances = (int)Json.num(m.get("perf"), 0);
                p.applause = (int)Json.num(m.get("ap"), 0);
                plays.put(p.id, p);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
