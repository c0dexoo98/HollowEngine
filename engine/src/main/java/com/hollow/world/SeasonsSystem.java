package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;

/** v5.0 — seasons system (HollowEngine 5.0).
 *  Four seasons cycling over a configurable year length; each season carries a
 *  preferred weather preset, a temperature band and a day-length modulation.
 *  Feeds Weather (preset suggestions) and DayNightCycle (day length scale), fires
 *  season-change events, and stays fully deterministic.
 */
public final class SeasonsSystem {

    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    public static final class SeasonProfile {
        public final Season season;
        public final Weather.Preset preferredWeather;
        public final float minTemp, maxTemp;       // °C band
        public final float dayLengthScale;         // multiplies DayNightCycle.dayLengthMinutes
        public SeasonProfile(Season s, Weather.Preset w, float minT, float maxT, float dayScale){
            season = s; preferredWeather = w; minTemp = minT; maxTemp = maxT; dayLengthScale = dayScale;
        }
        /** temperature at a 0..1 position inside the season (cooler at edges, peak mid-season) */
        public float tempAt(float phase01){
            float curve = (float)Math.sin(phase01 * Math.PI);           // 0..1..0
            return MathX.lerp(minTemp, maxTemp, curve);
        }
    }

    public static SeasonProfile profile(Season s){
        switch(s){
            case SPRING: return new SeasonProfile(s, Weather.Preset.RAIN,   6f,  18f, 1.0f);
            case SUMMER: return new SeasonProfile(s, Weather.Preset.CLEAR, 20f,  38f, 0.85f);
            case AUTUMN: return new SeasonProfile(s, Weather.Preset.CLOUDY,4f,  14f, 1.15f);
            case WINTER: return new SeasonProfile(s, Weather.Preset.SNOW, -14f,  2f, 1.3f);
            default:     return new SeasonProfile(s, Weather.Preset.CLEAR, 10f,  20f, 1.0f);
        }
    }

    public interface Listener { void onSeasonEvent(String type, Season season, float temp); }
    public Listener listener;

    /** real-world minutes for one FULL year (4 seasons) */
    public float yearLengthMinutes = 40f;
    /** current progress through the year, in days (0..4*daysPerSeason) */
    public int daysPerSeason = 7;
    public int day = 0;                        // absolute day counter
    public Season season = Season.SPRING;
    public long seed = 0x5EA50D1L;
    /** current temperature (°C), updated per tick */
    public float temperature = 10f;

    private float dayAccum = 0f;

    public SeasonsSystem(){ }
    public SeasonsSystem(int daysPerSeason, float yearLengthMinutes){
        this.daysPerSeason = Math.max(1, daysPerSeason);
        this.yearLengthMinutes = Math.max(1f, yearLengthMinutes);
    }

    public SeasonProfile profile(){ return profile(season); }

    /** advance by dt seconds; rolls the day counter, swaps seasons at boundaries */
    public void update(float dt){
        if(dt <= 0f) return;
        float secondsPerDay = yearLengthMinutes * 60f / (daysPerSeason * 4f);
        dayAccum += dt / secondsPerDay;
        while(dayAccum >= 1f){
            dayAccum -= 1f;
            day++;
            Season next = seasonOf(day);
            if(next != season){
                season = next;
                if(listener != null) listener.onSeasonEvent("changed", season, temperature);
            } else if(listener != null){
                listener.onSeasonEvent("day", season, temperature);
            }
        }
        temperature = currentTemp();
    }

    /** season for an absolute day index (deterministic, wraps forever) */
    public Season seasonOf(int dayIndex){
        int idx = (dayIndex / daysPerSeason) % 4;
        switch(idx){ case 1: return Season.SUMMER; case 2: return Season.AUTUMN;
                     case 3: return Season.WINTER; default: return Season.SPRING; }
    }

    /** temperature now: season band curve + daily noise (deterministic per day) */
    public float currentTemp(){
        SeasonProfile p = profile();
        float phase = dayAccum;                          // 0..1 within the current day
        float base = p.tempAt(phase);
        MathX.Rng rng = new MathX.Rng(seed ^ day * 7919L);
        return base + rng.range(-2.5f, 2.5f);
    }

    /** does the season suggest a weather switch for the current Weather state? */
    public boolean shouldSwitchWeather(Weather weather){
        return weather != null && weather.preset != profile().preferredWeather;
    }

    /** effective day-length multiplier to feed DayNightCycle */
    public float dayLengthScale(){ return profile().dayLengthScale; }

    // ---------------- persistence ----------------
    public String save(){
        JsonW w = new JsonW();
        w.obj().kv("year", yearLengthMinutes).kv("dps", daysPerSeason).kv("day", day)
          .kv("acc", dayAccum).kv("season", season.name());
        w.endObj();
        return w.toString();
    }

    public void load(String json){
        try {
            JsonV n = new JsonV(Json.parseObj(json));
            yearLengthMinutes = n.flt("year", yearLengthMinutes);
            daysPerSeason = Math.max(1, (int)n.flt("dps", daysPerSeason));
            day = (int)n.flt("day", day);
            dayAccum = n.flt("acc", dayAccum);
            try { season = Season.valueOf(n.str("season", season.name())); } catch(Exception ignored){ }
            temperature = currentTemp();
        } catch(Exception ignored){ }
    }
}
