package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v13.0 — astrologer system (HollowEngine 13.0 'ritual & remedy').
 *  One observatory per settlement: on a clear night the astronomer may
 *  fix one new star chart (deterministic roll), clouds waste the night,
 *  and each chart feeds the settlement's omen ledger.
 */
public final class AstrologerSystem {

    public static final String[] CHARTS = {
        "The Plough", "The Lantern", "The Serpent", "The Twins",
        "The Crown", "The Wolf", "The Anchor", "The Wheel"};

    public static final class Observatory {
        public final String settlementId;
        public int charts = 0;
        public int nights = 0;
        public int cloudedNights = 0;
        public Observatory(String settlementId){
            this.settlementId = settlementId;
        }
        public String latestOmen(){
            return charts == 0 ? "no charts yet" : CHARTS[(charts - 1) % CHARTS.length] + " reigns tonight";
        }
    }

    public interface Listener { void onSkyEvent(String type, String settlementId, int value); }
    public Listener listener;

    public final Map<String, Observatory> observatories = new LinkedHashMap<>();
    public long seed = 0x57A7L;
    public int day = 0;

    /** open an observatory; one per settlement */
    public boolean open(String settlementId){
        if(settlementId == null || settlementId.isEmpty() || observatories.containsKey(settlementId)) return false;
        observatories.put(settlementId, new Observatory(settlementId));
        if(listener != null) listener.onSkyEvent("opened", settlementId, 0);
        return true;
    }

    /** nightly tick: one roll partitions the sky — clouds 15%, chart 59.5%.
     *  (single roll per tick: the codebase convention; consecutive draws
     *  from one Rng correlate for small integer seeds) */
    public void tickDay(){
        day++;
        for(Observatory o : observatories.values()){
            o.nights++;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ o.settlementId.hashCode());
            float sky = rng.range(0f, 1f);
            if(sky < 0.15f){
                o.cloudedNights++;
                if(listener != null) listener.onSkyEvent("clouded", o.settlementId, o.nights);
                continue;
            }
            if(sky < 0.15f + 0.85f * 0.7f && o.charts < CHARTS.length){
                o.charts++;
                if(listener != null) listener.onSkyEvent("chart", o.settlementId, o.charts);
            }
        }
    }

    public Observatory observatory(String settlementId){ return observatories.get(settlementId); }
    public int observatoryCount(){ return observatories.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("observatories");
        for(Observatory o : observatories.values())
            w.obj().kv("s", o.settlementId).kv("ch", o.charts).kv("n", o.nights)
              .kv("cl", o.cloudedNights).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> os = n.list("observatories");
            if(os == null) return;
            for(Object o : os){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Observatory x = new Observatory(Json.str(m.get("s"), "?"));
                x.charts = MathX.clamp((int)Json.num(m.get("ch"), 0), 0, CHARTS.length);
                x.nights = (int)Json.num(m.get("n"), 0);
                x.cloudedNights = (int)Json.num(m.get("cl"), 0);
                observatories.put(x.settlementId, x);
            }
        } catch(Exception ignored){ }
    }
}
