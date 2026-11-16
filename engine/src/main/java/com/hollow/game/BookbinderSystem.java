package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v18.0 — bookbinder system (HollowEngine 18.0 'pounds & pages').
 *  The frame binds books from a quire stock (two quires paid up
 *  front, three days at the frame), then the spine test rolls once
 *  — 90% sound binding, 10% loose boards (kept in the ledger,
 *  flagged honestly).
 */
public final class BookbinderSystem {

    public static final int QUIRES_PER_BOOK = 2;
    public static final int BIND_DAYS = 3;
    public static final int QUIRES_CAP = 24;

    public static final class Book {
        public final int id;
        public final String title;
        public int daysBound = 0;
        public boolean done = false;
        public boolean sound = false;
        public boolean loose = false;
        public Book(int id, String title){
            this.id = id; this.title = title;
        }
    }

    public interface Listener { void onFrameEvent(String type, int bookId, String detail); }
    public Listener listener;

    public final Map<Integer, Book> books = new LinkedHashMap<>();
    public int quires = 0;
    public long seed = 0x899L;
    public int day = 0;
    private int nextId = 1;

    /** stock the shelf with quires */
    public boolean deliverQuires(int units){
        if(units <= 0 || quires + units > QUIRES_CAP) return false;
        quires += units;
        return true;
    }

    /** start a book; quires are paid up front */
    public Book bind(String title){
        if(title == null || title.isEmpty() || quires < QUIRES_PER_BOOK) return null;
        quires -= QUIRES_PER_BOOK;
        Book b = new Book(nextId++, title);
        books.put(b.id, b);
        if(listener != null) listener.onFrameEvent("started", b.id, title);
        return b;
    }

    /** daily tick: frame days pass; finished books roll the spine test once */
    public void tickDay(){
        day++;
        for(Book b : books.values()){
            if(b.done) continue;
            b.daysBound++;
            if(b.daysBound < BIND_DAYS) continue;
            b.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (b.id * 7919L) ^ (day * 131L));
            b.sound = rng.range(0f, 1f) < 0.9f;
            b.loose = !b.sound;
            if(listener != null) listener.onFrameEvent(b.sound ? "sound" : "loose", b.id, b.title);
        }
    }

    public Book book(int id){ return books.get(id); }
    public int soundCount(){
        int n = 0;
        for(Book b : books.values()) if(b.done && b.sound) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("quires", quires).kv("nextId", nextId);
        w.arr("books");
        for(Book x : books.values())
            w.obj().kv("id", x.id).kv("t", x.title).kv("db", x.daysBound)
              .kv("d", x.done).kv("s", x.sound).kv("l", x.loose).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            quires = MathX.clamp((int)n.flt("quires", quires), 0, QUIRES_CAP);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> bs = n.list("books");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Book x = new Book(nextIdFor(m.get("id")), Json.str(m.get("t"), "?"));
                x.daysBound = (int)Json.num(m.get("db"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.sound = Json.bool(m.get("s"), false);
                x.loose = Json.bool(m.get("l"), false);
                books.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
