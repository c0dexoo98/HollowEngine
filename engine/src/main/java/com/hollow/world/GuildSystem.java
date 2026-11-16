package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v9.0 — guild system (HollowEngine 9.0 'realm & saga').
 *  Merchant/craft/war guilds: members pay daily dues into shared coffers,
 *  upkeep drains them, a guild that cannot pay upkeep disbands and releases
 *  its members. Buffs are exposed for the game layer to wire.
 */
public final class GuildSystem {

    public enum Kind { MERCHANT, CRAFT, WAR }

    public static final class Guild {
        public final int id;
        public final String name;
        public final Kind kind;
        public final List<String> members = new ArrayList<>();
        public int coffers = 0;
        public int duesPerDay = 2;
        public int upkeepPerDay = 5;
        public boolean disbanded = false;
        public Guild(int id, String name, Kind kind, int coffers){
            this.id = id; this.name = name; this.kind = kind; this.coffers = coffers;
        }
    }

    public interface Listener { void onGuildEvent(String type, int guildId, String detail); }
    public Listener listener;

    public final Map<Integer, Guild> guilds = new LinkedHashMap<>();
    public int day = 0;
    private int nextId = 1;

    public Guild found(String name, GuildSystem.Kind kind, int startingCoffers){
        if(name == null || name.isEmpty() || kind == null) return null;
        Guild g = new Guild(nextId++, name, kind, Math.max(0, startingCoffers));
        guilds.put(g.id, g);
        if(listener != null) listener.onGuildEvent("founded", g.id, kind.name());
        return g;
    }

    public boolean join(int guildId, String npc){
        Guild g = guilds.get(guildId);
        if(g == null || g.disbanded || npc == null || g.members.contains(npc)) return false;
        g.members.add(npc);
        if(listener != null) listener.onGuildEvent("joined", guildId, npc);
        return true;
    }

    public boolean leave(int guildId, String npc){
        Guild g = guilds.get(guildId);
        if(g == null || !g.members.remove(npc)) return false;
        if(listener != null) listener.onGuildEvent("left", guildId, npc);
        return true;
    }

    /** daily tick: dues in, upkeep out, disband on empty coffers */
    public void tickDay(){
        day++;
        for(Guild g : guilds.values()){
            if(g.disbanded) continue;
            g.coffers += g.members.size() * g.duesPerDay;
            g.coffers -= g.upkeepPerDay;
            if(g.coffers < 0){
                g.disbanded = true;
                g.coffers = 0;
                g.members.clear();
                if(listener != null) listener.onGuildEvent("disbanded", g.id, "unpaid upkeep");
            }
        }
    }

    /** guild perk for the game layer: MERCHANT trade margin, CRAFT quality, WAR damage */
    public float buffOf(Kind kind, int memberCountThreshold){
        float sum = 0f;
        for(Guild g : guilds.values())
            if(!g.disbanded && g.kind == kind && g.members.size() >= memberCountThreshold)
                sum += 0.05f * Math.min(4, g.members.size());
        return sum;
    }

    public int activeCount(){
        int n = 0;
        for(Guild g : guilds.values()) if(!g.disbanded) n++;
        return n;
    }

    public Guild guild(int id){ return guilds.get(id); }

    /** saved id if present; otherwise allocate from nextId (never inflates on load) */
    private int nextIdFor(Object raw){
        int saved = (int)Json.num(raw, 0);
        return saved > 0 ? saved : nextId++;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("nextId", nextId);
        w.arr("guilds");
        for(Guild g : guilds.values()){
            w.obj().kv("id", g.id).kv("name", g.name).kv("kind", g.kind.name())
              .kv("coffers", g.coffers).kv("dues", g.duesPerDay).kv("upkeep", g.upkeepPerDay)
              .kv("dis", g.disbanded);
            w.arr("mem");
            for(String m : g.members) w.obj().kv("n", m).endObj();
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
            day = (int)n.flt("day", day);
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> gs = n.list("guilds");
            if(gs == null) return;
            for(Object o : gs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                Guild g;
                try {
                    g = new Guild(nextIdFor(m.get("id")), Json.str(m.get("name"), "?"),
                        GuildSystem.Kind.valueOf(Json.str(m.get("kind"), "MERCHANT")),
                        (int)Json.num(m.get("coffers"), 0));
                } catch(Exception e){ continue; }
                g.duesPerDay = (int)Json.num(m.get("dues"), g.duesPerDay);
                g.upkeepPerDay = (int)Json.num(m.get("upkeep"), g.upkeepPerDay);
                g.disbanded = Json.bool(m.get("dis"), false);
                List<Object> ms = Json.arr(m.get("mem"));
                if(ms != null) for(Object mo : ms){
                    Map<String,Object> mm = Json.obj(mo);
                    if(mm != null) g.members.add(Json.str(mm.get("n"), "?"));
                }
                guilds.put(g.id, g);
            }
        } catch(Exception ignored){ }
    }
}
