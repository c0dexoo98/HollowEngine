package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v6.0 — journal / codex system (HollowEngine 6.0 'living-world').
 *  Bestiary, location and lore entries discovered through world events; each
 *  discovery fires once, category completion is queryable, and the player can
 *  attach personal notes. JSON-persisted.
 */
public final class JournalSystem {

    public enum Category { BESTIARY, LOCATION, LORE, NPC, ITEM }

    public static final class Entry {
        public final String id;
        public final Category category;
        public String title;
        public String body = "";
        public boolean discovered = false;
        public int discoveredAtDay = -1;
        public Entry(String id, Category category, String title){
            this.id = id; this.category = category; this.title = title;
        }
        public Entry text(String b){ body = b; return this; }
    }

    public static final class Note {
        public final int id;
        public final String text;
        public final String markerId;           // optional map marker link (MapMarkerSystem)
        public Note(int id, String text, String markerId){ this.id = id; this.text = text; this.markerId = markerId; }
    }

    public interface Listener { void onJournalEvent(String type, String entryId, String category); }
    public Listener listener;

    public final Map<String, Entry> entries = new LinkedHashMap<>();
    public final List<Note> notes = new ArrayList<>();
    public int day = 0;
    private int nextNoteId = 1;

    public Entry define(Entry e){ entries.put(e.id, e); return e; }
    public Entry entry(String id){ return entries.get(id); }

    /** discover an entry; fires exactly once */
    public boolean discover(String id){
        Entry e = entries.get(id);
        if(e == null || e.discovered) return e != null && e.discovered;
        e.discovered = true;
        e.discoveredAtDay = day;
        if(listener != null) listener.onJournalEvent("discovered", id, e.category.name());
        return true;
    }

    public boolean isDiscovered(String id){
        Entry e = entries.get(id);
        return e != null && e.discovered;
    }

    public int discoveredCount(Category cat){
        int n = 0;
        for(Entry e : entries.values())
            if(e.category == cat && e.discovered) n++;
        return n;
    }
    public int totalCount(Category cat){
        int n = 0;
        for(Entry e : entries.values())
            if(e.category == cat) n++;
        return n;
    }
    /** 0..1 completion for one category (1 when the category has no entries) */
    public float completion(Category cat){
        int total = totalCount(cat);
        return total == 0 ? 1f : discoveredCount(cat) / (float)total;
    }
    public float totalCompletion(){
        int total = entries.size();
        if(total == 0) return 1f;
        int found = 0;
        for(Entry e : entries.values()) if(e.discovered) found++;
        return found / (float)total;
    }

    public Note addNote(String text, String markerId){
        Note n = new Note(nextNoteId++, text, markerId);
        notes.add(n);
        if(listener != null) listener.onJournalEvent("note", String.valueOf(n.id), null);
        return n;
    }
    public boolean removeNote(int id){
        return notes.removeIf(n -> n.id == id);
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextNote", nextNoteId);
        w.arr("entries");
        for(Entry e : entries.values()){
            w.obj().kv("id", e.id).kv("cat", e.category.name()).kv("title", e.title)
              .kv("body", e.body).kv("disc", e.discovered).kv("day", e.discoveredAtDay).endObj();
        }
        w.endArr();
        w.arr("notes");
        for(Note n : notes)
            w.obj().kv("id", n.id).kv("text", n.text).kv("marker", n.markerId == null ? "" : n.markerId).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextNoteId = Math.max(1, (int)n.flt("nextNote", nextNoteId));
            List<Object> es = n.list("entries");
            if(es != null) for(Object o : es){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String id = Json.str(m.get("id"), null);
                if(id == null) continue;
                Category cat;
                try { cat = Category.valueOf(Json.str(m.get("cat"), "LORE")); } catch(Exception x){ cat = Category.LORE; }
                Entry e = entries.get(id);
                if(e == null) e = define(new Entry(id, cat, Json.str(m.get("title"), id)));
                e.body = Json.str(m.get("body"), e.body);
                e.discovered = Json.bool(m.get("disc"), e.discovered);
                e.discoveredAtDay = (int)Json.num(m.get("day"), e.discoveredAtDay);
            }
            List<Object> ns = n.list("notes");
            if(ns != null) for(Object o : ns){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                notes.add(new Note((int)Json.num(m.get("id"), nextNoteId++),
                    Json.str(m.get("text"), ""), Json.str(m.get("marker"), "")));
            }
        } catch(Exception ignored){ }
    }
}
