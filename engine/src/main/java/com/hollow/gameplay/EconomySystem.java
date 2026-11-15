package com.hollow.gameplay;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v5.0 — vendor / economy system (HollowEngine 5.0).
 *  Vendors hold stock with base prices and finite quantities; the player buys at a
 *  price premium (buy margin) and sells at a discount (sell margin), with reputation
 *  and a per-vendor friendship discount modifier. Transactions are validated and
 *  logged; stock and wallet persist to JSON.
 */
public final class EconomySystem {

    public static final class StockItem {
        public final String itemId;
        public int basePrice;
        public int qty;                    // -1 = infinite stock
        public StockItem(String itemId, int basePrice, int qty){
            this.itemId = itemId; this.basePrice = Math.max(0, basePrice);
            this.qty = qty;
        }
    }

    public static final class Vendor {
        public final String id;
        public String name;
        public float buyMargin = 1.25f;    // player pays 125%
        public float sellMargin = 0.4f;    // player receives 40%
        public float discount = 0f;        // 0..1 — negotiated/faction discount off buy prices
        public final List<StockItem> stock = new ArrayList<>();
        public int gold = 500;             // vendor wallet for buying player goods
        public Vendor(String id, String name){ this.id = id; this.name = name; }
        public StockItem item(String itemId){
            for(StockItem s : stock) if(s.itemId.equals(itemId)) return s;
            return null;
        }
    }

    public static final class Transaction {
        public final int tick;
        public final String vendorId;
        public final String itemId;
        public final int qty;
        public final int gold;             // signed: + player paid, - player received
        public final boolean purchase;
        public Transaction(int tick, String vendorId, String itemId, int qty, int gold, boolean purchase){
            this.tick = tick; this.vendorId = vendorId; this.itemId = itemId;
            this.qty = qty; this.gold = gold; this.purchase = purchase;
        }
    }

    public interface Listener { void onEconomyEvent(String type, String vendorId, String detail); }
    public Listener listener;

    public final Map<String, Vendor> vendors = new LinkedHashMap<>();
    public int playerGold = 100;
    public int tick = 0;
    public final List<Transaction> log = new ArrayList<>();

    public Vendor define(Vendor v){ vendors.put(v.id, v); return v; }
    public Vendor vendor(String id){ return vendors.get(id); }

    /** price the player pays per unit */
    public int buyPrice(Vendor v, String itemId){
        StockItem s = v.item(itemId);
        if(s == null) return -1;
        float price = s.basePrice * v.buyMargin * (1f - MathX_c(v.discount));
        return Math.max(1, Math.round(price));
    }
    private static float MathX_c(float d){ return Math.max(0f, Math.min(0.9f, d)); }   // clamp 0..0.9

    /** price the player receives per unit */
    public int sellPrice(Vendor v, String itemId){
        StockItem s = v.item(itemId);
        int base = s != null ? s.basePrice : 10;   // unknown goods default to 10g baseline
        return Math.max(1, Math.round(base * v.sellMargin));
    }

    /** buy qty units; validates stock + wallet. Returns gold spent, or -1 on failure. */
    public int buy(String vendorId, String itemId, int qty){
        Vendor v = vendors.get(vendorId);
        if(v == null || qty <= 0) return -1;
        StockItem s = v.item(itemId);
        if(s == null || (s.qty >= 0 && s.qty < qty)) return -1;
        int unit = buyPrice(v, itemId);
        int cost = unit * qty;
        if(playerGold < cost){
            if(listener != null) listener.onEconomyEvent("insufficient_funds", vendorId, itemId + "x" + qty);
            return -1;
        }
        playerGold -= cost;
        v.gold += cost;
        if(s.qty >= 0) s.qty -= qty;
        log.add(new Transaction(tick, vendorId, itemId, qty, cost, true));
        if(listener != null) listener.onEconomyEvent("bought", vendorId, itemId + "x" + qty + " gold=" + cost);
        return cost;
    }

    /** sell qty units to a vendor (assumes the caller removed them from the player inventory
     *  on success). Returns gold received, or -1 if the vendor cannot afford it. */
    public int sell(String vendorId, String itemId, int qty){
        Vendor v = vendors.get(vendorId);
        if(v == null || qty <= 0) return -1;
        int unit = sellPrice(v, itemId);
        int payout = unit * qty;
        if(v.gold < payout){
            if(listener != null) listener.onEconomyEvent("vendor_broke", vendorId, itemId + "x" + qty);
            return -1;
        }
        playerGold += payout;
        v.gold -= payout;
        // vendor takes the goods: restock or bump an existing entry
        StockItem s = v.item(itemId);
        if(s != null){ if(s.qty >= 0) s.qty += qty; }
        else v.stock.add(new StockItem(itemId, Math.max(1, Math.round(unit / Math.max(0.1f, v.sellMargin))), qty));
        log.add(new Transaction(tick, vendorId, itemId, qty, -payout, false));
        if(listener != null) listener.onEconomyEvent("sold", vendorId, itemId + "x" + qty + " gold=" + payout);
        return payout;
    }

    public void advanceTick(){ tick++; }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("playerGold", playerGold).kv("tick", tick);
        w.arr("vendors");
        for(Vendor v : vendors.values()){
            w.obj().kv("id", v.id).kv("name", v.name)
              .kv("buyMargin", v.buyMargin).kv("sellMargin", v.sellMargin)
              .kv("discount", v.discount).kv("gold", v.gold);
            w.arr("stock");
            for(StockItem s : v.stock)
                w.obj().kv("item", s.itemId).kv("price", s.basePrice).kv("qty", s.qty).endObj();
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
            playerGold = (int)n.flt("playerGold", playerGold);
            tick = (int)n.flt("tick", tick);
            List<Object> vs = n.list("vendors");
            if(vs == null) return;
            for(Object o : vs){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                String id = Json.str(m.get("id"), null);
                if(id == null) continue;
                Vendor v = vendors.get(id);
                if(v == null) v = define(new Vendor(id, Json.str(m.get("name"), id)));
                v.buyMargin = n.flt("buyMargin", v.buyMargin);
                v.sellMargin = n.flt("sellMargin", v.sellMargin);
                v.discount = MathX_c((float)Json.num(m.get("discount"), v.discount));
                v.gold = (int)Json.num(m.get("gold"), v.gold);
                List<Object> ss = Json.arr(m.get("stock"));
                if(ss != null){
                    v.stock.clear();
                    for(Object so : ss){
                        Map<String,Object> sm = Json.obj(so);
                        if(sm == null) continue;
                        v.stock.add(new StockItem(Json.str(sm.get("item"), "?"),
                            (int)Json.num(sm.get("price"), 10), (int)Json.num(sm.get("qty"), 1)));
                    }
                }
            }
        } catch(Exception ignored){ }
    }
}
