package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v19.0 — shearer system (HollowEngine 19.0 'spindles & shuttles').
 *  The shears take one sheep at a time (a single day per job), then
 *  the shearing rolls once — 85% a clean fleece harvest of three,
 *  15% a nick and only one fleece (counted in the nicks ledger).
 *  No material is paid up front; the wool lands in the bin.
 */
public final class ShearerSystem {

    public static final int SHEAR_DAYS = 1;
    public static final int FLEECE_CLEAN = 3;
    public static final int FLEECE_NICK = 1;

    public static final class Job {
        public final int id;
        public final String tag;
        public int daysWaited = 0;
        public boolean done = false;
        public boolean nicked = false;
        public Job(int id, String tag){
            this.id = id; this.tag = tag;
        }
    }

    public interface Listener { void onShearsEvent(String type, int jobId, String detail); }
    public Listener listener;

    public final Map<Integer, Job> jobs = new LinkedHashMap<>();
    public int fleeces = 0;
    public int nicks = 0;
    public long seed = 0x531L;
    public int day = 0;
    private int nextId = 1;

    /** book a sheep in; the shears start the next day */
    public Job book(String tag){
        if(tag == null || tag.isEmpty()) return null;
        Job j = new Job(nextId++, tag);
        jobs.put(j.id, j);
        if(listener != null) listener.onShearsEvent("booked", j.id, tag);
        return j;
    }

    /** daily tick: shearing days pass; finished jobs roll once */
    public void tickDay(){
        day++;
        for(Job j : jobs.values()){
            if(j.done) continue;
            j.daysWaited++;
            if(j.daysWaited < SHEAR_DAYS) continue;
            j.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (j.id * 7919L) ^ (day * 131L));
            j.nicked = rng.range(0f, 1f) < 0.15f;
            int harvested = j.nicked ? FLEECE_NICK : FLEECE_CLEAN;
            fleeces += harvested;
            if(j.nicked) nicks++;
            if(listener != null) listener.onShearsEvent(j.nicked ? "nick" : "harvested", j.id, j.tag);
        }
    }

    public Job job(int id){ return jobs.get(id); }
    public int harvestedCount(){
        int n = 0;
        for(Job j : jobs.values()) if(j.done && !j.nicked) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("fleeces", fleeces).kv("nicks", nicks).kv("nextId", nextId);
        w.arr("jobs");
        for(Job x : jobs.values())
            w.obj().kv("id", x.id).kv("t", x.tag).kv("dw", x.daysWaited)
              .kv("d", x.done).kv("n", x.nicked).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            fleeces = (int)n.flt("fleeces", fleeces);
            nicks = (int)n.flt("nicks", nicks);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> js = n.list("jobs");
            if(js == null) return;
            for(Object o : js){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Job x = new Job(nextIdFor(m.get("id")), Json.str(m.get("t"), "?"));
                x.daysWaited = (int)Json.num(m.get("dw"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.nicked = Json.bool(m.get("n"), false);
                jobs.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
