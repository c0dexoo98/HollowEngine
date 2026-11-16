package com.hollow.game;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** v13.0 — mint system (HollowEngine 13.0 'ritual & remedy').
 *  Bullion bars are struck into coins at a fixed daily rate; a clipping
 *  scandal (deterministic roll) shaves a share of the coin stock and
 *  dents the mint's reputation once per scandal.
 */
public final class MintSystem {

    public static final int COINS_PER_BAR = 10;
    public static final int BARS_PER_DAY = 2;

    public interface Listener { void onMintEvent(String type, int value, String detail); }
    public Listener listener;

    public int bars = 0;
    public int coins = 0;
    public int struckTotal = 0;
    public int scandals = 0;
    public long seed = 0x41A7L;
    public int day = 0;

    /** deliver bullion bars to the mint */
    public boolean deliverBullion(int units){
        if(units <= 0) return false;
        bars += units;
        return true;
    }

    /** daily tick: striking, scandals */
    public void tickDay(){
        day++;
        int struck = Math.min(bars, BARS_PER_DAY);
        if(struck > 0){
            bars -= struck;
            coins += struck * COINS_PER_BAR;
            struckTotal += struck * COINS_PER_BAR;
            if(listener != null) listener.onMintEvent("struck", struck * COINS_PER_BAR, "bars=" + struck);
        }
        MathX.Rng rng = new MathX.Rng(seed ^ (day * 211L) ^ 0x417L);
        if(rng.range(0f, 1f) < 0.02f && coins > 0){
            int shaved = Math.max(1, Math.round(coins * 0.05f));
            coins -= shaved;
            scandals++;
            if(listener != null) listener.onMintEvent("clipped", shaved, "scandal=" + scandals);
        }
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("bars", bars).kv("coins", coins)
          .kv("struck", struckTotal).kv("scandals", scandals);
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            bars = Math.max(0, (int)n.flt("bars", bars));
            coins = Math.max(0, (int)n.flt("coins", coins));
            struckTotal = Math.max(0, (int)n.flt("struck", struckTotal));
            scandals = Math.max(0, (int)n.flt("scandals", scandals));
        } catch(Exception ignored){ }
    }
}
