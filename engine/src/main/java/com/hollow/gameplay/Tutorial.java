package com.hollow.gameplay;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.0.0 GAMEPLAY: tutorial / onboarding — step-by-step goals with completion
 * probes, unlock gates and hint repetition.
 *
 * Steps fire in order. Each step: a goal text, a done() probe polled by
 * update(), and optional timeout auto-skip. Steps can gate game actions
 * (wait-for-move before enabling attack) via the unlock flags map.
 */
public final class Tutorial {

    public static final class Step {
        public final String id;
        public String goalText = "";
        public String hint = "";
        public boolean skippable = true;
        public float timeoutSec = 0f;          // 0 = no timeout
        public float elapsed = 0f;
        public boolean done = false;
        public transient Probe probe;          // polled; true = step complete
        public transient Runnable onStart;     // fires when this step becomes current

        public Step(String id, String goalText){ this.id = id; this.goalText = goalText; }
        public interface Probe { boolean ok(); }
    }

    public final List<Step> steps = new ArrayList<>();
    public int currentIndex = -1;
    public boolean active = false;
    public boolean finished = false;

    /** fired when a step completes (for UI toasts/sounds) */
    public interface Listener { void onStepDone(Step s); void onTutorialFinished(); }
    public final List<Listener> listeners = new ArrayList<>();

    public Tutorial(){ }

    public Tutorial step(String id, String goal, Step.Probe probe){
        Step s = new Step(id, goal);
        s.probe = probe;
        steps.add(s);
        return this;
    }

    public Tutorial step(String id, String goal, String hint, Step.Probe probe){
        Step s = new Step(id, goal);
        s.hint = hint;
        s.probe = probe;
        steps.add(s);
        return this;
    }

    public void begin(){
        if(steps.isEmpty()){ finished = true; return; }
        currentIndex = 0;
        active = true;
        finished = false;
        fireStart();
    }

    public Step current(){
        return (currentIndex >= 0 && currentIndex < steps.size()) ? steps.get(currentIndex) : null;
    }

    /** poll the current step; advance when done */
    public void update(float dt){
        if(!active) return;
        Step s = current();
        if(s == null){ finish(); return; }
        s.elapsed += dt;
        boolean timedOut = s.timeoutSec > 0f && s.elapsed >= s.timeoutSec;
        if((s.probe != null && s.probe.ok()) || timedOut){
            s.done = true;
            for(Listener l : listeners) l.onStepDone(s);
            currentIndex++;
            if(currentIndex >= steps.size()) finish();
            else fireStart();
        }
    }

    /** manual complete (game layer decided) */
    public boolean completeCurrent(){
        Step s = current();
        if(s == null) return false;
        s.done = true;
        for(Listener l : listeners) l.onStepDone(s);
        currentIndex++;
        if(currentIndex >= steps.size()) finish();
        else fireStart();
        return true;
    }

    public void skipAll(){
        while(current() != null) completeCurrent();
    }

    private void finish(){
        active = false;
        finished = true;
        for(Listener l : listeners) l.onTutorialFinished();
    }

    private void fireStart(){
        Step s = current();
        if(s != null && s.onStart != null) s.onStart.run();
    }

    /** fraction complete 0..1 (for progress bar) */
    public float progress(){
        if(steps.isEmpty()) return 1f;
        int doneCount = 0;
        for(Step s : steps) if(s.done) doneCount++;
        return doneCount / (float)steps.size();
    }
}
