package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v10.0 — courier system (HollowEngine 10.0 'crown & coin').
 *  Letters travel between settlements over days; a deterministic roll at
 *  send time decides whether the courier is lost on the road. Delivered
 *  mail lands in the recipient settlement's mailbox.
 */
public final class CourierSystem {

    public static final class Letter {
        public final int id;
        public final String fromId;
        public final String toId;
        public final String body;
        public final int travelDays;
        public final int sentDay;
        public boolean lost = false;
        public boolean delivered = false;
        public Letter(int id, String fromId, String toId, String body, int travelDays, int sentDay){
            this.id = id; this.fromId = fromId; this.toId = toId;
            this.body = body; this.travelDays = Math.max(1, travelDays); this.sentDay = sentDay;
        }
    }

    public interface Listener { void onCourierEvent(String type, int letterId, String detail); }
    public Listener listener;

    public final Map<Integer, Letter> letters = new LinkedHashMap<>();
    public long seed = 0xC01213L;
    public int day = 0;
    private int nextId = 1;

    /** send a letter; the loss roll happens at send time (deterministic per letter) */
    public Letter send(String fromId, String toId, String body, int travelDays, float lossChance){
        if(fromId == null || toId == null || fromId.equals(toId)
           || body == null || body.isEmpty()) return null;
        Letter l = new Letter(nextId++, fromId, toId, body, travelDays, day);
        MathX.Rng rng = new MathX.Rng(seed ^ (l.id * 65537L) ^ day);
        l.lost = rng.range(0f, 1f) < MathX.clamp(lossChance, 0f, 1f);
        letters.put(l.id, l);
        if(listener != null) listener.onCourierEvent("sent", l.id, fromId + "->" + toId);
        return l;
    }

    /** daily tick: couriers move; on the arrival day a letter is delivered or reported lost */
    public void tickDay(){
        day++;
        for(Letter l : letters.values()){
            if(l.delivered) continue;
            if(day - l.sentDay < l.travelDays) continue;
            l.delivered = true;
            if(l.lost){
                if(listener != null) listener.onCourierEvent("lost", l.id, l.fromId + "->" + l.toId);
            } else {
                if(listener != null) listener.onCourierEvent("delivered", l.id, l.toId);
            }
        }
    }

    /** mail that reached this settlement */
    public List<Letter> mailboxOf(String settlementId){
        List<Letter> out = new ArrayList<>();
        for(Letter l : letters.values())
            if(l.delivered && !l.lost && l.toId.equals(settlementId)) out.add(l);
        return out;
    }

    public int pendingCount(){
        int n = 0;
        for(Letter l : letters.values()) if(!l.delivered) n++;
        return n;
    }

    public int lostCount(){
        int n = 0;
        for(Letter l : letters.values()) if(l.delivered && l.lost) n++;
        return n;
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId);
        w.arr("letters");
        for(Letter l : letters.values())
            w.obj().kv("id", l.id).kv("f", l.fromId).kv("t", l.toId).kv("b", l.body)
              .kv("td", l.travelDays).kv("sd", l.sentDay)
              .kv("lost", l.lost).kv("del", l.delivered).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> ls = n.list("letters");
            if(ls == null) return;
            for(Object o : ls){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Letter l = new Letter(nextIdFor(m.get("id")), Json.str(m.get("f"), "?"),
                    Json.str(m.get("t"), "?"), Json.str(m.get("b"), ""), (int)Json.num(m.get("td"), 1),
                    (int)Json.num(m.get("sd"), 0));
                l.lost = Json.bool(m.get("lost"), false);
                l.delivered = Json.bool(m.get("del"), false);
                letters.put(l.id, l);
            }
        } catch(Exception ignored){ }
    }
}
