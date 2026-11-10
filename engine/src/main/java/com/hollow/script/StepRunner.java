package com.hollow.script;

/** Interpreter skeleton for FlowScript step lists (Agent D implements). */
public abstract class StepRunner {
    /** tick pending waits; returns true while any graph is active */
    public abstract boolean update(float dt);
    /** run a step list synchronously (no waits) — used for immediate events */
    public abstract void runSteps(Object steps, com.hollow.scene.Entity self);
}
