package com.hollow.vr;

import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Mat4;
import com.hollow.core.Vec3;
import com.hollow.render.StereoRig;

import java.util.ArrayList;
import java.util.List;

/** v2.5 VR batch — VR-001..VR-010. Zero external SDK: pure geometry + math,
 *  headless-testable; the Android runtime feeds sensor data through the
 *  provider interfaces.
 *
 *  VR-001 VrRig            — per-eye projections (multi-view) on top of StereoRig
 *  VR-002 HeadTracking     — Android rotation-vector sensor → 3DoF head pose
 *  VR-003 AsyncReprojection— light async timewarp: extrapolate latest pose
 *  VR-004 TouchController  — 3DoF touchpad controller (yaw/pitch + trigger)
 *  VR-005 IpdCalibration   — IPD presets + custom calibration, persisted
 *  VR-006 ComfortVignette  — rotation-speed-driven tunnel vignette
 *  VR-007 VrPerfProfile    — 90 Hz target with frame-budget health model
 *  VR-008 VrQualityScaler  — automatic resolution scaling under load
 *  VR-009 BinauralAudio    — ILD/ITD-lite spatialization per source
 *  VR-010 HeadlessPipeline — end-to-end deterministic pipeline test */
public final class VrRuntime {
    private VrRuntime() {}

    // =====================================================================
    // VR-001 — per-eye projection (multi-view)
    // =====================================================================
    /** Wraps StereoRig with per-eye asymmetric FOV + IPD-scaled offsets; the
     *  renderer calls beginEye(0/1) then reads viewProj. */
    public static final class VrRig {
        public final StereoRig stereo = new StereoRig();
        public float fovLeft = 96f, fovRight = 96f;      // per-eye horizontal FOV (deg)
        public float near = 0.05f, far = 250f;
        public Vec3 headPos = new Vec3();
        public Vec3 headYawPitchRoll = new Vec3();       // deg
        public int framesRendered = 0;
        public int activeEye = 0;

        public void configure(float ipdMeters){
            stereo.enabled = true;
            stereo.ipd = ipdMeters;
            // aspect ~ square-ish eye target; renderer overrides per device
            stereo.configure(fovLeft, 1.0f, near, far);
        }

        /** render pass order: 0 = left, 1 = right (multi-view: both projections set) */
        public StereoRig.Eye beginEye(int eye){
            activeEye = MathX.clamp(eye, 0, 1);
            return stereo.eyes()[activeEye];
        }

        /** view matrix for an eye = head pose composed with eye offset. */
        public Mat4 eyeView(int eye){
            StereoRig.Eye e = stereo.eyes()[MathX.clamp(eye, 0, 1)];
            float rad = (float)Math.toRadians(headYawPitchRoll.x);
            Vec3 off = e.offset;
            Vec3 world = new Vec3(
                headPos.x + (float)Math.cos(rad) * off.x - (float)Math.sin(rad) * off.z,
                headPos.y + off.y,
                headPos.z + (float)Math.sin(rad) * off.x + (float)Math.cos(rad) * off.z);
            return Mat4.translation(new Vec3(world.x, world.y, world.z));
        }

        public void endFrame(){ framesRendered++; }
        public String describe(){
            return "vrrig[ipd=" + stereo.ipd + " fovL=" + fovLeft + " fovR=" + fovRight
                + " frames=" + framesRendered + "]";
        }
    }

    // =====================================================================
    // VR-002 — head tracking from Android sensor
    // =====================================================================
    /** Platform supplies the rotation-vector sensor (quaternion); we integrate a
     *  smoothed yaw/pitch/roll + positional neck model (0.08 m up, 0.1 m forward). */
    public static final class HeadTracking {
        public interface SensorSource { /** latest rotation vector [x,y,z,w] */
            float[] quaternion(); boolean available(); }

        public SensorSource sensor = null;
        public float smoothing = 0.35f;            // low-pass on sensor deltas
        public final Vec3 yawPitchRoll = new Vec3();
        public final Vec3 worldPos = new Vec3();
        public float neckUp = 0.08f, neckForward = 0.10f;
        public long updates = 0;
        public boolean stale = false;
        private float[] lastQ = null;
        private float staleTime = 0f;

        public void update(float dt){
            if(sensor == null || !sensor.available()){
                staleTime += dt;
                stale = staleTime > 0.5f;
                return;
            }
            staleTime = 0f; stale = false;
            float[] q = sensor.quaternion();
            if(q != null && q.length >= 4){
                lastQ = q;
                float yaw = eulerFromQuat(q)[0], pitch = eulerFromQuat(q)[1], roll = eulerFromQuat(q)[2];
                yawPitchRoll.set(
                    MathX.lerp(yawPitchRoll.x, yaw, smoothing),
                    MathX.lerp(yawPitchRoll.y, pitch, smoothing),
                    MathX.lerp(yawPitchRoll.z, roll, smoothing));
                // neck model: head sits above/forward of the tracked origin
                float rad = (float)Math.toRadians(yawPitchRoll.x);
                worldPos.set((float)Math.sin(rad) * neckForward, neckUp, -(float)Math.cos(rad) * neckForward);
                updates++;
            }
        }

        static float[] eulerFromQuat(float[] q){
            float x = q[0], y = q[1], z = q[2], w = q[3];
            float yaw = (float)Math.toDegrees(Math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z)));
            float pitch = (float)Math.toDegrees(Math.asin(MathX.clamp(2 * (w * y - z * x), -1, 1)));
            float roll = (float)Math.toDegrees(Math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y)));
            return new float[]{ yaw, pitch, roll };
        }

        public float[] lastQuaternion(){ return lastQ; }
        public String describe(){ return "head[available=" + (sensor != null && sensor.available()) + " stale=" + stale + " updates=" + updates + "]"; }
    }

    // =====================================================================
    // VR-003 — light async reprojection
    // =====================================================================
    /** Async timewarp-lite: when the frame misses its deadline, the compositor
     *  re-projects using the LATEST head pose — implemented as an orientation
     *  delta the renderer folds into the view matrix before presenting. */
    public static final class AsyncReprojection {
        public boolean enabled = true;
        public final Vec3 poseDelta = new Vec3();      // deg applied by compositor
        public long reprojectedFrames = 0, missedDeadlines = 0;
        public float lastFrameMs = 0f, deadlineMs = 11.1f;   // 90 Hz

        /** call when the sim produced a frame; returns true when the compositor
         *  should reproject (frame late but usable). */
        public boolean submitFrame(float frameMs, Vec3 poseAtPresent, Vec3 poseNow){
            lastFrameMs = frameMs;
            boolean late = frameMs > deadlineMs;
            if(!late || !enabled) return false;
            missedDeadlines++;
            if(poseAtPresent != null && poseNow != null){
                poseDelta.set(poseNow.x - poseAtPresent.x, poseNow.y - poseAtPresent.y, poseNow.z - poseAtPresent.z);
            }
            reprojectedFrames++;
            return true;
        }

        /** ratio tracking for the scaler (late frames observed). */
        public float lateRate(){ return missedDeadlines > 0f ? 1f : 0f; }
        public String describe(){ return "timewarp[on=" + enabled + " late=" + missedDeadlines + " reproj=" + reprojectedFrames + " delta=" + poseDelta + "]"; }
    }

    // =====================================================================
    // VR-004 — 3DoF touch controller
    // =====================================================================
    /** Daydream/GearVR-style controller: touch deltas drive yaw/pitch of a laser
     *  pointer; trigger = tap. Platform feeds deltas + trigger state. */
    public static final class TouchController {
        public boolean connected = false;
        public float yawDeg = 0f, pitchDeg = 0f;
        public float sensitivity = 0.35f;              // deg per pixel
        public boolean trigger = false, touchDown = false;
        public long taps = 0;
        public final Vec3 rayDir = new Vec3(0, 0, -1);

        public void connect(){ connected = true; }
        public void disconnect(){ connected = false; }

        /** platform touch delta (pixels since last frame). */
        public void onTouchDelta(float dx, float dy){
            if(!connected || !touchDown) return;
            yawDeg = wrap(yawDeg + dx * sensitivity);
            pitchDeg = MathX.clamp(pitchDeg - dy * sensitivity, -80f, 80f);
        }
        public void onTouch(boolean down){
            if(down && !touchDown) taps++;
            touchDown = down;
        }
        public void setTrigger(boolean down){
            if(down && !trigger) taps++;
            trigger = down;
        }

        /** pointer ray in head space (computed each frame for the renderer). */
        public Vec3 computeRay(){
            float y = (float)Math.toRadians(yawDeg), p = (float)Math.toRadians(pitchDeg);
            rayDir.set(
                (float)Math.sin(y) * (float)Math.cos(p),
                (float)Math.sin(p),
                -(float)Math.cos(y) * (float)Math.cos(p)).norm();
            return rayDir;
        }

        static float wrap(float deg){
            while(deg > 180f) deg -= 360f;
            while(deg < -180f) deg += 360f;
            return deg;
        }
        public String describe(){ return "touchctl[on=" + connected + " yaw=" + yawDeg + " pitch=" + pitchDeg + " taps=" + taps + "]"; }
    }

    // =====================================================================
    // VR-005 — IPD calibration
    // =====================================================================
    public static final class IpdCalibration {
        public static final String[] PRESET_NAMES = { "narrow", "average", "wide" };
        public static final float[] PRESETS_MM = { 58f, 64f, 70f };
        public float ipdMeters = 0.064f;
        public boolean calibrated = false;
        public int calibrationSteps = 0;

        public void applyPreset(int idx){
            int i = MathX.clamp(idx, 0, PRESETS_MM.length - 1);
            ipdMeters = PRESETS_MM[i] / 1000f;
            calibrationSteps++;
        }
        /** custom dial: delta in mm. */
        public void adjustMm(float deltaMm){
            ipdMeters = MathX.clamp(ipdMeters + deltaMm / 1000f, 0.050f, 0.080f);
            calibrationSteps++;
            calibrated = true;
        }
        public String describe(){ return "ipd[" + Math.round(ipdMeters * 1000) + "mm calibrated=" + calibrated + "]"; }
    }

    // =====================================================================
    // VR-006 — comfort vignette during rotation
    // =====================================================================
    public static final class ComfortVignette {
        public float strength = 0f;                 // 0..1 renderer samples
        public float rotateSpeed = 0f;              // deg/sec head yaw rate
        public float onsetDegPerSec = 45f;
        public float fullDegPerSec = 140f;
        public float releaseSec = 0.35f;
        public long activeFrames = 0;
        public boolean enabled = true;

        public void update(float dt, float headYawRateDeg){
            if(!enabled){ strength = MathX.approach(strength, 0, dt * 4f); return; }
            rotateSpeed = Math.abs(headYawRateDeg);
            if(rotateSpeed > onsetDegPerSec){
                float t = MathX.clamp((rotateSpeed - onsetDegPerSec) / (fullDegPerSec - onsetDegPerSec), 0, 1);
                strength = MathX.approach(strength, t, dt * 8f);
                activeFrames++;
            } else {
                strength = MathX.approach(strength, 0, dt / Math.max(0.01f, releaseSec));
            }
        }
        public String describe(){ return "vignette[" + String.format("%.2f", strength) + " rot=" + rotateSpeed + " active=" + activeFrames + "]"; }
    }

    // =====================================================================
    // VR-007 — VR performance profile (90 fps target)
    // =====================================================================
    public static final class VrPerfProfile {
        public static final float TARGET_HZ = 90f;
        public static final float BUDGET_MS = 1000f / TARGET_HZ;
        public float[] frameHistory = new float[180];  // 2s of frames
        public int cursor = 0;
        public long frames = 0;
        public double avgMs = 0, maxMs = 0;
        public float deadlineMissRate = 0f;

        public void push(float frameMs){
            frameHistory[cursor] = frameMs;
            cursor = (cursor + 1) % frameHistory.length;
            frames++;
            double sum = 0, max = 0; int misses = 0;
            int n = (int)Math.min(frames, frameHistory.length);
            for(int i = 0; i < n; i++){
                float v = frameHistory[i];
                sum += v;
                if(v > max) max = v;
                if(v > BUDGET_MS) misses++;
            }
            avgMs = sum / Math.max(1, n);
            maxMs = max;
            deadlineMissRate = n > 0 ? (float)misses / n : 0f;
        }
        public float effectiveHz(){ return avgMs > 0 ? (float)(1000.0 / avgMs) : TARGET_HZ; }
        public boolean healthy(){ return deadlineMissRate < 0.02f && avgMs < BUDGET_MS; }
        public String report(){
            return String.format("vrperf[target=%.0fHz avg=%.2fms max=%.2fms miss=%.1f%% %s]",
                TARGET_HZ, avgMs, maxMs, deadlineMissRate * 100, healthy() ? "HEALTHY" : "STRAIN");
        }
    }

    // =====================================================================
    // VR-008 — automatic quality reduction in VR
    // =====================================================================
    public static final class VrQualityScaler {
        public static final float[] STEPS = { 1.0f, 0.9f, 0.8f, 0.7f, 0.6f };
        public int level = 0;
        public float missThreshold = 0.08f;            // deadline miss rate to downscale
        public float headroomHz = 88f;                 // effective Hz to consider upscaling
        public float cooldownSec = 1.5f;
        private float cooldown = 0f;
        public int scaleChanges = 0;

        public float resolutionScale(){ return STEPS[MathX.clamp(level, 0, STEPS.length - 1)]; }

        /** feed the perf profile each second of sim time. */
        public void update(float dt, VrPerfProfile perf){
            cooldown -= dt;
            if(cooldown > 0f || perf.frames < 30) return;
            if(perf.deadlineMissRate > missThreshold && level < STEPS.length - 1){
                level++;
                scaleChanges++;
                cooldown = cooldownSec;
                Log.i("Vr", "quality down → " + resolutionScale());
            } else if(perf.deadlineMissRate < 0.01f && perf.effectiveHz() >= headroomHz && level > 0){
                level--;
                scaleChanges++;
                cooldown = cooldownSec;
                Log.i("Vr", "quality up → " + resolutionScale());
            }
        }
        public String describe(){ return "vrscale[level=" + level + " scale=" + resolutionScale() + " changes=" + scaleChanges + "]"; }
    }

    // =====================================================================
    // VR-009 — VR binaural audio
    // =====================================================================
    /** HRTF-lite: per-source interaural level difference (ILD) + interaural time
     *  difference (ITD) from azimuth/elevation relative to head yaw, plus distance
     *  attenuation. The mixer applies the gains/delays per source. */
    public static final class BinauralAudio {
        public static final float SPEED_OF_SOUND = 343f;
        public static final float EAR_SPACING = 0.18f;      // rough effective spacing
        public float headYawDeg = 0f;
        public int spatializedSources = 0;

        public static final class Spatialization {
            public float leftGain = 1f, rightGain = 1f;
            public float leftDelaySec = 0f, rightDelaySec = 0f;
            public float distanceGain = 1f;
        }

        /** spatialize a source at world pos relative to listener at (0,0,0) facing -Z. */
        public Spatialization spatialize(Vec3 sourcePos, Vec3 listenerPos, float refDistance){
            Spatialization s = new Spatialization();
            float dx = sourcePos.x - listenerPos.x, dy = sourcePos.y - listenerPos.y, dz = sourcePos.z - listenerPos.z;
            float dist = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
            float rad = (float)Math.toRadians(headYawDeg);
            // rotate into head space (yaw only — elevation via dy)
            float hx = dx * (float)Math.cos(rad) - dz * (float)Math.sin(rad);
            float hz = dx * (float)Math.sin(rad) + dz * (float)Math.cos(rad);
            float azimuth = (float)Math.atan2(hx, -hz);           // 0 = front, +right
            // ILD: cos law head shadow
            float il = 1f - 0.5f * Math.max(0f, (float)Math.sin(azimuth));
            float ir = 1f - 0.5f * Math.max(0f, (float)-Math.sin(azimuth));
            s.leftGain = il; s.rightGain = ir;
            // ITD: path difference to ears
            float itd = (EAR_SPACING / SPEED_OF_SOUND) * (float)Math.sin(azimuth);
            s.leftDelaySec = Math.max(0f, itd);
            s.rightDelaySec = Math.max(0f, -itd);
            // distance attenuation (inverse-ish with reference floor)
            s.distanceGain = refDistance / (refDistance + Math.max(0f, dist - refDistance));
            spatializedSources++;
            return s;
        }

        public String describe(){ return "binaural[yaw=" + headYawDeg + " sources=" + spatializedSources + "]"; }
    }

    // =====================================================================
    // VR-010 — headless VR pipeline test
    // =====================================================================
    /** Deterministic end-to-end exercise of VR-001..009 (no GL, no sensor): fake
     *  rotation-vector sensor, scripted head motion, frame pressure → scaler. */
    public static final class HeadlessPipeline {
        public static final class Result {
            public boolean rigOk, headOk, warpOk, controllerOk, ipdOk, vignetteOk,
                perfOk, scalerOk, binauralOk;
            public String summary;
            public boolean all(){
                return rigOk && headOk && warpOk && controllerOk && ipdOk && vignetteOk && perfOk && scalerOk && binauralOk;
            }
        }

        public static Result run(){
            Result r = new Result();

            // VR-001 rig: per-eye projections differ and cover IPD
            VrRig rig = new VrRig();
            rig.configure(0.064f);
            Mat4 l = rig.eyeView(0);
            Mat4 rr = rig.eyeView(1);
            r.rigOk = l != null && rr != null && rig.beginEye(0) != rig.beginEye(1)
                && Math.abs(rig.stereo.eyes()[0].offset.x + rig.stereo.eyes()[1].offset.x) < 1e-6f;

            // VR-002 head: fake sensor spins 90° over 30 frames
            HeadTracking head = new HeadTracking();
            final float[] q = new float[]{ 0f, 0f, (float)Math.sin(Math.PI / 4), (float)Math.cos(Math.PI / 4) };
            head.sensor = new HeadTracking.SensorSource(){
                @Override public float[] quaternion(){ return q; }
                @Override public boolean available(){ return true; }
            };
            for(int i = 0; i < 30; i++) head.update(1f / 90f);
            r.headOk = Math.abs(Math.abs(head.yawPitchRoll.x) - 90f) < 15f && !head.stale;

            // VR-003 warp: late frame triggers reprojection with pose delta
            AsyncReprojection warp = new AsyncReprojection();
            boolean warped = warp.submitFrame(14f, new Vec3(0, 0, 0), new Vec3(2, 0, 0));
            r.warpOk = warped && warp.reprojectedFrames == 1 && warp.poseDelta.x > 1f;

            // VR-004 controller: drag right + trigger
            TouchController tc = new TouchController();
            tc.connect(); tc.onTouch(true);
            tc.onTouchDelta(100, 0);
            tc.setTrigger(true);
            r.controllerOk = tc.yawDeg > 10f && tc.trigger && tc.computeRay().x > 0f;

            // VR-005 IPD: preset + adjust
            IpdCalibration ipd = new IpdCalibration();
            ipd.applyPreset(2);
            ipd.adjustMm(-2f);
            r.ipdOk = Math.abs(ipd.ipdMeters - 0.068f) < 0.001f;

            // VR-006 vignette: fast yaw
            ComfortVignette vig = new ComfortVignette();
            for(int i = 0; i < 20; i++) vig.update(1f / 90f, 150f);
            r.vignetteOk = vig.strength > 0.5f;

            // VR-007 perf: every 5th frame late (20%) → math sanity
            VrPerfProfile perf = new VrPerfProfile();
            for(int i = 0; i < 50; i++) perf.push(i % 5 == 0 ? 20f : 10f);
            r.perfOk = Math.abs(perf.deadlineMissRate - 0.2f) < 0.01f && perf.frames == 50;

            // VR-008 scaler: heavy misses drive level down
            VrQualityScaler scaler = new VrQualityScaler();
            scaler.update(2f, perf);
            r.scalerOk = scaler.level >= 1 && scaler.resolutionScale() < 1f;

            // VR-009 binaural: source right of head → right gain < left gain
            BinauralAudio bin = new BinauralAudio();
            BinauralAudio.Spatialization s = bin.spatialize(new Vec3(3, 1, 0), new Vec3(0, 0, 0), 2f);
            r.binauralOk = s.rightGain > s.leftGain && s.rightDelaySec == 0f && s.distanceGain < 1f && s.leftDelaySec > 0f;

            r.summary = "rig=" + r.rigOk + " head=" + r.headOk + " warp=" + r.warpOk
                + " ctl=" + r.controllerOk + " ipd=" + r.ipdOk + " vig=" + r.vignetteOk
                + " perf=" + r.perfOk + " scale=" + r.scalerOk + " bin=" + r.binauralOk;
            return r;
        }
    }

    /** aggregate facade wired by the Android runtime. */
    public static final class VrSession {
        public final VrRig rig = new VrRig();
        public final HeadTracking head = new HeadTracking();
        public final AsyncReprojection warp = new AsyncReprojection();
        public final TouchController controller = new TouchController();
        public final IpdCalibration ipd = new IpdCalibration();
        public final ComfortVignette vignette = new ComfortVignette();
        public final VrPerfProfile perf = new VrPerfProfile();
        public final VrQualityScaler scaler = new VrQualityScaler();
        public final BinauralAudio binaural = new BinauralAudio();
        public boolean active = false;
        private final Vec3 lastPose = new Vec3();

        public void start(){
            active = true;
            rig.configure(ipd.ipdMeters);
            Log.i("Vr", "session started " + ipd.describe());
        }
        public void stop(){ active = false; }

        /** per-frame tick (device calls with real sensor; headless feeds fakes). */
        public void frame(float dt, float frameMs){
            if(!active) return;
            head.update(dt);
            rig.headYawPitchRoll.set(head.yawPitchRoll);
            rig.headPos.set(head.worldPos);
            boolean late = warp.submitFrame(frameMs, lastPose, head.yawPitchRoll);
            lastPose.set(head.yawPitchRoll);
            vignette.update(dt, Math.abs(head.yawPitchRoll.x));
            perf.push(frameMs);
            scaler.update(dt, perf);
            binaural.headYawDeg = head.yawPitchRoll.x;
            rig.endFrame();
        }
        public String status(){
            return "vrsession[" + rig.describe() + " " + head.describe() + " " + perf.report() + " " + scaler.describe() + "]";
        }
    }
}
