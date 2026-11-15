package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** v8.0 — world chronicle (HollowEngine 8.0 'kingdom').
 *  A running history of the realm: notable events are recorded with a fame
 *  score, the chronicle keeps a bounded book (lowest fame evicted first),
 *  answers top/legendary/latest queries, and can retell a deterministic
 *  tale of its greatest entries.
 */
public final class ChronicleSystem {

    public static final class Entry {
        public final int id;
        public final int day;
        public final String type;                    // "war", "festival", "capture", "famine"...
        public final String text;
        public final int fame;
        public Entry(int id, int day, String type, String text, int fame){
            this.id = id; this.day = day; this.type = type; this.text = text; this.fame = fame;
        }
    }

    public interface Listener { void onChronicleEvent(String type, int entryId, int fame); }
    public Listener listener;

    public final List<Entry> entries = new ArrayList<>();
    public int day = 0;
    public int maxEntries = 300;
    public int legendaryFame = 50;
    public long seed = 0x7A0E1L;
    private int nextId = 1;

    /** record a notable event; the book stays bounded by evicting the least famous */
    public Entry record(String type, String text, int fame){
        if(type == null || type.isEmpty() || text == null || text.isEmpty()) return null;
        Entry e = new Entry(nextId++, day, type, text, fame);
        entries.add(e);
        while(entries.size() > maxEntries){
            Entry worst = entries.get(0);
            for(Entry c : entries) if(c.fame < worst.fame) worst = c;
            entries.remove(worst);
            if(listener != null) listener.onChronicleEvent("evicted", worst.id, worst.fame);
        }
        if(fame >= legendaryFame && listener != null) listener.onChronicleEvent("legendary", e.id, fame);
        return e;
    }

    /** the n most famous entries, best first */
    public List<Entry> top(int n){
        List<Entry> out = new ArrayList<>(entries);
        out.sort(Comparator.comparingInt((Entry e) -> e.fame).reversed());
        return out.subList(0, Math.min(n, out.size()));
    }

    /** the n most recent entries, newest first */
    public List<Entry> latest(int n){
        List<Entry> out = new ArrayList<>(entries);
        out.sort(Comparator.comparingInt((Entry e) -> -e.day));
        return out.subList(0, Math.min(n, out.size()));
    }

    public List<Entry> byType(String type){
        List<Entry> out = new ArrayList<>();
        for(Entry e : entries) if(e.type.equals(type)) out.add(e);
        return out;
    }

    public List<Entry> legendary(){
        List<Entry> out = new ArrayList<>();
        for(Entry e : entries) if(e.fame >= legendaryFame) out.add(e);
        return out;
    }

    /** deterministic retelling: k greatest deeds woven into one tale string */
    public String retell(int k){
        List<Entry> best = top(k);
        if(best.isEmpty()) return "The chronicle is empty.";
        StringBuilder sb = new StringBuilder("In the days of old: ");
        MathX.Rng rng = new MathX.Rng(seed ^ day);
        for(int i = 0; i < best.size(); i++){
            Entry e = best.get(i);
            if(i > 0) sb.append(rng.nextInt(2) == 0 ? " Then " : " And it came to pass that ");
            sb.append("on day ").append(e.day).append(", ").append(e.text);
        }
        sb.append(".");
        return sb.toString();
    }

    public int size(){ return entries.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId);
        w.arr("entries");
        for(Entry e : entries)
            w.obj().kv("id", e.id).kv("d", e.day).kv("t", e.type)
              .kv("x", e.text).kv("f", e.fame).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> es = n.list("entries");
            if(es == null) return;
            for(Object o : es){
                java.util.Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                entries.add(new Entry((int)Json.num(m.get("id"), nextId++), (int)Json.num(m.get("d"), 0),
                    Json.str(m.get("t"), "?"), Json.str(m.get("x"), ""),
                    (int)Json.num(m.get("f"), 0)));
            }
        } catch(Exception ignored){ }
    }
}
