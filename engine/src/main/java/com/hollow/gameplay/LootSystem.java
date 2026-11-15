package com.hollow.gameplay;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** v5.0 — loot system (weighted tables + luck + pity counter, HollowEngine 5.0).
 *  Loot tables hold weighted entries with per-entry quantity ranges; rolls are
 *  deterministic via engine Rng. Luck (0..1) skews the distribution toward rare
 *  entries; a pity counter guarantees a top-rarity drop after N unlucky rolls.
 */
public final class LootSystem {

    public enum Rarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

    public static final class Entry {
        public final String itemId;
        public int minQty = 1, maxQty = 1;
        public float weight = 1f;
        public Rarity rarity = Rarity.COMMON;
        public Entry(String itemId, float weight){ this.itemId = itemId; this.weight = Math.max(0.001f, weight); }
        public Entry qty(int min, int max){ minQty = min; maxQty = Math.max(min, max); return this; }
        public Entry rare(Rarity r){ rarity = r; return this; }
    }

    public static final class Table {
        public final String id;
        public final List<Entry> entries = new ArrayList<>();
        public String pityItemId = "";         // guaranteed after `pityAfter` dry rolls
        public int pityAfter = 0;              // 0 = disabled
        public Table(String id){ this.id = id; }
        public Table add(Entry e){ entries.add(e); return this; }
    }

    public static final class Roll {
        public final String itemId;
        public final int qty;
        public final Rarity rarity;
        public final boolean wasPity;
        public Roll(String itemId, int qty, Rarity rarity, boolean wasPity){
            this.itemId = itemId; this.qty = qty; this.rarity = rarity; this.wasPity = wasPity;
        }
        @Override public String toString(){ return itemId + "x" + qty + (wasPity ? " (pity)" : ""); }
    }

    public interface Listener { void onLootEvent(String type, String tableId, String itemId); }
    public Listener listener;

    public float luck = 0f;                // 0..1 — shifts weight toward higher rarities
    private final java.util.Map<String, Integer> pityCounters = new java.util.LinkedHashMap<>();

    public final java.util.Map<String, Table> tables = new java.util.LinkedHashMap<>();
    public Table define(Table t){ tables.put(t.id, t); return t; }
    public Table table(String id){ return tables.get(id); }

    /** roll a table; deterministic given the seed. Empty table → null. */
    public Roll roll(String tableId, long seed){
        Table t = tables.get(tableId);
        if(t == null || t.entries.isEmpty()) return null;
        MathX.Rng rng = new MathX.Rng(seed);

        // pity: after N rolls without an UNCOMMON+ drop, force the guaranteed item
        int dry = pityCounters.getOrDefault(tableId, 0);
        if(t.pityAfter > 0 && dry >= t.pityAfter && !t.pityItemId.isEmpty()){
            pityCounters.put(tableId, 0);
            Entry pity = findEntry(t, t.pityItemId);
            Rarity r = pity != null ? pity.rarity : Rarity.LEGENDARY;
            int qty = pity != null ? rng.nextInt(Math.max(1, pity.maxQty - pity.minQty + 1)) + pity.minQty : 1;
            if(listener != null) listener.onLootEvent("pity", tableId, t.pityItemId);
            return new Roll(t.pityItemId, qty, r, true);
        }

        // luck-weighted selection: each entry's weight scales with its rarity index
        float[] w = new float[t.entries.size()];
        float total = 0f;
        for(int i = 0; i < t.entries.size(); i++){
            Entry e = t.entries.get(i);
            float rarityBoost = 1f + luck * rarityIndex(e.rarity) * 1.6f;
            w[i] = e.weight * rarityBoost;
            total += w[i];
        }
        float pick = rng.range(0f, total);
        int chosen = t.entries.size() - 1;
        for(int i = 0; i < t.entries.size(); i++){
            pick -= w[i];
            if(pick <= 0f){ chosen = i; break; }
        }
        Entry e = t.entries.get(chosen);
        int qty = e.minQty + rng.nextInt(Math.max(1, e.maxQty - e.minQty + 1));
        // pity bookkeeping: rare drops reset the counter
        if(rarityIndex(e.rarity) >= 1) pityCounters.put(tableId, 0);
        else pityCounters.put(tableId, dry + 1);
        if(listener != null) listener.onLootEvent("rolled", tableId, e.itemId);
        return new Roll(e.itemId, qty, e.rarity, false);
    }

    private static Entry findEntry(Table t, String itemId){
        for(Entry e : t.entries) if(e.itemId.equals(itemId)) return e;
        return null;
    }
    private static int rarityIndex(Rarity r){
        switch(r){ case UNCOMMON: return 1; case RARE: return 2; case EPIC: return 3;
                   case LEGENDARY: return 4; default: return 0; }
    }
    public int pityCounter(String tableId){ return pityCounters.getOrDefault(tableId, 0); }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("luck", luck);
        w.arr("tables");
        for(Table t : tables.values()){
            w.obj().kv("id", t.id).kv("pityItem", t.pityItemId).kv("pityAfter", t.pityAfter);
            w.arr("entries");
            for(Entry e : t.entries)
                w.obj().kv("item", e.itemId).kv("weight", e.weight)
                  .kv("min", e.minQty).kv("max", e.maxQty).kv("rarity", e.rarity.name()).endObj();
            w.endArr();
            w.endObj();
        }
        w.endArr();
        w.obj("pity");
        for(java.util.Map.Entry<String, Integer> kv : pityCounters.entrySet()) w.kv(kv.getKey(), kv.getValue());
        w.endObj();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            luck = MathX.clamp(n.flt("luck", luck), 0f, 1f);
            List<Object> ts = n.list("tables");
            if(ts != null) for(Object o : ts){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String id = Json.str(m.get("id"), null);
                if(id == null) continue;
                Table t = tables.get(id);
                if(t == null){ t = new Table(id); define(t); }
                t.pityItemId = Json.str(m.get("pityItem"), t.pityItemId);
                t.pityAfter = (int)Json.num(m.get("pityAfter"), t.pityAfter);
                List<Object> es = Json.arr(m.get("entries"));
                if(es != null){
                    t.entries.clear();
                    for(Object eo : es){
                        Map<String,Object> em = Json.obj(eo);
                        if(em == null) continue;
                        Entry e = new Entry(Json.str(em.get("item"), "?"), (float)Json.num(em.get("weight"), 1f));
                        e.minQty = (int)Json.num(em.get("min"), 1);
                        e.maxQty = (int)Json.num(em.get("max"), e.minQty);
                        try { e.rarity = Rarity.valueOf(Json.str(em.get("rarity"), "COMMON")); } catch(Exception ignored){ }
                        t.entries.add(e);
                    }
                }
            }
            JsonV pity = n.node("pity");
            if(pity.map != null){
                for(String k : pity.map.keySet()) pityCounters.put(k, (int)Json.num(pity.map.get(k), 0));
            }
        } catch(Exception ignored){ }
    }
}
