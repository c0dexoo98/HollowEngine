package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v17.0 — charcoal burner system (HollowEngine 17.0 'banks & benches').
 *  One clamp kiln per settlement: wood is stacked (up to the clamp
 *  capacity) and each day a proper batch of four logs is burned;
 *  a single roll decides the draw — 90% two sacks of charcoal, 10%
 *  the batch clinkers to ash (wood spent, nothing yielded, counted
 *  honestly). Leftover logs below a batch wait in the clamp.
 */
public final class CharcoalBurnerSystem {

    public static final int CLAMP_CAPACITY = 24;
    public static final int BATCH = 4;
    public static final int SACKS_PER_BATCH = 2;

    public static final class Kiln {
        public final String settlementId;
        public int wood = 0;
        public int charcoal = 0;
        public int clinker = 0;
        public Kiln(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onKilnEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Kiln> kilns = new LinkedHashMap<>();
    public long seed = 0x6B1L;
    public int day = 0;

    /** dig a clamp kiln; one per settlement */
    public boolean dig(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || kilns.containsKey(settlementId)) return false;
        kilns.put(settlementId, new Kiln(settlementId));
        if(listener != null) listener.onKilnEvent("dug", settlementId, 0);
        return true;
    }

    /** stack the clamp with logs; refuses overflow and unknown kilns */
    public boolean loadWood(String settlementId, int logs){
        Kiln k = kilns.get(settlementId);
        if(k == null || logs <= 0 || k.wood + logs > CLAMP_CAPACITY) return false;
        k.wood += logs;
        if(listener != null) listener.onKilnEvent("loaded", settlementId, logs);
        return true;
    }

    /** daily tick: burn one batch, one roll for the draw */
    public void tickDay(){
        day++;
        for(Kiln k : kilns.values()){
            if(k.wood < BATCH) continue;
            k.wood -= BATCH;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ k.settlementId.hashCode());
            if(rng.range(0f, 1f) < 0.1f){
                k.clinker++;
                if(listener != null) listener.onKilnEvent("clinker", k.settlementId, k.clinker);
            } else {
                k.charcoal += SACKS_PER_BATCH;
                if(listener != null) listener.onKilnEvent("drawn", k.settlementId, SACKS_PER_BATCH);
            }
        }
    }

    public Kiln kiln(String settlementId){ return kilns.get(settlementId); }
    public int kilnCount(){ return kilns.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("kilns");
        for(Kiln k : kilns.values())
            w.obj().kv("s", k.settlementId).kv("wd", k.wood).kv("ch", k.charcoal)
              .kv("cl", k.clinker).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ks = n.list("kilns");
            if(ks == null) return;
            for(Object o : ks){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Kiln k = new Kiln(Json.str(m.get("s"), "?"));
                k.wood = MathX.clamp((int)Json.num(m.get("wd"), 0), 0, CLAMP_CAPACITY);
                k.charcoal = (int)Json.num(m.get("ch"), 0);
                k.clinker = (int)Json.num(m.get("cl"), 0);
                kilns.put(k.settlementId, k);
            }
        } catch(Exception ignored){ }
    }
}
