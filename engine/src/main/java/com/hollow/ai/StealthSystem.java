package com.hollow.ai;

import com.hollow.core.MathX;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.List;

/** v5.0 — stealth / detection system (HollowEngine 5.0).
 *  Observers accumulate a 0..1 detection meter toward hidden actors based on:
 *  vision cone (angle + range), line-of-sight obstruction flags, light level,
 *  actor sneak state and movement speed. Noise pings (from the EventBus in real
 *  play, direct calls in tests) spike meters and trigger search behavior.
 *
 *  States per actor: UNDETECTED → SUSPICIOUS (meter > 0.35) → SEARCHING (meter hit 1
 *  then lost sight) → DETECTED (confirmed). All deterministic and headless-testable.
 */
public final class StealthSystem {

    public enum State { UNDETECTED, SUSPICIOUS, SEARCHING, DETECTED }

    public static final class Hidden {
        public final String name;
        public Vec3 pos = new Vec3();
        public boolean crouching = false;
        public float lightLevel = 0.8f;        // 0 = pitch dark .. 1 = bright
        public float speed = 0f;               // m/s — sprinting is louder + more visible
        public boolean inShadowVolume = false;
        public State state = State.UNDETECTED;
        public float lastDetectedAt = -1f;
        public Hidden(String name){ this.name = name; }
    }

    public static final class Observer {
        public final String name;
        public Vec3 pos = new Vec3();
        public Vec3 facing = new Vec3(0, 0, -1);
        public float fovDeg = 110f;
        public float range = 18f;
        public float alertness = 1f;           // multiplier on accumulation rate
        public final List<Detecting> detections = new ArrayList<>();
        public Observer(String name){ this.name = name; }

        public static final class Detecting {
            public final String target;
            public float meter = 0f;           // 0..1
            public float peak = 0f;
            public boolean seenThisTick = false;
            public float loseSightTimer = 0f;
            public Detecting(String target){ this.target = target; }
        }
    }

    public interface Listener { void onStealthEvent(String type, String observer, String target, float meter); }
    public Listener listener;

    // tuning
    public float risePerSec = 0.55f;           // full detect in ~2 s at point-blank full light
    public float decayPerSec = 0.30f;
    public float confirmThreshold = 1.0f;
    public float suspiciousThreshold = 0.35f;
    public float loseSightDelay = 2.5f;        // seconds of no-sight before SEARCHING→UNDETECTED reset
    public long seed = 0x57EA17L;
    private float clock = 0f;

    public final List<Observer> observers = new ArrayList<>();
    public final List<Hidden> hidden = new ArrayList<>();

    public Observer addObserver(String name){ Observer o = new Observer(name); observers.add(o); return o; }
    public Hidden addHidden(String name){ Hidden h = new Hidden(name); hidden.add(h); return h; }
    public Observer observer(String name){
        for(Observer o : observers) if(o.name.equals(name)) return o;
        return null;
    }
    public Hidden hiddenActor(String name){
        for(Hidden h : hidden) if(h.name.equals(name)) return h;
        return null;
    }

    /** straight-line visibility (engine raycast plugs in for real occlusion) */
    public interface LineOfSight { boolean blocked(Vec3 from, Vec3 to); }
    public LineOfSight los;

    /** noise ping: pushes meters of observers within radius. Creates detection entries
     *  for unseen actors too — a shout can reveal someone the observer never noticed. */
    public int noise(Vec3 at, float radius, float strength){
        int affected = 0;
        for(Observer o : observers){
            float d = o.pos.dist(at);
            if(d > radius) continue;
            float push = strength * (1f - d / radius);
            for(Hidden h : hidden){
                Observer.Detecting det = find(o.detections, h.name);
                if(det == null){ det = new Observer.Detecting(h.name); o.detections.add(det); }
                det.meter = MathX.clamp(det.meter + push, 0f, 1.2f);   // noise can overshoot into instant detect
                det.loseSightTimer = Math.max(det.loseSightTimer, loseSightDelay);
                if(det.meter >= confirmThreshold) confirm(o, det);
            }
            affected++;
        }
        return affected;
    }

    private void confirm(Observer o, Observer.Detecting det){
        Hidden h = hiddenActor(det.target);
        if(h != null && h.state != State.DETECTED){
            h.state = State.DETECTED;
            h.lastDetectedAt = clock;
            if(listener != null) listener.onStealthEvent("detected", o.name, det.target, det.meter);
        } else if(listener != null){
            listener.onStealthEvent("detected", o.name, det.target, det.meter);
        }
        det.meter = confirmThreshold;
    }

    public void update(float dt){
        if(dt <= 0f) return;
        clock += dt;
        for(Observer o : observers){
            for(Hidden h : hidden){
                Observer.Detecting det = find(o.detections, h.name);
                boolean visible = canSee(o, h);
                if(visible){
                    if(det == null){ det = new Observer.Detecting(h.name); o.detections.add(det); }
                    det.seenThisTick = true;
                    det.loseSightTimer = loseSightDelay;
                    // visibility factors: distance falloff + light + crouch + movement + shadow
                    float d = o.pos.dist(h.pos);
                    float distK = 1f - MathX.clamp(d / Math.max(0.01f, o.range), 0f, 1f);
                    float lightK = MathX.lerp(0.25f, 1.15f, MathX.clamp(h.lightLevel, 0f, 1f));
                    float crouchK = h.crouching ? 0.45f : 1f;
                    float speedK = 1f + MathX.clamp(h.speed / 6f, 0f, 1f) * 0.6f;
                    float shadowK = h.inShadowVolume ? 0.5f : 1f;
                    float rise = risePerSec * o.alertness * distK * lightK * crouchK * speedK * shadowK;
                    det.meter = Math.min(confirmThreshold, det.meter + rise * dt);
                    if(det.meter >= suspiciousThreshold && h.state == State.UNDETECTED){
                        h.state = State.SUSPICIOUS;
                        if(listener != null) listener.onStealthEvent("suspicious", o.name, h.name, det.meter);
                    }
                    if(det.meter >= confirmThreshold) confirm(o, det);
                } else if(det != null){
                    det.seenThisTick = false;
                    det.meter = Math.max(0f, det.meter - decayPerSec * dt);
                    det.loseSightTimer -= dt;
                    if(det.meter < suspiciousThreshold){
                        if(h.state == State.SUSPICIOUS) h.state = State.UNDETECTED;
                        if(h.state == State.DETECTED && det.loseSightTimer <= 0f){
                            // broke line of sight long enough — fall back to searching
                            h.state = State.SEARCHING;
                            det.meter = suspiciousThreshold * 0.6f;
                            if(listener != null) listener.onStealthEvent("searching", o.name, h.name, det.meter);
                        }
                    }
                    if(det.loseSightTimer <= -loseSightDelay * 2f && h.state == State.SEARCHING){
                        h.state = State.UNDETECTED;   // gave up searching
                    }
                }
            }
        }
    }

    private Observer.Detecting find(List<Observer.Detecting> list, String target){
        for(Observer.Detecting d : list) if(d.target.equals(target)) return d;
        return null;
    }

    /** cone + range + LOS test */
    public boolean canSee(Observer o, Hidden h){
        Vec3 to = h.pos.cpy().sub(o.pos);
        float d = to.len();
        if(d > o.range) return false;
        if(d < 1.2f) return true;                     // practically on top of the observer
        Vec3 dir = to.scale(1f / d);
        float cos = o.facing.norm().dot(dir);
        float halfFov = (float)Math.toRadians(o.fovDeg) * 0.5f;
        if(Math.acos(MathX.clamp(cos, -1f, 1f)) > halfFov) return false;
        if(los != null && los.blocked(o.pos, h.pos)) return false;
        return true;
    }

    /** best (highest) meter any observer has toward the actor */
    public float meterFor(String actor){
        float best = 0f;
        for(Observer o : observers)
            for(Observer.Detecting d : o.detections)
                if(d.target.equals(actor)) best = Math.max(best, d.meter);
        return best;
    }
    public State stateFor(String actor){
        Hidden h = hiddenActor(actor);
        return h != null ? h.state : State.UNDETECTED;
    }
}
