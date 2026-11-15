package com.hollow.audio;

import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v5.0 — adaptive music director (HollowEngine 5.0).
 *  State machine over named music states (MENU / EXPLORE / STEALTH / COMBAT / BOSS ...),
 *  each with a track list and an intensity parameter (0..1). Requests hold for a
 *  minimum dwell time, then crossfade over a configurable window by raising the new
 *  state's gain while lowering the old one — the AudioEngine picks up `gainOf(state)`.
 *  Combat intensity follows a threat signal with attack/release smoothing.
 */
public final class MusicDirector {

    public enum Request { IMMEDIATE, HOLD_DWELL }        // switch now vs wait out the dwell timer

    public static final class State {
        public final String name;
        public final List<String> tracks = new ArrayList<>();
        public float baseGain = 1f;
        public float dwell = 4f;           // seconds the state must hold before a switch away
        public State(String name){ this.name = name; }
        public State track(String t){ tracks.add(t); return this; }
        public State gain(float g){ baseGain = g; return this; }
        public State dwellSec(float d){ dwell = d; return this; }
    }

    public interface Listener { void onMusicEvent(String type, String from, String to, String track); }
    public Listener listener;

    public final Map<String, State> states = new LinkedHashMap<>();
    public String current = "";
    public String requested = "";
    public float intensity = 0f;           // 0..1 — drives layering (drums/stings) in the engine
    public float crossfadeSec = 1.5f;
    /** combat smoothing: threat signal from the game layer */
    public float attackRate = 2.5f, releaseRate = 0.35f;
    public long seed = 0x4D51C1L;

    private float dwellTimer = 0f;
    private float fadeK = 1f;              // 1 = settled on `current`, 0 = fully switched
    private String fadeFrom = "";
    private float threat = 0f;
    private long trackPick = 0;

    public State define(State s){ states.put(s.name, s); return s; }
    public State state(String name){ return states.get(name); }

    /** gain a specific state contributes right now (AudioEngine samples this per track) */
    public float gainOf(String name){
        State s = states.get(name);
        if(s == null) return 0f;
        float g = s.baseGain;
        if(name.equals(current)) g *= fadeK;
        else if(name.equals(fadeFrom)) g *= 1f - fadeK;
        else g = 0f;
        // intensity layers on top of the active state only
        if(name.equals(current)) g = Math.min(1.5f, g + intensity * 0.4f);
        return g;
    }

    /** pick the next track of a state (deterministic rotation) */
    public String nextTrack(String name){
        State s = states.get(name);
        if(s == null || s.tracks.isEmpty()) return null;
        MathX.Rng rng = new MathX.Rng(seed ^ (trackPick++) * 0x9E3779B97F4A7C15L);
        String t = s.tracks.get(rng.nextInt(s.tracks.size()));
        if(listener != null) listener.onMusicEvent("track", current, name, t);
        return t;
    }

    /** request a music state */
    public void request(String name, Request mode){
        if(states.get(name) == null || name.equals(current)) return;
        requested = name;
        if(mode == Request.IMMEDIATE || current.isEmpty()){
            beginSwitch();
        }
        // HOLD_DWELL: the update loop switches once the dwell timer expires
    }

    /** game layer feeds a threat signal each frame (0 = safe .. 1 = firefight) */
    public void setThreat(float t){
        threat = Math.max(0f, Math.min(1f, t));
    }

    public void update(float dt){
        if(dt <= 0f) return;
        // threat → intensity with attack/release
        float rate = threat > intensity ? attackRate : releaseRate;
        intensity = MathX.approach(intensity, threat, rate * dt);

        // dwell-then-switch
        if(!requested.isEmpty() && !requested.equals(current)){
            dwellTimer += dt;
            State cur = states.get(current);
            float need = cur != null ? cur.dwell : 0f;
            if(current.isEmpty() || dwellTimer >= need) beginSwitch();
        } else {
            dwellTimer = 0f;
        }

        // crossfade progress
        if(fadeK < 1f) fadeK = Math.min(1f, fadeK + dt / Math.max(0.05f, crossfadeSec));
    }

    private void beginSwitch(){
        if(requested.isEmpty() || requested.equals(current)) return;
        fadeFrom = current;
        current = requested;
        requested = "";
        dwellTimer = 0f;
        fadeK = 0f;
        nextTrack(current);
        if(listener != null) listener.onMusicEvent("switch", fadeFrom, current, null);
    }

    /** convenience: combat auto-request helper driven by the threat signal */
    public String autoCombatState(String exploreState, String combatState, float threshold){
        if(threat >= threshold && !current.equals(combatState)){
            request(combatState, Request.HOLD_DWELL);
        } else if(threat < threshold * 0.4f && current.equals(combatState)){
            request(exploreState, Request.HOLD_DWELL);
        }
        return current;
    }

    public String describe(){
        return "music[state=" + current + " fade=" + Math.round(fadeK * 100f) + "%"
            + " intensity=" + Math.round(intensity * 100f) + "%]";
    }
}
