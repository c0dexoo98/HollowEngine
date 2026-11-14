package com.hollow.core;

/** Fixed-timestep simulation clock (Unreal-style frame pipeline). */
public final class Time {
    public float deltaTime = 0f;       // real delta this frame (seconds)
    public float fixedDelta = 1f/60f;  // fixed logic step
    public float time = 0f;            // accumulated world time
    public int tick = 0;               // fixed-step counter
    public int frame = 0;
    private long lastNanos = 0;
    private float accumulator = 0;
    public float maxFrameTime = 0.25f; // avoid spiral-of-death

    public void begin(){
        if(headless){ deltaTime = fixedDelta; frame++; return; }
        long now = System.nanoTime();
        deltaTime = lastNanos==0 ? fixedDelta : Math.min(maxFrameTime, (now-lastNanos)/1_000_000_000f);
        lastNanos = now;
        frame++;
    }
    /** true = deterministic fixed-step (CLI simulation), ignores wall clock */
    public boolean headless = false;
    /** returns number of fixed steps to run this frame */
    public int stepsDue(){
        accumulator += deltaTime;
        int n = 0;
        while(accumulator >= fixedDelta && n < 8){ accumulator -= fixedDelta; n++; }
        if(accumulator >= fixedDelta) accumulator = fixedDelta * 0.999f;   // hitch cap: keep alpha in [0,1)
        return n;
    }
    public void advanceFixed(){
        time += fixedDelta;
        tick++;
    }
    /** alpha in [0,1) for interpolation between fixed steps */
    public float alpha(){ return accumulator / fixedDelta; }
}
