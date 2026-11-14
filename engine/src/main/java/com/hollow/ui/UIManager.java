package com.hollow.ui;

import com.hollow.assets.Atlas;
import com.hollow.assets.AssetDB;
import com.hollow.assets.ImageData;
import com.hollow.core.Color;
import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.UiDrawList;
import com.hollow.core.Vec2;
import com.hollow.game.GameApp;
import com.hollow.game.Localization;
import com.hollow.script.EventBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** UI tree manager (UMG-like): retained-mode widget tree, JSON layout loading,
 *  pointer routing topmost-first, per-widget tweens, toast queue, RTL-aware labels.
 *  API is FINAL: buildDrawList + handlePointer + update + load used by framework/renderer. */
public final class UIManager {
    public TextShaper text = new DesktopTextShaper();

    private Widget root;
    private ToastHost toastHost;
    private AssetDB assets;
    private int screenW = 1280, screenH = 720;
    private float uiTime = 0;
    private int autoId = 1;
    private final Vec2 pointerPx = new Vec2(-1, -1);
    private Widget pressed, focused;

    // uv/texture-key caches (atlas sprites: "atlas.json#sprite")
    private final Map<String, float[]> uvCache = new HashMap<>();
    private final Map<String, String> texKeyCache = new HashMap<>();
    private final Map<String, Float> aspectCache = new HashMap<>();

    // ---------------- lifecycle ----------------

    /** load a UI JSON: {root:{type:"panel", anchors:{l,t,r,b}, children:[...]}} */
    public void load(String uiPath, AssetDB assets){
        try {
            Map<String, Object> doc = Json.parseObj(assets.files().readText(uiPath));
            this.assets = assets;
            Map<String, Object> rootNode = Json.obj(doc.get("root"));
            if(rootNode == null){ Log.w("UI", "no 'root' in " + uiPath); root = null; return; }
            root = build(rootNode);
            if(!root.hasAnchors) root.anchorsSet(0, 0, 1, 1);   // default: full screen
            Log.i("UI", "loaded " + uiPath);
        } catch(Exception e){
            Log.e("UI", "load failed: " + uiPath + " — " + e);
            root = null;
        }
    }

    public void unload(){
        root = null; pressed = null; focused = null;
        uvCache.clear(); texKeyCache.clear(); aspectCache.clear();
    }

    public Widget root(){ return root; }
    public Widget findWidget(String id){ return root == null ? null : root.findById(id); }
    public Vec2 pointer(){ return pointerPx; }

    /** toast host lives across loads (auto-created) */
    public ToastHost toasts(){
        if(toastHost == null){ toastHost = new ToastHost(); toastHost.id = "__toasts"; toastHost.anchorsSet(0,0,1,1); }
        return toastHost;
    }

    public void update(float dt){
        uiTime += dt;
        Tween.updateAll(dt);
        if(root != null) root.updateTree(dt, this);
        if(toastHost != null) toastHost.updateToasts(dt);
        // focused text field consumes typed chars from the framework input layer
        if(focused instanceof TextField tf){
            GameApp app = GameApp.instance;
            if(app != null && app.services() != null && app.services().input != null){
                String typed = app.services().input.typedText.toString();
                for(int i = 0; i < typed.length(); i++){
                    char c = typed.charAt(i);
                    if(c == '\b') tf.backspace();
                    else if(c >= 32 && c != 127) tf.appendText(String.valueOf(c));
                }
            }
        }
    }

    /** uiPos normalized 0..1 over the screen; routes press/drag/release topmost-first */
    public void handlePointer(Vec2 uiPos, boolean down, boolean justPressed, boolean justReleased){
        pointerPx.set(uiPos.x * screenW, uiPos.y * screenH);
        if(justPressed){
            pressed = root != null ? root.hitTest(pointerPx.x, pointerPx.y) : null;
            if(pressed != null){
                pressed.press(pointerPx.x, pointerPx.y);
                focused = (pressed instanceof TextField) ? pressed : null;
            } else focused = null;
        } else if(down && pressed != null){
            pressed.drag(pointerPx.x, pointerPx.y);
        }
        if(justReleased && pressed != null){
            Widget w = pressed;
            pressed = null;
            boolean inside = w.contains(pointerPx.x, pointerPx.y);
            w.release(pointerPx.x, pointerPx.y, inside);
            if(inside && w instanceof Button b) b.trigger();
        }
    }

    /** emit quads for the whole tree; screen space = pixels, origin top-left */
    public void buildDrawList(UiDrawList out, int screenW, int screenH){
        out.reset();
        this.screenW = screenW; this.screenH = screenH;
        if(root != null){
            root.layout(0, 0, screenW, screenH);
            root.drawTree(this, out, 1f);
        }
        if(toastHost != null){
            toastHost.layout(0, 0, screenW, screenH);
            toastHost.drawTree(this, out, 1f);
        }
    }

    /** test hook — triggers a Button handler by id (fires "ui_click" like a real press) */
    public void fireClick(String widgetId){
        Widget w = findWidget(widgetId);
        if(w instanceof Button b) b.trigger();
        else if(w != null) Log.w("UI", "fireClick: widget '" + widgetId + "' is not a button");
    }

    // ---------------- services for widgets ----------------

    static EventBus events(){
        GameApp app = GameApp.instance;
        return app != null && app.services() != null ? app.services().events : null;
    }

    AssetDB assetDb(){ return assets; }

    /** "@i18n:key" → localization lookup, else literal */
    String resolveText(String s){
        if(s == null) return "";
        if(s.startsWith("@i18n:")){
            GameApp app = GameApp.instance;
            String key = s.substring(6);
            return app != null && app.services() != null && app.services().i18n != null
                    ? app.services().i18n.get(key, key) : key;
        }
        return s;
    }

    /** resolve "atlas.json#sprite" / texture path → uv rect {u0,v0,u1,v1} (null if missing) */
    float[] resolveUv(String ref){
        if(ref == null || ref.isEmpty()) return null;
        float[] uv = uvCache.get(ref);
        if(uv != null) return uv;
        if(ref.contains("#")){
            String atlasPath = ref.substring(0, ref.indexOf('#'));
            String sprite = ref.substring(ref.indexOf('#') + 1);
            if(assets != null){
                Atlas atlas = assets.atlas(atlasPath);
                Atlas.Region r = atlas != null ? atlas.get(sprite) : null;
                if(r != null){
                    uv = new float[]{r.u0, r.v0, r.u1, r.v1};
                    texKeyCache.put(ref, atlas.texturePath != null ? atlas.texturePath : atlasPath);
                }
            }
        } else {
            uv = new float[]{0, 0, 1, 1};
            texKeyCache.put(ref, ref);
        }
        if(uv == null) Log.w("UI", "sprite not found: " + ref);
        else uvCache.put(ref, uv);
        return uv;
    }

    String textureKeyFor(String ref){
        resolveUv(ref);
        String k = texKeyCache.get(ref);
        return k != null ? k : "@white";
    }

    /** aspect (w/h) for fit=contain */
    float aspectFor(String ref){
        Float cached = aspectCache.get(ref);
        if(cached != null) return cached;
        float aspect = 0;
        try {
            if(ref.contains("#") && assets != null){
                Atlas atlas = assets.atlas(ref.substring(0, ref.indexOf('#')));
                Atlas.Region r = atlas != null ? atlas.get(ref.substring(ref.indexOf('#') + 1)) : null;
                if(r != null && r.ph > 0) aspect = r.pw / (float) r.ph;
            } else if(assets != null){
                ImageData im = assets.image(ref);
                if(im != null && im.height > 0) aspect = im.width / (float) im.height;
            }
        } catch(Exception e){ aspect = 0; }
        aspectCache.put(ref, aspect);
        return aspect;
    }

    static int rgbaOf(Color c, float alphaMul){
        int a = (int) (MathX.clamp(c.a * alphaMul, 0f, 1f) * 255f);
        int r = (int) (MathX.clamp(c.r, 0f, 1f) * 255f);
        int g = (int) (MathX.clamp(c.g, 0f, 1f) * 255f);
        int b = (int) (MathX.clamp(c.b, 0f, 1f) * 255f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ================= Widget base =================

    public static abstract class Widget {
        public String id = "";
        /** anchors {l,t,r,b} in 0..1 of parent rect */
        public final float[] anchors = {0, 0, 0, 0};
        public boolean hasAnchors = false;
        public float pivotX = 0, pivotY = 0;
        public boolean visible = true;
        public float alpha = 1;
        public final Color color = new Color(1, 1, 1, 1);
        public final List<Widget> children = new ArrayList<>();
        public Widget parent;
        /** pixel-mode convenience: pos/size in parent pixels */
        boolean pixelMode = false;
        float px, py, pw2, ph2;
        // layout result (absolute)
        public float x, y, w, h;
        // tween offsets
        float drawOffX, drawOffY;
        Map<String, Object> anim;
        float animElapsed = 0;

        public Widget add(Widget c){ c.parent = this; children.add(c); return this; }
        public Widget findById(String wid){
            if(id != null && id.equals(wid)) return this;
            for(Widget c : children){ Widget r = c.findById(wid); if(r != null) return r; }
            return null;
        }
        public void anchorsSet(float l, float t, float r, float b){
            anchors[0] = l; anchors[1] = t; anchors[2] = r; anchors[3] = b; hasAnchors = true;
        }
        public boolean contains(float px2, float py2){
            return px2 >= x && px2 <= x + w && py2 >= y && py2 <= y + h;
        }
        boolean interactive(){ return false; }

        /** topmost interactive widget at point (children before self) */
        Widget hitTest(float px2, float py2){
            if(!visible || alpha <= 0.01f) return null;
            for(int i = children.size() - 1; i >= 0; i--){
                Widget r = children.get(i).hitTest(px2, py2);
                if(r != null) return r;
            }
            return interactive() && contains(px2, py2) ? this : null;
        }

        void layout(float parentX, float parentY, float parentW, float parentH){
            if(pixelMode){
                x = parentX + px;
                y = parentY + py;
                w = pw2 > 0 ? pw2 : parentW;
                h = ph2 > 0 ? ph2 : parentH;
            } else {
                x = parentX + anchors[0] * parentW;
                y = parentY + anchors[1] * parentH;
                w = (anchors[2] - anchors[0]) * parentW;
                h = (anchors[3] - anchors[1]) * parentH;
            }
            for(Widget c : children) c.layout(x, y, w, h);
        }

        void updateTree(float dt, UIManager m){
            tickAnim(dt);
            updateWidget(dt, m);
            for(Widget c : children) c.updateTree(dt, m);
        }
        void updateWidget(float dt, UIManager m){}

        void drawTree(UIManager m, UiDrawList out, float parentAlpha){
            if(!visible) return;
            float a = alpha * parentAlpha;
            draw(m, out, a, x + drawOffX, y + drawOffY);
            for(Widget c : children) c.drawTree(m, out, a);
        }

        abstract void draw(UIManager m, UiDrawList out, float alpha, float dx, float dy);

        // pointer callbacks
        void press(float px2, float py2){}
        void drag(float px2, float py2){}
        void release(float px2, float py2, boolean inside){}

        /** minimal tween: anim {prop:"alpha"|"x"|"y", from, to, duration, delay} */
        void tickAnim(float dt){
            if(anim == null) return;
            float delay = (float) Json.num(anim.get("delay"), 0);
            float dur = Math.max(1e-3f, (float) Json.num(anim.get("duration"), 0.3f));
            animElapsed += dt;
            float t = MathX.clamp((animElapsed - delay) / dur, 0, 1);
            float v = MathX.lerp((float) Json.num(anim.get("from"), 0), (float) Json.num(anim.get("to"), 1), MathX.smoothstep(t));
            String prop = Json.str(anim.get("prop"), "alpha");
            switch(prop){
                case "alpha": alpha = v; break;
                case "y": drawOffY = v; break;
                case "x": drawOffX = v; break;
            }
        }
    }

    // ================= concrete widgets =================

    /** solid/texture rect + children */
    public static class Panel extends Widget {
        public String texture = null;      // texture path or "atlas.json#sprite"
        public float borderWidth = 0;
        public final Color borderColor = new Color(0, 0, 0, 0);

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            int rgba = rgbaOf(color, a);
            if(texture == null || texture.isEmpty()){
                if(color.a > 0.001f) out.addQuad("@white", dx, dy, w, h, 0, 0, 1, 1, rgba);
            } else {
                float[] uv = m.resolveUv(texture);
                if(uv != null) out.addQuad(m.textureKeyFor(texture), dx, dy, w, h, uv[0], uv[1], uv[2], uv[3], rgba);
            }
            if(borderWidth > 0 && borderColor.a > 0.001f){
                int brgba = rgbaOf(borderColor, a);
                out.addQuad("@white", dx, dy, w, borderWidth, 0, 0, 1, 1, brgba);
                out.addQuad("@white", dx, dy + h - borderWidth, w, borderWidth, 0, 0, 1, 1, brgba);
                out.addQuad("@white", dx, dy + borderWidth, borderWidth, h - borderWidth * 2, 0, 0, 1, 1, brgba);
                out.addQuad("@white", dx + w - borderWidth, dy + borderWidth, borderWidth, h - borderWidth * 2, 0, 0, 1, 1, brgba);
            }
        }
    }

    /** text (RTL auto-detected, "@i18n:" resolved) */
    public static class Label extends Widget {
        public String text = "";
        public float fontSize = 18;
        public int align = TextShaper.ALIGN_LEFT;
        boolean alignSet = false;
        public boolean wrap = false;

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            String s = m.resolveText(text);
            if(s.isEmpty()) return;
            int effAlign = align;
            if(!alignSet && Localization.isRtl(s)) effAlign = TextShaper.ALIGN_RIGHT;
            int rgba = rgbaOf(color, a);
            if(wrap){
                m.text.draw(out, s, dx, dy, fontSize, rgba, effAlign, w);
            } else {
                float tw = m.text.measure(s, fontSize);
                float ox = effAlign == TextShaper.ALIGN_CENTER ? (w - tw) * 0.5f
                        : effAlign == TextShaper.ALIGN_RIGHT ? (w - tw) : 0;
                m.text.draw(out, s, dx + ox, dy, fontSize, rgba, TextShaper.ALIGN_LEFT, 0);
            }
        }
    }

    /** normal/hover/pressed states; click → EventBus "ui_click" {id} */
    public static class Button extends Widget {
        public String label = "";
        public String action = "";          // optional extra event name fired with "ui_click"
        public float fontSize = 16;
        public final Color normal = new Color(0.18f, 0.42f, 0.7f, 1);
        public final Color hover = new Color(0.24f, 0.52f, 0.82f, 1);
        public final Color pressedC = new Color(0.12f, 0.3f, 0.55f, 1);
        public final Color textColor = new Color(1, 1, 1, 1);
        public Runnable onClick;
        boolean isPressed = false;

        @Override boolean interactive(){ return true; }
        @Override void press(float px2, float py2){ isPressed = true; }
        @Override void release(float px2, float py2, boolean inside){ isPressed = false; }

        public void trigger(){
            EventBus ev = events();
            if(ev != null){
                Map<String, Object> d = new HashMap<>();
                d.put("id", id);
                ev.fire("ui_click", d);
                if(action != null && !action.isEmpty()) ev.fire(action, d);
            }
            if(onClick != null){ try { onClick.run(); } catch(Exception e){ Log.w("UI", "onClick failed: " + e); } }
        }

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            boolean hovered = contains(m.pointerPx.x, m.pointerPx.y);
            Color c = isPressed ? pressedC : (hovered ? hover : normal);
            out.addQuad("@white", dx, dy, w, h, 0, 0, 1, 1, rgbaOf(c, a));
            String s = m.resolveText(label);
            if(!s.isEmpty()){
                float tw = m.text.measure(s, fontSize);
                float ox = MathX.clamp((w - tw) * 0.5f, 0, Math.max(0, w));
                m.text.draw(out, s, dx + ox, dy + (h - fontSize) * 0.5f, fontSize, rgbaOf(textColor, a), TextShaper.ALIGN_LEFT, 0);
            }
        }
    }

    /** texture or atlas sprite; fit: stretch | contain */
    public static class Image extends Widget {
        public String texture = "";
        public String fit = "stretch";

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            float[] uv = m.resolveUv(texture);
            if(uv == null) return;
            float rx = dx, ry = dy, rw = w, rh = h;
            if("contain".equals(fit)){
                float asp = m.aspectFor(texture);
                if(asp > 0){
                    float sw = w, sh = w / asp;
                    if(sh > h){ sh = h; sw = h * asp; }
                    rx = dx + (w - sw) * 0.5f;
                    ry = dy + (h - sh) * 0.5f;
                    rw = sw; rh = sh;
                }
            }
            out.addQuad(m.textureKeyFor(texture), rx, ry, rw, rh, uv[0], uv[1], uv[2], uv[3], rgbaOf(color, a));
        }
    }

    /** drag to change value → EventBus "ui_value" {id, value} */
    public static class Slider extends Widget {
        public float min = 0, max = 1, value = 0.5f;

        @Override boolean interactive(){ return true; }
        @Override void press(float px2, float py2){ setFromPx(px2); }
        @Override void drag(float px2, float py2){ setFromPx(px2); }

        void setFromPx(float px2){
            float trackX = x + 8;
            float trackW = Math.max(1f, w - 16);
            float t = MathX.clamp((px2 - trackX) / trackW, 0, 1);
            value = min + (max - min) * t;
            EventBus ev = events();
            if(ev != null){
                Map<String, Object> d = new HashMap<>();
                d.put("id", id);
                d.put("value", (double) value);
                ev.fire("ui_value", d);
            }
        }

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            float trackY = dy + h * 0.5f - 3;
            float trackW = Math.max(1f, w - 16);
            out.addQuad("@white", dx + 8, trackY, trackW, 6, 0, 0, 1, 1, rgbaOf(new Color(0.25f, 0.25f, 0.3f, 1), a));
            float t = MathX.clamp((value - min) / Math.max(1e-6f, max - min), 0, 1);
            out.addQuad("@white", dx + 8, trackY, trackW * t, 6, 0, 0, 1, 1, rgbaOf(new Color(0.3f, 0.6f, 0.95f, 1), a));
            float thumbX = dx + 8 + trackW * t - 7;
            out.addQuad("@white", thumbX, dy + h * 0.5f - 7, 14, 14, 0, 0, 1, 1, rgbaOf(new Color(0.9f, 0.9f, 0.95f, 1), a));
        }
    }

    /** toggle box → EventBus "ui_value" {id, value 0/1} */
    public static class Checkbox extends Widget {
        public boolean checked = false;
        public String label = "";

        @Override boolean interactive(){ return true; }
        @Override void release(float px2, float py2, boolean inside){
            if(inside){
                checked = !checked;
                EventBus ev = events();
                if(ev != null){
                    Map<String, Object> d = new HashMap<>();
                    d.put("id", id);
                    d.put("value", checked ? 1d : 0d);
                    ev.fire("ui_value", d);
                }
            }
        }

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            float box = Math.min(h, 22);
            out.addQuad("@white", dx, dy + (h - box) * 0.5f, box, box, 0, 0, 1, 1, rgbaOf(new Color(0.2f, 0.2f, 0.26f, 1), a));
            if(checked) out.addQuad("@white", dx + 4, dy + (h - box) * 0.5f + 4, box - 8, box - 8, 0, 0, 1, 1, rgbaOf(new Color(0.35f, 0.75f, 0.4f, 1), a));
            String s = m.resolveText(label);
            if(!s.isEmpty()) m.text.draw(out, s, dx + box + 8, dy + (h - fontSize()) * 0.5f, fontSize(), rgbaOf(color, a), TextShaper.ALIGN_LEFT, 0);
        }
        float fontSize(){ return 14; }
    }

    /** 0..1 bar */
    public static class ProgressBar extends Widget {
        public float value = 0;
        public final Color fill = new Color(0.3f, 0.75f, 0.4f, 1);

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            out.addQuad("@white", dx, dy, w, h, 0, 0, 1, 1, rgbaOf(new Color(0.15f, 0.15f, 0.2f, 1), a));
            float t = MathX.clamp(value, 0, 1);
            if(t > 0) out.addQuad("@white", dx + 1, dy + 1, Math.max(0, (w - 2) * t), Math.max(0, h - 2), 0, 0, 1, 1, rgbaOf(fill, a));
        }
    }

    /** single-line text with focus + caret blink */
    public static class TextField extends Widget {
        public String content = "";
        public float fontSize = 14;

        @Override boolean interactive(){ return true; }
        public void appendText(String s){ if(content.length() < 128) content += s; }
        public void backspace(){ if(content.length() > 0) content = content.substring(0, content.length() - 1); }

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            boolean isFocused = m.focused == this;
            out.addQuad("@white", dx, dy, w, h, 0, 0, 1, 1, rgbaOf(new Color(isFocused ? 0.16f : 0.1f, 0.16f, 0.2f, 1), a));
            String s = content;
            if(isFocused && (m.uiTime % 1f) < 0.5f) s += "|";
            m.text.draw(out, s, dx + 6, dy + (h - fontSize) * 0.5f, fontSize, rgbaOf(color, a), TextShaper.ALIGN_LEFT, Math.max(0, w - 12));
        }
    }

    /** toast queue host: slides up + fades; bottom-center of screen */
    public static class ToastHost extends Widget {
        static final class Toast {
            final String text; final float duration; float age = 0;
            Toast(String t, float d){ text = t; duration = d; }
        }
        final ArrayList<Toast> queue = new ArrayList<>();
        private final Color bg = new Color(0.08f, 0.08f, 0.12f, 0.92f);

        public void showToast(String text, float duration){
            if(queue.size() >= 4) queue.remove(0);
            queue.add(new Toast(text == null ? "" : text, Math.max(0.5f, duration)));
            Log.i("UI", "toast: " + text);
        }
        public int pending(){ return queue.size(); }
        public void clearToasts(){ queue.clear(); }

        void updateToasts(float dt){
            for(int i = queue.size() - 1; i >= 0; i--){
                Toast t = queue.get(i);
                t.age += dt;
                if(t.age > t.duration + 0.4f) queue.remove(i);
            }
        }

        @Override void draw(UIManager m, UiDrawList out, float a, float dx, float dy){
            float cy = dy + h - 110;
            for(Toast t : queue){
                float in = MathX.clamp(t.age / 0.25f, 0, 1);
                float fade = t.age > t.duration ? MathX.clamp(1 - (t.age - t.duration) / 0.4f, 0, 1) : 1;
                float ta = in * fade;
                float slide = (1 - in) * 24f;   // slides up while appearing
                float tw = m.text.measure(t.text, 16) + 32;
                float tx = dx + w * 0.5f - tw * 0.5f;
                out.addQuad("@white", tx, cy + slide, tw, 34, 0, 0, 1, 1, rgbaOf(bg, ta));
                m.text.draw(out, t.text, tx + 16, cy + slide + 9, 16, rgbaOf(new Color(1, 1, 1, 1), ta), TextShaper.ALIGN_LEFT, 0);
                cy -= 44;
            }
        }
    }

    // ================= JSON build =================

    private Widget build(Map<String, Object> node){
        String type = Json.str(node.get("type"), "panel").toLowerCase();
        Widget w;
        switch(type){
            case "label": w = new Label(); break;
            case "button": w = new Button(); break;
            case "image": w = new Image(); break;
            case "slider": w = new Slider(); break;
            case "checkbox": w = new Checkbox(); break;
            case "progressbar": case "progress": w = new ProgressBar(); break;
            case "textfield": case "input": w = new TextField(); break;
            case "toast": w = toasts(); break;
            case "nineslice": case "9slice": w = UIWidgets2.buildNineSlice(node); break;
            case "listview": case "list": w = UIWidgets2.buildListView(node); break;
            case "panel": default:
                if(!type.equals("panel")) Log.w("UI", "unknown widget type '" + type + "' — using panel");
                w = new Panel(); break;
        }
        w.id = Json.str(node.get("id"), type + "_" + autoId++);
        // anchors: {l,t,r,b} map or [l,t,r,b] array
        Object an = node.get("anchors");
        if(an instanceof Map){
            Map<String, Object> am = Json.obj(an);
            w.anchorsSet((float) Json.num(am.get("l"), 0), (float) Json.num(am.get("t"), 0),
                    (float) Json.num(am.get("r"), am.containsKey("w") ? 1f : (w.anchors[2] == 0f ? 1f : w.anchors[2])),
                    (float) Json.num(am.get("b"), am.containsKey("h") ? 0 : w.anchors[3]));
        } else if(an instanceof List && Json.arr(an).size() >= 4){
            List<Object> al = Json.arr(an);
            w.anchorsSet((float) Json.num(al.get(0), 0), (float) Json.num(al.get(1), 0),
                    (float) Json.num(al.get(2), 1), (float) Json.num(al.get(3), 1));
        }
        // pixel pos/size convenience
        List<Object> pos = Json.arr(node.get("pos"));
        List<Object> size = Json.arr(node.get("size"));
        if(pos != null && pos.size() >= 2){
            w.pixelMode = true;
            w.px = (float) Json.num(pos.get(0), 0);
            w.py = (float) Json.num(pos.get(1), 0);
        }
        if(size != null && size.size() >= 2){
            w.pixelMode = true;
            w.pw2 = (float) Json.num(size.get(0), 0);
            w.ph2 = (float) Json.num(size.get(1), 0);
        }
        // pivot: {x,y} or [x,y]
        Object pv = node.get("pivot");
        if(pv instanceof Map){
            Map<String, Object> pm = Json.obj(pv);
            w.pivotX = (float) Json.num(pm.get("x"), 0);
            w.pivotY = (float) Json.num(pm.get("y"), 0);
        } else if(pv instanceof List && Json.arr(pv).size() >= 2){
            w.pivotX = (float) Json.num(Json.arr(pv).get(0), 0);
            w.pivotY = (float) Json.num(Json.arr(pv).get(1), 0);
        }
        w.visible = Json.bool(node.get("visible"), true);
        w.alpha = (float) Json.num(node.get("alpha"), 1);
        Color c = Json.color(node.get("color"), null);
        if(c != null) w.color.set(c);

        // type-specific
        if(w instanceof Label){
            Label l = (Label) w;
            l.text = Json.str(node.get("text"), "");
            l.fontSize = (float) Json.num(node.get("fontSize"), 18);
            String al2 = Json.str(node.get("align"), null);
            if(al2 != null){
                l.alignSet = true;
                l.align = al2.equalsIgnoreCase("center") ? TextShaper.ALIGN_CENTER
                        : al2.equalsIgnoreCase("right") ? TextShaper.ALIGN_RIGHT : TextShaper.ALIGN_LEFT;
            }
            l.wrap = Json.bool(node.get("wrap"), false);
        } else if(w instanceof Button){
            Button b = (Button) w;
            b.label = Json.str(node.get("label"), Json.str(node.get("text"), ""));
            b.fontSize = (float) Json.num(node.get("fontSize"), 16);
            b.action = Json.str(node.get("action"), "");
            Color cn = Json.color(node.get("normal"), null); if(cn != null) b.normal.set(cn);
            Color ch = Json.color(node.get("hover"), null); if(ch != null) b.hover.set(ch);
            Color cp = Json.color(node.get("pressed"), null); if(cp != null) b.pressedC.set(cp);
        } else if(w instanceof Image){
            Image im = (Image) w;
            im.texture = Json.str(node.get("sprite"), Json.str(node.get("texture"), ""));
            im.fit = Json.str(node.get("fit"), "stretch");
        } else if(w instanceof Slider){
            Slider s = (Slider) w;
            s.min = (float) Json.num(node.get("min"), 0);
            s.max = (float) Json.num(node.get("max"), 1);
            s.value = (float) Json.num(node.get("value"), (s.min + s.max) * 0.5f);
        } else if(w instanceof Checkbox){
            Checkbox cb = (Checkbox) w;
            cb.checked = Json.bool(node.get("checked"), false);
            cb.label = Json.str(node.get("label"), "");
        } else if(w instanceof ProgressBar){
            ProgressBar pb = (ProgressBar) w;
            pb.value = (float) Json.num(node.get("value"), 0);
            Color f = Json.color(node.get("fill"), null); if(f != null) pb.fill.set(f);
        } else if(w instanceof TextField){
            TextField tf = (TextField) w;
            tf.content = Json.str(node.get("text"), "");
            tf.fontSize = (float) Json.num(node.get("fontSize"), 14);
        } else if(w instanceof Panel){
            Panel p = (Panel) w;
            p.texture = Json.str(node.get("texture"), null);
            p.borderWidth = (float) Json.num(node.get("borderWidth"), 0);
            Color bc = Json.color(node.get("borderColor"), null); if(bc != null) p.borderColor.set(bc);
        } else if(w instanceof UIWidgets2.ListView){
            UIWidgets2.ListView lv = (UIWidgets2.ListView) w;
            lv.selectedIndex = Json.int32(node.get("selectedIndex"), -1);
        }
        // minimal per-widget anim (alpha fade-in / slide)
        Object animNode = node.get("anim");
        if(animNode instanceof Map) w.anim = Json.obj(animNode);
        // children
        List<Object> kids = Json.arr(node.get("children"));
        if(kids != null){
            for(Object k : kids){
                Map<String, Object> kn = Json.obj(k);
                if(kn == null) continue;
                w.add(build(kn));
            }
        }
        return w;
    }
}
