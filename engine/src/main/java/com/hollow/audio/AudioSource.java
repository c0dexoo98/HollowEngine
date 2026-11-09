package com.hollow.audio;

import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.scene.Component;

/** Positional audio source. STUB — Agent D implements. API FINAL. */
public final class AudioSource extends Component {
    public String asset = "";
    public boolean playOnStart = false;
    public boolean loop = true;
    public float volume = 1f;
    public int bus = AudioEngine.BUS_AMBIENT;
    public float minDist = 1f, maxDist = 20f;

    transient AudioEngine.Handle handle;
    transient boolean startedPlaying = false;

    public void play(){
        AudioEngine a = engine();
        if(a != null && !asset.isEmpty()){
            AudioEngine.PlayParams p = new AudioEngine.PlayParams().bus(bus).volume(volume)
                    .at(entity != null ? entity.position : new com.hollow.core.Vec3()).range(minDist, maxDist);
            if(loop) p.loop();
            handle = a.play(asset, p);
        }
    }
    public void stop(){ if(handle != null) handle.stop(); }
    private AudioEngine engine(){
        return com.hollow.game.GameApp.instance != null ? com.hollow.game.GameApp.instance.services().audio : null;
    }

    @Override public void onUpdate(float dt){
        AudioEngine a = engine();
        if(a == null || entity == null) return;
        // playOnStart: kick off on the first logic tick after the world is live
        if(playOnStart && !startedPlaying){
            startedPlaying = true;
            play();
        }
        // 3D: refresh voice position each tick so panning/attenuation follow the entity
        if(handle != null && handle.voice != null) handle.voice.position.set(entity.position);
        // auto-stop tracking for non-loop clips once they finish
        if(handle != null && !loop && !handle.isPlaying()) handle = null;
    }
    @Override public void save(JsonW w){
        w.obj(); w.kv("type","AudioSource").kv("asset",asset).kv("loop",loop)
          .kv("volume",volume).kv("bus",bus).kv("minDist",minDist).kv("maxDist",maxDist)
          .kv("playOnStart",playOnStart); w.endObj();
    }
    @Override public void load(JsonV n){
        asset = n.str("asset",""); loop = n.bool("loop",true);
        volume = n.flt("volume",1f); bus = n.int32("bus", AudioEngine.BUS_AMBIENT);
        minDist = n.flt("minDist",1f); maxDist = n.flt("maxDist",20f);
        playOnStart = n.bool("playOnStart", false);
    }
}
