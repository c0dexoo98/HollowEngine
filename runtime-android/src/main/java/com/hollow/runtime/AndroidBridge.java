package com.hollow.runtime;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import com.hollow.assets.AssetDB;
import com.hollow.core.Log;
import com.hollow.core.Vec2;
import com.hollow.game.EngineBootstrap;
import com.hollow.game.EngineServices;
import com.hollow.game.GameConfig;
import com.hollow.render.PbrRenderer;
import com.hollow.render.RendererConfig;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** GL thread bridge: boots the engine, renders every frame, feeds touch input. */
final class AndroidBridge implements GLSurfaceView.Renderer {
    private final EngineActivity activity;
    EngineServices svc;
    RuntimeGame app;
    final com.hollow.game.InputActions input = new com.hollow.game.InputActions();

    private final PbrRenderer renderer = new PbrRenderer();
    private boolean booted = false;

    // touch state (virtual stick left half / look right half)
    private int movePointer = -1, lookPointer = -1;
    private final Vec2 moveOrigin = new Vec2();
    private final Vec2 lookLast = new Vec2();

    AndroidBridge(EngineActivity activity){ this.activity = activity; }

    // ------------- GLSurfaceView.Renderer -------------
    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config){
        if(booted) return;
        booted = true;
        try {
            boot();
        } catch(Exception e){
            Log.e("Runtime", "boot failed", e);
        }
    }

    private void boot(){
        Log.i("Runtime", "booting HollowEngine on Android");
        AndroidPlatform.AndroidImageDecoder.install();
        com.hollow.assets.OggDecoder.setImplementation((raw, name) -> AndroidOggDecoder.decode(raw, name));

        Context ctx = activity;
        AndroidPlatform.AssetFS fs = new AndroidPlatform.AssetFS(ctx, "project");
        GameConfig cfg = GameConfig.load(fs, "");
        EngineServices services = EngineBootstrap.boot(fs, "", new AndroidAudioOut());

        app = new RuntimeGame();
        input.loadMapping(fs, "");
        services.input = input;

        app.boot(services);
        renderer.init(1280, 720, RendererConfig.fromGameConfig(cfg.quality));
        svc = services;
        com.hollow.ui.TextShaper shaper = new AndroidTextShaper();
        services.ui.text = shaper;
        Log.i("Runtime", "boot complete: " + cfg.title);
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height){
        app.surfaceWidth = width;
        app.surfaceHeight = height;
        renderer.resize(width, height);
    }

    @Override public void onDrawFrame(GL10 gl){
        if(app == null || svc == null) return;
        try {
            app.frame();  // fixed-step logic + per-frame systems
            com.hollow.scene.Entity cam = app.activeCamera != null ? app.activeCamera : findCamera();
            if(cam != null && app.world != null){
                renderer.render(app.world, cam, svc);
            }
        } catch(Exception e){
            Log.e("Runtime", "frame error", e);
        }
    }

    private com.hollow.scene.Entity findCamera(){
        if(app.world == null) return null;
        for(com.hollow.scene.Entity e : app.world.entities){
            com.hollow.scene.CameraComponent c = e.get(com.hollow.scene.CameraComponent.class);
            if(c != null && c.active) return e;
        }
        return null;
    }

    // ------------- touch input (called on UI thread by activity) -------------
    void onTouch(MotionEvent ev){
        if(app == null || svc == null) return;
        int action = ev.getActionMasked();
        int w = Math.max(1, app.surfaceWidth), h = Math.max(1, app.surfaceHeight);
        switch(action){
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = ev.getActionIndex();
                float x = ev.getX(idx), y = ev.getY(idx);
                if(x < w * 0.45f && movePointer < 0){
                    movePointer = ev.getPointerId(idx);
                    moveOrigin.set(x, y);
                    svc.input.pointerDown = true;
                } else if(lookPointer < 0){
                    lookPointer = ev.getPointerId(idx);
                    lookLast.set(x, y);
                }
                svc.input.pointerX = x / w; svc.input.pointerY = y / h;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for(int i=0;i<ev.getPointerCount();i++){
                    int id = ev.getPointerId(i);
                    float x = ev.getX(i), y = ev.getY(i);
                    if(id == movePointer){
                        float dx = x - moveOrigin.x, dy = y - moveOrigin.y;
                        float maxR = w * 0.12f;
                        float len = (float)Math.sqrt(dx*dx + dy*dy);
                        if(len > maxR){ dx = dx/len*maxR; dy = dy/len*maxR; }
                        svc.input.touchMove.set(dx / maxR, dy / maxR);
                        svc.input.setButton("move_forward", dy < -maxR*0.3f);
                        svc.input.setButton("move_back", dy > maxR*0.3f);
                        svc.input.setButton("move_left", dx < -maxR*0.3f);
                        svc.input.setButton("move_right", dx > maxR*0.3f);
                    } else if(id == lookPointer){
                        float dx = x - lookLast.x, dy = y - lookLast.y;
                        lookLast.set(x, y);
                        svc.input.touchLook.set(dx / w, dy / h);
                        svc.input.setButton("look_x", Math.abs(dx) > 2);
                        svc.input.setButton("look_y", Math.abs(dy) > 2);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int idx = ev.getActionIndex();
                int id = ev.getPointerId(idx);
                if(id == movePointer){
                    movePointer = -1;
                    svc.input.touchMove.set(0,0);
                    svc.input.setButton("move_forward", false);
                    svc.input.setButton("move_back", false);
                    svc.input.setButton("move_left", false);
                    svc.input.setButton("move_right", false);
                    svc.input.pointerDown = false;
                } else if(id == lookPointer){
                    lookPointer = -1;
                    svc.input.setButton("look_x", false);
                    svc.input.setButton("look_y", false);
                }
                break;
            }
        }
    }
}
