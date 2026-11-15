package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.LinkedHashMap;
import java.util.Map;

/** v11.0 — school system (HollowEngine 11.0 'hearth & home').
 *  Village schools: one per settlement with a teacher and a capacity;
 *  pupils advance one lesson per day and graduate after enough of them.
 *  Literacy rate tracks graduates over everyone ever enrolled.
 */
public final class SchoolSystem {

    public static final class School {
        public final String settlementId;
        public final String teacher;
        public final int capacity;
        public int lessonsRequired;
        public final java.util.Map<String, Integer> pupils = new LinkedHashMap<>();   // name → lessons taken
        public int graduated = 0;
        public School(String settlementId, String teacher, int capacity, int lessonsRequired){
            this.settlementId = settlementId; this.teacher = teacher;
            this.capacity = Math.max(1, capacity);
            this.lessonsRequired = Math.max(1, lessonsRequired);
        }
    }

    public interface Listener { void onSchoolEvent(String type, String pupil, String detail); }
    public Listener listener;

    public final java.util.Map<String, School> schools = new LinkedHashMap<>();
    public int day = 0;
    public int everEnrolled = 0;

    /** found a school; one per settlement */
    public boolean found(String settlementId, String teacher, int capacity, int lessonsRequired){
        if(settlementId == null || settlementId.isEmpty() || teacher == null || teacher.isEmpty()
           || schools.containsKey(settlementId)) return false;
        schools.put(settlementId, new School(settlementId, teacher, capacity, lessonsRequired));
        if(listener != null) listener.onSchoolEvent("founded", settlementId, teacher);
        return true;
    }

    /** enroll a pupil; refuses full classes and duplicate names in the same school */
    public boolean enroll(String settlementId, String pupil){
        School s = schools.get(settlementId);
        if(s == null || pupil == null || pupil.isEmpty()
           || s.pupils.containsKey(pupil) || s.pupils.size() >= s.capacity) return false;
        s.pupils.put(pupil, 0);
        everEnrolled++;
        if(listener != null) listener.onSchoolEvent("enrolled", pupil, settlementId);
        return true;
    }

    /** daily tick: one lesson each; graduates leave the bench */
    public void tickDay(){
        day++;
        for(School s : schools.values()){
            java.util.List<String> done = new java.util.ArrayList<>();
            for(Map.Entry<String, Integer> kv : s.pupils.entrySet()){
                int lessons = kv.getValue() + 1;
                if(lessons >= s.lessonsRequired) done.add(kv.getKey());
                else kv.setValue(lessons);
            }
            for(String name : done){
                s.pupils.remove(name);
                s.graduated++;
                if(listener != null) listener.onSchoolEvent("graduated", name, s.settlementId);
            }
        }
    }

    /** drop out of a school */
    public boolean dropout(String settlementId, String pupil){
        School s = schools.get(settlementId);
        if(s == null || s.pupils.remove(pupil) == null) return false;
        if(listener != null) listener.onSchoolEvent("dropped", pupil, settlementId);
        return true;
    }

    /** graduates over everyone ever enrolled; 1.0 once everyone has finished */
    public float literacyRate(){
        return everEnrolled == 0 ? 0f : (float)schools.values().stream().mapToInt(x -> x.graduated).sum() / everEnrolled;
    }

    public int graduatedCount(){
        return schools.values().stream().mapToInt(x -> x.graduated).sum();
    }

    public School school(String settlementId){ return schools.get(settlementId); }
    public int schoolCount(){ return schools.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("ever", everEnrolled);
        w.arr("schools");
        for(School s : schools.values()){
            w.obj().kv("s", s.settlementId).kv("t", s.teacher).kv("cap", s.capacity)
              .kv("les", s.lessonsRequired).kv("grad", s.graduated);
            w.arr("pupils");
            for(Map.Entry<String, Integer> kv : s.pupils.entrySet())
                w.obj().kv("n", kv.getKey()).kv("l", kv.getValue()).endObj();
            w.endArr();
            w.endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            everEnrolled = (int)n.flt("ever", everEnrolled);
            java.util.List<Object> ss = n.list("schools");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                School s = new School(Json.str(m.get("s"), "?"), Json.str(m.get("t"), "?"),
                    (int)Json.num(m.get("cap"), 1), (int)Json.num(m.get("les"), 1));
                s.graduated = (int)Json.num(m.get("grad"), 0);
                java.util.List<Object> ps = Json.arr(m.get("pupils"));
                if(ps != null) for(Object po : ps){
                    Map<String,Object> pm = Json.obj(po);
                    if(pm != null) s.pupils.put(Json.str(pm.get("n"), "?"), (int)Json.num(pm.get("l"), 0));
                }
                schools.put(s.settlementId, s);
            }
        } catch(Exception ignored){ }
    }
}
