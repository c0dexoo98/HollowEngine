package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v17.0 — peat cutting system (HollowEngine 17.0 'banks & benches').
 *  One bog bank per settlement: fresh-cut blocks are stacked (up to
 *  the bank's drying capacity) and the sun dries three a day into
 *  burnable fuel; a rainy day (10% single roll) dries nothing. The
 *  dried-fuel ledger is kept honestly.
 */
public final class PeatCuttingSystem {

    public static final int BANK_CAPACITY = 40;
    public static final int DRY_PER_DAY = 3;

    public static final class Bank {
        public final String settlementId;
        public int cutPeat = 0;
        public int dried = 0;
        public int rainDelays = 0;
        public Bank(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onPeatEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Bank> banks = new LinkedHashMap<>();
    public long seed = 0x77L;
    public int day = 0;

    /** open a bog bank; one per settlement */
    public boolean open(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || banks.containsKey(settlementId)) return false;
        banks.put(settlementId, new Bank(settlementId));
        if(listener != null) listener.onPeatEvent("opened", settlementId, 0);
        return true;
    }

    /** stack fresh-cut blocks; refuses overflow and unknown banks */
    public boolean cut(String settlementId, int blocks){
        Bank b = banks.get(settlementId);
        if(b == null || blocks <= 0 || b.cutPeat + blocks > BANK_CAPACITY) return false;
        b.cutPeat += blocks;
        if(listener != null) listener.onPeatEvent("cut", settlementId, blocks);
        return true;
    }

    /** daily tick: rain or drying sun */
    public void tickDay(){
        day++;
        for(Bank b : banks.values()){
            if(b.cutPeat <= 0) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ b.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.1f){
                b.rainDelays++;
                if(listener != null) listener.onPeatEvent("rain", b.settlementId, b.rainDelays);
                continue;
            }
            int driedToday = Math.min(DRY_PER_DAY, b.cutPeat);
            b.cutPeat -= driedToday;
            b.dried += driedToday;
            if(listener != null) listener.onPeatEvent("dried", b.settlementId, driedToday);
        }
    }

    public Bank bank(String settlementId){ return banks.get(settlementId); }
    public int bankCount(){ return banks.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("banks");
        for(Bank b : banks.values())
            w.obj().kv("s", b.settlementId).kv("cp", b.cutPeat).kv("dr", b.dried)
              .kv("rd", b.rainDelays).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> bs = n.list("banks");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Bank b = new Bank(Json.str(m.get("s"), "?"));
                b.cutPeat = MathX.clamp((int)Json.num(m.get("cp"), 0), 0, BANK_CAPACITY);
                b.dried = (int)Json.num(m.get("dr"), 0);
                b.rainDelays = (int)Json.num(m.get("rd"), 0);
                banks.put(b.settlementId, b);
            }
        } catch(Exception ignored){ }
    }
}
