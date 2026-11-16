package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v13.0 — apothecary system (HollowEngine 13.0 'ritual & remedy').
 *  The workshop runs several brews in parallel, each consuming herbs up
 *  front; finished batches roll a deterministic efficacy that improves
 *  with the apothecary's skill, and skill sharpens one point per batch.
 */
public final class ApothecarySystem {

    public static final class Batch {
        public final int id;
        public final String remedy;
        public final int brewDays;
        public int progress = 0;
        public float efficacy = -1f;                 // set when brewed
        public Batch(int id, String remedy, int brewDays){
            this.id = id; this.remedy = remedy;
            this.brewDays = Math.max(1, brewDays);
        }
    }

    public interface Listener { void onApothecaryEvent(String type, int batchId, String detail); }
    public Listener listener;

    public final Map<Integer, Batch> batches = new LinkedHashMap<>();
    public int herbs = 0;
    public float skill = 0.5f;                       // grows +0.01 per finished batch, capped at 1
    public long seed = 0xA071L;
    public int day = 0;
    private int nextId = 1;

    /** stock the workshop with herbs */
    public boolean deliverHerbs(int units){
        if(units <= 0) return false;
        herbs += units;
        return true;
    }

    /** start a brew; herbs are paid up front */
    public Batch brew(String remedy, int brewDays, int herbCost){
        int cost = herbCost <= 0 ? 2 : herbCost;
        if(remedy == null || remedy.isEmpty() || brewDays < 1 || herbs < cost) return null;
        herbs -= cost;
        Batch b = new Batch(nextId++, remedy, brewDays);
        batches.put(b.id, b);
        if(listener != null) listener.onApothecaryEvent("started", b.id, remedy);
        return b;
    }

    /** daily tick: brews steep; finished batches roll efficacy */
    public void tickDay(){
        day++;
        for(Batch b : batches.values()){
            if(b.efficacy >= 0f) continue;
            b.progress++;
            if(b.progress < b.brewDays) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (b.id * 7919L) ^ (day * 131L));
            b.efficacy = MathX.clamp(0.5f + skill * 0.4f + (rng.range(0f, 0.2f) - 0.1f), 0f, 1f);
            skill = Math.min(1f, skill + 0.01f);
            if(listener != null) listener.onApothecaryEvent("brewed", b.id,
                b.remedy + " e=" + b.efficacy);
        }
    }

    public Batch batch(int id){ return batches.get(id); }
    public int finishedCount(){
        int n = 0;
        for(Batch b : batches.values()) if(b.efficacy >= 0f) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("herbs", herbs).kv("skill", skill).kv("nextId", nextId);
        w.arr("batches");
        for(Batch b : batches.values())
            w.obj().kv("id", b.id).kv("r", b.remedy).kv("bd", b.brewDays)
              .kv("prog", b.progress).kv("e", b.efficacy).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            herbs = (int)n.flt("herbs", herbs);
            skill = MathX.clamp((float)n.flt("skill", skill), 0f, 1f);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> bs = n.list("batches");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Batch b = new Batch(nextIdFor(m.get("id")),
                    Json.str(m.get("r"), "?"), (int)Json.num(m.get("bd"), 1));
                b.progress = (int)Json.num(m.get("prog"), 0);
                b.efficacy = (float)Json.num(m.get("e"), -1f);
                batches.put(b.id, b);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
