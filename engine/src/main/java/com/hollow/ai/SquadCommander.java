package com.hollow.ai;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v6.0 — squad commander (HollowEngine 6.0 'living-world').
 *  Player-issued orders for NPC squads: FOLLOW (wedge formation behind the leader),
 *  HOLD (ring around the anchor), ATTACK (move to target), RETREAT (flee to rally
 *  point), DISMISS. Members steer toward their assigned slot with capped speed and
 *  snap when close; real navigation plugs in via an optional Mover. Deterministic.
 */
public final class SquadCommander {

    public enum Order { FOLLOW, HOLD, ATTACK, RETREAT, DISMISS }

    public static final class Member {
        public final String name;
        public Vec3 pos = new Vec3();
        public float speed = 4.5f;              // m/s
        public int slotIndex = 0;
        public Order order = Order.FOLLOW;
        public Member(String name){ this.name = name; }
    }

    public static final class Squad {
        public final String id;
        public Order order = Order.FOLLOW;
        public Vec3 anchor = new Vec3();        // leader pos (FOLLOW) / hold point (HOLD/RETREAT)
        public Vec3 target = new Vec3();        // ATTACK target
        public float spacing = 2.2f;
        public final List<Member> members = new ArrayList<>();
        public Squad(String id){ this.id = id; }
        public Member member(String name){
            for(Member m : members) if(m.name.equals(name)) return m;
            return null;
        }
    }

    /** optional: real locomotion (navmesh steering) — default is direct line movement */
    public interface Mover { void move(Member m, Vec3 to, float dt); }
    public Mover mover;
    public interface Listener { void onSquadEvent(String squadId, Order order, String detail); }
    public Listener listener;

    public final Map<String, Squad> squads = new LinkedHashMap<>();
    public long seed = 0x50AD1L;

    public Squad define(String id){ Squad s = new Squad(id); squads.put(id, s); return s; }
    public Squad squad(String id){ return squads.get(id); }

    public boolean recruit(String squadId, String name){
        Squad s = squads.get(squadId);
        if(s == null || s.member(name) != null) return false;
        Member m = new Member(name);
        m.slotIndex = s.members.size();
        s.members.add(m);
        return true;
    }

    /** issue an order to the whole squad; members holding a personal order keep it */
    public boolean order(String squadId, Order order, Vec3 arg){
        Squad s = squads.get(squadId);
        if(s == null) return false;
        Order previous = s.order;
        s.order = order;
        if(arg != null){
            if(order == Order.ATTACK) s.target.set(arg);
            else s.anchor.set(arg);
        }
        for(Member m : s.members)
            if(m.order == previous) m.order = order;   // personal overrides stick
        if(listener != null) listener.onSquadEvent(squadId, order, s.members.size() + " members");
        return true;
    }

    /** order a single member only */
    public boolean orderMember(String squadId, String name, Order order, Vec3 arg){
        Squad s = squads.get(squadId);
        Member m = s != null ? s.member(name) : null;
        if(m == null) return false;
        m.order = order;
        if(arg != null && order == Order.ATTACK) s.target.set(arg);
        if(listener != null) listener.onSquadEvent(squadId, order, name);
        return true;
    }

    /** formation slot position for member i of n — honors the member's own order */
    public Vec3 slot(Squad s, Member m, int i){
        Order order = m != null ? m.order : s.order;
        Vec3 out = new Vec3();
        switch(order){
            case FOLLOW: {
                // wedge behind the anchor along -Z of the anchor's facing (anchor.z axis)
                int row = (int)(Math.sqrt(i + 1) - 0.0001f);          // 0,1,1,2,2,2,3..
                int idxInRow = i - (row * (row + 1)) / 2;
                float lateral = (idxInRow - row / 2f) * s.spacing;
                float back = (row + 1) * s.spacing * 0.9f;
                out.set(s.anchor.x + lateral, s.anchor.y, s.anchor.z + back);
                break;
            }
            case HOLD: {
                // ring around the anchor
                double ang = (2 * Math.PI * i) / Math.max(1, s.members.size());
                float r = s.spacing * (1f + i / (float)Math.max(1, s.members.size()));
                out.set(s.anchor.x + (float)Math.cos(ang) * r, s.anchor.y, s.anchor.z + (float)Math.sin(ang) * r);
                break;
            }
            case ATTACK: {
                // encircle the target
                double ang = (2 * Math.PI * i) / Math.max(1, s.members.size());
                float r = s.spacing * 1.4f;
                out.set(s.target.x + (float)Math.cos(ang) * r, s.target.y, s.target.z + (float)Math.sin(ang) * r);
                break;
            }
            case RETREAT: {
                // cluster tight at the rally point
                MathX.Rng rng = new MathX.Rng(seed ^ i * 7919L);
                out.set(s.anchor.x + rng.range(-1f, 1f) * s.spacing,
                        s.anchor.y,
                        s.anchor.z + rng.range(-1f, 1f) * s.spacing);
                break;
            }
            default: out.set(s.anchor);                                // DISMISS: stay put
        }
        return out;
    }

    public void update(float dt){
        if(dt <= 0f) return;
        for(Squad s : squads.values()){
            for(Member m : s.members){
                Vec3 goal = slot(s, m, m.slotIndex);
                float d = m.pos.dist(goal);
                if(m.order == Order.DISMISS) continue;
                if(d < 0.25f) continue;                                // snapped
                if(mover != null){ mover.move(m, goal, dt); continue; }
                float step = Math.min(m.speed * dt, d);
                Vec3 dir = goal.cpy().sub(m.pos).scale(1f / Math.max(0.0001f, d));
                m.pos.addScaled(dir, step);
            }
        }
    }

    /** share of non-dismissed members snapped within 0.5 m of their slot */
    public float formationQuality(String squadId){
        Squad s = squads.get(squadId);
        int counted = 0, snapped = 0;
        if(s != null) for(Member m : s.members){
            if(m.order == Order.DISMISS) continue;      // dismissed members are not in formation
            counted++;
            if(m.pos.dist(slot(s, m, m.slotIndex)) < 0.5f) snapped++;
        }
        return counted == 0 ? 1f : snapped / (float)counted;
    }

    // ---------------- persistence (orders + slots, not positions) ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj();
        w.arr("squads");
        for(Squad s : squads.values()){
            w.obj().kv("id", s.id).kv("order", s.order.name()).kv("spacing", s.spacing)
              .kvv("anchor", s.anchor.x, s.anchor.y, s.anchor.z)
              .kvv("target", s.target.x, s.target.y, s.target.z);
            w.arr("members");
            for(Member m : s.members)
                w.obj().kv("name", m.name).kv("slot", m.slotIndex).kv("order", m.order.name()).endObj();
            w.endArr();
            w.endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            List<Object> ss = n.list("squads");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String id = Json.str(m.get("id"), null);
                if(id == null) continue;
                Squad s = squads.get(id);
                if(s == null) s = define(id);
                s.spacing = (float)Json.num(m.get("spacing"), s.spacing);
                try { s.order = Order.valueOf(Json.str(m.get("order"), s.order.name())); } catch(Exception ignored){ }
                List<Object> a = Json.arr(m.get("anchor"));
                if(a != null && a.size() >= 3)
                    s.anchor.set((float)Json.num(a.get(0), 0), (float)Json.num(a.get(1), 0), (float)Json.num(a.get(2), 0));
                List<Object> t = Json.arr(m.get("target"));
                if(t != null && t.size() >= 3)
                    s.target.set((float)Json.num(t.get(0), 0), (float)Json.num(t.get(1), 0), (float)Json.num(t.get(2), 0));
                List<Object> ms = Json.arr(m.get("members"));
                if(ms != null) for(Object mo : ms){
                    Map<String,Object> mm = Json.obj(mo);
                    if(mm == null) continue;
                    String name = Json.str(mm.get("name"), null);
                    if(name == null) continue;
                    Member mem = s.member(name);
                    if(mem == null){ recruit(id, name); mem = s.member(name); }
                    if(mem != null){
                        mem.slotIndex = (int)Json.num(mm.get("slot"), mem.slotIndex);
                        try { mem.order = Order.valueOf(Json.str(mm.get("order"), mem.order.name())); } catch(Exception ignored){ }
                    }
                }
            }
        } catch(Exception ignored){ }
    }
}
