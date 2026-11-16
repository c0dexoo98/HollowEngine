package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v19.0 — wool staple system (HollowEngine 19.0 'spindles & shuttles').
 *  One wool staple per town: bales are received (up to the staple's
 *  capacity); each sale is one roll — 90% the wool is dry and fetches
 *  the fair price per bale, 10% damp wool takes the staple's price
 *  dip. The sold and gross ledgers are kept honestly.
 */
public final class WoolStapleSystem {

    public static final int BALES_CAP = 20;
    public static final int PRICE_FAIR = 40;
    public static final int PRICE_DAMP = 36;
    public static final float DIP_CHANCE = 0.1f;

    public static final class Staple {
        public final String townId;
        public int bales = 0;
        public int sold = 0;
        public int gross = 0;
        public Staple(String townId){
            this.townId = townId;
        }
    }

    public interface Listener { void onStapleEvent(String type, String townId, int value); }
    public Listener listener;

    public final Map<String, Staple> staples = new LinkedHashMap<>();
    public long seed = 0x577L;
    public int day = 0;

    /** open a wool staple; one per town */
    public boolean open(String townId){
        if(townId == null || townId.isEmpty() || staples.containsKey(townId)) return false;
        staples.put(townId, new Staple(townId));
        if(listener != null) listener.onStapleEvent("opened", townId, 0);
        return true;
    }

    /** receive wool bales; refuses overflow and unknown staples */
    public boolean receive(String townId, int bales){
        Staple s = staples.get(townId);
        if(s == null || bales <= 0 || s.bales + bales > BALES_CAP) return false;
        s.bales += bales;
        if(listener != null) listener.onStapleEvent("received", townId, bales);
        return true;
    }

    /** sell bales: one roll decides the day's price for the whole lot */
    public boolean sell(String townId, int bales){
        Staple s = staples.get(townId);
        if(s == null || bales <= 0 || bales > s.bales) return false;
        s.bales -= bales;
        s.sold += bales;
        MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ townId.hashCode());
        int price = rng.range(0f, 1f) < DIP_CHANCE ? PRICE_DAMP : PRICE_FAIR;
        s.gross += bales * price;
        if(listener != null) listener.onStapleEvent(price == PRICE_DAMP ? "damp" : "fair", townId, s.gross);
        return true;
    }

    /** daily tick: the day advances; sale rolls key off it */
    public void tickDay(){
        day++;
    }

    public Staple staple(String townId){ return staples.get(townId); }
    public int stapleCount(){ return staples.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("staples");
        for(Staple s : staples.values())
            w.obj().kv("t", s.townId).kv("b", s.bales).kv("so", s.sold)
              .kv("g", s.gross).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ss = n.list("staples");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Staple s = new Staple(Json.str(m.get("t"), "?"));
                s.bales = MathX.clamp((int)Json.num(m.get("b"), 0), 0, BALES_CAP);
                s.sold = (int)Json.num(m.get("so"), 0);
                s.gross = (int)Json.num(m.get("g"), 0);
                staples.put(s.townId, s);
            }
        } catch(Exception ignored){ }
    }
}
