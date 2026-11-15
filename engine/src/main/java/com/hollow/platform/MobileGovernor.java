package com.hollow.platform;

import com.hollow.core.MemoryStats;
import com.hollow.render.RendererConfig;

/**
 * v3.0.0 PLATFORM: mobile optimization governor — watches frame time and
 * thermal pressure signals, steps renderer quality down (or up) to hold the
 * target frame rate, and budgets battery by capping FPS on low battery.
 *
 * Signals (game layer feeds what it has each second):
 *   - avgFrameMs      (render thread timing)
 *   - batteryPercent  (0..100, negative = unknown/AC)
 *   - thermal         (0 = nominal .. 1 = throttling hard)
 *
 * Policy ladder (from best to worst):
 *   battery saver -> fpsCap 30
 *   frame over budget 2s    -> drop one quality step
 *   thermal > 0.8          -> drop one quality step + fpsCap 30
 *   headroom 5s            -> climb one step (hysteresis)
 */
public final class MobileGovernor {

    public enum Level { ULTRA, HIGH, MEDIUM, LOW, MINIMAL }

    public static final float FRAME_BUDGET_MARGIN = 1.25f;   // ms over budget that counts as miss
    public static final int MISS_STREAK_TO_DROP = 120;       // ~2s at 60
    public static final int HEADROOM_TO_CLIMB = 300;         // ~5s at 60
    public static final float THERMAL_DROP_THRESHOLD = 0.8f;

    public Level level = Level.HIGH;
    public int targetFps = 60;
    public int fpsCap = 60;                    // effective cap (battery saver -> 30)
    public float avgFrameMs = 16.7f;
    public float batteryPercent = -1f;         // -1 unknown
    public float thermal = 0f;                 // 0..1
    public boolean batterySaver = false;

    public int missStreak = 0, goodStreak = 0;
    public long drops = 0, climbs = 0;

    /** translate level -> RendererConfig; game layer applies this */
    public RendererConfig configFor(Level lvl){
        RendererConfig c = new RendererConfig();
        switch(lvl){
            case ULTRA:   c.quality = RendererConfig.Quality.ULTRA; break;
            case HIGH:    c.quality = RendererConfig.Quality.HIGH; break;
            case MEDIUM:  c.quality = RendererConfig.Quality.MEDIUM; break;
            case LOW:     c.quality = RendererConfig.Quality.LOW; break;
            case MINIMAL: c.quality = RendererConfig.Quality.LOW;
                           c.resolutionScale = 0.5f; c.bloom = false; c.fxaa = false; break;
        }
        return c;
    }

    /** feed measurements once per second; returns true when config changed */
    public boolean report(float frameMs, float battery, float thermalSignal){
        this.avgFrameMs = frameMs;
        this.batteryPercent = battery;
        this.thermal = thermalSignal;
        this.batterySaver = battery >= 0f && battery < 15f;

        // battery saver caps fps first
        fpsCap = (batterySaver || thermal > THERMAL_DROP_THRESHOLD) ? 30 : targetFps;

        float budgetMs = 1000f / Math.max(1, targetFps);
        boolean miss = frameMs > budgetMs * FRAME_BUDGET_MARGIN;

        boolean changed = false;
        if(miss){
            missStreak++; goodStreak = 0;
            if(missStreak >= MISS_STREAK_TO_DROP) changed = stepDown();
        } else if(frameMs < budgetMs * 0.7f){
            goodStreak++; missStreak = 0;
            if(goodStreak >= HEADROOM_TO_CLIMB && thermal < 0.5f && !batterySaver) changed = stepUp();
        } else {
            missStreak = 0; goodStreak = 0;
        }
        if(thermal > THERMAL_DROP_THRESHOLD && missStreak > 30) changed = stepDown() || changed;
        return changed;
    }

    private boolean stepDown(){
        if(level == Level.MINIMAL) return false;
        level = Level.values()[Math.min(Level.values().length - 1, ordinal() + 1)];
        drops++; missStreak = 0;
        return true;
    }

    private boolean stepUp(){
        if(level == Level.ULTRA) return false;
        level = Level.values()[Math.max(0, ordinal() - 1)];
        climbs++; goodStreak = 0;
        return true;
    }

    private int ordinal(){ return level.ordinal(); }

    /** advisory for the game layer: streaming/update LOD hints */
    public int aiUpdateHz(){
        switch(level){
            case ULTRA: case HIGH: return 20;
            case MEDIUM: return 10;
            default: return 5;
        }
    }

    public String status(){
        return "governor[level=" + level + " fpsCap=" + fpsCap + " frame=" + String.format("%.1f", avgFrameMs)
             + "ms battery=" + (batteryPercent < 0 ? "?" : String.format("%.0f%%", batteryPercent))
             + " thermal=" + String.format("%.2f", thermal) + " drops=" + drops + " climbs=" + climbs + "]";
    }
}
