package com.hollow.game;

import com.hollow.core.Log;
import com.hollow.core.Time;
import com.hollow.core.Vec3;
import com.hollow.scene.Entity;
import com.hollow.scene.Scene;
import com.hollow.scene.World;

/** Application lifecycle — the engine main loop. Games subclass this.
 *  Works headless (CLI simulation) and on-device (Android GL thread calls the same methods). */
public abstract class GameApp {
    public static GameApp instance;

    public EngineServices svc;
    public World world;
    public Entity player;
    public Entity activeCamera;
    public String currentScenePath = "";
    /** renderer is injected on device (null in headless mode) */
    public Object renderer;   // typed as Object to keep desktop-safe; runtime casts to PbrRenderer

    protected boolean running = true;
    private float fadeAlpha = 0f;         // 0 = clear, 1 = black
    private float fadeTarget = 0f, fadeSpeed = 1f;
    private String pendingScene = null;
    private Runnable pendingAfterLoad = null;
    public boolean cinematicMode = false;

    // ------------- lifecycle -------------
    /** called once after services are built */
    public abstract void onStart();

    /** per fixed logic step (world.update already ran) */
    public void onFixedUpdate(float dt) {}

    /** per rendered/simulated frame */
    public void onFrameUpdate(float dt) {}

    public void requestExit(){ running = false; }
    public boolean isRunning(){ return running; }

    // ------------- boot -------------
    public final void boot(EngineServices services){
        instance = this;
        this.svc = services;
        services.app = this;
        EngineBootstrap.registerAll(services);
        onStart();
        loadScene(svc.config.entryScene, null);
    }

    // ------------- scene -------------
    public final void loadScene(String path, Runnable afterLoad){
        pendingScene = path;
        pendingAfterLoad = afterLoad;
        fadeTarget = 1f;
        fadeSpeed = 3f;
    }

    protected final void performSceneLoad(String path, Runnable afterLoad){
        try {
            long t0 = System.nanoTime();
            World w = Scene.load(path, svc.fs);
            this.world = w;
            this.currentScenePath = path;
            this.activeCamera = null;   // v2.5.1: onWorldLoaded re-picks; stale camera streamed the old scene
            EngineBootstrap.onWorldLoaded(services(), w);
            long t1 = System.nanoTime();
            Log.i("GameApp", "scene loaded: " + path + " entities=" + w.count() + " in " + (t1-t0)/1_000_000 + "ms");
            if(afterLoad != null) afterLoad.run();
        } catch(Exception e){
            Log.e("GameApp", "scene load failed: " + path, e);
        }
    }

    public EngineServices services(){ return svc; }

    // ------------- fades -------------
    public void fadeOut(float speed){ fadeTarget = 1f; fadeSpeed = speed; }
    public void fadeIn(float speed){ fadeTarget = 0f; fadeSpeed = speed; }
    public float fadeAlpha(){ return fadeAlpha; }

    // ------------- main pipeline (call once per frame) -------------
    public final void frame(){
        if(svc == null) return;
        svc.time.begin();

        // fade / pending scene
        fadeAlpha = com.hollow.core.MathX.approach(fadeAlpha, fadeTarget, fadeSpeed * svc.time.deltaTime);
        if(pendingScene != null && fadeAlpha >= 0.999f){
            String p = pendingScene; Runnable r = pendingAfterLoad;
            pendingScene = null; pendingAfterLoad = null;
            performSceneLoad(p, r);
            fadeIn(2f);
        }
        if(world == null) return;

        // fixed logic steps
        int steps = svc.time.stepsDue();
        for(int i=0;i<steps;i++){
            svc.time.advanceFixed();
            svc.frameSeed = 0x9E3779B97F4A7C15L ^ (svc.time.tick * 0xB5297A4D);
            int z;
            z = com.hollow.core.Profiler.begin("anim");
            svc.anim.update(svc.time.fixedDelta, world);
            com.hollow.core.Profiler.end(z);
            z = com.hollow.core.Profiler.begin("ai");
            svc.ai.update(svc.time.fixedDelta, world);
            com.hollow.core.Profiler.end(z);
            z = com.hollow.core.Profiler.begin("physics");
            svc.physics.update(svc.time.fixedDelta);
            com.hollow.core.Profiler.end(z);
            z = com.hollow.core.Profiler.begin("particles");
            svc.particles.update(svc.time.fixedDelta);
            com.hollow.core.Profiler.end(z);
            z = com.hollow.core.Profiler.begin("sequencer");
            svc.sequencer.update(svc.time.fixedDelta);
            com.hollow.core.Profiler.end(z);
            z = com.hollow.core.Profiler.begin("audio");
            svc.audio.update(svc.time.fixedDelta);
            com.hollow.core.Profiler.end(z);
            z = com.hollow.core.Profiler.begin("world");
            world.update(svc.time.fixedDelta);
            com.hollow.core.Profiler.end(z);
            // v2.0 world streaming (throttled internally)
            z = com.hollow.core.Profiler.begin("streaming");
            Vec3 listener = activeCamera != null ? activeCamera.worldPosition()
                          : (player != null ? player.worldPosition() : null);
            if(listener != null) svc.streaming.update(svc.time.fixedDelta, world, listener);
            com.hollow.core.Profiler.end(z);
            onFixedUpdate(svc.time.fixedDelta);
        }
        svc.events.flush();

        // per-frame systems
        svc.ui.update(svc.time.deltaTime);
        svc.input.update(svc.time.deltaTime);
        com.hollow.core.Profiler.frame();
        onFrameUpdate(svc.time.deltaTime);
        svc.time.frame++;
        // MEM-012: per-frame scratch arena resets automatically at frame end
        com.hollow.core.FrameArena.endFrame();
    }

    // ------------- helpers for subclasses -------------
    public final Entity findEntity(String name){ return world != null ? world.find(name) : null; }
    public final Object getVar(String k){ return svc.vars.get(k); }
    public final void setVar(String k, Object v){ svc.vars.set(k, v); }

    /** set by the platform (Android) each frame */
    public int surfaceWidth = 1280, surfaceHeight = 720;
}
