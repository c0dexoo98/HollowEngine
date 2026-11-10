package com.hollow.vfx;

import com.hollow.core.Json;
import com.hollow.core.JsonW;
import com.hollow.core.JsonV;
import com.hollow.scene.Component;

import java.util.List;
import java.util.Map;

/** Particle emitter (Niagara-lite). STUB — Agent D implements simulation. API FINAL. */
public final class ParticleEmitter extends Component {
    public String sprite = "";              // atlas sprite or texture path
    public float rate = 10f;                // particles/sec
    public int burst = 0;                   // initial burst count
    public float lifeMin = 0.6f, lifeMax = 1.2f;
    public float speedMin = 0.3f, speedMax = 1.0f;
    public float spreadDeg = 25f;
    public final com.hollow.core.Vec3 direction = new com.hollow.core.Vec3(0,1,0);
    public final com.hollow.core.Vec3 gravity = new com.hollow.core.Vec3(0,0,0);
    public float drag = 0.5f;
    public float sizeMin = 0.05f, sizeMax = 0.15f;
    public List<Object> sizeCurve = null;       // [[t, v], ...]
    public List<Object> colorCurve = null;      // [[t, r,g,b,a], ...]
    public int maxCount = 256;
    public boolean additive = true;
    public boolean localSpace = false;
    public String effect = "";              // registered effect name (target of ParticleLibrary.burst)

    // ---- v2.0: trails / sub-emitters / collision ----
    public boolean trail = false;           // spawn along entity movement
    public float trailMinDist = 0.15f;      // min move distance between spawns
    public float trailSpacing = 0.08f;      // particle spacing along the path
    public String deathEffect = "";         // sub-emitter: effect name fired at particle death
    public boolean collide = false;         // raycast particles against statics
    public boolean collideKill = true;      // kill on contact (false = bounce)
    public float collideBounce = 0.4f;      // restitution when bouncing
    public float collideRadius = 0.05f;

    boolean burstDone = false;
    com.hollow.game.EngineServices services() {
        return com.hollow.game.GameApp.instance != null ? com.hollow.game.GameApp.instance.services() : null;
    }
    @Override public void onAttach(){
        com.hollow.game.EngineServices s = services();
        if(s != null) s.particles.registerEmitter(this);
    }
    @Override public void onDetach(){
        com.hollow.game.EngineServices s = services();
        if(s != null) s.particles.unregisterEmitter(this);
    }
    @Override public void onUpdate(float dt){
        // Agent D: handled by ParticleLibrary.update; nothing here
    }
    @Override public void save(JsonW w){
        w.obj(); w.kv("type","ParticleEmitter").kv("sprite",sprite).kv("rate",rate).kv("burst",burst)
          .kvv("lifetime",lifeMin,lifeMax).kvv("speed",speedMin,speedMax).kv("spread",spreadDeg)
          .kvv("gravity",gravity.x,gravity.y,gravity.z).kv("drag",drag).kvv("size",sizeMin,sizeMax)
          .kv("maxCount",maxCount).kv("additive",additive).kv("localSpace",localSpace);
        if(!effect.isEmpty()) w.kv("effect",effect);
        if(trail){
            w.kv("trail",true).kv("trailMinDist",trailMinDist).kv("trailSpacing",trailSpacing);
        }
        if(!deathEffect.isEmpty()) w.kv("deathEffect",deathEffect);
        if(collide){
            w.kv("collide",true).kv("collideKill",collideKill).kv("collideBounce",collideBounce)
             .kv("collideRadius",collideRadius);
        }
        if(sizeCurve != null) w.kv("sizeCurve", Json.write(sizeCurve));
        if(colorCurve != null) w.kv("colorCurve", Json.write(colorCurve));
        w.endObj();
    }
    @Override public void load(JsonV n){
        sprite = n.str("sprite","");
        rate = n.flt("rate",10f); burst = n.int32("burst",0);
        com.hollow.core.Vec2 life = com.hollow.core.Json.vec2range(n.get("lifetime"), new com.hollow.core.Vec2(lifeMin, lifeMax));
        lifeMin = life.x; lifeMax = life.y;
        com.hollow.core.Vec2 spd = com.hollow.core.Json.vec2range(n.get("speed"), new com.hollow.core.Vec2(speedMin, speedMax));
        speedMin = spd.x; speedMax = spd.y;
        spreadDeg = n.flt("spread",25f);
        com.hollow.core.Vec3 d = com.hollow.core.Json.vec3(n.get("direction"), direction);
        direction.set(d);
        com.hollow.core.Vec3 g = com.hollow.core.Json.vec3(n.get("gravity"), gravity);
        gravity.set(g);
        drag = n.flt("drag",0.5f);
        com.hollow.core.Vec2 sz = com.hollow.core.Json.vec2range(n.get("size"), new com.hollow.core.Vec2(sizeMin, sizeMax));
        sizeMin = sz.x; sizeMax = sz.y;
        maxCount = n.int32("maxCount",256);
        additive = n.bool("additive",true);
        localSpace = n.bool("localSpace",false);
        effect = n.str("effect","");
        trail = n.bool("trail",false);
        trailMinDist = n.flt("trailMinDist",0.15f);
        trailSpacing = n.flt("trailSpacing",0.08f);
        deathEffect = n.str("deathEffect","");
        collide = n.bool("collide",false);
        collideKill = n.bool("collideKill",true);
        collideBounce = n.flt("collideBounce",0.4f);
        collideRadius = n.flt("collideRadius",0.05f);
        sizeCurve = n.list("sizeCurve");
        colorCurve = n.list("colorCurve");
    }
}
