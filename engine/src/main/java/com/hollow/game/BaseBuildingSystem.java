package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v6.0 — base building system (HollowEngine 6.0 'living-world').
 *  Blueprint catalog (footprint cells, material costs, durability), grid placement
 *  with rotation and overlap rejection, material validation through a pluggable
 *  ItemSink (inventory in real play), damage/destroy/repair with integrity tracking.
 *  Deterministic and JSON-persisted.
 */
public final class BaseBuildingSystem {

    public static final class Blueprint {
        public final String id;
        public String name;
        public int w = 1, h = 1;                // footprint in grid cells (rotation swaps w/h)
        public final Map<String, Integer> materials = new LinkedHashMap<>();
        public float durability = 100f;
        public String category = "structure";   // wall / floor / station / furniture
        public Blueprint(String id, String name){ this.id = id; this.name = name; }
        public Blueprint size(int w, int h){ this.w = Math.max(1, w); this.h = Math.max(1, h); return this; }
        public Blueprint needs(String item, int qty){ materials.put(item, qty); return this; }
        public Blueprint dura(float d){ durability = Math.max(1f, d); return this; }
        public Blueprint cat(String c){ category = c; return this; }
    }

    public static final class Structure {
        public final int id;
        public final String blueprintId;
        public int gx, gz;                      // top-left cell
        public int rotation = 0;                // 0 / 90 / 180 / 270
        public float integrity = 1f;            // 0..1
        public Structure(int id, String blueprintId, int gx, int gz, int rotation){
            this.id = id; this.blueprintId = blueprintId; this.gx = gx; this.gz = gz; this.rotation = rotation;
        }
    }

    /** material wallet hook: the game layer maps this to the player inventory */
    public interface ItemSink {
        boolean has(Map<String, Integer> materials);
        void consume(Map<String, Integer> materials);
        void refund(Map<String, Integer> materials);
    }
    public ItemSink items;
    public interface Listener { void onBuildEvent(String type, int structureId, String detail); }
    public Listener listener;

    public final Map<String, Blueprint> blueprints = new LinkedHashMap<>();
    public final Map<Integer, Structure> structures = new LinkedHashMap<>();
    public int gridSize = 64;                   // world cells per axis
    private int nextId = 1;

    public Blueprint define(Blueprint b){ blueprints.put(b.id, b); return b; }
    public Blueprint blueprint(String id){ return blueprints.get(id); }
    public Structure structure(int id){ return structures.get(id); }

    /** footprint cells for a placement (rotation 90/270 swaps w and h) */
    public int footprintW(Blueprint b, int rotation){
        return (rotation == 90 || rotation == 270) ? b.h : b.w;
    }
    public int footprintH(Blueprint b, int rotation){
        return (rotation == 90 || rotation == 270) ? b.w : b.h;
    }

    /** all cells a placement would occupy */
    public List<long[]> cells(Blueprint b, int gx, int gz, int rotation){
        List<long[]> out = new ArrayList<>();
        int w = footprintW(b, rotation), h = footprintH(b, rotation);
        for(int dx = 0; dx < w; dx++)
            for(int dz = 0; dz < h; dz++)
                out.add(new long[]{ gx + dx, gz + dz });
        return out;
    }

    public boolean inBounds(Blueprint b, int gx, int gz, int rotation){
        for(long[] c : cells(b, gx, gz, rotation))
            if(c[0] < 0 || c[1] < 0 || c[0] >= gridSize || c[1] >= gridSize) return false;
        return true;
    }

    public boolean overlaps(Blueprint b, int gx, int gz, int rotation, int ignoreId){
        for(long[] c : cells(b, gx, gz, rotation))
            for(Structure s : structures.values()){
                if(s.id == ignoreId) continue;
                Blueprint ob = blueprints.get(s.blueprintId);
                if(ob == null) continue;
                for(long[] oc : cells(ob, s.gx, s.gz, s.rotation))
                    if(oc[0] == c[0] && oc[1] == c[1]) return true;
            }
        return false;
    }

    /** place a structure: bounds + overlap + material checks. Returns structure id or -1. */
    public int place(String blueprintId, int gx, int gz, int rotation){
        Blueprint b = blueprints.get(blueprintId);
        if(b == null) return -1;
        if(rotation % 90 != 0) return -1;
        if(!inBounds(b, gx, gz, rotation) || overlaps(b, gx, gz, rotation, -1)){
            if(listener != null) listener.onBuildEvent("rejected", -1, blueprintId);
            return -1;
        }
        if(items != null){
            if(!items.has(b.materials)){
                if(listener != null) listener.onBuildEvent("missing_materials", -1, blueprintId);
                return -1;
            }
            items.consume(b.materials);
        }
        Structure s = new Structure(nextId++, blueprintId, gx, gz, rotation);
        structures.put(s.id, s);
        if(listener != null) listener.onBuildEvent("built", s.id, blueprintId);
        return s.id;
    }

    /** remove and refund half the materials */
    public boolean demolish(int id){
        Structure s = structures.get(id);
        Blueprint b = s != null ? blueprints.get(s.blueprintId) : null;
        if(b == null) return false;
        structures.remove(id);
        if(items != null){
            Map<String, Integer> half = new LinkedHashMap<>();
            for(Map.Entry<String, Integer> kv : b.materials.entrySet())
                half.put(kv.getKey(), Math.max(1, kv.getValue() / 2));
            items.refund(half);
        }
        if(listener != null) listener.onBuildEvent("demolished", id, b.id);
        return true;
    }

    /** damage a structure; destroys at integrity 0 */
    public void damage(int id, float amount){
        Structure s = structures.get(id);
        Blueprint b = s != null ? blueprints.get(s.blueprintId) : null;
        if(b == null) return;
        s.integrity = MathX_clamp(s.integrity - amount / b.durability);
        if(s.integrity <= 0f){
            structures.remove(id);
            if(listener != null) listener.onBuildEvent("destroyed", id, b.id);
        } else if(listener != null){
            listener.onBuildEvent("damaged", id, String.valueOf(Math.round(s.integrity * 100f)) + "%");
        }
    }

    /** repair to full integrity; costs 1 of the blueprint's most expensive material per 25% */
    public int repair(int id){
        Structure s = structures.get(id);
        Blueprint b = s != null ? blueprints.get(s.blueprintId) : null;
        if(b == null || s.integrity >= 1f) return -1;
        float missing = 1f - s.integrity;
        int units = (int)Math.ceil(missing * (b.durability / 25f));
        Map<String, Integer> cost = new LinkedHashMap<>();
        String costliest = "";
        int best = 0;
        for(Map.Entry<String, Integer> kv : b.materials.entrySet())
            if(kv.getValue() > best){ best = kv.getValue(); costliest = kv.getKey(); }
        if(costliest.isEmpty()) return -1;
        cost.put(costliest, Math.max(1, units));
        if(items != null){
            if(!items.has(cost)) return -1;
            items.consume(cost);
        }
        s.integrity = 1f;
        if(listener != null) listener.onBuildEvent("repaired", id, costliest + "x" + cost.get(costliest));
        return cost.get(costliest);
    }

    private static float MathX_clamp(float v){ return Math.max(0f, Math.min(1f, v)); }

    public int count(){ return structures.size(); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("nextId", nextId);
        w.arr("structures");
        for(Structure s : structures.values()){
            w.obj().kv("id", s.id).kv("bp", s.blueprintId).kv("gx", s.gx)
              .kv("gz", s.gz).kv("rot", s.rotation).kv("integ", s.integrity).endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            nextId = Math.max(1, (int)n.flt("nextId", nextId));
            List<Object> ss = n.list("structures");
            if(ss == null) return;
            for(Object o : ss){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                int id = (int)Json.num(m.get("id"), -1);
                String bp = Json.str(m.get("bp"), null);
                if(id < 0 || bp == null || !blueprints.containsKey(bp)) continue;
                Structure s = new Structure(id, bp, (int)Json.num(m.get("gx"), 0), (int)Json.num(m.get("gz"), 0),
                    (int)Json.num(m.get("rot"), 0));
                s.integrity = MathX_clamp((float)Json.num(m.get("integ"), 1f));
                structures.put(id, s);
            }
        } catch(Exception ignored){ }
    }
}
