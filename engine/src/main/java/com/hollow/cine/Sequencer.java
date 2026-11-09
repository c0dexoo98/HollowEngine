package com.hollow.cine;

import com.hollow.anim.Animator;
import com.hollow.assets.AudioClipData;
import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Quat;
import com.hollow.core.Vec3;
import com.hollow.scene.CameraComponent;
import com.hollow.scene.Entity;
import com.hollow.scene.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Timeline cutscene player (Sequencer). Deterministic evaluation from JSON:
 *  {duration, letterbox, tracks:[camera|transform|anim|event|sound|music|fade|caption]}
 *  While playing the framework disables player input (reads isPlaying()/activeCameraEntity). */
public final class Sequencer {
    public boolean playing = false;
    public String activeCameraEntity = null;   // while playing, framework uses this camera
    public float letterbox = 0f;               // 0..1 animated by sequencer
    public float fadeColorAlpha = 0f;

    // ---- timeline data ----
    private float duration = 0;
    private boolean letterboxEnabled = true;
    private String camEntityName = "CineCam";
    private final ArrayList<CamKey> camKeys = new ArrayList<>();
    private final ArrayList<Trk> transformTracks = new ArrayList<>();
    private final ArrayList<AnimTrk> animTracks = new ArrayList<>();
    private final ArrayList<Ev> eventTracks = new ArrayList<>();
    private final ArrayList<Snd> soundTracks = new ArrayList<>();
    private String musicAsset = null;
    private float musicVolume = 1f;
    private final ArrayList<FadeTrk> fadeTracks = new ArrayList<>();
    private final ArrayList<Cap> captionTracks = new ArrayList<>();
    private final ArrayList<LtTrk> lightTracks = new ArrayList<>();   // LGT-018

    // ---- runtime ----
    private float t = 0;
    private World world;
    private Runnable onComplete;
    private Entity cineCam;
    private Entity prevCamera;
    private com.hollow.audio.AudioEngine.Handle musicHandle;

    private static final class CamKey {
        float t; Vec3 pos; Vec3 rot; float fov;
    }
    private static final class TrKey {
        float t; Vec3 pos; Vec3 rot; Vec3 scl;
    }
    private static final class Trk {
        String target; final ArrayList<TrKey> keys = new ArrayList<>();
    }
    private static final class AnimTrk { String target; String clip; boolean loop; }
    private static final class Ev { float t; String name; Map<String, Object> data; boolean fired; }
    private static final class Snd { float t; String asset; float volume; boolean fired; }
    private static final class FadeTrk { float t; float duration; boolean fadeIn; }
    private static final class Cap { float t; float duration; String text; boolean fired; }
    /** v2.2 LGT-018: animated light intensity/ color keys */
    private static final class LtKey { float t; Float intensity; Vec3 color; }
    private static final class LtTrk {
        String target; final ArrayList<LtKey> keys = new ArrayList<>();
    }

    // ---------------- load ----------------

    public void load(String path, com.hollow.core.FileSystem fs){
        playing = false;
        camKeys.clear(); transformTracks.clear(); animTracks.clear();
        eventTracks.clear(); soundTracks.clear(); fadeTracks.clear(); captionTracks.clear();
        lightTracks.clear();
        musicAsset = null; musicVolume = 1f;
        duration = 0; letterboxEnabled = true; camEntityName = "CineCam";
        if(fs == null || path == null){ Log.w("Cine", "load: missing fs/path"); return; }
        try {
            Map<String, Object> root = Json.parseObj(fs.readText(path));
            duration = (float) Json.num(root.get("duration"), 0);
            letterboxEnabled = Json.bool(root.get("letterbox"), true);
            List<Object> tracks = Json.arr(root.get("tracks"));
            if(tracks == null) return;
            for(Object o : tracks){
                Map<String, Object> tr = Json.obj(o);
                if(tr == null) continue;
                String type = Json.str(tr.get("type"), "");
                switch(type){
                    case "camera": {
                        camEntityName = Json.str(tr.get("entity"), "CineCam");
                        List<Object> keys = Json.arr(tr.get("keys"));
                        if(keys != null) for(Object k : keys){
                            Map<String, Object> kn = Json.obj(k);
                            if(kn == null) continue;
                            CamKey ck = new CamKey();
                            ck.t = (float) Json.num(kn.get("t"), 0);
                            ck.pos = Json.vec3(kn.get("position"), new Vec3());
                            ck.rot = Json.vec3(kn.get("rotationEuler"), new Vec3());
                            ck.fov = (float) Json.num(kn.get("fov"), 60);
                            camKeys.add(ck);
                        }
                        break;
                    }
                    case "transform": {
                        Trk trk = new Trk();
                        trk.target = Json.str(tr.get("target"), "");
                        List<Object> keys = Json.arr(tr.get("keys"));
                        if(keys != null) for(Object k : keys){
                            Map<String, Object> kn = Json.obj(k);
                            if(kn == null) continue;
                            TrKey tk = new TrKey();
                            tk.t = (float) Json.num(kn.get("t"), 0);
                            tk.pos = Json.vec3(kn.get("position"), null);
                            tk.rot = Json.vec3(kn.get("rotationEuler"), null);
                            tk.scl = Json.vec3(kn.get("scale"), null);
                            trk.keys.add(tk);
                        }
                        transformTracks.add(trk);
                        break;
                    }
                    case "anim": {
                        AnimTrk a = new AnimTrk();
                        a.target = Json.str(tr.get("target"), "");
                        a.clip = Json.str(tr.get("clip"), "");
                        a.loop = Json.bool(tr.get("loop"), false);
                        animTracks.add(a);
                        break;
                    }
                    case "event": {
                        Ev e = new Ev();
                        e.t = (float) Json.num(tr.get("t"), 0);
                        e.name = Json.str(tr.get("name"), "");
                        e.data = Json.obj(tr.get("data"));
                        eventTracks.add(e);
                        break;
                    }
                    case "sound": {
                        Snd s = new Snd();
                        s.t = (float) Json.num(tr.get("t"), 0);
                        s.asset = Json.str(tr.get("asset"), "");
                        s.volume = (float) Json.num(tr.get("volume"), 1);
                        soundTracks.add(s);
                        break;
                    }
                    case "music": {
                        musicAsset = Json.str(tr.get("asset"), null);
                        musicVolume = (float) Json.num(tr.get("volume"), 1);
                        break;
                    }
                    case "fade": {
                        FadeTrk f = new FadeTrk();
                        f.t = (float) Json.num(tr.get("t"), 0);
                        f.duration = Math.max(1e-3f, (float) Json.num(tr.get("duration"), 0.5f));
                        f.fadeIn = Json.bool(tr.get("fadeIn"), false);
                        fadeTracks.add(f);
                        break;
                    }
                    case "light": {                       // LGT-018
                        LtTrk lt = new LtTrk();
                        lt.target = Json.str(tr.get("target"), "");
                        List<Object> keys = Json.arr(tr.get("keys"));
                        if(keys != null) for(Object k : keys){
                            Map<String, Object> kn = Json.obj(k);
                            if(kn == null) continue;
                            LtKey lk = new LtKey();
                            lk.t = (float) Json.num(kn.get("t"), 0);
                            if(kn.containsKey("intensity")) lk.intensity = (float) Json.num(kn.get("intensity"), 1);
                            lk.color = Json.vec3(kn.get("color"), null);
                            lt.keys.add(lk);
                        }
                        lightTracks.add(lt);
                        break;
                    }
                    case "caption": {
                        Cap c = new Cap();
                        c.t = (float) Json.num(tr.get("t"), 0);
                        c.duration = (float) Json.num(tr.get("duration"), 2);
                        c.text = Json.str(tr.get("text"), "");
                        captionTracks.add(c);
                        break;
                    }
                    default:
                        Log.w("Cine", "unknown track type '" + type + "' in " + path);
                }
            }
            Log.i("Cine", "loaded " + path + " (duration=" + duration + "s, tracks=" + tracks.size() + ")");
        } catch(Exception e){
            Log.e("Cine", "load failed: " + path + " — " + e);
        }
    }

    // ---------------- play / stop ----------------

    public void play(World world, Runnable onComplete){
        this.world = world;
        this.onComplete = onComplete;
        this.t = 0;
        this.playing = true;
        this.letterbox = 0;
        for(Ev e : eventTracks) e.fired = false;
        for(Snd s : soundTracks) s.fired = false;
        for(Cap c : captionTracks) c.fired = false;

        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        prevCamera = app != null ? app.activeCamera : null;

        // cine camera (spawn if absent) + route framework camera through it
        if(world != null){
            cineCam = world.find(camEntityName);
            if(cineCam == null){
                cineCam = world.spawn(camEntityName);
                cineCam.add(new CameraComponent());
            }
        }
        activeCameraEntity = camEntityName;
        if(app != null && cineCam != null) app.activeCamera = cineCam;

        // kick animation tracks
        for(AnimTrk a : animTracks){
            Entity e = world != null ? world.find(a.target) : null;
            Animator an = e != null ? e.get(Animator.class) : null;
            if(an != null){
                an.loop = a.loop;
                an.play(a.clip);
            } else Log.w("Cine", "anim track: no Animator on '" + a.target + "'");
        }

        // music
        com.hollow.game.EngineServices svc = services();
        if(musicAsset != null && !musicAsset.isEmpty() && svc != null && svc.audio != null){
            musicHandle = svc.audio.play(musicAsset, new com.hollow.audio.AudioEngine.PlayParams()
                    .bus(com.hollow.audio.AudioEngine.BUS_MUSIC).volume(musicVolume).loop());
        }
        Log.i("Cine", "playing (" + duration + "s)");
    }

    public void stop(){
        playing = false;
        activeCameraEntity = null;
        letterbox = 0;
        fadeColorAlpha = 0;
        if(musicHandle != null){ musicHandle.stop(); musicHandle = null; }
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        if(app != null && prevCamera != null) app.activeCamera = prevCamera;
        prevCamera = null;
        cineCam = null;
    }

    // ---------------- update ----------------

    public void update(float dt){
        if(!playing) return;
        t += dt;
        letterbox = letterboxEnabled ? MathX.approach(letterbox, 1f, dt / 0.5f) : 0;

        com.hollow.game.EngineServices svc = services();

        // camera track
        if(cineCam != null && !camKeys.isEmpty()) applyCamera();
        // transform tracks
        for(Trk trk : transformTracks) applyTransform(trk);
        // light tracks (LGT-018)
        for(LtTrk lt : lightTracks) applyLight(lt);
        // events (fire once, in track order)
        for(Ev e : eventTracks){
            if(!e.fired && t >= e.t){
                e.fired = true;
                if(svc != null && svc.events != null) svc.events.fire(e.name, e.data);
            }
        }
        // sounds (once)
        for(Snd s : soundTracks){
            if(!s.fired && t >= s.t){
                s.fired = true;
                if(svc != null && svc.audio != null && !s.asset.isEmpty())
                    svc.audio.play(s.asset, new com.hollow.audio.AudioEngine.PlayParams()
                            .bus(com.hollow.audio.AudioEngine.BUS_SFX).volume(s.volume));
            }
        }
        // captions → toasts (guarded for headless)
        if(svc != null && svc.ui != null){
            for(Cap c : captionTracks){
                if(!c.fired && t >= c.t){
                    c.fired = true;
                    svc.ui.toasts().showToast(c.text, c.duration);
                }
            }
        }
        // fade overlay (later tracks override earlier ones)
        float alpha = 0;
        for(FadeTrk f : fadeTracks){
            if(t >= f.t){
                float p = MathX.clamp((t - f.t) / f.duration, 0, 1);
                alpha = f.fadeIn ? 1 - p : p;
            }
        }
        fadeColorAlpha = alpha;

        if(duration > 0 && t >= duration){
            stop();
            if(svc != null && svc.events != null) svc.events.fire("cinematic_end");
            if(onComplete != null){ try { onComplete.run(); } catch(Exception e){ Log.w("Cine", "onComplete failed: " + e); } }
        }
    }

    public boolean isPlaying(){ return playing; }
    public float time(){ return t; }
    public float durationSeconds(){ return duration; }

    // ---------------- key evaluation ----------------

    private void applyCamera(){
        int hi = 0;
        while(hi < camKeys.size() && camKeys.get(hi).t < t) hi++;
        Vec3 pos, rot;
        float fov;
        if(hi <= 0){ CamKey k = camKeys.get(0); pos = k.pos; rot = k.rot; fov = k.fov; }
        else if(hi >= camKeys.size()){ CamKey k = camKeys.get(camKeys.size() - 1); pos = k.pos; rot = k.rot; fov = k.fov; }
        else {
            CamKey a = camKeys.get(hi - 1), b = camKeys.get(hi);
            float f = MathX.clamp((t - a.t) / Math.max(1e-5f, b.t - a.t), 0, 1);
            pos = new Vec3(MathX.lerp(a.pos.x, b.pos.x, f), MathX.lerp(a.pos.y, b.pos.y, f), MathX.lerp(a.pos.z, b.pos.z, f));
            rot = new Vec3(MathX.lerp(a.rot.x, b.rot.x, f), MathX.lerp(a.rot.y, b.rot.y, f), MathX.lerp(a.rot.z, b.rot.z, f));
            fov = MathX.lerp(a.fov, b.fov, f);
        }
        cineCam.position.set(pos);
        cineCam.rotation.set(Quat.fromEuler(rot.x, rot.y, rot.z));
        cineCam.markDirtyDeep();
        CameraComponent cam = cineCam.get(CameraComponent.class);
        if(cam != null) cam.fovY = fov;
    }

    private void applyTransform(Trk trk){
        Entity e = world != null ? world.find(trk.target) : null;
        if(e == null || trk.keys.isEmpty()) return;
        int hi = 0;
        while(hi < trk.keys.size() && trk.keys.get(hi).t < t) hi++;
        TrKey a, b;
        if(hi <= 0){ a = b = trk.keys.get(0); }
        else if(hi >= trk.keys.size()){ a = b = trk.keys.get(trk.keys.size() - 1); }
        else { a = trk.keys.get(hi - 1); b = trk.keys.get(hi); }
        float f = a == b ? 0 : MathX.clamp((t - a.t) / Math.max(1e-5f, b.t - a.t), 0, 1);
        if(a.pos != null && b.pos != null)
            e.position.set(MathX.lerp(a.pos.x, b.pos.x, f), MathX.lerp(a.pos.y, b.pos.y, f), MathX.lerp(a.pos.z, b.pos.z, f));
        if(a.rot != null && b.rot != null)
            e.rotation.set(Quat.fromEuler(MathX.lerp(a.rot.x, b.rot.x, f), MathX.lerp(a.rot.y, b.rot.y, f), MathX.lerp(a.rot.z, b.rot.z, f)));
        if(a.scl != null && b.scl != null)
            e.scale.set(MathX.lerp(a.scl.x, b.scl.x, f), MathX.lerp(a.scl.y, b.scl.y, f), MathX.lerp(a.scl.z, b.scl.z, f));
        e.markDirtyDeep();
    }

    /** v2.2 LGT-018: light intensity/color keyframes */
    private void applyLight(LtTrk lt){
        Entity e = world != null ? world.find(lt.target) : null;
        if(e == null || lt.keys.isEmpty()) return;
        com.hollow.scene.LightComponent lc = e.get(com.hollow.scene.LightComponent.class);
        if(lc == null) return;
        int hi = 0;
        while(hi < lt.keys.size() && lt.keys.get(hi).t < t) hi++;
        LtKey a, b;
        if(hi <= 0){ a = b = lt.keys.get(0); }
        else if(hi >= lt.keys.size()){ a = b = lt.keys.get(lt.keys.size() - 1); }
        else { a = lt.keys.get(hi - 1); b = lt.keys.get(hi); }
        float f = a == b ? 0 : MathX.clamp((t - a.t) / Math.max(1e-5f, b.t - a.t), 0, 1);
        if(a.intensity != null && b.intensity != null)
            lc.intensity = MathX.lerp(a.intensity, b.intensity, f);
        if(a.color != null && b.color != null)
            lc.color.set(MathX.lerp(a.color.x, b.color.x, f), MathX.lerp(a.color.y, b.color.y, f), MathX.lerp(a.color.z, b.color.z, f), 1f);
    }

    private static com.hollow.game.EngineServices services(){
        return com.hollow.game.GameApp.instance != null ? com.hollow.game.GameApp.instance.services() : null;
    }
}
