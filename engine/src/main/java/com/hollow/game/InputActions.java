package com.hollow.game;

import com.hollow.core.Vec2;
import com.hollow.core.Vec3;

/** Input action mapping (Unreal Enhanced-Input style), driven by JSON `input.json`.
 *  Platform feeds raw events; game queries named actions. */
public final class InputActions {
    public static final class Action {
        public String name;
        public float value = 0f;          // -1..1 for axes, 0/1 for buttons
        public boolean pressed = false;   // went down this frame
        public boolean released = false;
        public boolean held = false;
        public float heldTime = 0f;
    }

    private final java.util.Map<String, Action> actions = new java.util.LinkedHashMap<>();
    // raw state
    public final Vec2 touchLook = new Vec2();       // delta since last frame (pixels or normalized)
    public final Vec2 touchMove = new Vec2();       // virtual stick -1..1
    public boolean pointerDown = false;
    public float pointerX, pointerY;                // UI-space 0..1
    public StringBuilder typedText = new StringBuilder(); // chars this frame (desktop debug only)

    public InputActions(){
        define("move_forward"); define("move_back"); define("move_left"); define("move_right");
        define("look_x"); define("look_y"); define("jump"); define("crouch");
        define("interact"); define("attack"); define("aim"); define("run");
        define("flashlight"); define("pause"); define("sprint");
    }
    private void define(String name){ Action a = new Action(); a.name = name; actions.put(name, a); }

    public Action action(String name){ return actions.get(name); }
    /** v2.5 (DBG-012): full action map for input replay/inspector. */
    public java.util.Map<String, Action> all(){ return actions; }
    public float axis(String name){ Action a = actions.get(name); return a == null ? 0 : a.value; }
    public boolean pressed(String name){ Action a = actions.get(name); return a != null && a.pressed; }
    public boolean held(String name){ Action a = actions.get(name); return a != null && a.held; }
    public boolean released(String name){ Action a = actions.get(name); return a != null && a.released; }
    public float heldTime(String name){ Action a = actions.get(name); return a == null ? 0 : a.heldTime; }

    /** called by platform layer on raw button/zone events */
    public void setButton(String name, boolean down){
        Action a = actions.get(name);
        if(a == null){ a = new Action(); a.name = name; actions.put(name, a); }   // auto-define (v2.4: split/combos)
        if(down && !a.held){ a.pressed = true; a.held = true; a.heldTime = 0; }
        else if(!down && a.held){ a.released = true; a.held = false; }
        a.value = a.held ? 1f : 0f;
    }
    /** frame-end: clear per-frame flags */
    public void update(float dt){
        for(Action a : actions.values()){
            a.pressed = false; a.released = false;
            if(a.held) a.heldTime += dt;
            if(a.name.equals("look_x")) a.value = 0;
            if(a.name.equals("look_y")) a.value = 0;
        }
        touchLook.set(0,0);
        typedText.setLength(0);
    }

    /** movement vector from WASD-ish actions */
    public Vec3 moveVector(){
        float x = axis("move_right") - axis("move_left");
        float z = axis("move_back") - axis("move_forward");
        return new Vec3(x, 0, z);
    }

    // ------- JSON mapping (input.json): {"keymap": {"w": "move_forward", ...}, "touch": {...}} -------
    public void loadMapping(com.hollow.core.FileSystem fs, String projectDir){
        try {
            com.hollow.core.JsonV n = com.hollow.core.JsonV.parse(fs.readText(projectDir + "/input.json"));
            com.hollow.core.JsonV km = n.node("keymap");
            for(java.util.Map.Entry<String,Object> e : km.map.entrySet()){
                String actionName = com.hollow.core.Json.str(e.getValue(), null);
                if(actionName != null && !actions.containsKey(actionName)) define(actionName);
                keyBind.put(e.getKey(), actionName);
            }
        } catch(Exception ignored){}
    }
    public final java.util.Map<String,String> keyBind = new java.util.LinkedHashMap<>();

    /** raw keyboard event from platform: maps through keyBind */
    public void keyEvent(String key, boolean down){
        String a = keyBind.get(key);
        if(a == null) a = keyBind.get(key.toLowerCase());
        if(a != null) setButton(a, down);
        else if(key.length()==1 && down) typedText.append(key);
    }

    /** v2.5 (INP-007): analog axis feed (gamepad sticks/triggers). Writes the value
     *  directly — pressed/released edges stay button-driven via setButton. */
    public void setAxis(String name, float value){
        Action a = actions.get(name);
        if(a == null){ a = new Action(); a.name = name; actions.put(name, a); }
        a.value = com.hollow.core.MathX.clamp(value, -1f, 1f);
        // analog axes also drive held edges for binary consumers
        boolean nowHeld = Math.abs(value) > 0.01f;
        if(nowHeld && !a.held){ a.pressed = true; a.held = true; a.heldTime = 0; }
        else if(!nowHeld && a.held){ a.released = true; a.held = false; }
    }
}
