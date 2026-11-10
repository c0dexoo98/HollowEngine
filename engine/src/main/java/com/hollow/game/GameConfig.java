package com.hollow.game;

import com.hollow.core.Json;

import java.util.Map;

/** Project-level configuration (project.json). */
public final class GameConfig {
    public String title = "HollowEngine Game";
    public String appId = "com.hollow.runtime";
    public String version = "1.0.0";
    public int versionCode = 1;
    public String entryScene = "scenes/main.scene.json";
    public String orientation = "sensorLandscape";   // portrait | landscape | sensorLandscape
    public String quality = "HIGH";                  // LOW/MEDIUM/HIGH/ULTRA
    public boolean headless = false;

    public static GameConfig fromJson(Map<String,Object> m){
        GameConfig c = new GameConfig();
        if(m == null) return c;
        c.title = Json.str(m.get("title"), c.title);
        c.appId = Json.str(m.get("appId"), c.appId);
        c.version = Json.str(m.get("version"), c.version);
        c.versionCode = Json.int32(m.get("versionCode"), 1);
        c.entryScene = Json.str(m.get("entryScene"), c.entryScene);
        c.orientation = Json.str(m.get("orientation"), c.orientation);
        c.quality = Json.str(m.get("quality"), c.quality).toUpperCase();
        return c;
    }
    public static GameConfig load(com.hollow.core.FileSystem fs, String projectDir){
        try {
            return fromJson(Json.parseObj(fs.readText(projectDir + "/project.json")));
        } catch(Exception e){
            com.hollow.core.Log.w("GameConfig", "no project.json — using defaults");
            return new GameConfig();
        }
    }
}
