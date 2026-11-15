package com.hollow.gameplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.0.0 GAMEPLAY: weapon system — archetypes, magazines, fire modes, spread,
 * recoil, reload — fed by ammo from an Inventory.
 *
 * Pure logic + timings; the game layer maps fire input to tryFire(), plays
 * sounds/effects from the returned FireResult, and renders projectiles/traces
 * itself (engine render stays decoupled).
 */
public final class Weapon {

    public static final String FIRE_SEMI = "semi", FIRE_AUTO = "auto", FIRE_BURST = "burst";

    public String id = "rifle";
    public String name = "Rifle";
    public String ammoId = "ammo_rifle";       // inventory item id
    public float damage = 25f;
    public String damageType = "physical";
    public float fireRate = 8f;                // shots/sec
    public int magSize = 30;
    public int magAmmo = 30;
    public float reloadTime = 2.2f;            // seconds
    public String fireMode = FIRE_AUTO;
    public int burstCount = 3;                 // for FIRE_BURST

    /** accuracy: cone half-angle in degrees at reference range */
    public float spreadDeg = 1.5f;
    public float recoilKick = 0.8f;            // view kick units (game layer maps to camera)
    public float range = 120f;                 // trace distance

    // runtime state
    public float cooldown = 0f;                // seconds until next shot allowed
    public float reloading = 0f;               // remaining reload seconds
    public int burstRemaining = 0;
    public long shotsFired = 0, shotsHit = 0;  // stats

    /** result of a fire attempt — game layer drives fx/audio from this */
    public static final class FireResult {
        public boolean fired = false;
        public boolean empty = false;          // trigger pulled, no ammo
        public boolean onCooldown = false;
        public float recoil = 0f;
        public float spreadRad;                // applied cone (radians) for this pellet
        public int consumedFromInventory;      // ammo drained from inventory this shot
    }

    /** advance cooldowns; call from fixed update */
    public void update(float dt){
        if(cooldown > 0f) cooldown -= dt;
        if(reloading > 0f){
            reloading -= dt;
            if(reloading <= 0f) finishReload();
        }
    }

    /**
     * attempt to fire once (semi) or continuously (auto — call every tick while held).
     * SEMI mode: the caller passes triggerHeld=true ONLY on the frame the trigger went
     * down (edge), or consecutive calls fire on cooldown cadence — for true one-per-pull
     * use tryFirePressed(). Ammo: magazine first, then reload pulls from inventory.
     */
    public FireResult tryFire(boolean triggerHeld, Inventory ammoSource){
        return tryFireInternal(triggerHeld, triggerHeld, ammoSource);
    }

    /** strict semi: fires exactly once per trigger press (rising edge) */
    public FireResult tryFirePressed(boolean pressed, Inventory ammoSource){
        return tryFireInternal(pressed, false, ammoSource);
    }

    private FireResult tryFireInternal(boolean triggerHeld, boolean semiAllowsHold, Inventory ammoSource){
        FireResult r = new FireResult();
        if(reloading > 0f){ r.onCooldown = true; return r; }
        boolean wantsShot = FIRE_AUTO.equals(fireMode) ? triggerHeld
                          : FIRE_BURST.equals(fireMode) ? (burstRemaining > 0 || triggerHeld)
                          : (triggerHeld || semiAllowsHold);   // semi: fire on press edge; tryFire() also allows held (cooldown-gated)
        if(!wantsShot) return r;
        if(cooldown > 0f){ r.onCooldown = true; return r; }
        if(magAmmo <= 0){
            // auto-reload attempt from inventory
            if(ammoSource != null && ammoSource.has(ammoId, 1)){
                startReload(ammoSource);
                r.onCooldown = true;
                return r;
            }
            r.empty = true;
            return r;
        }
        magAmmo--;
        shotsFired++;
        if(FIRE_BURST.equals(fireMode) && triggerHeld && burstRemaining <= 0) burstRemaining = burstCount - 1;
        else if(burstRemaining > 0) burstRemaining--;
        cooldown = 1f / Math.max(0.01f, fireRate);
        r.fired = true;
        r.spreadRad = (float)(Math.toRadians(spreadDeg) * (0.5 + Math.random() * 0.5));
        r.recoil = recoilKick;
        return r;
    }

    private float recoilNorm;

    public void startReload(Inventory ammoSource){
        if(reloading > 0f || magAmmo >= magSize) return;
        reloading = reloadTime;
        this.pendingReloadSource = ammoSource;
    }

    private Inventory pendingReloadSource;

    private void finishReload(){
        int need = magSize - magAmmo;
        if(need <= 0) return;
        if(pendingReloadSource != null){
            // pull from inventory (only what's there)
            int got = pendingReloadSource.remove(ammoId, need);
            magAmmo += got;
            pendingReloadSource = null;
        } else {
            // arcade mode: infinite reserve
            magAmmo += need;
        }
    }

    /** drain inventory ammo into the magazine immediately (manual reload, no timer) */
    public int completeReloadFrom(Inventory inv){
        int need = magSize - magAmmo;
        if(need <= 0) return 0;
        int got = inv.remove(ammoId, need);
        magAmmo += got;
        return got;
    }

    /** manual: swap fire mode (semi <-> auto <-> burst cycles) */
    public void cycleFireMode(){
        if(FIRE_SEMI.equals(fireMode)) fireMode = FIRE_AUTO;
        else if(FIRE_AUTO.equals(fireMode)) fireMode = FIRE_BURST;
        else fireMode = FIRE_SEMI;
    }

    public Map<String,Object> toJson(){
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("mag", magAmmo); m.put("reload", reloading);
        m.put("mode", fireMode); m.put("fired", shotsFired); m.put("hit", shotsHit);
        return m;
    }

    /** weapon library: id -> factory */
    public static final Map<String, Weapon> LIBRARY = new LinkedHashMap<>();

    public static Weapon register(Weapon w){
        LIBRARY.put(w.id, w);
        return w;
    }

    public static Weapon spawn(String id){
        Weapon proto = LIBRARY.get(id);
        if(proto == null) return null;
        Weapon w = new Weapon();
        w.id = proto.id; w.name = proto.name; w.ammoId = proto.ammoId;
        w.damage = proto.damage; w.damageType = proto.damageType;
        w.fireRate = proto.fireRate; w.magSize = proto.magSize; w.magAmmo = proto.magSize;
        w.reloadTime = proto.reloadTime; w.fireMode = proto.fireMode; w.burstCount = proto.burstCount;
        w.spreadDeg = proto.spreadDeg; w.recoilKick = proto.recoilKick; w.range = proto.range;
        return w;
    }
}
