package com.hollow.core;

/** Engine identity (CORE): semantic version + build metadata + banner. */
public final class EngineVersion {
    public static final int MAJOR = 2;
    public static final int MINOR = 4;
    public static final int PATCH = 0;
    public static final String CODENAME = "hollow-worlds";
    public static final String NAME = "HollowEngine";
    public static final String FULL = NAME + " " + MAJOR + "." + MINOR + "." + PATCH + " (" + CODENAME + ")";
    public static final String FEATURE_MATRIX = "570-item master checklist — docs/FEATURES.md";

    private EngineVersion(){}

    public static String banner(){
        return NAME + " " + MAJOR + "." + MINOR + "." + PATCH + " '" + CODENAME + "' — pure Java, Android + Linux CLI\n"
             + "  world/AI/UI/net/sav/vfx batch-4..6 active — day/night, GOAP, horror director, reliable UDP, weather\n"
             + "  see `hollow shell` for the interactive console, `hollow selftest` for 159 regression tests";
    }
}
