package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v15.0 — tailor system (HollowEngine 15.0 'trade & tide').
 *  The tailor's shop holds a cloth stock; each order takes two lengths
 *  paid up front and its stated days at the needle, then the fitting
 *  rolls once — 90% fits, the rest is botched and the cloth is lost.
 */
public final class TailorSystem {

    public static final int CLOTH_PER_ORDER = 2;

    public static final class Order {
        public final int id;
        public final String garment;
        public final int workDays;
        public int daysWorked = 0;
        public boolean done = false;
        public boolean fitted = false;
        public boolean botched = false;
        public Order(int id, String garment, int workDays){
            this.id = id; this.garment = garment;
            this.workDays = Math.max(1, workDays);
        }
    }

    public interface Listener { void onStitchEvent(String type, int orderId, String detail); }
    public Listener listener;

    public final Map<Integer, Order> orders = new LinkedHashMap<>();
    public int cloth = 0;
    public long seed = 0x71L;
    public int day = 0;
    private int nextId = 1;

    /** stock the cloth shelf */
    public boolean deliverCloth(int lengths){
        if(lengths <= 0) return false;
        cloth += lengths;
        return true;
    }

    /** place an order; cloth is paid up front */
    public Order place(String garment, int workDays){
        if(garment == null || garment.isEmpty() || workDays < 1 || cloth < CLOTH_PER_ORDER) return null;
        cloth -= CLOTH_PER_ORDER;
        Order o = new Order(nextId++, garment, workDays);
        orders.put(o.id, o);
        if(listener != null) listener.onStitchEvent("placed", o.id, garment);
        return o;
    }

    /** daily tick: orders progress; finished ones roll the fitting once */
    public void tickDay(){
        day++;
        for(Order o : orders.values()){
            if(o.done) continue;
            o.daysWorked++;
            if(o.daysWorked < o.workDays) continue;
            o.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (o.id * 7919L) ^ (day * 131L));
            o.fitted = rng.range(0f, 1f) < 0.9f;
            o.botched = !o.fitted;
            if(listener != null) listener.onStitchEvent(o.fitted ? "fitted" : "botched", o.id, o.garment);
        }
    }

    public Order order(int id){ return orders.get(id); }
    public int fittedCount(){
        int n = 0;
        for(Order o : orders.values()) if(o.done && o.fitted) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("cloth", cloth).kv("nextId", nextId);
        w.arr("orders");
        for(Order o : orders.values())
            w.obj().kv("id", o.id).kv("g", o.garment).kv("wd", o.workDays)
              .kv("dw", o.daysWorked).kv("d", o.done).kv("f", o.fitted).kv("b", o.botched).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            cloth = (int)n.flt("cloth", cloth);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> os = n.list("orders");
            if(os == null) return;
            for(Object o : os){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Order ord = new Order(nextIdFor(m.get("id")),
                    Json.str(m.get("g"), "?"), (int)Json.num(m.get("wd"), 1));
                ord.daysWorked = (int)Json.num(m.get("dw"), 0);
                ord.done = Json.bool(m.get("d"), false);
                ord.fitted = Json.bool(m.get("f"), false);
                ord.botched = Json.bool(m.get("b"), false);
                orders.put(ord.id, ord);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
