package com.hollow.game;

import com.hollow.core.Log;
import com.hollow.core.MathX;

import java.util.LinkedHashMap;
import java.util.Map;

/** INP-007 — Android gamepad support (Bluetooth controllers).
 *
 *  The Android platform layer forwards KeyEvent/MotionEvent gamepad data here:
 *  - standard button codes (KEYCODE_BUTTON_A/B/X/Y/…) → named actions,
 *  - two sticks + triggers with radial deadzone and resolution curves,
 *  - D-pad → movement actions, Start/Select → pause/interact,
 *  - hot-plug connect/disconnect events, rumble interface, battery hints.
 *  Output is written straight into InputActions (setButton / setAxis) so games
 *  never see device-specific codes. */
public final class GamepadInput {

    /** Android KeyEvent KEYCODE_BUTTON_* constants (subset we bind). */
    public static final int BTN_A = 0x60, BTN_B = 0x61, BTN_X = 0x62, BTN_Y = 0x63;
    public static final int BTN_L1 = 0x64, BTN_R1 = 0x65, BTN_L2 = 0x66, BTN_R2 = 0x67;
    public static final int BTN_START = 0x6C, BTN_SELECT = 0x6D;
    public static final int BTN_DPAD_UP = 0x13, BTN_DPAD_DOWN = 0x14,
        BTN_DPAD_LEFT = 0x15, BTN_DPAD_RIGHT = 0x16;
    public static final int BTN_THUMBL = 0x6E, BTN_THUMBR = 0x6F;

    /** default binding: Android button code -> InputActions action */
    public final Map<Integer, String> buttonBind = new LinkedHashMap<>();
    /** analog bindings */
    public String bindMoveX = "move_right";     // +X → right, -X → move_left value
    public String bindMoveY = "move_back";      // +Y (down) → back
    public String bindLookX = "look_x";
    public String bindLookY = "look_y";
    public String bindTriggerL = "aim";         // LT
    public String bindTriggerR = "attack";      // RT
    public String bindSprint = "sprint";        // click L3

    /** tuning */
    public float deadzone = 0.18f;              // radial deadzone (fraction of range)
    public float triggerThreshold = 0.5f;       // digital crossover for LT/RT
    public float lookSensitivity = 1.0f;
    public float lookMaxDelta = 14f;            // max look units per frame

    /** state */
    public boolean connected = false;
    public String deviceName = "";
    public int deviceId = -1;
    public float batteryLevel = 1f;             // 0..1 (MotionEvent.AXIS_BATTERY if exposed)
    public int framesWithInput = 0;

    private final InputActions target;
    private float lastLX, lastLY, lastRX, lastRY, lastLT, lastRT;

    public interface Rumble { void rumble(int durationMs, float strength); }
    public Rumble rumbleHook = null;

    public GamepadInput(InputActions target){
        this.target = target;
        defaultBind();
    }

    public void defaultBind(){
        buttonBind.put(BTN_A, "jump");
        buttonBind.put(BTN_B, "crouch");
        buttonBind.put(BTN_X, "interact");
        buttonBind.put(BTN_Y, "flashlight");
        buttonBind.put(BTN_L1, "aim");
        buttonBind.put(BTN_R1, "attack");
        buttonBind.put(BTN_START, "pause");
        buttonBind.put(BTN_SELECT, "interact");
        buttonBind.put(BTN_DPAD_UP, "move_forward");
        buttonBind.put(BTN_DPAD_DOWN, "move_back");
        buttonBind.put(BTN_DPAD_LEFT, "move_left");
        buttonBind.put(BTN_DPAD_RIGHT, "move_right");
        buttonBind.put(BTN_THUMBR, "sprint");
    }

    // ---------------- platform feed ----------------

    /** connect/disconnect from InputDeviceListener */
    public void onConnected(int id, String name){
        connected = true; deviceId = id; deviceName = name == null ? "gamepad" : name;
        Log.i("Gamepad", "connected: " + deviceName + " (id=" + id + ")");
        if(target != null) target.setButton("gamepad_connected", true);
    }
    public void onDisconnected(){
        connected = false;
        Log.i("Gamepad", "disconnected: " + deviceName);
        // release everything to avoid stuck keys
        if(target != null){
            for(String a : buttonBind.values()) target.setButton(a, false);
            applySticks(0, 0, 0, 0, 0, 0);
        }
    }

    /** KeyEvent: action = KeyEvent.ACTION_DOWN(0)/ACTION_UP(1), code = KEYCODE_BUTTON_* */
    public void onKeyEvent(int action, int keyCode, int repeat){
        String actionName = buttonBind.get(keyCode);
        if(actionName != null && target != null) target.setButton(actionName, action == 0);
    }

    /** MotionEvent axes: LX(0), LY(1), RX(11), RY(14), LTrig(17 = AXIS_LTRIGGER or brake 23) */
    public void onJoystickEvent(float lx, float ly, float rx, float ry, float lt, float rt){
        lastLX = lx; lastLY = ly; lastRX = rx; lastRY = ry; lastLT = lt; lastRT = rt;
        framesWithInput++;
        applySticks(lx, ly, rx, ry, lt, rt);
    }

    void applySticks(float lx, float ly, float rx, float ry, float lt, float rt){
        if(target == null) return;
        float mx = dz(lx), my = dz(ly);
        // move axis: left stick X → move_right/move_left split values, Y → forward/back
        target.setAxis(bindMoveX, mx > 0 ? mx : 0);
        target.setAxis("move_left", mx < 0 ? -mx : 0);
        target.setAxis(bindMoveY, my > 0 ? my : 0);
        target.setAxis("move_forward", my < 0 ? -my : 0);
        // look axis (right stick) — scaled delta
        float rdlx = dz(rx), rdly = dz(ry);
        target.setAxis(bindLookX, MathX.clamp(rdlx * lookSensitivity, -lookMaxDelta, lookMaxDelta));
        target.setAxis(bindLookY, MathX.clamp(rdly * lookSensitivity * 0.6f, -lookMaxDelta, lookMaxDelta));
        // triggers: digital crossover
        boolean ltD = lt >= triggerThreshold, rtD = rt >= triggerThreshold;
        target.setAxis(bindTriggerL, lt);
        target.setAxis(bindTriggerR, rt);
        target.setButton(bindTriggerL, ltD);   // pressed/released edges derive in setButton
        target.setButton(bindTriggerR, rtD);
    }

    /** per-axis deadzone with renormalized range */
    float dz(float v){
        float mag = Math.abs(v);
        if(mag < deadzone) return 0;
        float t = Math.min(1f, (mag - deadzone) / (1f - deadzone));
        return v < 0 ? -t : t;
    }

    /** rumble (device-side; hook set by Android platform) */
    public void rumble(int durationMs, float strength){
        if(rumbleHook != null) rumbleHook.rumble(durationMs, MathX.clamp(strength, 0, 1));
    }

    public String status(){
        return "gamepad[" + (connected ? deviceName + " id=" + deviceId : "disconnected")
            + " battery=" + Math.round(batteryLevel * 100) + "% frames=" + framesWithInput + "]";
    }
}
