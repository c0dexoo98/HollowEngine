package com.hollow.physics;

import java.util.HashMap;
import java.util.Map;

/** v2.3 PHY-029 — physical material registry with per-pair combination.
 *  Named presets (concrete/wood/metal/ice/...) carry friction + restitution;
 *  a pair interaction combines A/B materials with a selectable rule:
 *  AVERAGE (default), MIN, MAX, MULTIPLY — matching PhysX behaviour.
 *  Bodies reference materials by name ("material":"ice"); unknown names fall
 *  back to DEFAULT (0.6 friction / 0 restitution). Thread-free by design:
 *  physics runs on the simulation thread only.
 */
public final class PhysicsMaterials {
    private PhysicsMaterials() {}

    public enum Combine { AVERAGE, MIN, MAX, MULTIPLY }

    public static final class Def {
        public final String name;
        public float friction;        // Coulomb µ
        public float restitution;     // 0..1
        /** COL-024: surface sound cue name played by gameplay on hard contacts ("wood","metal",...) */
        public String surfaceSound = "";
        public Def(String name, float friction, float restitution){
            this.name = name; this.friction = friction; this.restitution = restitution;
        }
        public Def sound(String s){ this.surfaceSound = s; return this; }
    }

    private static final Map<String, Def> REGISTRY = new HashMap<>();
    public static Combine frictionCombine = Combine.AVERAGE;
    public static Combine restitutionCombine = Combine.AVERAGE;

    static {
        register("default", 0.6f, 0f);
        register("concrete", 0.7f, 0.05f).sound("concrete");
        register("wood",     0.55f, 0.15f).sound("wood");
        register("metal",    0.35f, 0.25f).sound("metal");
        register("ice",      0.03f, 0.05f).sound("ice");
        register("mud",      0.9f, 0f).sound("mud");
        register("carpet",   0.85f, 0f).sound("carpet");
        register("glass",    0.25f, 0.4f).sound("glass");
        register("flesh",    0.8f, 0f).sound("flesh");
        register("rubber",   0.9f, 0.8f).sound("rubber");
    }

    public static Def register(String name, float friction, float restitution){
        Def d = new Def(name, friction, restitution);
        REGISTRY.put(name.toLowerCase(), d);
        return d;
    }

    public static Def get(String name){
        if(name == null) return REGISTRY.get("default");
        Def d = REGISTRY.get(name.toLowerCase());
        return d != null ? d : REGISTRY.get("default");
    }

    public static float combineFriction(float a, float b){
        return combine(a, b, frictionCombine);
    }

    public static float combineRestitution(float a, float b){
        return combine(a, b, restitutionCombine);
    }

    private static float combine(float a, float b, Combine mode){
        return switch(mode){
            case AVERAGE -> (a + b) * 0.5f;
            case MIN -> Math.min(a, b);
            case MAX -> Math.max(a, b);
            case MULTIPLY -> a * b;
        };
    }

    /** resolve pair µ: if both bodies carry a named material, combine their defs */
    public static float pairFriction(RigidBody a, RigidBody b){
        if(a == null || b == null) return 0.6f;
        boolean na = a.material != null && !a.material.isEmpty();
        boolean nb = b.material != null && !b.material.isEmpty();
        if(na && nb) return combineFriction(get(a.material).friction, get(b.material).friction);
        if(na) return get(a.material).friction;
        if(nb) return get(b.material).friction;
        return combineFriction(a.friction, b.friction);
    }

    public static float pairRestitution(RigidBody a, RigidBody b){
        if(a == null || b == null) return 0f;
        boolean na = a.material != null && !a.material.isEmpty();
        boolean nb = b.material != null && !b.material.isEmpty();
        if(na && nb) return combineRestitution(get(a.material).restitution, get(b.material).restitution);
        if(na) return get(a.material).restitution;
        if(nb) return get(b.material).restitution;
        return combineRestitution(a.bounciness, b.bounciness);
    }

    /** COL-024 surface cue for a contact between two bodies (empty if neither defines one) */
    public static String pairSurfaceSound(RigidBody a, RigidBody b){
        String sa = a != null && a.material != null && !a.material.isEmpty() ? get(a.material).surfaceSound : "";
        String sb = b != null && b.material != null && !b.material.isEmpty() ? get(b.material).surfaceSound : "";
        return !sa.isEmpty() ? sa : sb;
    }
}
