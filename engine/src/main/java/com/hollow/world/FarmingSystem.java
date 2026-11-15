package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v6.0 — farming system (HollowEngine 6.0 'living-world').
 *  Plots hold a crop type with a growth counter in "crop days". Each simulated day
 *  advances growth (more when watered, more in the crop's preferred season), stages
 *  are SPROUT → GROWING → RIPE; wrong temperature bands wither the crop over days.
 *  Harvest yields a deterministic quantity influenced by the season bonus.
 */
public final class FarmingSystem {

    public enum Stage { EMPTY, SPROUT, GROWING, RIPE, WITHERED }

    public static final class CropType {
        public final String id;
        public String name;
        public int growthDays = 5;              // crop-days to full ripeness
        public float minTemp = 5f, maxTemp = 32f;
        public SeasonsSystem.Season preferred;
        public int yieldMin = 1, yieldMax = 3;
        public CropType(String id, String name, int growthDays, SeasonsSystem.Season preferred){
            this.id = id; this.name = name; this.growthDays = Math.max(1, growthDays); this.preferred = preferred;
        }
        public CropType yields(int min, int max){ yieldMin = min; yieldMax = max; return this; }
        public CropType tempBand(float min, float max){ minTemp = min; maxTemp = max; return this; }
    }

    public static final class Plot {
        public final int index;
        public String cropId = "";
        public float growth = 0f;               // crop-days accumulated
        public boolean watered = false;         // set each day, consumed by the day tick
        public int witherStreak = 0;            // consecutive days outside the temp band
        public Plot(int index){ this.index = index; }
        public Stage stage(String cropId, float cropDays){
            if(this.cropId.isEmpty()) return Stage.EMPTY;
            if(this.cropId.equals(cropId) && growth >= cropDays) return Stage.RIPE;
            if(this.cropId.equals("__withered__")) return Stage.WITHERED;
            return growth < cropDays * 0.4f ? Stage.SPROUT : Stage.GROWING;
        }
    }

    public interface Listener { void onFarmEvent(String type, int plot, String crop, int qty); }
    public Listener listener;

    public final Map<String, CropType> crops = new LinkedHashMap<>();
    public final List<Plot> plots = new ArrayList<>();
    public int day = 0;
    public long seed = 0xFA7B1L;
    /** current season + temperature, fed from SeasonsSystem each tick */
    public SeasonsSystem.Season season = SeasonsSystem.Season.SPRING;
    public float temperature = 12f;

    public FarmingSystem(int plotCount){
        for(int i = 0; i < plotCount; i++) plots.add(new Plot(i));
    }

    public CropType define(CropType c){ crops.put(c.id, c); return c; }
    public CropType crop(String id){ return crops.get(id); }

    public boolean plant(int plotIndex, String cropId){
        Plot p = plot(plotIndex);
        CropType c = crops.get(cropId);
        if(p == null || c == null || !p.cropId.isEmpty()) return false;
        p.cropId = cropId;
        p.growth = 0f;
        p.witherStreak = 0;
        if(listener != null) listener.onFarmEvent("planted", plotIndex, cropId, 0);
        return true;
    }

    public void water(int plotIndex){
        Plot p = plot(plotIndex);
        if(p != null) p.watered = true;
    }
    public void waterAll(){ for(Plot p : plots) p.watered = true; }

    /** advance one in-game day: growth, season bonus, withering */
    public void tickDay(){
        day++;
        for(Plot p : plots){
            if(p.cropId.isEmpty()) continue;
            if(p.cropId.equals("__withered__")) continue;
            CropType c = crops.get(p.cropId);
            if(c == null) continue;

            // temperature tolerance: out of band builds a wither streak
            if(temperature < c.minTemp || temperature > c.maxTemp){
                p.witherStreak++;
                if(p.witherStreak >= 3){
                    p.cropId = "__withered__";
                    if(listener != null) listener.onFarmEvent("withered", p.index, c.id, 0);
                    continue;
                }
            } else {
                p.witherStreak = 0;
            }

            float gain = p.watered ? 1f : 0.4f;
            if(c.preferred == season) gain *= 1.5f;                 // in-season growth bonus
            p.growth += gain;
            p.watered = false;                                      // must water again next day
            if(p.growth >= c.growthDays && listener != null)
                listener.onFarmEvent("ripe", p.index, c.id, 0);
        }
    }

    /** harvest a ripe plot → deterministic yield (min..max, +1 in the preferred season) */
    public int harvest(int plotIndex){
        Plot p = plot(plotIndex);
        if(p == null || p.cropId.isEmpty() || p.cropId.equals("__withered__")) return -1;
        CropType c = crops.get(p.cropId);
        if(c == null || p.growth < c.growthDays) return -1;
        MathX.Rng rng = new MathX.Rng(seed ^ (day * 131L + plotIndex));
        int qty = c.yieldMin + rng.nextInt(Math.max(1, c.yieldMax - c.yieldMin + 1));
        if(c.preferred == season) qty += 1;
        p.cropId = "";
        p.growth = 0f;
        if(listener != null) listener.onFarmEvent("harvested", plotIndex, c.id, qty);
        return qty;
    }

    /** clear a withered plot so it can be replanted */
    public boolean clear(int plotIndex){
        Plot p = plot(plotIndex);
        if(p == null || p.cropId.isEmpty()) return false;
        p.cropId = ""; p.growth = 0f; p.witherStreak = 0;
        return true;
    }

    public Plot plot(int index){
        return (index >= 0 && index < plots.size()) ? plots.get(index) : null;
    }
    public int ripeCount(){
        int n = 0;
        for(Plot p : plots){
            if(p.cropId.isEmpty() || p.cropId.equals("__withered__")) continue;
            CropType c = crops.get(p.cropId);
            if(c != null && p.growth >= c.growthDays) n++;
        }
        return n;
    }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("day", day).kv("season", season.name());
        w.arr("plots");
        for(Plot p : plots){
            w.obj().kv("i", p.index).kv("crop", p.cropId).kv("growth", p.growth)
              .kv("watered", p.watered).kv("streak", p.witherStreak).endObj();
        }
        w.endArr();
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            day = (int)n.flt("day", day);
            try { season = SeasonsSystem.Season.valueOf(n.str("season", season.name())); } catch(Exception ignored){ }
            List<Object> ps = n.list("plots");
            if(ps == null) return;
            for(Object o : ps){
                Map<String,Object> m = Json.obj(o);
                if(m == null) continue;
                int i = (int)Json.num(m.get("i"), -1);
                Plot p = plot(i);
                if(p == null) continue;
                p.cropId = Json.str(m.get("crop"), "");
                p.growth = (float)Json.num(m.get("growth"), 0f);
                p.watered = Json.bool(m.get("watered"), false);
                p.witherStreak = (int)Json.num(m.get("streak"), 0);
            }
        } catch(Exception ignored){ }
    }
}
