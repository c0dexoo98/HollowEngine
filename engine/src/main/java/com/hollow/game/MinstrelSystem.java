package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v20.0 — minstrel system (HollowEngine 20.0 'harps & halls').
 *  A minstrel plays one song per single-day booking, then the crowd
 *  rolls once — 90% applause worth five silver, 10% silence worth
 *  one. The silver ledger and both crowd tallies are kept honestly.
 */
public final class MinstrelSystem {

    public static final int PLAY_DAYS = 1;
    public static final int APPLAUSE_TIP = 5;
    public static final int SILENCE_TIP = 1;

    public static final class Booking {
        public final int id;
        public final String song;
        public int daysWaited = 0;
        public boolean done = false;
        public boolean applause = false;
        public Booking(int id, String song){
            this.id = id; this.song = song;
        }
    }

    public interface Listener { void onMinstrelEvent(String type, int bookingId, String detail); }
    public Listener listener;

    public final Map<Integer, Booking> bookings = new LinkedHashMap<>();
    public int silver = 0;
    public long seed = 0xF1DL;
    public int day = 0;
    private int nextId = 1;

    /** book a song; the minstrel plays the next day */
    public Booking play(String song){
        if(song == null || song.isEmpty()) return null;
        Booking b = new Booking(nextId++, song);
        bookings.put(b.id, b);
        if(listener != null) listener.onMinstrelEvent("booked", b.id, song);
        return b;
    }

    /** daily tick: play days pass; finished bookings roll the crowd once */
    public void tickDay(){
        day++;
        for(Booking b : bookings.values()){
            if(b.done) continue;
            b.daysWaited++;
            if(b.daysWaited < PLAY_DAYS) continue;
            b.done = true;
            MathX.Rng rng = new MathX.Rng(seed ^ (b.id * 7919L) ^ (day * 131L));
            b.applause = rng.range(0f, 1f) < 0.9f;
            silver += b.applause ? APPLAUSE_TIP : SILENCE_TIP;
            if(listener != null) listener.onMinstrelEvent(b.applause ? "applause" : "silence", b.id, b.song);
        }
    }

    public Booking booking(int id){ return bookings.get(id); }
    public int applauseCount(){
        int n = 0;
        for(Booking b : bookings.values()) if(b.done && b.applause) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("silver", silver).kv("nextId", nextId);
        w.arr("bookings");
        for(Booking x : bookings.values())
            w.obj().kv("id", x.id).kv("s", x.song).kv("dw", x.daysWaited)
              .kv("d", x.done).kv("a", x.applause).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            silver = (int)n.flt("silver", silver);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            java.util.List<Object> bs = n.list("bookings");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Booking x = new Booking(nextIdFor(m.get("id")), Json.str(m.get("s"), "?"));
                x.daysWaited = (int)Json.num(m.get("dw"), 0);
                x.done = Json.bool(m.get("d"), false);
                x.applause = Json.bool(m.get("a"), false);
                bookings.put(x.id, x);
            }
        } catch(Exception ignored){ }
    }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }
}
