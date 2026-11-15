package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v7.0 — bounty board (HollowEngine 7.0 'civilization').
 *  Post bounties (target, reward, tier, expiry), hunters claim them, completion
 *  pays the reward and tracks hunter stats; unclaimed bounties expire on tick.
 */
public final class BountySystem {

    public enum State { OPEN, CLAIMED, DONE, EXPIRED }

    public static final class Bounty {
        public final int id;
        public final String target;
        public int reward;
        public int tier = 1;                        // 1 trivial .. 5 legendary
        public int postedDay, expiresDay;
        public State state = State.OPEN;
        public String hunter = "";
        public Bounty(int id, String target, int reward, int tier, int postedDay, int expiresDay){
            this.id = id; this.target = target; this.reward = reward;
            this.tier = tier; this.postedDay = postedDay; this.expiresDay = expiresDay;
        }
    }

    public interface Listener { void onBountyEvent(String type, int bountyId, String detail); }
    public Listener listener;

    public final Map<Integer, Bounty> bounties = new LinkedHashMap<>();
    public final Map<String, Integer> hunterCompletions = new LinkedHashMap<>();
    public final Map<String, Integer> hunterEarnings = new LinkedHashMap<>();
    public int day = 0;
    private int nextId = 1;

    public Bounty post(String target, int reward, int tier, int durationDays){
        Bounty b = new Bounty(nextId++, target, Math.max(1, reward), MathXcl(tier), day, day + Math.max(1, durationDays));
        bounties.put(b.id, b);
        if(listener != null) listener.onBountyEvent("posted", b.id, target);
        return b;
    }
    private static int MathXcl(int t){ return Math.max(1, Math.min(5, t)); }

    public boolean claim(int bountyId, String hunter){
        Bounty b = bounties.get(bountyId);
        if(b == null || b.state != State.OPEN) return false;
        b.state = State.CLAIMED;
        b.hunter = hunter;
        if(listener != null) listener.onBountyEvent("claimed", bountyId, hunter);
        return true;
    }

    /** hunter finished the job → pays out; or failed → bounty returns to the board */
    public boolean complete(int bountyId, boolean success){
        Bounty b = bounties.get(bountyId);
        if(b == null || b.state != State.CLAIMED) return false;
        if(success){
            b.state = State.DONE;
            hunterCompletions.put(b.hunter, hunterCompletions.getOrDefault(b.hunter, 0) + 1);
            hunterEarnings.put(b.hunter, hunterEarnings.getOrDefault(b.hunter, 0) + b.reward);
            if(listener != null) listener.onBountyEvent("done", bountyId, b.hunter + " +" + b.reward);
        } else {
            b.state = State.OPEN;
            b.hunter = "";
            if(listener != null) listener.onBountyEvent("failed", bountyId, "");
        }
        return true;
    }

    /** advance a day: only unclaimed (OPEN) bounties expire — claimed ones stay with their hunter */
    public void tickDay(){
        day++;
        for(Bounty b : bounties.values()){
            if(b.state == State.OPEN && day > b.expiresDay){
                b.state = State.EXPIRED;
                if(listener != null) listener.onBountyEvent("expired", b.id, b.target);
            }
        }
    }

    public List<Bounty> openBounties(){
        List<Bounty> out = new ArrayList<>();
        for(Bounty b : bounties.values()) if(b.state == State.OPEN) out.add(b);
        return out;
    }

    public int completions(String hunter){ return hunterCompletions.getOrDefault(hunter, 0); }
    public int earned(String hunter){ return hunterEarnings.getOrDefault(hunter, 0); }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId);
        w.arr("bounties");
        for(Bounty b : bounties.values()){
            w.obj().kv("id", b.id).kv("target", b.target).kv("reward", b.reward).kv("tier", b.tier)
              .kv("posted", b.postedDay).kv("expires", b.expiresDay).kv("state", b.state.name())
              .kv("hunter", b.hunter).endObj();
        }
        w.endArr();
        w.arr("hunters");
        for(Map.Entry<String, Integer> kv : hunterCompletions.entrySet())
            w.obj().kv("name", kv.getKey()).kv("done", kv.getValue())
              .kv("gold", hunterEarnings.getOrDefault(kv.getKey(), 0)).endObj();
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> bs = n.list("bounties");
            if(bs != null) for(Object o : bs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Bounty b = new Bounty(nextIdFor(m.get("id")), Json.str(m.get("target"), "?"),
                    (int)Json.num(m.get("reward"), 1), (int)Json.num(m.get("tier"), 1),
                    (int)Json.num(m.get("posted"), day), (int)Json.num(m.get("expires"), day + 1));
                try { b.state = State.valueOf(Json.str(m.get("state"), "OPEN")); } catch(Exception ignored){ }
                b.hunter = Json.str(m.get("hunter"), "");
                bounties.put(b.id, b);
            }
            List<Object> hs = n.list("hunters");
            if(hs != null) for(Object o : hs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String name = Json.str(m.get("name"), null);
                if(name == null) continue;
                hunterCompletions.put(name, (int)Json.num(m.get("done"), 0));
                hunterEarnings.put(name, (int)Json.num(m.get("gold"), 0));
            }
        } catch(Exception ignored){ }
    }
}
