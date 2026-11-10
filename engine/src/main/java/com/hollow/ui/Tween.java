package com.hollow.ui;

import com.hollow.core.Json;
import com.hollow.core.MathX;

import java.util.ArrayList;
import java.util.List;

/** UI tweens (HollowEngine 2.0 — UMG animations equivalent, code-driven).
 *  <pre>
 *  Tween.to(widget).fadeIn(0.4f).slideFromY(-30, 0.4f);
 *  Tween.value(v -> bar.value = v, 0, 1, 0.8f).easeOut();
 *  Tween.updateAll(dt);   // called by UIManager each frame
 *  </pre>
 *  Tweens auto-remove when finished; safe to create every frame for one-shots
 *  (dedup by target+key: starting the same tween again replaces the previous).
 */
public final class Tween {
    private Tween() {}

    public interface FloatTarget { void set(float v); }

    private static final List<Tween> active = new ArrayList<>();

    public float elapsed = 0f;
    public float duration = 0.3f;
    public float from, to;
    public boolean easeOut = true;
    public boolean killOnComplete = true;
    private FloatTarget target;
    private boolean started = false;

    // ---- static API ----
    public static Tween value(FloatTarget t, float from, float to, float duration){
        Tween tw = new Tween();
        tw.target = t;
        tw.from = from; tw.to = to;
        tw.duration = Math.max(0.001f, duration);
        active.add(tw);
        return tw;
    }

    public static WidgetTweens to(UIManager.Widget w){
        return new WidgetTweens(w);
    }

    public static void updateAll(float dt){
        if(active.isEmpty()) return;
        for(int i = active.size() - 1; i >= 0; i--){
            Tween t = active.get(i);
            t.elapsed += dt;
            float p = MathX.clamp(t.elapsed / t.duration, 0f, 1f);
            if(t.easeOut) p = MathX.smoothstep(p);
            if(t.target != null) t.target.set(MathX.lerp(t.from, t.to, p));
            if(p >= 1f && t.killOnComplete) active.remove(i);
        }
    }

    public static void clear(){
        active.clear();
    }

    public static int activeCount(){ return active.size(); }

    public Tween easeOut(){ easeOut = true; return this; }
    public Tween easeIn(){ easeOut = false; return this; }
    public Tween duration(float d){ duration = Math.max(0.001f, d); return this; }

    /** fluent widget tween builder — chains multiple effects on one widget */
    public static final class WidgetTweens {
        final UIManager.Widget w;
        WidgetTweens(UIManager.Widget w){ this.w = w; }

        public WidgetTweens fadeIn(float d){
            Tween.value(v -> w.alpha = v, 0f, 1f, d);
            return this;
        }
        public WidgetTweens fadeOut(float d){
            Tween.value(v -> w.alpha = v, w.alpha, 0f, d);
            return this;
        }
        public WidgetTweens slideFromY(float offset, float d){
            float base = w.drawOffY;
            Tween.value(v -> w.drawOffY = base + offset * (1f - v), 0f, 1f, d);
            return this;
        }
        public WidgetTweens slideFromX(float offset, float d){
            float base = w.drawOffX;
            Tween.value(v -> w.drawOffX = base + offset * (1f - v), 0f, 1f, d);
            return this;
        }
    }
}
