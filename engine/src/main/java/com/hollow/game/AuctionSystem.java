package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v8.0 — auction house (HollowEngine 8.0 'kingdom').
 *  Sellers list stacks with a reserve price; bidders outbid each other by a
 *  minimum increment; listings close on their expiry day — sold ones pay the
 *  seller minus a house fee, empty ones return unsold. Deterministic.
 */
public final class AuctionSystem {

    public enum State { OPEN, SOLD, EXPIRED }

    public static final class Listing {
        public final int id;
        public final String item;
        public final int qty;
        public final int reserve;
        public final String seller;
        public final int expiresDay;
        public State state = State.OPEN;
        public String topBidder = "";
        public int topBid = 0;
        public Listing(int id, String item, int qty, int reserve, String seller, int expiresDay){
            this.id = id; this.item = item; this.qty = qty;
            this.reserve = reserve; this.seller = seller; this.expiresDay = expiresDay;
        }
    }

    public interface Listener { void onAuctionEvent(String type, int listingId, String detail); }
    public Listener listener;

    public final Map<Integer, Listing> listings = new LinkedHashMap<>();
    public final Map<String, Integer> proceeds = new LinkedHashMap<>();    // seller → gold earned
    public int day = 0;
    public int minIncrement = 1;
    public int feePercent = 5;                       // house cut on sale
    private int nextId = 1;

    public Listing list(String item, int qty, int reserve, String seller, int durationDays){
        if(item == null || item.isEmpty() || qty <= 0 || reserve <= 0 || seller == null) return null;
        Listing l = new Listing(nextId++, item, qty, reserve, seller, day + Math.max(1, durationDays));
        listings.put(l.id, l);
        if(listener != null) listener.onAuctionEvent("listed", l.id, item);
        return l;
    }

    public Listing listing(int id){ return listings.get(id); }

    /** place a bid: first bid must meet the reserve, later bids must beat the top by the increment;
     *  bids on a listing past its expiry day are refused (tickDay closes it) */
    public boolean bid(int listingId, String bidder, int amount){
        Listing l = listings.get(listingId);
        if(l == null || l.state != State.OPEN || day > l.expiresDay) return false;
        if(bidder == null || bidder.equals(l.seller)) return false;
        if(amount < l.reserve) return false;
        if(!l.topBidder.isEmpty() && amount < l.topBid + minIncrement) return false;
        l.topBidder = bidder;
        l.topBid = amount;
        if(listener != null) listener.onAuctionEvent("bid", l.id, bidder + "=" + amount);
        return true;
    }

    /** daily close: expired listings resolve */
    public void tickDay(){
        day++;
        for(Listing l : listings.values()){
            if(l.state != State.OPEN || day <= l.expiresDay) continue;
            if(l.topBidder.isEmpty()){
                l.state = State.EXPIRED;
                if(listener != null) listener.onAuctionEvent("expired", l.id, l.item);
            } else {
                l.state = State.SOLD;
                int fee = l.topBid * feePercent / 100;
                int net = l.topBid - fee;
                proceeds.put(l.seller, proceeds.getOrDefault(l.seller, 0) + net);
                if(listener != null) listener.onAuctionEvent("sold", l.id, l.topBidder + " net=" + net);
            }
        }
    }

    public int sellerProceeds(String seller){ return proceeds.getOrDefault(seller, 0); }

    public int openCount(){
        int n = 0;
        for(Listing l : listings.values()) if(l.state == State.OPEN) n++;
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
        w.arr("listings");
        for(Listing l : listings.values()){
            w.obj().kv("id", l.id).kv("item", l.item).kv("qty", l.qty).kv("reserve", l.reserve)
              .kv("seller", l.seller).kv("exp", l.expiresDay).kv("state", l.state.name())
              .kv("bidder", l.topBidder).kv("bid", l.topBid).endObj();
        }
        w.endArr();
        w.arr("proceeds");
        for(Map.Entry<String, Integer> kv : proceeds.entrySet())
            w.obj().kv("s", kv.getKey()).kv("g", kv.getValue()).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> ls = n.list("listings");
            if(ls != null) for(Object o : ls){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Listing l = new Listing(nextIdFor(m.get("id")), Json.str(m.get("item"), "?"),
                    (int)Json.num(m.get("qty"), 0), (int)Json.num(m.get("reserve"), 1),
                    Json.str(m.get("seller"), ""), (int)Json.num(m.get("exp"), day + 1));
                try { l.state = State.valueOf(Json.str(m.get("state"), "OPEN")); } catch(Exception ignored){ }
                l.topBidder = Json.str(m.get("bidder"), "");
                l.topBid = (int)Json.num(m.get("bid"), 0);
                listings.put(l.id, l);
            }
            List<Object> ps = n.list("proceeds");
            if(ps != null) for(Object o : ps){
                Map<String,Object> m = Json.obj(o);
                if(m != null) proceeds.put(Json.str(m.get("s"), ""), (int)Json.num(m.get("g"), 0));
            }
        } catch(Exception ignored){ }
    }
}
