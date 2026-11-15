package com.hollow.ui;

import com.hollow.core.Ease;
import com.hollow.core.Json;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Vec2;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.4 UI/INP batch — UI-009..UI-018, INP-009..INP-015 (desktop-safe parts).
 *  Everything deterministic & headless-testable. Android-specific parts
 *  (gamepad, vibration hardware) live behind small interfaces. */
public final class UISystems {
    private UISystems(){}

    // =====================================================================
    // UI-009 — RTL layout mirror
    // =====================================================================
    /** Mirrors horizontal anchors/margins for RTL languages. Pure math —
     *  the renderer keeps LTR coordinates; we mirror the rect instead. */
    public static final class RtlLayout {
        /** mirror a rect within a parent: x' = parentW - x - w */
        public static float[] mirrorRect(float x, float y, float w, float h, float parentW){
            return new float[]{ parentW - x - w, y, w, h };
        }
        /** should this string render RTL? (first strong char Arabic/Hebrew block) */
        public static boolean isRtl(String s){
            return com.hollow.game.Localization.isRtl(s);
        }
        /** bidi-lite: split mixed text into runs so shaping orders segments correctly. */
        public static List<String> runs(String s){
            List<String> out = new ArrayList<>();
            if(s == null || s.isEmpty()){ out.add(s == null ? "" : s); return out; }
            boolean curRtl = isRtl(s.substring(0,1));
            StringBuilder run = new StringBuilder();
            for(int i = 0; i < s.length(); i++){
                boolean r = isRtl(s.substring(i, i+1));
                if(r != curRtl){ out.add(run.toString()); run.setLength(0); curRtl = r; }
                run.append(s.charAt(i));
            }
            out.add(run.toString());
            return out;
        }
        /** Persian/Arabic-Indic digit conversion for numerals in RTL text. */
        public static String toPersianDigits(String s){
            char[] d = {'۰','۱','۲','۳','۴','۵','۶','۷','۸','۹'};
            StringBuilder b = new StringBuilder(s.length());
            for(char c : s.toCharArray()){
                b.append(c >= '0' && c <= '9' ? d[c - '0'] : c);
            }
            return b.toString();
        }
        /** LOС-010: locale-aware number formatting (grouping). */
        public static String formatNumber(double v, boolean rtl, boolean group){
            String raw = group ? String.format("%,d", (long)v) : String.valueOf((long)v);
            return rtl ? toPersianDigits(raw.replace(",", "٬")) : raw;
        }
    }

    // =====================================================================
    // UI-010 — rich text (inline color/bold markup)
    // =====================================================================
    public static final class RichText {
        public static final class Span {
            public String text; public int color; public boolean bold;
            public Span(String t, int c, boolean b){ text = t; color = c; bold = b; }
        }
        /** parse "[#RRGGBB]text[/] and [*]bold[/]" markup. Unknown tags kept literal. */
        public static List<Span> parse(String s){
            List<Span> out = new ArrayList<>();
            if(s == null){ return out; }
            int color = 0xFFFFFFFF;
            boolean bold = false;
            int i = 0;
            StringBuilder plain = new StringBuilder();
            while(i < s.length()){
                if(s.charAt(i) == '['){
                    int close = s.indexOf(']', i);
                    if(close > i){
                        String tag = s.substring(i+1, close);
                        if(tag.startsWith("#")){
                            try { color = (int)Long.parseLong(tag.substring(1), 16); } catch(Exception ignored){}
                            flush(out, plain, color, bold);
                            i = close + 1; continue;
                        } else if(tag.equals("*")){ flush(out, plain, color, bold); bold = true; i = close + 1; continue; }
                        else if(tag.equals("/")){ flush(out, plain, color, bold); bold = false; color = 0xFFFFFFFF; i = close + 1; continue; }
                    }
                }
                plain.append(s.charAt(i)); i++;
            }
            flush(out, plain, color, bold);
            return out;
        }
        private static void flush(List<Span> out, StringBuilder plain, int color, boolean bold){
            if(plain.length() == 0) return;
            out.add(new Span(plain.toString(), color, bold));
            plain.setLength(0);
        }
        /** strip markup -> plain (for width measuring). */
        public static String plain(String s){
            StringBuilder b = new StringBuilder();
            for(Span sp : parse(s)) b.append(sp.text);
            return b.toString();
        }
    }

    // =====================================================================
    // UI-011 + UI-012 — scroll view with inertia + virtualized list
    // =====================================================================
    public static final class ScrollView {
        public float contentHeight = 0f;
        public float viewportHeight = 100f;
        public float scroll = 0f;              // 0..contentHeight-viewport
        public float velocity = 0f;            // px/s
        public float friction = 3.5f;          // inertia decay
        public boolean dragging = false;

        public void drag(float deltaPx){ scroll = MathX.clamp(scroll + deltaPx, 0f, maxScroll()); velocity = -deltaPx * 30f; }
        public void fling(float velocityPxPerSec){ velocity = velocityPxPerSec; }
        public float maxScroll(){ return Math.max(0f, contentHeight - viewportHeight); }

        /** inertia physics; returns settled flag. */
        public boolean update(float dt){
            if(!dragging && Math.abs(velocity) > 1f){
                scroll += velocity * dt;
                velocity = MathX.approach(velocity, 0f, friction * Math.abs(velocity) * dt + 10f * dt);
                // bounce clamp
                if(scroll < 0f){ scroll = 0f; velocity = 0f; }
                if(scroll > maxScroll()){ scroll = maxScroll(); velocity = 0f; }
                return false;
            }
            return Math.abs(velocity) <= 1f;
        }
        /** UI-012: visible item range for a virtualized list (never build 1000 widgets). */
        public int[] visibleRange(int itemCount, float itemHeight){
            int first = (int)(scroll / itemHeight);
            int count = (int)Math.ceil(viewportHeight / itemHeight) + 1;
            first = Math.max(0, Math.min(first, Math.max(0, itemCount - 1)));
            count = Math.min(count, itemCount - first);
            return new int[]{ first, count };
        }
    }

    // =====================================================================
    // UI-013 — UI tweening on transform (Ease integration)
    // =====================================================================
    public static final class UiTween {
        public interface Apply { void set(float v); }
        public float from, to, duration, t = 0f;
        public boolean started = false, done = false, easeOut = true;
        private final Apply apply;

        public UiTween(Apply apply, float from, float to, float duration){
            this.apply = apply; this.from = from; this.to = to; this.duration = Math.max(0.001f, duration);
        }
        public boolean update(float dt){
            if(done) return true;
            t += dt;
            float k = MathX.clamp(t / duration, 0f, 1f);
            float e = easeOut ? Ease.cubicOut(k) : Ease.cubicIn(k);
            apply.set(MathX.lerp(from, to, e));
            if(k >= 1f) done = true;
            return done;
        }
        private static final List<UiTween> registry = new ArrayList<>();
        public static UiTween moveX(UIManager.Widget w, float to, float dur){
            UiTween tw = new UiTween(v -> w.x = v, w.x, to, dur);
            registry.add(tw);
            return tw;
        }
        public static UiTween moveY(UIManager.Widget w, float to, float dur){
            UiTween tw = new UiTween(v -> w.y = v, w.y, to, dur);
            registry.add(tw);
            return tw;
        }
        public static UiTween alpha(UIManager.Widget w, float to, float dur){
            UiTween tw = new UiTween(v -> w.alpha = v, w.alpha, to, dur);
            registry.add(tw);
            return tw;
        }
        /** called by UIManager.update via Tween.updateAll — or directly per frame. */
        public static void updateRegistry(float dt){
            for(int i = registry.size()-1; i >= 0; i--){
                if(registry.get(i).update(dt)) registry.remove(i);
            }
        }
        public static int registrySize(){ return registry.size(); }
    }

    // =====================================================================
    // UI-014 — phone safe area (notch)
    // =====================================================================
    public static final class SafeArea {
        public float left, right, top, bottom;    // px insets
        public void set(float l, float r, float t, float b){ left=l; right=r; top=t; bottom=b; }
        public float[] apply(float x, float y, float w, float h, float screenW, float screenH){
            float nx = x + left;
            float ny = y + top;
            float nw = Math.max(1f, w - left - right);
            float nh = Math.max(1f, h - top - bottom);
            return new float[]{ nx, ny, nw, nh };
        }
    }

    // =====================================================================
    // UI-016 — declarative UI from JSON
    // =====================================================================
    /** Builds UIManager.Widget trees from ui/*.ui.json — editor-friendly. */
    public static final class UiJson {
        /** {"id":"hud","children":[{"id":"hp","type":"bar","x":20,"y":20,"w":180,"h":14,"color":"#ff3040"}]} */
        public static UIManager.Widget build(Map<String,Object> def){
            return buildWidget(def);
        }
        public static UIManager.Widget buildFile(String json){
            Map<String,Object> root = Json.parseObj(json);
            return buildWidget(root);
        }
        @SuppressWarnings("unchecked")
        private static UIManager.Widget buildWidget(Map<String,Object> def){
            String type = Json.str(def.get("type"), "panel");
            UIManager.Widget w;
            switch(type){
                case "label" -> w = new UIManager.Label();
                case "button" -> w = new UIManager.Button();
                case "slider" -> w = new UIManager.Slider();
                case "image" -> w = new UIManager.Image();
                case "checkbox" -> w = new UIManager.Checkbox();
                case "progress", "bar" -> w = new UIManager.ProgressBar();
                default -> w = new UIManager.Panel();
            }
            w.id = Json.str(def.get("id"), "");
            w.x = (float)Json.num(def.get("x"), 0);
            w.y = (float)Json.num(def.get("y"), 0);
            w.w = (float)Json.num(def.get("w"), 100);
            w.h = (float)Json.num(def.get("h"), 40);
            Object col = def.get("color");
            if(col instanceof String cs){
                com.hollow.core.Color c = com.hollow.core.Color.parse(cs, null);
                if(c != null) w.color.set(c);
            }
            List<Object> children = (List<Object>)def.get("children");
            if(children != null){
                for(Object c : children){
                    Map<String,Object> cm = Json.obj(c);
                    if(cm != null) w.add(buildWidget(cm));
                }
            }
            return w;
        }
    }

    // =====================================================================
    // UI-017 — global theme/palette (swappable)
    // =====================================================================
    public static final class Theme {
        public int accent = 0xFF3F9FFF;
        public int background = 0xE0101418;
        public int text = 0xFFE8ECF2;
        public int danger = 0xFFFF4055;
        public int success = 0xFF3FDD7F;
        public String font = "";
        private static final Map<String,Theme> presets = new LinkedHashMap<>();
        public static Theme get(String name){
            return presets.computeIfAbsent(name, k -> new Theme());
        }
        public static void register(String name, Theme t){ presets.put(name, t); }
        public void applyColor(UIManager.Widget w, int argb){
            w.color.set(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                        (argb & 0xFF) / 255f, ((argb >> 24) & 0xFF) / 255f);
        }
    }

    // =====================================================================
    // INP-009 — combo detection
    // =====================================================================
    /** Recognizes action sequences (e.g. light,light,heavy) inside a time window. */
    public static final class ComboDetector {
        public static final class Combo {
            public final String name; public final String[] sequence; public final float windowSec;
            public Combo(String name, String[] seq, float window){ this.name = name; this.sequence = seq; this.windowSec = window; }
        }
        private final List<Combo> combos = new ArrayList<>();
        private final List<String> buffer = new ArrayList<>();
        private final List<Float> bufferTime = new ArrayList<>();   // R15: per-press arrival time
        private float timer = 0f;
        private float clock = 0f;

        public void register(String name, String[] sequence, float windowSec){
            combos.add(new Combo(name, sequence, windowSec));
        }
        /** feed an action press; returns matched combo name or null. */
        public String press(String action, float dt){
            clock += dt;
            timer += dt;
            // R15: expire after the widest registered window (was hardcoded 1.5 s, windowSec ignored)
            float maxWindow = 1.5f;
            for(Combo c : combos) maxWindow = Math.max(maxWindow, c.windowSec);
            if(timer > maxWindow){ buffer.clear(); bufferTime.clear(); }
            buffer.add(action);
            bufferTime.add(clock);
            timer = 0f;
            for(Combo c : combos){
                int n = c.sequence.length;
                if(buffer.size() < n) continue;
                boolean match = true;
                for(int i = 0; i < n; i++){
                    if(!c.sequence[i].equals(buffer.get(buffer.size() - n + i))){ match = false; break; }
                }
                if(match){
                    float span = clock - bufferTime.get(bufferTime.size() - n);   // R15: honor the combo's own window
                    if(span > c.windowSec) continue;
                    buffer.clear();
                    bufferTime.clear();
                    return c.name;
                }
            }
            if(buffer.size() > 8){ buffer.remove(0); bufferTime.remove(0); }
            return null;
        }
        public int bufferLen(){ return buffer.size(); }
    }

    // =====================================================================
    // INP-010 — context-sensitive action maps
    // =====================================================================
    /** Named context stacks: "menu" overrides "game" bindings. */
    public static final class ContextMaps {
        private final Map<String, Map<String,String>> contexts = new LinkedHashMap<>();  // context -> action -> binding
        private final List<String> stack = new ArrayList<>();

        public void define(String ctx, Map<String,String> bindings){ contexts.put(ctx, bindings); }
        public void push(String ctx){ if(!stack.contains(ctx)) stack.add(ctx); }
        public void pop(String ctx){ stack.remove(ctx); }
        public String current(){ return stack.isEmpty() ? "game" : stack.get(stack.size()-1); }
        /** resolve binding: top context first, then lower. */
        public String binding(String action){
            for(int i = stack.size()-1; i >= 0; i--){
                Map<String,String> m = contexts.get(stack.get(i));
                if(m != null && m.containsKey(action)) return m.get(action);
            }
            Map<String,String> base = contexts.get("game");
            return base != null ? base.get(action) : null;
        }
        public int stackDepth(){ return stack.size(); }
    }

    // =====================================================================
    // INP-011 + INP-013 — live rebind + user input profile persistence
    // =====================================================================
    public static final class InputProfile {
        public final Map<String,String> binds = new LinkedHashMap<>();
        public float deadzone = 0.18f;
        public float lookSensitivity = 1.0f;

        public void rebind(String action, String key){ binds.put(action, key); }
        public String save(){
            StringBuilder b = new StringBuilder("{\n  \"deadzone\": " + deadzone + ",\n  \"lookSensitivity\": " + lookSensitivity + ",\n  \"binds\": {");
            boolean first = true;
            for(Map.Entry<String,String> e : binds.entrySet()){
                if(!first) b.append(",");
                first = false;
                b.append("\n    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"");
            }
            b.append("\n  }\n}");
            return b.toString();
        }
        public void load(String json){
            Map<String,Object> root = Json.parseObj(json);
            deadzone = (float)Json.num(root.get("deadzone"), 0.18);
            lookSensitivity = (float)Json.num(root.get("lookSensitivity"), 1.0);
            Map<String,Object> bs = Json.obj(root.get("binds"));
            binds.clear();
            if(bs != null) for(Map.Entry<String,Object> e : bs.entrySet())
                binds.put(e.getKey(), String.valueOf(e.getValue()));
        }
    }

    // =====================================================================
    // INP-014 — input replay for automated testing
    // =====================================================================
    public static final class InputReplay {
        public static final class Ev { public float t; public String action; public boolean down; }
        public final List<Ev> events = new ArrayList<>();
        private float clock = 0f; private int cursor = 0; public boolean playing = false;
        public int firedCount = 0;

        public void record(float t, String action, boolean down){
            Ev e = new Ev(); e.t = t; e.action = action; e.down = down;
            events.add(e);
        }
        public void clear(){ events.clear(); cursor = 0; clock = 0f; playing = false; firedCount = 0; }

        public void play(){ playing = true; clock = 0f; cursor = 0; firedCount = 0; }
        public void stop(){ playing = false; }

        /** advance; invokes handler for every event due this tick. */
        public void update(float dt, java.util.function.BiConsumer<String,Boolean> handler){
            if(!playing) return;
            clock += dt;
            while(cursor < events.size() && events.get(cursor).t <= clock){
                Ev e = events.get(cursor++);
                handler.accept(e.action, e.down);
                firedCount++;
            }
            if(cursor >= events.size()) playing = false;
        }
        public int remaining(){ return events.size() - cursor; }
    }

    // =====================================================================
    // INP-015 — lightweight 2-player local split (action source tagging)
    // =====================================================================
    public static final class LocalSplit {
        public final com.hollow.game.InputActions p1 = new com.hollow.game.InputActions();
        public final com.hollow.game.InputActions p2 = new com.hollow.game.InputActions();
        /** route raw presses: source 0/1 -> action on that player's map. */
        public void press(int source, String action, boolean down){
            com.hollow.game.InputActions tgt = source == 0 ? p1 : p2;
            tgt.setButton(action, down);
        }
        public com.hollow.game.InputActions player(int i){ return i == 0 ? p1 : p2; }
    }

    // =====================================================================
    // INP-008 — vibration events (interface; Android impl injects hardware)
    // =====================================================================
    public interface Vibrator { void vibrate(int ms, float amplitude); }
    public static final class Rumble {
        public Vibrator device;
        public void hit(float intensity, int ms){ if(device != null) device.vibrate(ms, MathX.clamp(intensity, 0f, 1f)); }
    }

    // =====================================================================
    // INP-012 — analog trigger precision
    // =====================================================================
    public static final class TriggerAxis {
        public float raw = 0f;                 // 0..1 hardware value
        public float threshold = 0.12f;        // fire activation past this
        public boolean active = false;
        public void update(float analogValue){
            raw = MathX.clamp(analogValue, 0f, 1f);
            active = raw >= threshold;
        }
        /** precision fire rate scale: light pull = slower precise fire. */
        public float precision(){ return 0.4f + 0.6f * raw; }
    }

    // =====================================================================
    // UI-015 — gamepad navigation map (focus graph)
    // =====================================================================
    public static final class NavMap {
        private final Map<String, Map<String,String>> focus = new LinkedHashMap<>();   // id -> dir -> id
        public void edge(String from, String dir, String to){
            focus.computeIfAbsent(from, k -> new LinkedHashMap<>()).put(dir, to);
        }
        public String navigate(String from, String dir){
            Map<String,String> m = focus.get(from);
            return m != null ? m.get(dir) : null;
        }
        /** auto-build line navigation for ordered widget ids. */
        public void autoLine(List<String> ids){
            for(int i = 0; i < ids.size(); i++){
                if(i > 0) edge(ids.get(i), "up", ids.get(i-1));
                if(i < ids.size()-1) edge(ids.get(i), "down", ids.get(i+1));
            }
        }
    }

    // =====================================================================
    // UI-018 — localization-aware auto text sizing
    // =====================================================================
    public static final class AutoTextSize {
        /** estimate: Persian/Arabic ~0.52 em per char avg; Latin ~0.5; returns font size fitting box. */
        public static float fit(String text, float boxW, float boxH, float baseSize){
            if(text == null || text.isEmpty()) return baseSize;
            float perChar = com.hollow.game.Localization.isRtl(text) ? 0.52f : 0.50f;
            float estW = text.length() * perChar * baseSize;
            if(estW <= boxW && baseSize <= boxH) return baseSize;
            float k = Math.min(boxW / Math.max(1f, estW), boxH / Math.max(1f, baseSize));
            return Math.max(8f, baseSize * k);
        }
    }
}
