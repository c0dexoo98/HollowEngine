package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.LinkedHashMap;
import java.util.Map;

/** v12.0 — carpenter system (HollowEngine 12.0 'trades & crafts').
 *  The workshop runs several commissions in parallel, each costing
 *  timber up front; jobs advance one day at a time until the piece is
 *  crafted. Timber must be delivered before it can be spent.
 */
public final class CarpenterSystem {

    public static final int DEFAULT_WOOD_COST = 2;

    public static final class Job {
        public final int id;
        public final String item;
        public final int workDays;
        public int progress = 0;
        public Job(int id, String item, int workDays){
            this.id = id; this.item = item;
            this.workDays = Math.max(1, workDays);
        }
    }

    public interface Listener { void onShopEvent(String type, int jobId, String detail); }
    public Listener listener;

    public final Map<Integer, Job> jobs = new LinkedHashMap<>();
    public int timber = 0;
    public int craftedCount = 0;
    public int day = 0;
    private int nextId = 1;

    /** stock the workshop with timber */
    public boolean deliverTimber(int units){
        if(units <= 0) return false;
        timber += units;
        return true;
    }

    /** commission a piece; timber is paid up front */
    public Job commission(String item, int workDays, int woodCost){
        int cost = woodCost <= 0 ? DEFAULT_WOOD_COST : woodCost;
        if(item == null || item.isEmpty() || workDays < 1 || timber < cost) return null;
        timber -= cost;
        Job j = new Job(nextId++, item, workDays);
        jobs.put(j.id, j);
        if(listener != null) listener.onShopEvent("commissioned", j.id, item);
        return j;
    }

    /** daily tick: every bench advances; finished pieces leave the shop */
    public void tickDay(){
        day++;
        java.util.List<Integer> done = new java.util.ArrayList<>();
        for(Job j : jobs.values()){
            j.progress++;
            if(j.progress >= j.workDays) done.add(j.id);
        }
        for(Integer id : done){
            Job j = jobs.remove(id);
            craftedCount++;
            if(listener != null) listener.onShopEvent("crafted", id, j.item);
        }
    }

    public Job job(int id){ return jobs.get(id); }
    public int jobCount(){ return jobs.size(); }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("timber", timber).kv("nextId", nextId)
          .kv("crafted", craftedCount);
        w.arr("jobs");
        for(Job j : jobs.values())
            w.obj().kv("id", j.id).kv("item", j.item).kv("wd", j.workDays)
              .kv("prog", j.progress).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            timber = (int)n.flt("timber", timber);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            craftedCount = (int)n.flt("crafted", craftedCount);
            java.util.List<Object> js = n.list("jobs");
            if(js == null) return;
            for(Object o : js){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Job j = new Job(nextIdFor(m.get("id")),
                    Json.str(m.get("item"), "?"), (int)Json.num(m.get("wd"), 1));
                j.progress = (int)Json.num(m.get("prog"), 0);
                jobs.put(j.id, j);
            }
        } catch(Exception ignored){ }
    }
}
