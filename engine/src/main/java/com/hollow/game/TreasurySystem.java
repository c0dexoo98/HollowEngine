package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** v8.0 — kingdom treasury (HollowEngine 8.0 'kingdom').
 *  Gold balance with a rolling ledger, tax collection over settlement
 *  populations, daily wages, insolvency guard (expenses beyond the balance are
 *  refused) and a low-balance warning event. JSON-persisted.
 */
public final class TreasurySystem {

    public static final class Entry {
        public final int day;
        public final String type;                    // "tax", "wages", "build", "trade"...
        public final int amount;                     // positive income, negative expense
        public final String note;
        public Entry(int day, String type, int amount, String note){
            this.day = day; this.type = type; this.amount = amount; this.note = note;
        }
    }

    public interface Listener { void onTreasuryEvent(String type, int balance, int amount); }
    public Listener listener;

    public int balance = 0;
    public int day = 0;
    public int dailyWages = 0;                       // total paid out each tickDay
    public int lowBalance = 20;                      // warning threshold
    private boolean lowWarned = false;
    private final Deque<Entry> ledger = new ArrayDeque<>();
    public int ledgerLimit = 100;

    /** record income; returns the new balance */
    public int income(String type, int amount, String note){
        if(amount <= 0) return balance;
        balance += amount;
        push(new Entry(day, type, amount, note));
        if(balance > lowBalance) lowWarned = false;
        if(listener != null) listener.onTreasuryEvent(type, balance, amount);
        return balance;
    }

    /** record an expense; refused when it would overdraw the treasury */
    public boolean expense(String type, int amount, String note){
        if(amount <= 0) return true;
        if(amount > balance){
            if(listener != null) listener.onTreasuryEvent("insolvent", balance, amount);
            return false;
        }
        balance -= amount;
        push(new Entry(day, type, -amount, note));
        if(balance <= lowBalance && !lowWarned){
            lowWarned = true;
            if(listener != null) listener.onTreasuryEvent("low_balance", balance, amount);
        }
        if(listener != null) listener.onTreasuryEvent(type, balance, -amount);
        return true;
    }

    /** collect a flat tax from each head in the realm */
    public int collectTaxes(List<Integer> settlementPops, int perHead){
        if(settlementPops == null || perHead <= 0) return 0;
        int heads = 0;
        for(int p : settlementPops) heads += Math.max(0, p);
        int take = heads * perHead;
        return income("tax", take, heads + " heads");
    }

    /** advance a day: wages go out first */
    public void tickDay(){
        day++;
        if(dailyWages > 0) expense("wages", dailyWages, "daily payroll");
    }

    private void push(Entry e){
        ledger.addFirst(e);
        while(ledger.size() > ledgerLimit) ledger.removeLast();
    }

    public List<Entry> recent(int n){
        List<Entry> out = new ArrayList<>();
        for(Entry e : ledger){
            if(out.size() >= n) break;
            out.add(e);
        }
        return out;
    }

    public int ledgerSize(){ return ledger.size(); }

    public int netOf(String type){
        int sum = 0;
        for(Entry e : ledger) if(e.type.equals(type)) sum += e.amount;
        return sum;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("balance", balance).kv("wages", dailyWages).kv("low", lowBalance);
        w.arr("ledger");
        for(Entry e : ledger)
            w.obj().kv("d", e.day).kv("t", e.type).kv("a", e.amount).kv("n", e.note).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            balance = (int)n.flt("balance", balance);
            dailyWages = (int)n.flt("wages", dailyWages);
            lowBalance = (int)n.flt("low", lowBalance);
            lowWarned = balance <= lowBalance;
            List<Object> es = n.list("ledger");
            if(es == null) return;
            for(Object o : es){
                java.util.Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                // saved newest-first; append (not addFirst) keeps that order so recent() still yields newest first
                ledger.addLast(new Entry((int)Json.num(m.get("d"), 0), Json.str(m.get("t"), "?"),
                    (int)Json.num(m.get("a"), 0), Json.str(m.get("n"), "")));
            }
            while(ledger.size() > ledgerLimit) ledger.removeLast();
        } catch(Exception ignored){ }
    }
}
