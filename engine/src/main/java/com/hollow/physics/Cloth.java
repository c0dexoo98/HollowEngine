package com.hollow.physics;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;

import java.util.ArrayList;
import java.util.List;

/** v2.3 PHY-020 — cloth (Verlet pseudo-mesh) with sphere collision.
 *  Grid of particles integrated with Verlet (pos + prevPos), constraints:
 *  structural (right/down neighbours), shear (diagonals), bend (2-apart).
 *  Pins by grid index; collides against world RigidBody spheres and explicit
 *  sphere list (table edges, characters). Constraint break threshold enables
 *  tearing. Pure allocation-light: particle arrays reused every tick.
 *  Serialized: {"type":"Cloth","cols":12,"rows":12,"spacing":0.15,"stiffness":...}
 */
public final class Cloth extends Component {
    public int cols = 12, rows = 12;
    public float spacing = 0.15f;          // rest distance between neighbours
    public float stiffness = 0.9f;         // constraint correction per iteration
    public float damping = 0.03f;
    public float tearThreshold = 0f;       // 0 = never tear; else relative stretch to snap
    public int iterations = 3;
    public Vec3 wind = new Vec3();

    /** pinned particles (grid index list serialized as array) */
    public final List<Integer> pins = new ArrayList<>();
    /** explicit colliders (world-space spheres) beyond the physics bodies */
    public final List<Sphere> spheres = new ArrayList<>();

    private final List<P> parts = new ArrayList<>();
    private final List<C> cons = new ArrayList<>();
    private boolean built = false;

    public static final class Sphere { public final Vec3 c = new Vec3(); public float r = 0.3f; }
    static final class P { final Vec3 pos = new Vec3(); final Vec3 prev = new Vec3(); boolean pinned = false; int gx, gy; }
    static final class C { P a, b; float rest; boolean broken; }

    /** build the grid in the entity's local XY plane, +Z out of the surface */
    public void build(){
        parts.clear(); cons.clear();
        for(int y = 0; y < rows; y++)
            for(int x = 0; x < cols; x++){
                P p = new P();
                p.gx = x; p.gy = y;
                p.pos.set(x * spacing, y * spacing, 0);
                p.prev.set(p.pos);
                parts.add(p);
            }
        for(int y = 0; y < rows; y++)
            for(int x = 0; x < cols; x++){
                P p = at(x, y);
                if(x + 1 < cols) addC(p, at(x + 1, y));
                if(y + 1 < rows) addC(p, at(x, y + 1));
                if(x + 1 < cols && y + 1 < rows) addC(p, at(x + 1, y + 1));       // shear
                if(x + 2 < cols) addC(p, at(x + 2, y), spacing * 2f);             // bend
                if(y + 2 < rows) addC(p, at(x, y + 2), spacing * 2f);
            }
        for(int idx : pins){ P p = parts.get(idx); if(p != null) p.pinned = true; }
        built = true;
    }

    private P at(int x, int y){ return parts.get(y * cols + x); }
    private void addC(P a, P b){ addC(a, b, spacing); }
    private void addC(P a, P b, float rest){
        C c = new C(); c.a = a; c.b = b; c.rest = rest; cons.add(c);
    }

    public void pin(int x, int y){
        int idx = y * cols + x;
        if(idx >= 0 && idx < parts.size()){ pins.add(idx); if(built) parts.get(idx).pinned = true; }
    }

    @Override public void onUpdate(float dt){
        if(entity == null || entity.destroyed) return;
        if(!built) build();
        float dtc = Math.min(0.033f, Math.max(0.001f, dt));
        // collect collision spheres: explicit + dynamic bodies nearby
        List<Sphere> active = spheres;
        List<RigidBody> bodies = null;
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        if(app != null && app.services() != null && app.services().physics != null)
            bodies = app.services().physics.bodyList();
        // integrate (Verlet)
        Vec3 half = new Vec3();
        for(P p : parts){
            if(p.pinned) continue;
            half.set(p.pos).sub(p.prev);
            p.prev.set(p.pos);
            p.pos.addScaled(half, 1f - damping);
            p.pos.addScaled(wind, dtc * dtc * 40f);
            p.pos.y -= 9.81f * dtc * dtc;
        }
        // satisfy constraints
        for(int it = 0; it < iterations; it++){
            for(C c : cons){
                if(c.broken) continue;
                Vec3 d = c.b.pos.cpy().sub(c.a.pos);
                float len = d.len();
                if(len < 1e-7f) continue;
                float diff = (len - c.rest) / len;
                if(tearThreshold > 0f && Math.abs(diff) * c.rest > c.rest * tearThreshold){ c.broken = true; continue; }
                float corr = diff * stiffness * 0.5f;
                if(!c.a.pinned) c.a.pos.addScaled(d, corr);
                if(!c.b.pinned) c.b.pos.addScaled(d, -corr);
            }
            // sphere collisions (explicit)
            for(Sphere s : active){
                for(P p : parts){
                    if(p.pinned) continue;
                    Vec3 d = p.pos.cpy().sub(s.c);
                    float dist = d.len();
                    if(dist < s.r && dist > 1e-7f){
                        p.pos.addScaled(d.scale(1f / dist), s.r - dist);
                    }
                }
            }
            // dynamic body spheres
            if(bodies != null){
                for(RigidBody rb : bodies){
                    if(rb.entity == null || rb.entity.destroyed) continue;
                    Vec3 c = rb.entity.position;
                    float r = rb.effectiveRadius();
                    for(P p : parts){
                        if(p.pinned) continue;
                        Vec3 d = p.pos.cpy().sub(c);
                        float dist = d.len();
                        if(dist < r && dist > 1e-7f){
                            p.pos.addScaled(d.scale(1f / dist), r - dist);
                        }
                    }
                }
            }
        }
        // push root vertices into the entity transform (rendering picks it up via GPU reader)
        entity.setMeta("clothDirty", true);
        entity.setMeta("clothParticles", parts.size());
        entity.markDirtyDeep();
    }

    /** particle world position (for the renderer / rope meshes) */
    public Vec3 particlePos(int x, int y, Vec3 out){
        P p = at(x, y);
        return out.set(entity.worldMatrix.transformPoint(p.pos));
    }

    public int particleCount(){ return parts.size(); }
    public int constraintCount(){ return cons.size(); }
    public int brokenCount(){ int n = 0; for(C c : cons) if(c.broken) n++; return n; }
    /** public accessor for tests/gameplay: local-space Y of a particle */
    public float particleLocalY(int x, int y){ return at(x, y).pos.y; }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Cloth").kv("cols",cols).kv("rows",rows).kv("spacing",spacing)
          .kv("stiffness",stiffness).kv("damping",damping).kv("tearThreshold",tearThreshold)
          .kvv("wind",wind.x,wind.y,wind.z);
        w.key("pins"); w.arr();
        for(int i : pins) w.valueRaw(String.valueOf(i));
        w.endArr();
        w.endObj();
    }

    @Override public void load(JsonV n){
        cols = Math.max(2, (int)n.flt("cols",12f));
        rows = Math.max(2, (int)n.flt("rows",12f));
        spacing = n.flt("spacing",0.15f);
        stiffness = n.flt("stiffness",0.9f);
        damping = n.flt("damping",0.03f);
        tearThreshold = n.flt("tearThreshold",0f);
        Object wd = n.get("wind");
        if(wd instanceof List<?> l && l.size() >= 3) wind.set(num(l.get(0)), num(l.get(1)), num(l.get(2)));
        pins.clear();
        Object pn = n.get("pins");
        if(pn instanceof List<?> l) for(Object o : l) pins.add((int)num(o));
        built = false;                                   // rebuild with new dims on first tick
    }

    private static float num(Object o){
        if(o instanceof Number num) return num.floatValue();
        try { return Float.parseFloat(String.valueOf(o)); } catch(Exception e){ return 0f; }
    }
}
