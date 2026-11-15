package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

/** v12.0 — blacksmith system (HollowEngine 12.0 'trades & crafts').
 *  A single forge works one commission at a time; each finished order
 *  sharpens the smith's skill, and skill drives the deterministic
 *  quality roll of the next piece.
 */
public final class BlacksmithSystem {

    public static final class Commission {
        public final int id;
        public final String item;
        public final int workDays;
        public int progress = 0;
        public float quality = -1f;                  // set when forged
        public Commission(int id, String item, int workDays){
            this.id = id; this.item = item;
            this.workDays = Math.max(1, workDays);
        }
    }

    public interface Listener { void onForgeEvent(String type, int commissionId, String detail); }
    public Listener listener;

    public final java.util.Map<Integer, Commission> orders = new java.util.LinkedHashMap<>();
    public float skill = 0.5f;                       // grows +0.02 per finished order, capped at 1
    public long seed = 0x6131L;
    public int day = 0;
    private int nextId = 1;

    /** take a commission; the forge works one order at a time */
    public Commission takeOrder(String item, int workDays){
        if(item == null || item.isEmpty() || workDays < 1 || activeOrder() != null) return null;
        Commission c = new Commission(nextId++, item, workDays);
        orders.put(c.id, c);
        if(listener != null) listener.onForgeEvent("taken", c.id, item);
        return c;
    }

    /** the commission currently on the anvil, if any */
    public Commission activeOrder(){
        for(Commission c : orders.values())
            if(c.quality < 0f) return c;
        return null;
    }

    /** daily tick: hammer falls; finished pieces roll their quality */
    public void tickDay(){
        day++;
        for(Commission c : orders.values()){
            if(c.quality >= 0f) continue;
            c.progress++;
            if(c.progress < c.workDays) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (c.id * 7919L) ^ (day * 131L));
            c.quality = MathX.clamp(0.3f + skill * 0.6f + (rng.range(0f, 0.4f) - 0.2f), 0f, 1f);
            skill = Math.min(1f, skill + 0.02f);
            if(listener != null) listener.onForgeEvent("forged", c.id, c.item + " q=" + c.quality);
        }
    }

    public Commission commission(int id){ return orders.get(id); }
    public int finishedCount(){
        int n = 0;
        for(Commission c : orders.values()) if(c.quality >= 0f) n++;
        return n;
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId).kv("skill", skill);
        w.arr("orders");
        for(Commission c : orders.values())
            w.obj().kv("id", c.id).kv("item", c.item).kv("wd", c.workDays)
              .kv("prog", c.progress).kv("q", c.quality).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            skill = MathX.clamp((float)n.flt("skill", skill), 0f, 1f);
            java.util.List<Object> os = n.list("orders");
            if(os == null) return;
            for(Object o : os){
                java.util.Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Commission c = new Commission(nextIdFor(m.get("id")),
                    Json.str(m.get("item"), "?"), (int)Json.num(m.get("wd"), 1));
                c.progress = (int)Json.num(m.get("prog"), 0);
                c.quality = (float)Json.num(m.get("q"), -1f);
                orders.put(c.id, c);
            }
        } catch(Exception ignored){ }
    }
}
