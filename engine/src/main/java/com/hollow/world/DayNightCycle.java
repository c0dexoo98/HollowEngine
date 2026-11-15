package com.hollow.world;

import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;
import com.hollow.scene.LightComponent;
import com.hollow.scene.World;

import java.util.Map;

/** WLD-010 / ENV-006 — global day/night cycle driving sky, sun/moon light and fog.
 *  Time-of-day 0..24 h. Deterministic: same time -> same sky state.
 *  Integrated via update(): mutates world.environment (renderer reads it) and
 *  the brightest directional LightComponent in the world. */
public final class DayNightCycle extends Component {
    /** CVar-controlled defaults are set at attach; per-instance fields below. */
    public float timeOfDay = 8f;          // hours 0..24
    public float dayLengthMinutes = 10f;  // real minutes for a full 24 h cycle
    public float latitudeTilt = 0.35f;    // sun path flattening (0=zenith, 1=low arc)
    public boolean advance = true;        // false = frozen time
    /** current sun elevation/azimuth (degrees) — renderer/debug may read */
    public float sunElevation = 0f, sunAzimuth = 0f;
    /** daylight factor 0..1 (1 = noon, 0 = night) */
    public float daylight = 1f;
    /** optional per-phase fog density multiplier (ENV-007 hooks here) */
    public float fogMultiplier = 1f;

    // cached day phases
    private static final float SUNRISE = 6f, SUNSET = 18f;

    public DayNightCycle(){ }

    @Override public void onUpdate(float dt){
        tick(dt);
        applyToEnvironment(ownerWorld());
    }

    /** advance clock by dt seconds (called by WorldClockSystem; safe to call directly) */
    public void tick(float dt){
        if(!advance) return;
        float hours = dt / 60f / Math.max(0.01f, dayLengthMinutes) * 24f;
        timeOfDay = (timeOfDay + hours) % 24f;
        if(timeOfDay < 0) timeOfDay += 24f;
    }

    /** compute sun direction for time t (0..24). Noon at azimuth south, elevation depends on tilt.
     *  At night this returns the MOON path (positive elevation) — callers rely on that. */
    public Vec3 sunDirection(){
        boolean night = timeOfDay < SUNRISE || timeOfDay > SUNSET;
        float pathT;                                   // 0..1 across the current body's arc
        if(night){
            // R7: night spans 18h -> 30h (6 next morning) = 12 h. The old per-branch formulas
            // (denominators 18 and 24) peaked at 6 AM, never hit midnight zenith, and jumped
            // discontinuously when the clock wrapped 24 -> 0.
            pathT = ((timeOfDay < SUNRISE ? timeOfDay + 24f : timeOfDay) - SUNSET) / (24f + SUNRISE - SUNSET);
        } else {
            pathT = (timeOfDay - SUNRISE) / (SUNSET - SUNRISE);            // sunrise(0) -> noon(.5) -> sunset(1)
        }
        float ang = pathT * (float)Math.PI;                                // rise(0) -> zenith(pi/2) -> set(pi)
        float elev = (float)Math.sin(ang) * (1f - latitudeTilt * 0.6f);    // normalized elevation
        float azim = pathT * 2f - 1f;                                      // -1..1 east->west
        // direction FROM scene TO light source
        return new Vec3(azim * 0.8f, Math.max(0.05f, elev), -0.45f).norm();
    }

    /** daylight factor with smooth twilight band (civil twilight ~30 min). */
    public float daylightFactor(){
        float t = timeOfDay;
        float up = smoothstep(SUNRISE - 0.5f, SUNRISE + 0.5f, t);      // dawn ramp
        float down = 1f - smoothstep(SUNSET - 0.5f, SUNSET + 0.5f, t); // dusk ramp
        return Math.max(0f, Math.min(up, down));
    }

    /** moonlight factor (inverse of daylight, softer). */
    public float moonFactor(){
        return (1f - daylightFactor()) * 0.25f;
    }

    @Override public void onAttach(){
        applyToEnvironment(ownerWorld());
    }

    /** resolve World via GameApp (components have no back-pointer in this engine) */
    public static World ownerWorld(){
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        return app != null ? app.world : null;
    }

    /** push current state into world.environment JSON consumed by the renderer. */
    public void applyToEnvironment(World w){
        if(w == null) return;
        daylight = daylightFactor();
        Vec3 dir = sunDirection();
        sunElevation = (float)Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, dir.y))));
        sunAzimuth = (float)Math.toDegrees(Math.atan2(dir.x, -dir.z));
        Map<String,Object> env = w.environment;
        if(env == null){ env = new java.util.LinkedHashMap<>(); w.environment = env; }
        // ---- ambient from sky ----
        float dl = daylight;
        float[] dayAmb = {0.55f, 0.62f, 0.75f}, nightAmb = {0.06f, 0.08f, 0.14f};
        float[] amb = new float[3];
        for(int i=0;i<3;i++) amb[i] = nightAmb[i] + (dayAmb[i] - nightAmb[i]) * dl;
        env.put("ambient", amb);
        // ---- sky params ----
        Map<String,Object> sky = asMap(env.get("sky"));
        if(sky == null){ sky = new java.util.LinkedHashMap<>(); env.put("sky", sky); }
        // top/horizon color keyed by phase
        float[] dayTop = {0.30f, 0.55f, 0.95f}, nightTop = {0.010f, 0.014f, 0.035f};
        float[] dayHor = {0.75f, 0.85f, 0.98f}, nightHor = {0.05f, 0.07f, 0.12f};
        float[] top = new float[3], hor = new float[3];
        for(int i=0;i<3;i++){
            top[i] = nightTop[i] + (dayTop[i]-nightTop[i]) * dl;
            hor[i] = nightHor[i] + (dayHor[i]-nightHor[i]) * dl;
        }
        // golden hour tint near sunrise/sunset
        float dusk = twilightFactor();
        if(dusk > 0.01f){
            float[] warm = {0.95f, 0.55f, 0.25f};
            for(int i=0;i<3;i++) hor[i] = hor[i] + (warm[i]-hor[i]) * dusk * 0.7f;
        }
        sky.put("topColor", top);
        sky.put("horizonColor", hor);
        sky.put("starDensity", Math.max(0f, 1f - dl * 2.5f));
        Map<String,Object> moon = asMap(sky.get("moonPos"));
        if(moon == null){ moon = new java.util.LinkedHashMap<>(); sky.put("moonPos", moon); }
        boolean isNight = dl < 0.25f;
        // R7: at night sunDirection()/sunElevation already describe the MOON — the old code
        // negated the elevation (moon locked below the horizon all night) and flipped the
        // azimuth 180° so it never matched the visible path.
        moon.put("az", isNight ? sunAzimuth : (sunAzimuth + 180f) % 360f - 180f);
        moon.put("el", (isNight ? 1f : -1f) * sunElevation * 0.8f);
        // ---- fog ----
        Map<String,Object> fog = asMap(env.get("fog"));
        if(fog == null){ fog = new java.util.LinkedHashMap<>(); env.put("fog", fog); }
        float baseFog = 0.015f;
        fog.put("density", baseFog * fogMultiplier * (1f + (1f - dl) * 0.6f));   // denser at night
        float[] fogDay = {0.70f, 0.78f, 0.90f}, fogNight = {0.02f, 0.03f, 0.06f};
        float[] fc = new float[3];
        for(int i=0;i<3;i++) fc[i] = fogNight[i] + (fogDay[i]-fogNight[i]) * dl;
        fog.put("color", fc);
        // ---- drive directional light ----
        LightComponent sun = findSun(w);
        if(sun != null){
            boolean night = dl < 0.25f;
            float inten = night ? moonFactor() * 4f : 0.4f + dl * 2.2f;
            sun.intensity = inten;
            // orientation: light entity rotates to face along dir
            Entity sunEnt = sun.entity;
            if(sunEnt != null){
                float yaw = (float)Math.toDegrees(Math.atan2(-dir.x, -dir.z));
                float pitch = (float)Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, dir.y))));
                sunEnt.rotation.set(com.hollow.core.Quat.fromEuler(pitch, yaw, 0));
            }
            if(night){ sun.color.set(0.62f, 0.70f, 1.0f, 1f); }               // moonlight blue
            else {
                float warm = twilightFactor();
                sun.color.set(1.0f - warm*0.35f, 0.96f - warm*0.25f, 0.90f - warm*0.5f, 1f);
            }
        }
    }

    private static float smoothstep(float a, float b, float x){
        float t = MathX.clamp((x - a) / Math.max(1e-5f, b - a), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private float twilightFactor(){
        float t = timeOfDay;
        float dawn = 1f - Math.min(1f, Math.abs(t - SUNRISE) / 1.2f);
        float dusk = 1f - Math.min(1f, Math.abs(t - SUNSET) / 1.2f);
        return Math.max(0, Math.max(dawn, dusk));
    }

    private static LightComponent findSun(World w){
        LightComponent best = null;
        for(Entity e : w.entities){
            if(!e.active || e.destroyed) continue;
            LightComponent l = e.get(LightComponent.class);
            if(l == null || l.kind != com.hollow.scene.LightComponent.Kind.DIRECTIONAL) continue;
            if(best == null || l.intensity > best.intensity) best = l;
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> asMap(Object o){
        return o instanceof Map ? (Map<String,Object>)o : null;
    }

    // ---------------- persistence (SAV integration: ENV-006 "time سیو") ----------------
    @Override public void save(JsonW out){
        out.obj(); out.kv("type", "DayNightCycle");
        out.kv("timeOfDay", timeOfDay); out.kv("dayLengthMinutes", dayLengthMinutes);
        out.kv("latitudeTilt", latitudeTilt); out.kv("advance", advance);
        out.kv("fogMultiplier", fogMultiplier);
        out.endObj();
    }
    @Override public void load(JsonV node){
        timeOfDay = node.flt("timeOfDay", 8f);
        dayLengthMinutes = node.flt("dayLengthMinutes", 10f);
        latitudeTilt = node.flt("latitudeTilt", 0.35f);
        advance = node.bool("advance", true);
        fogMultiplier = node.flt("fogMultiplier", 1f);
    }
}
