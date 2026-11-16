package com.hollow.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.hollow.render.Frame;
import com.hollow.render.Renderer;
import com.hollow.scene.Scene;
import com.hollow.scene.ScriptHost;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

/** Hollow Vale on Android — the deterministic campaign runs live on a
 *  SurfaceView through the same software rasterizer the desktop CLI
 *  uses (Scene → ScriptHost.stepWorld → Renderer.render → Frame pixels
 *  → Bitmap). Touch controls: RESTART / PAUSE / SPEED / LOG.
 *  No anonymous classes or method refs: this d8 chokes on them. */
public class ValeActivity extends Activity {

    private static final int RW = 640, RH = 360;   // internal render size
    private static final int AMBER = 0xFFE8C878;
    private static final int[] SPEEDS = {1, 2, 4, 12};

    private SurfaceView surface;
    private TextView logText;
    private ScrollView logScroller;
    private Button pauseBtn, speedBtn;
    private final StringBuilder console = new StringBuilder();

    // game state, guarded by `state`
    private final Object state = new Object();
    private Scene scene;
    private ScriptHost host;
    private Renderer renderer;
    private boolean paused = false;
    private int speed = 4;            // sim frames per loop tick

    private volatile boolean running = true;
    private volatile boolean surfaceReady = false;
    private Thread gameThread;
    private Bitmap bmp;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();

    // ---------- named listeners (d8-safe) ----------

    private final class Cb implements SurfaceHolder.Callback {
        public void surfaceCreated(SurfaceHolder h){ surfaceReady = true; }
        public void surfaceChanged(SurfaceHolder h, int f, int w, int hh){ surfaceReady = true; }
        public void surfaceDestroyed(SurfaceHolder h){ surfaceReady = false; }
    }

    private static final class Act implements View.OnClickListener {
        private final Runnable r;
        Act(Runnable r){ this.r = r; }
        public void onClick(View v){ r.run(); }
    }

    private static final class LineSink implements Consumer<String> {
        private final ValeActivity a;
        LineSink(ValeActivity a){ this.a = a; }
        public void accept(String s){ a.onLine(s); }
    }

    private final class UiRefresh implements Runnable {
        public void run(){
            synchronized(console){ logText.setText(console.toString()); }
            logScroller.fullScroll(View.FOCUS_DOWN);
        }
    }

    private final class RestartAct implements Runnable { public void run(){ restart(); } }
    private final class PauseAct implements Runnable { public void run(){
        synchronized(state){ paused = !paused; }
        pauseBtn.setText(paused ? "RESUME" : "PAUSE");
    } }
    private final class SpeedAct implements Runnable { public void run(){
        synchronized(state){
            int i = 0; while(i < SPEEDS.length - 1 && SPEEDS[i] != speed) i++;
            speed = SPEEDS[(i + 1) % SPEEDS.length];
        }
        speedBtn.setText("SPEED x" + speed);
    } }
    private final class LogAct implements Runnable { public void run(){
        boolean show = logScroller.getVisibility() == View.GONE;
        logScroller.setVisibility(show ? View.VISIBLE : View.GONE);
    } }

    // ---------- lifecycle ----------

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        String dir = copyAssets("game");
        String sceneJson = readText(dir + "/scene.hscene");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(new Cb());
        root.addView(surface, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // mission log overlay (toggle)
        logScroller = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextColor(AMBER);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        logText.setTextSize(11);
        logText.setText("");
        logScroller.addView(logText);
        logScroller.setBackgroundColor(0xB8101014);
        logScroller.setVisibility(View.GONE);
        root.addView(logScroller, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // bottom touch bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0x80000000);
        bar.setGravity(android.view.Gravity.CENTER);
        bar.addView(btn("RESTART", new RestartAct()));
        pauseBtn = btn("PAUSE", new PauseAct());
        bar.addView(pauseBtn);
        speedBtn = btn("SPEED x4", new SpeedAct());
        bar.addView(speedBtn);
        bar.addView(btn("LOG", new LogAct()));
        root.addView(bar, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44), android.view.Gravity.BOTTOM));

        setContentView(root);

        synchronized(state){
            scene = new Scene();
            try { scene.load(sceneJson); } catch(Exception e){ throw new RuntimeException(e); }
            renderer = new Renderer(new File(dir).toPath());
            host = new ScriptHost(scene, new LineSink(this), new File(dir).toPath());
            host.start();
        }

        gameThread = new Thread(new GameLoop(), "vale-game");
        gameThread.start();
    }

    private Button btn(String label, Runnable action){
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(AMBER);
        b.setTextSize(13);
        b.setTypeface(android.graphics.Typeface.MONOSPACE);
        b.setBackgroundColor(0x00000000);
        b.setOnClickListener(new Act(action));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1);
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int v){
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** called by scripts via print()/say() — buffered, UI refreshed by the loop */
    void onLine(String line){
        synchronized(console){
            if(console.length() > 16000) console.setLength(0);
            console.append(line).append('\n');
        }
    }

    // ------------------------- game loop -------------------------

    private final class GameLoop implements Runnable {
        public void run(){
            long lastUi = 0;
            while(running){
                long t0 = System.nanoTime();
                Frame frame = null;
                if(surfaceReady){
                    synchronized(state){
                        if(!paused){
                            for(int i = 0; i < speed; i++) host.stepWorld(1.0 / 60.0);
                        }
                        try { frame = renderer.render(scene, RW, RH, host.hudLines); }
                        catch(Exception e){ /* keep last frame */ }
                    }
                }
                if(frame != null) draw(frame);

                // refresh log overlay at most ~4x per second
                long now = System.nanoTime();
                if(now - lastUi > 250_000_000L){
                    lastUi = now;
                    runOnUiThread(new UiRefresh());
                }
                long work = (System.nanoTime() - t0) / 1_000_000;
                try { Thread.sleep(Math.max(4, 16 - work)); } catch(InterruptedException e){ return; }
            }
        }
    }

    private void draw(Frame f){
        SurfaceHolder h = surface.getHolder();
        if(bmp == null) bmp = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);
        bmp.setPixels(f.pixels, 0, RW, 0, 0, RW, RH);
        Canvas c = null;
        try {
            c = h.lockCanvas();
            if(c == null) return;
            dst.set(0, 0, c.getWidth(), c.getHeight());
            c.drawColor(Color.BLACK);
            c.drawBitmap(bmp, null, dst, paint);
        } finally {
            if(c != null) h.unlockCanvasAndPost(c);
        }
    }

    private void restart(){
        synchronized(state){
            try {
                scene = new Scene();
                scene.load(readText(getFilesDir() + "/game/scene.hscene"));
                host = new ScriptHost(scene, new LineSink(this),
                                      new File(getFilesDir() + "/game").toPath());
                host.start();
                paused = false;
                pauseBtn.setText("PAUSE");
                onLine("— RESTARTED —");
            } catch(Exception e){ onLine("restart failed: " + e.getMessage()); }
        }
    }

    // ------------------------- assets -------------------------

    /** recursively copy assets/<name> → filesDir/<name>; returns target dir */
    private String copyAssets(String name){
        File out = new File(getFilesDir(), name);
        try { copyDir(getAssets(), name, out); }
        catch(Exception e){ throw new RuntimeException("asset copy failed: " + e.getMessage()); }
        return out.getAbsolutePath();
    }

    private static void copyDir(android.content.res.AssetManager am, String path, File out)
            throws Exception{
        String[] items = am.list(path);
        if(items == null || items.length == 0){
            File dstFile = new File(out.getPath());
            dstFile.getParentFile().mkdirs();
            try (InputStream in = am.open(path); OutputStream os = new FileOutputStream(dstFile)){
                byte[] buf = new byte[8192]; int n;
                while((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return;
        }
        out.mkdirs();
        for(String it : items) copyDir(am, path + "/" + it, new File(out, it));
    }

    private static String readText(String path){
        try { return new String(java.nio.file.Files.readAllBytes(new File(path).toPath()), "UTF-8"); }
        catch(Exception e){ throw new RuntimeException("read failed: " + path); }
    }

    @Override protected void onDestroy(){
        running = false;
        if(gameThread != null) gameThread.interrupt();
        super.onDestroy();
    }
}
