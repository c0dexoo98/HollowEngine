package com.hollow.game;

import com.hollow.core.FileSystem;
import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Save slots: world vars + entity states + player transform + inventory. */
public final class SaveSystem {
    public final FileSystem fs;
    public String projectDir = "";
    public final List<String> inventory = new ArrayList<>();
    public int activeSlot = 0;

    public SaveSystem(FileSystem fs){ this.fs = fs; }

    public void save(int slot, World world, Entity player){
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("slot", slot);
        root.put("scene", GameApp.instance != null ? GameApp.instance.currentScenePath : "");
        root.put("time", GameApp.instance != null ? GameApp.instance.services().time.time : 0);
        root.put("vars", GameApp.instance != null ? GameApp.instance.services().vars.snapshot() : new LinkedHashMap<>());
        root.put("inventory", inventory);
        if(player != null){
            Map<String,Object> p = new LinkedHashMap<>();
            p.put("name", player.name);
            p.put("position", new float[]{player.position.x, player.position.y, player.position.z});
            p.put("yaw", player.rotation.yawDeg());
            p.put("pitch", player.rotation.pitchDeg());
            root.put("player", p);
        }
        if(world != null){
            List<Object> states = new ArrayList<>();
            for(Entity e : world.entities){
                if(e.destroyed || e.tag.isEmpty()) continue;
                Map<String,Object> s = new LinkedHashMap<>();
                s.put("name", e.name);
                s.put("active", e.active);
                states.add(s);
            }
            root.put("entities", states);
        }
        try {
            fs.write(projectDir + "/saves/slot_" + slot + ".json", Json.write(root).getBytes("UTF-8"));
            Log.i("Save", "saved slot " + slot);
        } catch(Exception e){ Log.e("Save", "save failed", e); }
    }

    /** returns false if slot empty */
    public boolean load(int slot, World world){
        try {
            Map<String,Object> root = Json.parseObj(fs.readText(projectDir + "/saves/slot_" + slot + ".json"));
            // vars
            Map<String,Object> vars = Json.obj(root.get("vars"));
            if(vars != null && GameApp.instance != null) GameApp.instance.services().vars.restore(vars);
            // inventory
            inventory.clear();
            List<Object> inv = Json.arr(root.get("inventory"));
            if(inv != null) for(Object o : inv) inventory.add(String.valueOf(o));
            // player
            Map<String,Object> p = Json.obj(root.get("player"));
            if(p != null && world != null){
                Entity pe = world.find(Json.str(p.get("name"), "Player"));
                if(pe != null){
                    List<Object> pos = Json.arr(p.get("position"));
                    if(pos != null && pos.size() >= 3){
                        pe.position.set((float)Json.num(pos.get(0),0), (float)Json.num(pos.get(1),0), (float)Json.num(pos.get(2),0));
                        pe.markDirtyDeep();
                    }
                    float yaw = (float) Json.num(p.get("yaw"), 0), pitch = (float) Json.num(p.get("pitch"), 0);
                    pe.rotation.set(com.hollow.core.Quat.fromEuler(pitch, yaw, 0));
                }
            }
            // entity active flags
            List<Object> states = Json.arr(root.get("entities"));
            if(states != null && world != null){
                for(Object o : states){
                    Map<String,Object> s = Json.obj(o);
                    if(s == null) continue;
                    Entity e = world.find(Json.str(s.get("name"), ""));
                    if(e != null) e.active = Json.bool(s.get("active"), true);
                }
            }
            Log.i("Save", "loaded slot " + slot);
            return true;
        } catch(Exception e){
            Log.w("Save", "no save in slot " + slot);
            return false;
        }
    }
}
