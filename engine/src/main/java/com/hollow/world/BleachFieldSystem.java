package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v19.0 — bleach field system (HollowEngine 19.0 'spindles & shuttles').
 *  One bleach field per village: cloth lengths are laid out (up to the
 *  field's capacity) and need a set number of cumulative sun days; each
 *  day one roll decides the weather — 20% a rain shower wastes the day
 *  (counted in the rains ledger), else the sun counts and a full batch
 *  finishes when its sun days are banked.
 */
public final class BleachFieldSystem {

    public static final int FIELD_CAPACITY = 12;
    public static final int BLEACH_DAYS = 3;
    public static final float RAIN_CHANCE = 0.2f;

    public static final class Field {
        public final String villageId;
        public int lengths = 0;
        public int sunDays = 0;
        public int bleached = 0;
        public int rains = 0;
        public Field(String villageId){
            this.villageId = villageId;
        }
    }

    public interface Listener { void onFieldEvent(String type, String villageId, int value); }
    public Listener listener;

    public final Map<String, Field> fields = new LinkedHashMap<>();
    public long seed = 0x81L;
    public int day = 0;

    /** stake out a bleach field; one per village */
    public boolean stake(String villageId){
        if(villageId == null || villageId.isEmpty() || fields.containsKey(villageId)) return false;
        fields.put(villageId, new Field(villageId));
        if(listener != null) listener.onFieldEvent("staked", villageId, 0);
        return true;
    }

    /** lay cloth lengths; refuses overflow and unknown fields */
    public boolean lay(String villageId, int lengths){
        Field f = fields.get(villageId);
        if(f == null || lengths <= 0 || f.lengths + lengths > FIELD_CAPACITY) return false;
        f.lengths += lengths;
        if(listener != null) listener.onFieldEvent("laid", villageId, lengths);
        return true;
    }

    /** daily tick: the weather roll banks or wastes a sun day */
    public void tickDay(){
        day++;
        for(Field f : fields.values()){
            if(f.lengths <= 0) continue;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ f.villageId.hashCode());
            if(rng.range(0f, 1f) < RAIN_CHANCE){
                f.rains++;
                if(listener != null) listener.onFieldEvent("rain", f.villageId, f.rains);
            } else {
                f.sunDays++;
                if(f.sunDays >= BLEACH_DAYS){
                    f.bleached += f.lengths;
                    f.lengths = 0;
                    f.sunDays = 0;
                    if(listener != null) listener.onFieldEvent("bleached", f.villageId, f.bleached);
                }
            }
        }
    }

    public Field field(String villageId){ return fields.get(villageId); }
    public int fieldCount(){ return fields.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("fields");
        for(Field f : fields.values())
            w.obj().kv("v", f.villageId).kv("l", f.lengths).kv("sd", f.sunDays)
              .kv("bl", f.bleached).kv("ra", f.rains).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> fs = n.list("fields");
            if(fs == null) return;
            for(Object o : fs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Field f = new Field(Json.str(m.get("v"), "?"));
                f.lengths = MathX.clamp((int)Json.num(m.get("l"), 0), 0, FIELD_CAPACITY);
                f.sunDays = MathX.clamp((int)Json.num(m.get("sd"), 0), 0, BLEACH_DAYS);
                f.bleached = (int)Json.num(m.get("bl"), 0);
                f.rains = (int)Json.num(m.get("ra"), 0);
                fields.put(f.villageId, f);
            }
        } catch(Exception ignored){ }
    }
}
