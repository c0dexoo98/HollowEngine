package com.hollow.render;

import com.hollow.core.Mat4;
import com.hollow.core.Quat;
import com.hollow.core.Vec3;

/**
 * v3.0.0 RENDER: planar reflections — mirror-camera math + clip plane for
 * floor/water reflections, renderer-agnostic.
 *
 * The renderer renders the scene a second time from the mirrored camera into
 * a reflection target, then composites it under the surface with a fade by
 * distance. This class owns ALL the math so tests can verify it without a GPU:
 *   - reflectionView(): view matrix of the mirrored camera
 *   - clipPlane(): oblique near plane that clips everything below the surface
 *   - projectPoint(): where a world point lands in the reflection texture
 */
public final class PlanarReflection {

    /** reflective plane definition: point + normal (normalized, pointing up out of the surface) */
    public final Vec3 planePoint = new Vec3(0, 0, 0);
    public final Vec3 planeNormal = new Vec3(0, 1, 0);
    /** reflection intensity 0..1 (water shine, wet floor...) */
    public float intensity = 0.6f;
    /** distance fade: beyond this many units the reflection fades out */
    public float fadeDistance = 25f;
    /** distortion strength for water ripples (renderer maps to uv wobble) */
    public float rippleStrength = 0.02f;
    /** resolution scale of the reflection target vs main framebuffer */
    public float resolutionScale = 0.5f;
    /** render reflections only when camera is above the plane (perf) */
    public boolean onlyFromAbove = true;

    public PlanarReflection(){ }

    public void setPlane(Vec3 point, Vec3 normal){
        planePoint.set(point);
        float len = normal.len();
        if(len > 1e-6f){
            planeNormal.set(normal.x / len, normal.y / len, normal.z / len);
        }
    }

    /** mirror a camera eye position across the plane */
    public Vec3 mirrorEye(Vec3 eye){
        // reflected = eye - 2 * (eye - p)·n * n
        float d = (eye.x - planePoint.x) * planeNormal.x
                + (eye.y - planePoint.y) * planeNormal.y
                + (eye.z - planePoint.z) * planeNormal.z;
        return new Vec3(
            eye.x - 2f * d * planeNormal.x,
            eye.y - 2f * d * planeNormal.y,
            eye.z - 2f * d * planeNormal.z);
    }

    /** mirror the camera orientation (forward vector reflected, up flipped) */
    public Vec3 mirrorForward(Vec3 forward){
        // reflect across plane: f' = f - 2(f·n)n
        float d = forward.x * planeNormal.x + forward.y * planeNormal.y + forward.z * planeNormal.z;
        return new Vec3(
            forward.x - 2f * d * planeNormal.x,
            forward.y - 2f * d * planeNormal.y,
            forward.z - 2f * d * planeNormal.z);
    }

    /** build the reflection view matrix (right-handed, looking along mirrored forward) */
    public Mat4 reflectionView(Vec3 eye, Vec3 forward, Vec3 up){
        Vec3 mEye = mirrorEye(eye);
        Vec3 mFwd = mirrorForward(forward).cpy().scale(-1f);   // camera looks along -Z: negate
        Vec3 mUp = mirrorForward(up);
        // standard lookAt with mirrored basis (handedness flip handled by the reflection itself)
        Vec3 zAxis = mFwd.cpy().norm();
        Vec3 xAxis = new Vec3(
            mUp.y * zAxis.z - mUp.z * zAxis.y,
            mUp.z * zAxis.x - mUp.x * zAxis.z,
            mUp.x * zAxis.y - mUp.y * zAxis.x).norm();
        Vec3 yAxis = new Vec3(
            zAxis.y * xAxis.z - zAxis.z * xAxis.y,
            zAxis.z * xAxis.x - zAxis.x * xAxis.z,
            zAxis.x * xAxis.y - zAxis.y * xAxis.x);
        Mat4 m = new Mat4();
        m.m[0] = xAxis.x; m.m[4] = xAxis.y; m.m[8]  = xAxis.z;
        m.m[1] = yAxis.x; m.m[5] = yAxis.y; m.m[9]  = yAxis.z;
        m.m[2] = zAxis.x; m.m[6] = zAxis.y; m.m[10] = zAxis.z;
        m.m[12] = -(xAxis.x * mEye.x + xAxis.y * mEye.y + xAxis.z * mEye.z);
        m.m[13] = -(yAxis.x * mEye.x + yAxis.y * mEye.y + yAxis.z * mEye.z);
        m.m[14] = -(zAxis.x * mEye.x + zAxis.y * mEye.y + zAxis.z * mEye.z);
        m.m[15] = 1f;
        return m;
    }

    /** world-space plane as clip vector (for oblique projection or shader clipping) */
    public float[] clipPlane(){
        // plane: n·(x - p) = 0  ->  (n, -n·p)
        return new float[]{
            planeNormal.x, planeNormal.y, planeNormal.z,
            -(planeNormal.x * planePoint.x + planeNormal.y * planePoint.y + planeNormal.z * planePoint.z)
        };
    }

    /** is the camera on the reflecting side? (perf gate) */
    public boolean shouldRender(Vec3 eye){
        if(!onlyFromAbove) return intensity > 0.01f;
        float d = (eye.x - planePoint.x) * planeNormal.x
                + (eye.y - planePoint.y) * planeNormal.y
                + (eye.z - planePoint.z) * planeNormal.z;
        return d > 0f && intensity > 0.01f;
    }

    /** fade factor for a surface point at distance `dist` from camera */
    public float fadeAt(float dist){
        if(fadeDistance <= 0f) return 1f;
        return Math.max(0f, 1f - dist / fadeDistance) * intensity;
    }

    /** mirror a full camera transform for the render pass */
    public static Mat4 mirrorMatrix(Vec3 point, Vec3 normal){
        // reflection matrix across plane n·(x-p)=0
        float nx = normal.x, ny = normal.y, nz = normal.z;
        float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
        if(len < 1e-6f) return new Mat4();
        nx /= len; ny /= len; nz /= len;
        float d = nx * point.x + ny * point.y + nz * point.z;
        Mat4 m = new Mat4();
        m.m[0]  = 1f - 2f*nx*nx;  m.m[4]  = -2f*nx*ny;     m.m[8]  = -2f*nx*nz;     m.m[12] = 2f*nx*d;
        m.m[1]  = -2f*ny*nx;      m.m[5]  = 1f - 2f*ny*ny;  m.m[9]  = -2f*ny*nz;     m.m[13] = 2f*ny*d;
        m.m[2]  = -2f*nz*nx;      m.m[6]  = -2f*nz*ny;     m.m[10] = 1f - 2f*nz*nz;  m.m[14] = 2f*nz*d;
        m.m[3]  = 0f;             m.m[7]  = 0f;             m.m[11] = 0f;             m.m[15] = 1f;
        return m;
    }
}
