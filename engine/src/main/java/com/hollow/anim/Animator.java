package com.hollow.anim;

import com.hollow.assets.AnimClip;
import com.hollow.assets.ModelAsset;
import com.hollow.assets.Skeleton;
import com.hollow.core.JsonV;
import com.hollow.core.JsonW;
import com.hollow.core.Mat4;
import com.hollow.core.MathX;
import com.hollow.core.Quat;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Skeletal animation player (Agent C implementation).
 *
 *  - Loads skeleton + clips lazily from svc.assets.model(modelPath) (Skeleton via
 *    ModelAsset.skins[0], AnimClip via ModelAsset.clips).
 *  - play(clip) starts at t=0; a crossfade blends the previous clip (fade out) with the
 *    new clip (fade in): translations lerped, rotations slerped, scales lerped.
 *  - Per joint: sample TRS -> Mat4.trs local -> walk Skeleton parent hierarchy into world
 *    space -> multiply inverseBind -> write column-major skinning matrices into
 *    jointMatrices (16 * jointCount, capped at 64 joints).
 *  - Models without a skin leave jointMatrices null (static mesh).
 *  - Non-looping clips stop at the end and fire "anim_end" {clip} on the EventBus
 *    (null-safe: skipped when no services/event bus are available).
 */
public final class Animator extends Component {
    public String modelPath = "";
    public String currentClip = "";
    public boolean loop = true;
    public float speed = 1f;
    public float crossfade = 0.25f;
    public boolean playing = false;
    /** joint matrices (skin-space) written each update; length 16*jointCount */
    public float[] jointMatrices = null;

    // --- v2.0: blend space playback ---------------------------------
    private BlendSpace blendSpace;
    private String blendSpacePath = "";
    public float blendX, blendY;              // parameter values (set per-frame)
    private boolean blendActive = false;
    private final BlendSpace.Weighted bw = new BlendSpace.Weighted();
    private final BlendSpace.Weighted prevBw = new BlendSpace.Weighted();

    // --- v2.0: anim notifies -----------------------------------------
    public static final class Notify {
        public String clip;
        public float t;
        public String event;
    }
    public final List<Notify> notifies = new ArrayList<>();
    private float lastNotifyTime = -1f;

    // --- v2.0: foot IK hook ------------------------------------------
    private FootIK footIK;

    /** play a blend space (anim/blendspace JSON). Subsequent frames: update blendX/blendY.
     *  Set speed=1, loop=true. Clips crossfade in via blend weights automatically. */
    public void playBlendSpace(String path){
        com.hollow.game.EngineServices svc = services();
        if(svc == null || path == null || path.isEmpty()) return;
        BlendSpace bs = svc.assets.blendSpace(path);
        if(bs == null || bs.samples.isEmpty()){
            com.hollow.core.Log.w("Animator", "blendspace not found: " + path);
            return;
        }
        blendSpace = bs;
        blendSpacePath = path;
        blendActive = true;
        playing = true;
        endFired = false;
        restWritten = false;
        prevClip = "";
        fadeDur = 0f;
    }

    public void stopBlendSpace(){
        blendActive = false;
        blendSpace = null;
    }

    public boolean isBlendActive(){ return blendActive && blendSpace != null; }
    public String blendSpacePath(){ return blendSpacePath; }

    // --- runtime (not serialized) ---
    private boolean loaded;
    private Skeleton skeleton;
    private Map<String, AnimClip> clips;
    private int jointCount;

    private float time;
    private String prevClip = "";
    private float prevTime;
    private float fadeT, fadeDur;
    private boolean endFired;
    private boolean restWritten;

    private Mat4[] localMats, worldMats;
    private Mat4[] invBind;
    private Vec3[] sPos, sScl;
    private Quat[] sRot;
    private final Mat4 scratch = new Mat4();
    private final float[] tr = new float[3], sc = new float[3];

    public void play(String clip){ play(clip, crossfade); }

    /** start clip at t=0, crossfading from the currently playing clip over fade seconds */
    public void play(String clip, float fade){
        if(clip == null || clip.isEmpty()) return;
        if(clip.equals(currentClip) && playing) return;
        prevClip = currentClip;
        prevTime = time;
        fadeDur = Math.max(0f, fade);
        fadeT = 0f;
        currentClip = clip;
        time = 0f;
        playing = true;
        endFired = false;
        restWritten = false;
    }

    public void stop(){ playing = false; }

    public boolean hasClip(String clip){
        if(entity == null || modelPath.isEmpty()) return false;
        return services() != null && services().assets.clip(modelPath, clip) != null;
    }

    com.hollow.game.EngineServices services(){
        return com.hollow.game.GameApp.instance != null ? com.hollow.game.GameApp.instance.services() : null;
    }

    // ------------------------------------------------------------------
    private void ensureLoaded(){
        if(loaded) return;
        loaded = true;
        com.hollow.game.EngineServices svc = services();
        if(svc == null || modelPath.isEmpty()) return;
        ModelAsset m = svc.assets.model(modelPath);
        if(m == null) return;
        skeleton = m.skins.isEmpty() ? null : m.skins.get(0);
        clips = m.clips;
        if(skeleton != null){
            jointCount = Math.min(64, skeleton.jointCount());
            localMats = new Mat4[jointCount];
            worldMats = new Mat4[jointCount];
            invBind = new Mat4[jointCount];
            sPos = new Vec3[jointCount];
            sScl = new Vec3[jointCount];
            sRot = new Quat[jointCount];
            for(int i = 0; i < jointCount; i++){
                localMats[i] = new Mat4();
                worldMats[i] = new Mat4();
                invBind[i] = new Mat4();
                System.arraycopy(skeleton.joints.get(i).inverseBind, 0, invBind[i].m, 0, 16);
                sPos[i] = new Vec3();
                sScl[i] = new Vec3(1,1,1);
                sRot[i] = new Quat();
            }
        }
    }

    @Override public void onUpdate(float dt){
        if(entity == null) return;
        ensureLoaded();
        if(skeleton == null) return;    // static mesh: jointMatrices stays null
        if(jointMatrices == null || jointMatrices.length != 16 * jointCount)
            jointMatrices = new float[16 * jointCount];

        if(!playing){
            if(!restWritten){ writeRestPose(); restWritten = true; }
            return;
        }

        AnimClip clip = currentClip != null ? clips.get(currentClip) : null;
        if(clip == null) clip = fallbackClip();
        if(clip == null){ writeRestPose(); restWritten = true; playing = false; return; }

        // ---- blend space path: weighted multi-clip sampling ----------------
        if(blendActive && blendSpace != null){
            BlendSpace.Weighted wres = blendSpace.sample(blendX, blendY);
            animateBlend(wres, dt);
            fireNotifies(dt);
            return;
        }

        time += dt * speed;
        if(clip.duration > 0f){
            if(loop){
                time %= clip.duration;
            } else if(time >= clip.duration){
                time = clip.duration;
                if(!endFired){
                    endFired = true;
                    playing = false;
                    fireAnimEnd();
                }
            }
        }

        // crossfade bookkeeping
        boolean blending = false;
        AnimClip prev = null;
        float w = 1f;
        if(fadeDur > 0f && fadeT < fadeDur && prevClip != null && !prevClip.isEmpty()){
            prev = clips.get(prevClip);
            if(prev != null){
                blending = true;
                w = MathX.clamp(fadeT / fadeDur, 0f, 1f);
            }
        }
        fadeT += dt;
        if(prev != null && prev.duration > 0f){
            prevTime += dt * speed;
            if(prevTime > prev.duration) prevTime = loop ? prevTime % prev.duration : prev.duration;
        }

        // sample: defaults to rest pose, channels override
        for(int i = 0; i < jointCount; i++){
            Skeleton.Joint j = skeleton.joints.get(i);
            sPos[i].set(j.restPos);
            sRot[i].set(j.restRot);
            sScl[i].set(j.restScale);
        }
        sampleClip(clip, time);
        if(blending) blendClip(prev, prevTime, w);

        applyFootIK();

        // local matrices
        for(int i = 0; i < jointCount; i++)
            localMats[i].set(Mat4.trs(sPos[i], sRot[i], sScl[i]));

        // parent multiply (parents usually precede children; fixpoint sweep for any order)
        computeWorld();

        for(int i = 0; i < jointCount; i++){
            scratch.set(worldMats[i]).mul(invBind[i]);
            System.arraycopy(scratch.m, 0, jointMatrices, 16 * i, 16);
        }
        if(!blending) prevClip = "";
        fireNotifies(dt);
    }

    /** v2.0: weighted blend-space animation (up to 4 clips, inverse-distance weights) */
    private void animateBlend(BlendSpace.Weighted wr, float dt){
        if(wr.count == 0){ writeRestPose(); restWritten = true; return; }
        // advance per-clip clocks
        for(int i = 0; i < wr.count; i++){
            AnimClip c = clips.get(wr.clips[i]);
            if(c == null) continue;
            Float tObj = clipClocks.get(wr.clips[i]);
            float t = (tObj == null ? 0f : tObj) + dt * speed * wr.baseSpeeds[i];
            if(c.duration > 0f) t %= c.duration;
            clipClocks.put(wr.clips[i], t);
        }
        // rest pose defaults
        for(int i = 0; i < jointCount; i++){
            Skeleton.Joint j = skeleton.joints.get(i);
            sPos[i].set(j.restPos);
            sRot[i].set(j.restRot);
            sScl[i].set(j.restScale);
        }
        // accumulate weighted samples, normalized by total weight (rest pose only fills
        // channels no clip animates — a lerp-from-rest chain leaks rest weight into blends)
        float wSum = 0f;
        for(int i = 0; i < wr.count; i++) wSum += wr.weights[i];
        if(wSum <= 1e-5f) wSum = 1e-5f;
        float[] accPos = new float[jointCount * 3];
        float[] accScl = new float[jointCount * 3];
        float[] accRot = new float[jointCount * 4];
        float[] chWeight = new float[jointCount];
        float[] chRotWeight = new float[jointCount];   // P2: rotation-only channels never fed chWeight
        Quat q2 = new Quat();
        for(int i = 0; i < wr.count; i++){
            AnimClip c = clips.get(wr.clips[i]);
            if(c == null) continue;
            float t = clipClocks.getOrDefault(wr.clips[i], 0f);
            float wgt = wr.weights[i];
            for(Map.Entry<Integer, AnimClip.Channel> e : c.channels.entrySet()){
                int ji = e.getKey();
                if(ji < 0 || ji >= jointCount) continue;
                AnimClip.Channel ch = e.getValue();
                if(ch.times == null || ch.times.length == 0) continue;
                if(ch.translations != null && ch.translations.length >= 3){
                    c.sampleTranslation(ch, t, tr);
                    accPos[ji*3] += tr[0]*wgt; accPos[ji*3+1] += tr[1]*wgt; accPos[ji*3+2] += tr[2]*wgt;
                    chWeight[ji] += wgt;
                }
                if(ch.scales != null && ch.scales.length >= 3){
                    c.sampleScale(ch, t, sc);
                    accScl[ji*3] += sc[0]*wgt; accScl[ji*3+1] += sc[1]*wgt; accScl[ji*3+2] += sc[2]*wgt;
                }
                if(ch.rotations != null && ch.rotations.length >= 4){
                    c.sampleRotation(ch, t, bwQuat);
                    // weighted quat blend: nlerp accumulation (stable at gameplay scale).
                    // P2: gate the first-touch on accRot alone — chWeight is fed by the TRANSLATION
                    // branch, so rotation-only joints never reached the accumulate path and their
                    // animated rotation was dropped by the final normalize (frozen joints).
                    if(accRot[ji*4] == 0f && accRot[ji*4+1] == 0f && accRot[ji*4+2] == 0f && accRot[ji*4+3] == 0f){
                        accRot[ji*4]=bwQuat.x*wgt; accRot[ji*4+1]=bwQuat.y*wgt; accRot[ji*4+2]=bwQuat.z*wgt; accRot[ji*4+3]=bwQuat.w*wgt;
                    } else {
                        q2.set(accRot[ji*4], accRot[ji*4+1], accRot[ji*4+2], accRot[ji*4+3]);
                        if(q2.dot(bwQuat) < 0f){ bwQuat.x=-bwQuat.x; bwQuat.y=-bwQuat.y; bwQuat.z=-bwQuat.z; bwQuat.w=-bwQuat.w; }
                        accRot[ji*4]  += bwQuat.x*wgt; accRot[ji*4+1] += bwQuat.y*wgt;
                        accRot[ji*4+2] += bwQuat.z*wgt; accRot[ji*4+3] += bwQuat.w*wgt;
                    }
                    chRotWeight[ji] += wgt;
                }
            }
        }
        for(int ji = 0; ji < jointCount; ji++){
            float w = chWeight[ji];
            if(w > 1e-5f){
                sPos[ji].set(accPos[ji*3]/w, accPos[ji*3+1]/w, accPos[ji*3+2]/w);
                sScl[ji].set(accScl[ji*3]/w, accScl[ji*3+1]/w, accScl[ji*3+2]/w);
            }
            if(chRotWeight[ji] > 1e-5f){   // P2: normalize by the ROTATION weight, not the translation weight
                sRot[ji].set(accRot[ji*4], accRot[ji*4+1], accRot[ji*4+2], accRot[ji*4+3]).norm();
            }
            // joints no clip touches keep the rest pose set above
        }
        applyFootIK();
        for(int i = 0; i < jointCount; i++)
            localMats[i].set(Mat4.trs(sPos[i], sRot[i], sScl[i]));
        computeWorld();
        for(int i = 0; i < jointCount; i++){
            scratch.set(worldMats[i]).mul(invBind[i]);
            System.arraycopy(scratch.m, 0, jointMatrices, 16 * i, 16);
        }
        // remember weights for notify association
        prevBw.count = wr.count;
        for(int i = 0; i < wr.count; i++){
            prevBw.clips[i] = wr.clips[i];
            prevBw.weights[i] = wr.weights[i];
            prevBw.baseSpeeds[i] = wr.baseSpeeds[i];
        }
    }

    private final java.util.HashMap<String, Float> clipClocks = new java.util.HashMap<>();
    private final Quat bwQuat = new Quat();

    /** v2.0: fire notifies whose time was crossed this frame (current clip or blend clips) */
    private void fireNotifies(float dt){
        if(notifies.isEmpty()) return;
        com.hollow.game.EngineServices svc = services();
        if(svc == null || svc.events == null) return;
        float now = isBlendActive() ? blendClock() : time;
        String curClip = isBlendActive() ? "" : currentClip;
        if(now < lastNotifyTime){ lastNotifyTime = -1f; }         // loop wrap
        for(Notify n : notifies){
            if(n.event == null || n.event.isEmpty()) continue;
            if(!curClip.isEmpty() && n.clip != null && !n.clip.isEmpty() && !n.clip.equals(curClip)) continue;
            if(n.t > lastNotifyTime && n.t <= now){
                Map<String,Object> data = new java.util.LinkedHashMap<>();
                data.put("event", n.event);
                data.put("clip", n.clip == null ? curClip : n.clip);
                svc.events.fire(n.event, data);
            }
        }
        lastNotifyTime = now;
    }

    /** pseudo-clock for blend mode (max contributing clip clock) — notifies use it */
    private float blendClock(){
        float mx = 0f;
        for(Float f : clipClocks.values()) if(f != null && f > mx) mx = f;
        return mx;
    }

    /** v2.0: world-space transform of a bone (socket). null if bone unknown. */
    public boolean boneWorldTransform(String boneName, Mat4 out){
        if(entity == null || skeleton == null || worldMats == null) return false;
        int ji = skeleton.indexOf(boneName);
        if(ji < 0 || ji >= jointCount) return false;
        entity.updateWorld();
        out.set(entity.worldMatrix).mul(worldMats[ji]);
        return true;
    }

    /** v2.0: FootIK hook — shifts hip joint vertically by raycast result */
    private void applyFootIK(){
        if(footIK == null) footIK = entity != null ? entity.get(FootIK.class) : null;
        if(footIK == null || !footIK.enabled) return;
        int hip = footIK.hipJoint(skeleton);
        if(hip < 0) return;
        float off = footIK.consumeHipOffset();
        if(Math.abs(off) > 1e-5f) sPos[hip].y += off;
    }

    private void sampleClip(AnimClip clip, float t){
        for(Map.Entry<Integer, AnimClip.Channel> e : clip.channels.entrySet()){
            int ji = e.getKey();
            if(ji < 0 || ji >= jointCount) continue;
            AnimClip.Channel c = e.getValue();
            if(c.times == null || c.times.length == 0) continue;
            if(c.translations != null && c.translations.length >= 3){
                clip.sampleTranslation(c, t, tr);
                sPos[ji].set(tr[0], tr[1], tr[2]);
            }
            if(c.scales != null && c.scales.length >= 3){
                clip.sampleScale(c, t, sc);
                sScl[ji].set(sc[0], sc[1], sc[2]);
            }
            if(c.rotations != null && c.rotations.length >= 4) clip.sampleRotation(c, t, sRot[ji]);
        }
    }

    /** blend prev clip pose INTO current pose by weight w (prev fades out, current fades in) */
    private void blendClip(AnimClip prev, float t, float w){
        for(Map.Entry<Integer, AnimClip.Channel> e : prev.channels.entrySet()){
            int ji = e.getKey();
            if(ji < 0 || ji >= jointCount) continue;
            AnimClip.Channel c = e.getValue();
            if(c.times == null || c.times.length == 0) continue;
            if(c.translations != null && c.translations.length >= 3){
                prev.sampleTranslation(c, t, tr);
                sPos[ji].set(MathX.lerp(tr[0], sPos[ji].x, w), MathX.lerp(tr[1], sPos[ji].y, w), MathX.lerp(tr[2], sPos[ji].z, w));
            }
            if(c.scales != null && c.scales.length >= 3){
                prev.sampleScale(c, t, sc);
                sScl[ji].set(MathX.lerp(sc[0], sScl[ji].x, w), MathX.lerp(sc[1], sScl[ji].y, w), MathX.lerp(sc[2], sScl[ji].z, w));
            }
            if(c.rotations != null && c.rotations.length >= 4){
                Quat pq = new Quat();
                prev.sampleRotation(c, t, pq);
                // result = pq slerped toward current by w (pq fades out)
                pq.slerp(sRot[ji], w);
                sRot[ji].set(pq);
            }
        }
    }

    private AnimClip fallbackClip(){
        if(clips == null || clips.isEmpty()) return null;
        return clips.values().iterator().next();
    }

    private void computeWorld(){
        boolean[] done = new boolean[jointCount];
        int remaining = jointCount;
        while(remaining > 0){
            boolean progress = false;
            for(int i = 0; i < jointCount; i++){
                if(done[i]) continue;
                int p = skeleton.joints.get(i).parent;
                if(p < 0 || p >= jointCount){
                    worldMats[i].set(localMats[i]);
                } else if(done[p]){
                    worldMats[i].set(worldMats[p]).mul(localMats[i]);
                } else continue;
                done[i] = true;
                progress = true;
                remaining--;
            }
            if(!progress){ // cyclic guard: treat remaining as roots
                for(int i = 0; i < jointCount; i++)
                    if(!done[i]) worldMats[i].set(localMats[i]);
                break;
            }
        }
    }

    private void writeRestPose(){
        for(int i = 0; i < jointCount; i++){
            Skeleton.Joint j = skeleton.joints.get(i);
            localMats[i].set(Mat4.trs(j.restPos, j.restRot, j.restScale));
        }
        computeWorld();
        for(int i = 0; i < jointCount; i++){
            scratch.set(worldMats[i]).mul(invBind[i]);
            System.arraycopy(scratch.m, 0, jointMatrices, 16 * i, 16);
        }
    }

    private void fireAnimEnd(){
        com.hollow.game.EngineServices svc = services();
        if(svc == null || svc.events == null) return;
        Map<String,Object> data = new java.util.LinkedHashMap<>();
        data.put("clip", currentClip);
        svc.events.fire("anim_end", data);
    }

    @Override public void save(JsonW w){
        w.obj(); w.kv("type","Animator").kv("model",modelPath).kv("clip",currentClip)
          .kv("loop",loop).kv("speed",speed).kv("crossfade",crossfade).kv("autoplay",playing);
        if(blendSpacePath != null && !blendSpacePath.isEmpty()) w.kv("blendSpace",blendSpacePath);
        if(!notifies.isEmpty()){
            w.key("notifies"); w.arr();
            for(Notify n : notifies){
                w.obj(); w.kv("clip", n.clip == null ? "" : n.clip).kv("t", n.t).kv("event", n.event); w.endObj();
            }
            w.endArr();
        }
        w.endObj();
    }
    @Override public void load(JsonV n){
        modelPath = n.str("model", n.str("modelPath", ""));
        currentClip = n.str("clip","");
        loop = n.bool("loop", true);
        speed = n.flt("speed",1f);
        crossfade = n.flt("crossfade",0.25f);
        playing = n.bool("autoplay", n.bool("playing", false));
        String bs = n.str("blendSpace","");
        if(!bs.isEmpty()){
            blendSpacePath = bs;
            playBlendSpace(bs);
        }
        for(Object o : n.list("notifies")){
            if(!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String,Object> m = (Map<String,Object>)o;
            Notify nn = new Notify();
            nn.clip = com.hollow.core.Json.str(m.get("clip"), "");
            nn.t = (float)com.hollow.core.Json.num(m.get("t"), 0);
            nn.event = com.hollow.core.Json.str(m.get("event"), "");
            notifies.add(nn);
        }
        if(playing && !currentClip.isEmpty() && !isBlendActive()) play(currentClip);
    }
}
