package com.hollow.anim;

import com.hollow.assets.AnimClip;
import com.hollow.assets.Skeleton;
import com.hollow.core.JsonW;
import com.hollow.core.MathX;
import com.hollow.core.Quat;
import com.hollow.core.Vec3;
import com.hollow.scene.Component;
import com.hollow.scene.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.3 animation suite (Batch 3) — ANI-013..ANI-025.
 *  <pre>
 *  ANI-013 Retarget          map source-skeleton clips onto a different skeleton
 *  ANI-014 AdditiveLayer     difference-clip overlay (breathing on walk) with weight
 *  ANI-015 RootMotion        extract root translation from a clip, apply to entity
 *  ANI-016 ClipCompressor    quantized clip codec (16-bit rot, fixed trans) + max error
 *  ANI-017 ProceduralLean    tilt a bone toward velocity/acceleration
 *  ANI-018 LipSync           viseme weights from an amplitude envelope
 *  ANI-019 FacialRig         32 named blendshape slots with weights (renderer-readable)
 *  ANI-020 MultiClipBlender  N-clip weighted blend (base + 3 overlays, normalized)
 *  ANI-021 MultiChainIK      run several TwoBone/LookAt chains in priority order
 *  ANI-022 DriftCorrection   re-anchor root motion to the logical path (spring)
 *  ANI-023 NotifyPayload     notifies with custom data payload (EventBus dispatch)
 *  ANI-024 BoneSprings       spring-damper secondary motion on leaf bones
 *  ANI-025 PoseDump          sample a clip at time t and dump joint transforms (CLI)
 *  </pre>
 *  All blocks are deterministic and headless-testable: sample functions are pure
 *  functions of (clip, t); systems keep no wall clock.
 */
public final class AnimationV23 {
    private AnimationV23() {}

    // ==================================================================
    // ANI-013 — retargeting
    // ==================================================================
    public static final class Retarget {
        /** source joint name -> target joint name (same-skeleton-structure assumption) */
        public final Map<String, String> boneMap = new LinkedHashMap<>();
        public float translationScale = 1f;      // height ratio target/source
        public float rotationWeight = 1f;        // keep some of the source pose style

        /** auto-map by normalized name matching (strip prefixes: mixamorig:, Bip01_, etc.) */
        public static Retarget autoMap(Skeleton src, Skeleton dst){
            Retarget r = new Retarget();
            for(Skeleton.Joint sj : src.joints){
                String key = normalize(sj.name);
                for(Skeleton.Joint dj : dst.joints){
                    if(normalize(dj.name).equals(key)){ r.boneMap.put(sj.name, dj.name); break; }
                }
            }
            return r;
        }

        private static String normalize(String n){
            String s = n.toLowerCase();
            int i = s.lastIndexOf(':');
            if(i >= 0) s = s.substring(i + 1);
            if(s.startsWith("bip01_")) s = s.substring(6);
            if(s.startsWith("bip01")) s = s.substring(5);
            return s.trim();
        }

        /** resample src clip onto dst skeleton channels (translation scaled, rotation kept) */
        public AnimClip apply(AnimClip src, Skeleton srcSkel, Skeleton dstSkel){
            AnimClip out = new AnimClip();
            out.name = src.name + "_retargeted";
            out.duration = src.duration;
            out.loop = src.loop;
            for(Map.Entry<Integer, AnimClip.Channel> kv : src.channels.entrySet()){
                int srcJointIdx = kv.getKey();
                if(srcJointIdx < 0 || srcJointIdx >= srcSkel.jointCount()) continue;
                String srcName = srcSkel.joints.get(srcJointIdx).name;
                String dstName = boneMap.get(srcName);
                if(dstName == null) continue;
                int dstIdx = dstSkel.indexOf(dstName);
                if(dstIdx < 0) continue;
                AnimClip.Channel sc = kv.getValue();
                AnimClip.Channel dc = new AnimClip.Channel();
                dc.times = sc.times;
                dc.interpolation = sc.interpolation;
                if(sc.translations != null){
                    int n = sc.translations.length / 3;
                    dc.translations = new float[n * 3];
                    for(int i = 0; i < n; i++){
                        dc.translations[i * 3]     = sc.translations[i * 3] * translationScale;
                        dc.translations[i * 3 + 1] = sc.translations[i * 3 + 1] * translationScale;
                        dc.translations[i * 3 + 2] = sc.translations[i * 3 + 2] * translationScale;
                    }
                }
                if(sc.rotations != null){
                    dc.rotations = sc.rotations.clone();   // quaternion rotations transfer 1:1
                }
                if(sc.scales != null) dc.scales = sc.scales.clone();
                out.channels.put(dstIdx, dc);
            }
            return out;
        }
    }

    // ==================================================================
    // ANI-014 — additive layer
    // ==================================================================
    public static final class AdditiveLayer {
        public String clipName;                  // difference clip (authored additive)
        public float weight = 1f;
        public boolean autoDerive = true;        // derive difference vs rest pose

        /** additive local TRS for joint i at time t: delta = pose - rest (rot via Quat diff) */
        private final float[] scratch3 = new float[3];
        public boolean sample(AnimClip clip, Skeleton skel, int jointIdx, float t,
                              Quat baseLocal, Vec3 outTransDelta, Quat outRotDelta){
            if(clip == null || skel == null || weight <= 0f) return false;
            AnimClip.Channel c = clip.channels.get(jointIdx);
            if(c == null) return false;
            Vec3 pos = new Vec3();
            if(c.translations != null){
                clip.sampleTranslation(c, t, scratch3);
                pos.set(scratch3[0], scratch3[1], scratch3[2]);
            }
            Quat rot = new Quat();
            if(c.rotations != null) clip.sampleRotation(c, t, rot);
            Skeleton.Joint j = skel.joints.get(jointIdx);
            // derive difference against rest pose
            if(autoDerive){
                outTransDelta.set(pos).sub(j.restPos);
                // rot delta = clipRot * inverse(restRot)
                Quat invRest = j.restRot.invert();
                outRotDelta.set(rot.mul(invRest)).norm();
            } else {
                outTransDelta.set(pos);
                outRotDelta.set(rot);
            }
            outTransDelta.scale(weight);
            // slerp identity -> delta by weight
            Quat identity = new Quat();
            outRotDelta.set(identity.slerp(outRotDelta, weight)).norm();
            return true;
        }
    }

    // ==================================================================
    // ANI-015 — root motion
    // ==================================================================
    public static final class RootMotion {
        public String rootJoint = "root";
        public boolean applyYaw = true;
        public float scale = 1f;
        private final Vec3 accumulated = new Vec3();
        private float accumulatedYaw = 0f;

        /** extract root translation delta over [t0,t1); call per frame BEFORE sampling */
        public Vec3 extractDelta(AnimClip clip, Skeleton skel, float t0, float t1, float dt, Vec3 out){
            out.set(0, 0, 0);
            if(clip == null || skel == null || dt <= 0f) return out;
            int idx = skel.indexOf(rootJoint);
            if(idx < 0) idx = 0;
            AnimClip.Channel c = clip.channels.get(idx);
            if(c == null || c.translations == null) return out;
            float[] a = new float[3], b = new float[3];
            clip.sampleTranslation(c, t0 % Math.max(0.01f, clip.duration), a);
            clip.sampleTranslation(c, t1 % Math.max(0.01f, clip.duration), b);
            out.set(b[0] - a[0], b[1] - a[1], b[2] - a[2]).scale(scale);
            accumulated.add(out);
            return out;
        }

        /** drive the entity: position += delta (rotation.y += yaw delta if channel exists) */
        public void apply(Entity entity, Vec3 delta, float yawDeltaDeg){
            if(entity == null) return;
            entity.position.add(delta);
            if(applyYaw && Math.abs(yawDeltaDeg) > 1e-4f){
                Quat q = Quat.fromAxisAngle(0, 1, 0, yawDeltaDeg);
                q.mul(entity.rotation);
                entity.rotation.set(q).norm();
            }
            entity.markDirtyDeep();
        }

        /** ANI-022 companion: total accumulated root motion since reset */
        public Vec3 accumulated(Vec3 out){ return out.set(accumulated); }
        public void reset(){ accumulated.set(0, 0, 0); accumulatedYaw = 0f; }
        public float accumulatedYaw(){ return accumulatedYaw; }
    }

    // ==================================================================
    // ANI-016 — clip compression (quantize)
    // ==================================================================
    public static final class ClipCompressor {
        public static final class Result {
            public short[] rotQ = new short[0];       // 4×snorm16 per key
            public short[] transQ = new short[0];     // 3×fixed (scale 1/1000 m)
            public int keys;
            public float maxRotErrorDeg;
            public float maxTransErrorM;
            public float compressionRatio;
        }

        /** quantize every channel; returns packed arrays + measured error */
        public Result compress(AnimClip clip){
            Result r = new Result();
            List<Short> rot = new ArrayList<>();
            List<Short> trans = new ArrayList<>();
            int keys = 0;
            float maxRotErr = 0f, maxTransErr = 0f;
            int origFloats = 0, packedShorts = 0;
            for(AnimClip.Channel c : clip.channels.values()){
                if(c.rotations != null){
                    int n = c.rotations.length / 4;
                    keys = Math.max(keys, n);
                    origFloats += n * 4;
                    packedShorts += n * 4;
                    for(int i = 0; i < n; i++){
                        Quat q = new Quat(c.rotations[i*4], c.rotations[i*4+1], c.rotations[i*4+2], c.rotations[i*4+3]);
                        Quat back = fromSnorm16(
                            toSnorm16(q.x), toSnorm16(q.y), toSnorm16(q.z), toSnorm16(q.w));
                        maxRotErr = Math.max(maxRotErr, q.dist(back) * 2f);   // approx deg/2
                        rot.add(toSnorm16(q.x)); rot.add(toSnorm16(q.y));
                        rot.add(toSnorm16(q.z)); rot.add(toSnorm16(q.w));
                    }
                }
                if(c.translations != null){
                    int n = c.translations.length / 3;
                    origFloats += n * 3;
                    packedShorts += n * 3;
                    for(int i = 0; i < n * 3; i++){
                        short q = toFixed16(c.translations[i], 1000f);
                        float back = fromFixed16(q, 1000f);
                        maxTransErr = Math.max(maxTransErr, Math.abs(back - c.translations[i]));
                        trans.add(q);
                    }
                }
            }
            r.keys = keys;
            r.rotQ = new short[rot.size()];
            for(int i = 0; i < rot.size(); i++) r.rotQ[i] = rot.get(i);
            r.transQ = new short[trans.size()];
            for(int i = 0; i < trans.size(); i++) r.transQ[i] = trans.get(i);
            r.maxRotErrorDeg = maxRotErr;
            r.maxTransErrorM = maxTransErr;
            r.compressionRatio = packedShorts == 0 ? 1f : (origFloats * 4f) / (packedShorts * 2f);
            return r;
        }

        public static short toSnorm16(float v){
            float c = MathX.clamp(v, -1f, 1f);
            return (short) Math.round(c * 32767f);
        }
        public static float fromSnorm16(short s){ return s / 32767f; }
        public static Quat fromSnorm16(short x, short y, short z, short w){
            return new Quat(fromSnorm16(x), fromSnorm16(y), fromSnorm16(z), fromSnorm16(w)).norm();
        }
        public static short toFixed16(float v, float scale){
            return (short) Math.round(MathX.clamp(v * scale, -32767f, 32767f));
        }
        public static float fromFixed16(short s, float scale){ return s / scale; }
    }

    // ==================================================================
    // ANI-017 — procedural lean (velocity tilt)
    // ==================================================================
    public static final class ProceduralLean {
        public String targetBone = "spine";
        public float maxLeanDeg = 12f;
        public float speedScale = 0.12f;         // lean per (m/s)
        public float accelScale = 0.9f;          // extra lean on acceleration (start/stop)
        public float smoothing = 8f;
        private float leanX = 0f, leanZ = 0f;    // smoothed lean angles (deg)
        private Vec3 lastVel = new Vec3();

        /** update from entity velocity; returns the lean rotation to compose onto the bone */
        public Quat update(Entity entity, float dt, Quat out){
            Vec3 vel = entity.worldPosition().cpy().sub(entity.worldPosition());
            // use CharacterController velocity when available, else estimate from position delta
            com.hollow.physics.CharacterController cc = entity.get(com.hollow.physics.CharacterController.class);
            if(cc != null) vel.set(cc.getVelocity());
            Vec3 accel = vel.cpy().sub(lastVel).scale(1f / Math.max(0.001f, dt));
            lastVel.set(vel);
            float speed = vel.len();
            // lean toward movement: forward tilt in local Z (speed), side tilt in X (accel lateral)
            float wantX = MathX.clamp(accel.x * accelScale, -maxLeanDeg, maxLeanDeg);
            float wantZ = MathX.clamp(speed * speedScale, 0f, maxLeanDeg);
            leanX = MathX.approach(leanX, wantX, Math.max(0.001f, smoothing * Math.abs(wantX - leanX)) * dt + 0.001f);
            leanZ = MathX.approach(leanZ, wantZ, Math.max(0.001f, smoothing * Math.abs(wantZ - leanZ)) * dt + 0.001f);
            Quat q = Quat.fromAxisAngle(1, 0, 0, leanZ);
            Quat q2 = Quat.fromAxisAngle(0, 0, 1, leanX);
            q.mul(q2).norm();
            out.set(q);
            return out;
        }

        public float leanForwardDeg(){ return leanZ; }
        public float leanSideDeg(){ return leanX; }
        public void reset(){ leanX = leanZ = 0f; lastVel.set(0, 0, 0); }
    }

    // ==================================================================
    // ANI-018 — lip sync (viseme from amplitude)
    // ==================================================================
    public static final class LipSync {
        /** viseme slots: 0=sil, 1=AI, 2=E, 3=O, 4=U, 5=MBP, 6=FV, 7=L — 8 weights */
        public static final int VISEMES = 8;
        public float sensitivity = 3.2f;
        public float smoothing = 14f;
        private final float[] weights = new float[VISEMES];
        private float env = 0f, envFast = 0f, prevEnv = 0f;

        /** feed one frame of mono amplitude (0..1) — typically AudioSource level meter */
        public void update(float amplitude, float dt, float[] outWeights){
            env = MathX.approach(env, MathX.clamp(amplitude * sensitivity, 0f, 1f), Math.max(0.001f, 24f * dt));
            envFast = MathX.approach(envFast, env, Math.max(0.001f, 60f * dt));
            float openness = envFast;
            float attack = Math.max(0f, env - prevEnv) / Math.max(0.001f, dt);   // 0..~1
            prevEnv = env;
            // map envelope dynamics to viseme weights (cheap vowel approximation)
            float sil = 1f - openness;
            float w1 = openness * (0.7f + 0.3f * Math.min(1f, attack));          // AI (wide open)
            float w2 = openness * 0.5f;                                           // E
            float w3 = openness * (attack > 0.4f ? 0.6f : 0.35f);                 // O
            float w4 = openness * 0.25f;                                          // U
            float w5 = Math.min(1f, attack * 1.4f) * (openness > 0.15f ? 1f : 0f);// MBP (closed bursts)
            float w6 = openness * 0.3f * Math.min(1f, attack + 0.2f);             // FV
            float w7 = openness * 0.4f;                                           // L
            float[] target = { sil, w1, w2, w3, w4, w5, w6, w7 };
            float k = Math.max(0.001f, smoothing * dt);
            float sum = 0f;
            for(int i = 0; i < VISEMES; i++){
                weights[i] = MathX.approach(weights[i], target[i], k);
                sum += weights[i];
            }
            if(sum > 1e-4f) for(int i = 0; i < VISEMES; i++) weights[i] /= sum;
            if(outWeights != null) System.arraycopy(weights, 0, outWeights, 0, VISEMES);
        }

        public float[] weights(){ return weights; }
        public int dominantViseme(){
            int best = 0;
            for(int i = 1; i < VISEMES; i++) if(weights[i] > weights[best]) best = i;
            return best;
        }
        public void reset(){ java.util.Arrays.fill(weights, 0f); env = envFast = prevEnv = 0f; }
    }

    // ==================================================================
    // ANI-019 — facial rig (32 blendshapes)
    // ==================================================================
    public static final class FacialRig extends Component {
        public static final int SHAPES = 32;
        public static final String[] NAMES = {
            "browInnerUp","browDownL","browDownR","browOuterUpL","browOuterUpR",
            "eyeLookUpL","eyeLookUpR","eyeLookDownL","eyeLookDownR","eyeLookInL","eyeLookInR",
            "eyeLookOutL","eyeLookOutR","eyeBlinkL","eyeBlinkR","eyeSquintL","eyeSquintR",
            "noseSneer","cheekSquintL","cheekSquintR","mouthClose","mouthFunnel","mouthPucker",
            "mouthSmileL","mouthSmileR","mouthFrownL","mouthFrownR","mouthShrugUpper",
            "jawOpen","jawForward","viseme_aa","viseme_oo"};
        public final float[] weights = new float[SHAPES];
        public final LipSync lips = new LipSync();     // ANI-018 integration

        public boolean set(String name, float w){
            for(int i = 0; i < SHAPES; i++)
                if(NAMES[i].equalsIgnoreCase(name)){ weights[i] = MathX.clamp(w, 0f, 1f); return true; }
            return false;
        }
        public float get(String name){
            for(int i = 0; i < SHAPES; i++)
                if(NAMES[i].equalsIgnoreCase(name)) return weights[i];
            return 0f;
        }
        /** drive jawOpen + visemes from the lip sync weights */
        public void applyLipSync(float amplitude, float dt){
            float[] w = new float[LipSync.VISEMES];
            lips.update(amplitude, dt, w);
            set("jawOpen", w[1] * 0.8f + w[2] * 0.5f);
            set("viseme_aa", w[1] + w[2]);
            set("viseme_oo", w[3] + w[4]);
            set("mouthPucker", w[3] * 0.7f);
            set("mouthClose", w[5]);
        }
        /** blink helper (idle life): close+open over duration */
        public void blink(float t, float duration, boolean both){
            float k = (float)Math.sin(Math.PI * MathX.clamp(t / duration, 0f, 1f));
            if(both){ set("eyeBlinkL", k); set("eyeBlinkR", k); }
        }
        /** expose to renderer: packed meta on the entity (skin morph pipeline reads it) */
        @Override public void onUpdate(float dt){
            if(entity == null) return;
            entity.setMeta("faceWeights", weights);
        }
        public int activeCount(){ int n = 0; for(float w : weights) if(w > 0.01f) n++; return n; }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type", "FacialRig");
            w.key("weights"); w.arr();
            for(float v : weights) w.valueRaw(String.valueOf(v));
            w.endArr();
            w.endObj();
        }
        @Override public void load(com.hollow.core.JsonV n){
            Object arr = n.get("weights");
            if(arr instanceof List<?> l){
                int i = 0;
                for(Object o : l){
                    if(i >= SHAPES) break;
                    weights[i++] = o instanceof Number num ? num.floatValue() : 0f;
                }
            }
        }
    }

    // ==================================================================
    // ANI-020 — multi-clip blender (3+ simultaneous)
    // ==================================================================
    public static final class MultiClipBlender {
        public static final class Layer {
            public AnimClip clip;
            public float weight = 1f;
            public float time = 0f;
            public float speed = 1f;
            public boolean additive = false;      // additive layers skip normalization
        }
        public final List<Layer> layers = new ArrayList<>();
        public String targetBone;                 // optional single-joint blend (else all)

        public Layer add(AnimClip clip, float weight, boolean additive){
            Layer l = new Layer();
            l.clip = clip; l.weight = weight; l.additive = additive;
            layers.add(l);
            return l;
        }

        /** advance all layer clocks (deterministic) */
        public void update(float dt){
            for(Layer l : layers){
                if(l.clip == null || l.weight <= 0f) continue;
                l.time += dt * l.speed;
                if(l.clip.loop) l.time %= Math.max(0.01f, l.clip.duration);
                else l.time = Math.min(l.time, Math.max(0.01f, l.clip.duration));
            }
        }

        /** blended pose for one joint: weighted rotation (slerp chain) + weighted translation */
        public boolean sample(int jointIdx, float[] outTrans, Quat outRot){
            float wSum = 0f;
            for(Layer l : layers) if(!l.additive && l.clip != null && l.clip.channels.containsKey(jointIdx)) wSum += l.weight;
            if(wSum < 1e-5f) return false;
            Vec3 acc = new Vec3();
            Quat accQ = new Quat();
            boolean first = true;
            float written = 0f;
            for(Layer l : layers){
                if(l.additive || l.clip == null) continue;
                AnimClip.Channel c = l.clip.channels.get(jointIdx);
                if(c == null) continue;
                float w = l.weight / wSum;
                if(c.translations != null){
                    float[] tmp = new float[3];
                    l.clip.sampleTranslation(c, l.time, tmp);
                    acc.x += tmp[0] * w; acc.y += tmp[1] * w; acc.z += tmp[2] * w;
                }
                if(c.rotations != null){
                    Quat q = new Quat();
                    l.clip.sampleRotation(c, l.time, q);
                    if(first){ accQ.set(q); first = false; }
                    else accQ.slerp(q, w);              // sequential slerp = weighted blend
                }
                written += w;
            }
            if(outTrans != null) outTrans[0] = acc.x;
            if(outTrans != null && outTrans.length > 1){ outTrans[1] = acc.y; outTrans[2] = acc.z; }
            outRot.set(accQ).norm();
            return written > 0f;
        }
    }

    // ==================================================================
    // ANI-021 — multi-chain IK (hands + head simultaneously)
    // ==================================================================
    public static final class MultiChainIK {
        public static final class Chain {
            public String name;
            public int upperJoint = -1, lowerJoint = -1;
            public Vec3 target = new Vec3();
            public Vec3 pole = new Vec3();
            public float weight = 1f;
            public int priority;                       // lower first
            public IK.TwoBoneResult result = new IK.TwoBoneResult();
        }
        public final List<Chain> chains = new ArrayList<>();
        public Chain head;                             // LookAt chain

        public Chain addChain(String name, int upper, int lower, Vec3 target, Vec3 pole, int priority){
            Chain c = new Chain();
            c.name = name; c.upperJoint = upper; c.lowerJoint = lower;
            c.target = target; c.pole = pole; c.priority = priority;
            chains.add(c);
            chains.sort((a, b) -> Integer.compare(a.priority, b.priority));
            return c;
        }

        public void setHeadTarget(Vec3 t){ if(head == null){ head = new Chain(); head.name = "head"; } head.target.set(t); }

        /** solve all chains in priority order; writes into each chain.result */
        public void solve(Skeleton skel, float[] jointWorldPos, Quat rootWorldRot, float[] boneLens){
            for(Chain c : chains){
                if(c.upperJoint < 0 || c.lowerJoint < 0 || c.weight <= 0f) continue;
                Vec3 rootWorld = new Vec3(jointWorldPos[c.upperJoint * 3], jointWorldPos[c.upperJoint * 3 + 1], jointWorldPos[c.upperJoint * 3 + 2]);
                Vec3 target = c.target.cpy();
                // weight: pull target toward rest (weight < 1 relaxes)
                if(c.weight < 1f){
                    Vec3 restTarget = rootWorld.cpy().addScaled(target.cpy().sub(rootWorld), c.weight);
                    target.set(restTarget);
                }
                IK.TwoBoneResult r = IK.solveTwoBone(
                    boneLens[c.upperJoint], boneLens[c.lowerJoint],
                    rootWorld, target, c.pole,
                    skel.joints.get(c.upperJoint).restRot,
                    skel.joints.get(c.lowerJoint).restRot,
                    rootWorldRot);
                c.result.rotA.set(r.rotA);
                c.result.rotB.set(r.rotB);
                c.result.valid = r.valid;
            }
            if(head != null && head.target.len2() > 1e-6f){
                // head look-at applied by caller (needs head world transform)
            }
        }
    }

    // ==================================================================
    // ANI-022 — root motion drift correction
    // ==================================================================
    public static final class DriftCorrection {
        public float correctionRate = 4f;          // pull speed (1/s)
        public float maxCorrection = 1.5f;         // meters
        private final Vec3 error = new Vec3();

        /** logicalPathPos: where gameplay says the character should be */
        public Vec3 update(Vec3 logicalPathPos, Vec3 actualPos, float dt, Vec3 outCorrection){
            error.set(logicalPathPos).sub(actualPos);
            float len = error.len();
            if(len > maxCorrection){
                error.scale(maxCorrection / len);   // clamp — never teleport
            }
            outCorrection.set(error).scale(MathX.clamp(correctionRate * dt, 0f, 1f));
            return outCorrection;
        }

        public float errorLength(){ return error.len(); }
        public void reset(){ error.set(0, 0, 0); }
    }

    // ==================================================================
    // ANI-023 — notify payload
    // ==================================================================
    public static final class NotifyBus {
        public static final class NotifyEvent {
            public String clip;
            public float t;
            public String event;
            public final Map<String, Object> payload = new LinkedHashMap<>();   // custom data
        }

        public interface Listener { void onNotify(NotifyEvent e); }
        private final List<Listener> listeners = new ArrayList<>();
        private final List<NotifyEvent> queue = new ArrayList<>();

        public void subscribe(Listener l){ listeners.add(l); }

        /** animator calls this when a notify fires (with payload built from JSON) */
        public void fire(String clip, float t, String event, Map<String, Object> payload){
            NotifyEvent e = new NotifyEvent();
            e.clip = clip; e.t = t; e.event = event;
            if(payload != null) e.payload.putAll(payload);
            queue.add(e);
        }

        /** dispatch queued events (called once per frame after sampling) */
        public int dispatch(){
            int n = queue.size();
            for(NotifyEvent e : queue){
                for(Listener l : listeners) l.onNotify(e);
                // mirror to EventBus for script access
                com.hollow.game.GameApp g = com.hollow.game.GameApp.instance;
                if(g != null && g.services() != null && g.services().events != null){
                    Map<String, Object> data = new LinkedHashMap<>(e.payload);
                    data.put("clip", e.clip);
                    data.put("event", e.event);
                    g.services().events.fire("anim_notify", data);
                }
            }
            queue.clear();
            return n;
        }

        public int queued(){ return queue.size(); }
    }

    /** shared bus singleton (game + CLI share it) */
    private static final NotifyBus BUS = new NotifyBus();
    public static NotifyBus bus(){ return BUS; }

    // ==================================================================
    // ANI-024 — bone springs (secondary motion)
    // ==================================================================
    public static final class BoneSpring {
        public String boneName;
        public float stiffness = 60f;
        public float damping = 8f;
        public float maxOffset = 0.12f;            // meters (meters in bone space)
        public final Vec3 offset = new Vec3();     // current spring offset
        private final Vec3 vel = new Vec3();
        private Vec3 prevParentPos = new Vec3();
        private boolean started = false;

        public BoneSpring(String bone){ boneName = bone; }

        /** drive from the parent bone's world movement (accelerations push the leaf) */
        public void update(Vec3 parentWorldPos, float dt){
            if(!started){ prevParentPos.set(parentWorldPos); started = true; return; }
            Vec3 accel = parentWorldPos.cpy().sub(prevParentPos).scale(1f / Math.max(0.001f, dt));
            prevParentPos.set(parentWorldPos);
            // spring toward zero offset, excited by -acceleration
            Vec3 target = accel.cpy().scale(-0.02f);
            float maxT = maxOffset;
            if(target.len() > maxT) target.scale(maxT / target.len());
            Vec3 force = target.cpy().sub(offset).scale(stiffness);
            force.addScaled(vel, -damping);
            vel.addScaled(force, dt);
            offset.addScaled(vel, dt);
            if(offset.len() > maxOffset) offset.scale(maxOffset / offset.len());
        }

        public void reset(){ offset.set(0, 0, 0); vel.set(0, 0, 0); started = false; }
    }

    /** manager: list of springs on one animator */
    public static final class BoneSpringRig extends Component {
        public final List<BoneSpring> springs = new ArrayList<>();
        public void add(String bone, float stiffness, float damping, float maxOffset){
            BoneSpring s = new BoneSpring(bone);
            s.stiffness = stiffness; s.damping = damping; s.maxOffset = maxOffset;
            springs.add(s);
        }
        public void updateBone(int idx, Vec3 parentWorldPos, float dt){
            if(idx >= 0 && idx < springs.size()) springs.get(idx).update(parentWorldPos, dt);
        }
        public Vec3 offsetOf(String bone, Vec3 out){
            for(BoneSpring s : springs) if(s.boneName.equalsIgnoreCase(bone)) return out.set(s.offset);
            return out.set(0, 0, 0);
        }
        @Override public void onUpdate(float dt){
            if(entity == null) return;
            entity.setMeta("boneSpringCount", springs.size());
        }

        @Override public void save(JsonW w){
            w.obj(); w.kv("type", "BoneSpringRig");
            w.key("springs"); w.arr();
            for(BoneSpring s : springs){
                w.obj(); w.kv("bone", s.boneName).kv("stiffness", s.stiffness)
                  .kv("damping", s.damping).kv("maxOffset", s.maxOffset);
                w.endObj();
            }
            w.endArr();
            w.endObj();
        }
        @Override public void load(com.hollow.core.JsonV n){
            Object arr = n.get("springs");
            springs.clear();
            if(arr instanceof List<?> l){
                for(Object o : l){
                    if(!(o instanceof Map<?,?> m)) continue;
                    String bone = String.valueOf(m.get("bone"));
                    float st = m.get("stiffness") instanceof Number num ? num.floatValue() : 60f;
                    float dm = m.get("damping") instanceof Number num ? num.floatValue() : 8f;
                    float mo = m.get("maxOffset") instanceof Number num ? num.floatValue() : 0.12f;
                    add(bone, st, dm, mo);
                }
            }
        }
    }

    // ==================================================================
    // ANI-025 — pose dump (CLI)
    // ==================================================================
    public static final class PoseDumper {
        /** sample clip at t on skeleton; write human-readable JSON of joint local transforms */
        public static String dump(AnimClip clip, Skeleton skel, float t, int maxJoints){
            StringBuilder sb = new StringBuilder(4096);
            JsonW w = new JsonW();
            w.obj();
            w.kv("clip", clip.name);
            w.kv("duration", clip.duration);
            w.kv("time", t);
            w.kv("joints", skel.jointCount());
            w.key("pose"); w.arr();
            int n = Math.min(maxJoints, skel.jointCount());
            for(int i = 0; i < n; i++){
                Skeleton.Joint j = skel.joints.get(i);
                AnimClip.Channel c = clip.channels.get(i);
                w.obj();
                w.kv("joint", j.name);
                w.kv("parent", j.parent);
                if(c != null){
                    if(c.translations != null){
                        float[] p = new float[3];
                        clip.sampleTranslation(c, t, p);
                        w.kv("tx", p[0]).kv("ty", p[1]).kv("tz", p[2]);
                    } else {
                        w.kv("tx", j.restPos.x).kv("ty", j.restPos.y).kv("tz", j.restPos.z);
                    }
                    if(c.rotations != null){
                        Quat q = new Quat();
                        clip.sampleRotation(c, t, q);
                        w.kv("qx", q.x).kv("qy", q.y).kv("qz", q.z).kv("qw", q.w);
                    } else {
                        w.kv("qx", j.restRot.x).kv("qy", j.restRot.y).kv("qz", j.restRot.z).kv("qw", j.restRot.w);
                    }
                } else {
                    w.kv("tx", j.restPos.x).kv("ty", j.restPos.y).kv("tz", j.restPos.z);
                    w.kv("qx", j.restRot.x).kv("qy", j.restRot.y).kv("qz", j.restRot.z).kv("qw", j.restRot.w);
                }
                w.endObj();
            }
            w.endArr();
            w.endObj();
            return w.toString();
        }
    }
}
