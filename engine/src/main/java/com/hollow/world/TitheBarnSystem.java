package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v18.0 — tithe barn system (HollowEngine 18.0 'pounds & pages').
 *  One tithe barn per parish: sheaves are stored (up to the barn's
 *  capacity); each day the reeve hands over a literal tenth of the
 *  stock (floor division, deterministic) and one roll follows —
 *  5% mice scatter one sheaf from the remainder. The tithed ledger
 *  and the mice ledger are kept honestly.
 */
public final class TitheBarnSystem {

    public static final int BARN_CAPACITY = 50;
    public static final int TITHE_DIVISOR = 10;

    public static final class Barn {
        public final String parishId;
        public int sheaves = 0;
        public int tithed = 0;
        public int mice = 0;
        public Barn(String parishId){
            this.parishId = parishId;
        }
    }

    public interface Listener { void onBarnEvent(String type, String parishId, int value); }
    public Listener listener;

    public final Map<String, Barn> barns = new LinkedHashMap<>();
    public long seed = 0x73B1L;
    public int day = 0;

    /** raise a tithe barn; one per parish */
    public boolean raise(String parishId){
        if(parishId == null || parishId.isEmpty() || barns.containsKey(parishId)) return false;
        barns.put(parishId, new Barn(parishId));
        if(listener != null) listener.onBarnEvent("raised", parishId, 0);
        return true;
    }

    /** store sheaves; refuses overflow and unknown barns */
    public boolean store(String parishId, int sheaves){
        Barn b = barns.get(parishId);
        if(b == null || sheaves <= 0 || b.sheaves + sheaves > BARN_CAPACITY) return false;
        b.sheaves += sheaves;
        if(listener != null) listener.onBarnEvent("stored", parishId, sheaves);
        return true;
    }

    /** daily tick: the tenth is handed over, then the mice roll */
    public void tickDay(){
        day++;
        for(Barn b : barns.values()){
            if(b.sheaves <= 0) continue;
            int tenth = b.sheaves / TITHE_DIVISOR;          // a literal tenth, floor division
            b.sheaves -= tenth;
            b.tithed += tenth;
            if(listener != null) listener.onBarnEvent("tithed", b.parishId, tenth);
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ b.parishId.hashCode());
            if(b.sheaves > 0 && rng.range(0f, 1f) < 0.05f){
                b.sheaves--;
                b.mice++;
                if(listener != null) listener.onBarnEvent("mice", b.parishId, b.mice);
            }
        }
    }

    public Barn barn(String parishId){ return barns.get(parishId); }
    public int barnCount(){ return barns.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("barns");
        for(Barn b : barns.values())
            w.obj().kv("p", b.parishId).kv("sh", b.sheaves).kv("ti", b.tithed)
              .kv("mi", b.mice).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> bs = n.list("barns");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Barn b = new Barn(Json.str(m.get("p"), "?"));
                b.sheaves = MathX.clamp((int)Json.num(m.get("sh"), 0), 0, BARN_CAPACITY);
                b.tithed = (int)Json.num(m.get("ti"), 0);
                b.mice = (int)Json.num(m.get("mi"), 0);
                barns.put(b.parishId, b);
            }
        } catch(Exception ignored){ }
    }
}
