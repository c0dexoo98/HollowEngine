package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v10.0 — wager system (HollowEngine 10.0 'crown & coin').
 *  Bets on tournament champions: a stake at fixed odds (>= 1.0), settled
 *  when the tournament's champion is declared. Winners take stake x odds,
 *  the house keeps the rest. Deterministic bookkeeping only — the outcome
 *  itself comes from TournamentSystem.
 */
public final class WagerSystem {

    public static final class Bet {
        public final int id;
        public final String bettor;
        public final int tourneyId;
        public final String onKnight;
        public final int amount;
        public final float odds;                     // >= 1.0
        public boolean settled = false;
        public boolean won = false;
        public int payout = 0;
        public Bet(int id, String bettor, int tourneyId, String onKnight, int amount, float odds){
            this.id = id; this.bettor = bettor; this.tourneyId = tourneyId;
            this.onKnight = onKnight; this.amount = amount; this.odds = odds;
        }
    }

    public interface Listener { void onWagerEvent(String type, int betId, String detail); }
    public Listener listener;

    public final Map<Integer, Bet> bets = new LinkedHashMap<>();
    public int houseNet = 0;                         // stakes kept minus payouts made
    private int nextId = 1;

    /** place a bet; refuses garbage stakes and odds below even money */
    public Bet place(String bettor, int tourneyId, String onKnight, int amount, float odds){
        if(bettor == null || bettor.isEmpty() || onKnight == null || onKnight.isEmpty()
           || amount <= 0 || odds < 1f) return null;
        Bet b = new Bet(nextId++, bettor, tourneyId, onKnight, amount, odds);
        bets.put(b.id, b);
        if(listener != null) listener.onWagerEvent("placed", b.id, bettor + "=" + amount);
        return b;
    }

    /** settle every unsettled bet on a tournament once its champion is known */
    public void settle(int tourneyId, String champion){
        for(Bet b : bets.values()){
            if(b.settled || b.tourneyId != tourneyId) continue;
            b.settled = true;
            b.won = b.onKnight.equals(champion);
            b.payout = b.won ? Math.round(b.amount * b.odds) : 0;
            houseNet += b.amount - b.payout;
            if(listener != null)
                listener.onWagerEvent(b.won ? "won" : "lost", b.id, b.bettor + "=" + b.payout);
        }
    }

    /** net profit of a bettor across settled bets (stakes included) */
    public int profitOf(String bettor){
        int sum = 0;
        for(Bet b : bets.values())
            if(b.settled && b.bettor.equals(bettor)) sum += b.payout - b.amount;
        return sum;
    }

    public List<Bet> unsettledOf(int tourneyId){
        List<Bet> out = new ArrayList<>();
        for(Bet b : bets.values()) if(!b.settled && b.tourneyId == tourneyId) out.add(b);
        return out;
    }

    public int betCount(){ return bets.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("nextId", nextId).kv("house", houseNet);
        w.arr("bets");
        for(Bet b : bets.values())
            w.obj().kv("id", b.id).kv("who", b.bettor).kv("t", b.tourneyId).kv("on", b.onKnight)
              .kv("amt", b.amount).kv("odds", b.odds).kv("set", b.settled)
              .kv("won", b.won).kv("pay", b.payout).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            houseNet = (int)n.flt("house", houseNet);
            List<Object> bs = n.list("bets");
            if(bs == null) return;
            for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Bet b = new Bet((int)Json.num(m.get("id"), nextId++), Json.str(m.get("who"), "?"),
                    (int)Json.num(m.get("t"), 0), Json.str(m.get("on"), "?"),
                    (int)Json.num(m.get("amt"), 0), (float)Json.num(m.get("odds"), 1f));
                b.settled = Json.bool(m.get("set"), false);
                b.won = Json.bool(m.get("won"), false);
                b.payout = (int)Json.num(m.get("pay"), 0);
                bets.put(b.id, b);
            }
        } catch(Exception ignored){ }
    }
}
