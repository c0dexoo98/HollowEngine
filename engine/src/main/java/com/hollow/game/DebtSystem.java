package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v10.0 — debt system (HollowEngine 10.0 'crown & coin').
 *  Loans with daily interest accrual and a due date: past-due loans are
 *  marked defaulted (an event the game layer can turn into consequences),
 *  repayment is accepted any time and clears the loan at zero.
 */
public final class DebtSystem {

    public static final class Loan {
        public final int id;
        public final String lender;
        public final int principal;
        public final int interestPerDay;
        public final int takenDay;
        public final int dueDay;
        public int remaining;
        public int repaid = 0;
        public boolean defaulted = false;
        public Loan(int id, String lender, int principal, int interestPerDay, int takenDay, int termDays){
            this.id = id; this.lender = lender; this.principal = principal;
            this.interestPerDay = interestPerDay; this.takenDay = takenDay;
            this.dueDay = takenDay + Math.max(1, termDays);
            this.remaining = principal;
        }
    }

    public interface Listener { void onDebtEvent(String type, int loanId, String detail); }
    public Listener listener;

    public final Map<Integer, Loan> loans = new LinkedHashMap<>();
    public int day = 0;
    private int nextId = 1;

    /** take a loan; returns it or null on garbage input */
    public Loan borrow(String lender, int principal, int interestPerDay, int termDays){
        if(lender == null || lender.isEmpty() || principal <= 0 || interestPerDay < 0) return null;
        Loan l = new Loan(nextId++, lender, principal, interestPerDay, day, termDays);
        loans.put(l.id, l);
        if(listener != null) listener.onDebtEvent("borrowed", l.id, lender + "=" + principal);
        return l;
    }

    /** daily tick: interest accrues on live loans; overdue ones default */
    public void tickDay(){
        day++;
        for(Loan l : loans.values()){
            if(l.remaining <= 0) continue;
            l.remaining += l.interestPerDay;
            if(!l.defaulted && day > l.dueDay){
                l.defaulted = true;
                if(listener != null) listener.onDebtEvent("defaulted", l.id, l.lender);
            }
        }
    }

    /** pay down a loan (allowed even after default — partial recovery); returns amount applied */
    public int repay(int loanId, int amount){
        Loan l = loans.get(loanId);
        if(l == null || amount <= 0 || l.remaining <= 0) return 0;
        int paid = Math.min(amount, l.remaining);
        l.remaining -= paid;
        l.repaid += paid;
        if(l.remaining == 0 && listener != null) listener.onDebtEvent("cleared", l.id, l.lender);
        else if(listener != null) listener.onDebtEvent("repaid", l.id, "" + paid);
        return paid;
    }

    public int outstanding(){
        int sum = 0;
        for(Loan l : loans.values()) sum += Math.max(0, l.remaining);
        return sum;
    }

    public List<Loan> loansOf(String lender){
        List<Loan> out = new ArrayList<>();
        for(Loan l : loans.values()) if(l.lender.equals(lender)) out.add(l);
        return out;
    }

    public int defaultedCount(){
        int n = 0;
        for(Loan l : loans.values()) if(l.defaulted && l.remaining > 0) n++;
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId);
        w.arr("loans");
        for(Loan l : loans.values())
            w.obj().kv("id", l.id).kv("who", l.lender).kv("p", l.principal).kv("i", l.interestPerDay)
              .kv("t0", l.takenDay).kv("due", l.dueDay).kv("rem", l.remaining)
              .kv("rep", l.repaid).kv("def", l.defaulted).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> ls = n.list("loans");
            if(ls == null) return;
            for(Object o : ls){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Loan l = new Loan((int)Json.num(m.get("id"), nextId++), Json.str(m.get("who"), "?"),
                    (int)Json.num(m.get("p"), 0), (int)Json.num(m.get("i"), 0),
                    (int)Json.num(m.get("t0"), 0),
                    Math.max(1, (int)Json.num(m.get("due"), 1) - (int)Json.num(m.get("t0"), 0)));
                l.remaining = (int)Json.num(m.get("rem"), l.remaining);
                l.repaid = (int)Json.num(m.get("rep"), 0);
                l.defaulted = Json.bool(m.get("def"), false);
                loans.put(l.id, l);
            }
        } catch(Exception ignored){ }
    }
}
