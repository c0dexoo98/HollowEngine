package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v14.0 — falconry system (HollowEngine 14.0 'watches & works').
 *  A mew per settlement stocks up to six falcons; every day the birds
 *  fly one hunt — a single deterministic roll partitions the sky into
 *  a kill (50%), a lost bird (10%) or an empty sky (40%).
 */
public final class FalconrySystem {

    public static final int MEW_CAPACITY = 6;

    public static final class Mew {
        public final String settlementId;
        public int falcons = 0;
        public int quarry = 0;
        public int escaped = 0;
        public int hunts = 0;
        public Mew(String settlementId){
            this.settlementId = settlementId;
        }
    }

    public interface Listener { void onMewEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Mew> mews = new LinkedHashMap<>();
    public long seed = 0x4A1L;
    public int day = 0;

    /** open a mew; one per settlement */
    public boolean open(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || mews.containsKey(settlementId)) return false;
        mews.put(settlementId, new Mew(settlementId));
        if(listener != null) listener.onMewEvent("opened", settlementId, 0);
        return true;
    }

    /** stock falcons into the mew; refuses overflow and unknown mews */
    public boolean stock(String settlementId, int n){
        Mew m = mews.get(settlementId);
        if(m == null || n <= 0 || m.falcons + n > MEW_CAPACITY) return false;
        m.falcons += n;
        if(listener != null) listener.onMewEvent("stocked", settlementId, n);
        return true;
    }

    /** daily tick: one hunt per mew that keeps birds (single roll) */
    public void tickDay(){
        day++;
        for(Mew m : mews.values()){
            if(m.falcons <= 0) continue;
            m.hunts++;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ m.settlementId.hashCode());
            float sky = rng.range(0f, 1f);
            if(sky < 0.1f){
                m.falcons--;
                m.escaped++;
                if(listener != null) listener.onMewEvent("escaped", m.settlementId, m.falcons);
            } else if(sky < 0.6f){
                m.quarry++;
                if(listener != null) listener.onMewEvent("quarry", m.settlementId, m.quarry);
            }
        }
    }

    public Mew mew(String settlementId){ return mews.get(settlementId); }
    public int mewCount(){ return mews.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("mews");
        for(Mew m : mews.values())
            w.obj().kv("s", m.settlementId).kv("f", m.falcons).kv("q", m.quarry)
              .kv("esc", m.escaped).kv("h", m.hunts).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> ms = n.list("mews");
            if(ms == null) return;
            for(Object o : ms){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Mew x = new Mew(Json.str(m.get("s"), "?"));
                x.falcons = MathX.clamp((int)Json.num(m.get("f"), 0), 0, MEW_CAPACITY);
                x.quarry = (int)Json.num(m.get("q"), 0);
                x.escaped = (int)Json.num(m.get("esc"), 0);
                x.hunts = (int)Json.num(m.get("h"), 0);
                mews.put(x.settlementId, x);
            }
        } catch(Exception ignored){ }
    }
}
