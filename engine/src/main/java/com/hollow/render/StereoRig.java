package com.hollow.render;

import com.hollow.core.Mat4;
import com.hollow.core.Vec3;

/** Stereo multi-view rig (RND-035, VR-001-ready): two eye offsets + per-eye
 *  projections sharing one asymmetric-fov parameterization. The renderer walks
 *  {@link #eyes()} and renders the frame once per eye into its own FBO.
 *  No Android VR SDK dependency — pure geometry, works headless for tests. */
public final class StereoRig {

    public static final class Eye {
        public final Vec3 offset = new Vec3();      // head-space offset (meters)
        public final Mat4 projection = new Mat4();
        public String label = "eye";
    }

    public boolean enabled = false;
    /** interpupillary distance (meters) */
    public float ipd = 0.064f;
    /** converged distance the eyes toe-in toward (0 = parallel) */
    public float convergence = 0f;

    private final Eye[] eyes = { new Eye(), new Eye() };

    public StereoRig(){
        eyes[0].label = "left";
        eyes[1].label = "right";
    }

    /** (re)build eye offsets + projections from a mono camera */
    public void configure(float fovYDeg, float aspect, float near, float far){
        eyes[0].offset.set(-ipd * 0.5f, 0, 0);
        eyes[1].offset.set(+ipd * 0.5f, 0, 0);
        // per-eye horizontal NDC shift toward convergence (asymmetric frusta)
        float eyeShift = convergence > 0.01f ? (ipd * 0.5f) / convergence : 0f;
        float t = (float)Math.tan(Math.toRadians(fovYDeg) * 0.5f) * near;
        float ndc = eyeShift / (t * aspect);            // shift as fraction of half-width
        eyes[0].projection.set(shiftedPerspective(fovYDeg, aspect, near, far, -ndc));
        eyes[1].projection.set(shiftedPerspective(fovYDeg, aspect, near, far, +ndc));
    }

    /** perspective with horizontal NDC shift (asymmetric frustum) */
    public static Mat4 shiftedPerspective(float fovYDeg, float aspect, float near, float far, float ndcShiftX){
        Mat4 p = Mat4.perspective(fovYDeg, aspect, near, far);
        // offset x' = x + shift * w : fold into column 2 (m[8]) per standard frustum offset
        p.m[8] += 2f * ndcShiftX;
        return p;
    }

    public Eye[] eyes(){ return eyes; }
    public int eyeCount(){ return enabled ? 2 : 1; }

    public String describe(){
        return "stereo[enabled=" + enabled + " ipd=" + ipd + " convergence=" + convergence + "]";
    }
}
