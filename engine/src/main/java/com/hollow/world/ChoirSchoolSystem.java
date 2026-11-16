package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v20.0 — choir school system (HollowEngine 20.0 'harps & halls').
 *  One choir school per village: pupils are enrolled (up to the
 *  school's benches); the cohort trains toward voice day — each day
 *  one roll decides a throat — 90% healthy, 10% a croak and one
 *  pupil leaves (counted honestly). After four training days the
 *  surviving pupils are voiced and the bench empties for the next
 *  cohort. An empty school idles: no training, no roll.
 */
public final class ChoirSchoolSystem {

    public static final int PUPILS_CAP = 8;
    public static final int VOICE_DAYS = 4;
    public static final float CROAK_CHANCE = 0.1f;

    public static final class Choir {
        public final String villageId;
        public int pupils = 0;
        public int daysTrained = 0;
        public int voiced = 0;
        public int croaks = 0;
        public Choir(String villageId){
            this.villageId = villageId;
        }
    }

    public interface Listener { void onChoirEvent(String type, String villageId, int value); }
    public Listener listener;

    public final Map<String, Choir> choirs = new LinkedHashMap<>();
    public long seed = 0xB0DL;
    public int day = 0;

    /** found a choir school; one per village */
    public boolean found(String villageId){
        if(villageId == null || villageId.isEmpty() || choirs.containsKey(villageId)) return false;
        choirs.put(villageId, new Choir(villageId));
        if(listener != null) listener.onChoirEvent("founded", villageId, 0);
        return true;
    }

    /** enroll one pupil; refuses overflow and unknown schools */
    public boolean enroll(String villageId){
        Choir c = choirs.get(villageId);
        if(c == null || c.pupils + 1 > PUPILS_CAP) return false;
        c.pupils++;
        if(listener != null) listener.onChoirEvent("enrolled", villageId, c.pupils);
        return true;
    }

    /** daily tick: the cohort trains one day; a croak loses a pupil */
    public void tickDay(){
        day++;
        for(Choir c : choirs.values()){
            if(c.pupils <= 0) continue;
            c.daysTrained++;
            MathX.Rng rng = new MathX.Rng(seed ^ (day * 71L) ^ c.villageId.hashCode());
            if(rng.range(0f, 1f) < CROAK_CHANCE){
                c.pupils--;
                c.croaks++;
                if(listener != null) listener.onChoirEvent("croak", c.villageId, c.croaks);
            }
            if(c.daysTrained >= VOICE_DAYS){
                if(c.pupils > 0){
                    c.voiced += c.pupils;
                    if(listener != null) listener.onChoirEvent("voiced", c.villageId, c.pupils);
                }
                c.pupils = 0;
                c.daysTrained = 0;
            }
        }
    }

    public Choir choir(String villageId){ return choirs.get(villageId); }
    public int choirCount(){ return choirs.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day);
        w.arr("choirs");
        for(Choir c : choirs.values())
            w.obj().kv("v", c.villageId).kv("p", c.pupils).kv("dt", c.daysTrained)
              .kv("vo", c.voiced).kv("cr", c.croaks).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            java.util.List<Object> cs = n.list("choirs");
            if(cs == null) return;
            for(Object o : cs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Choir c = new Choir(Json.str(m.get("v"), "?"));
                c.pupils = MathX.clamp((int)Json.num(m.get("p"), 0), 0, PUPILS_CAP);
                c.daysTrained = MathX.clamp((int)Json.num(m.get("dt"), 0), 0, VOICE_DAYS);
                c.voiced = (int)Json.num(m.get("vo"), 0);
                c.croaks = (int)Json.num(m.get("cr"), 0);
                choirs.put(c.villageId, c);
            }
        } catch(Exception ignored){ }
    }
}
