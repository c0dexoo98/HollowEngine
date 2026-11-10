package com.hollow.render;

import com.hollow.core.AABB;
import com.hollow.core.Mat4;
import com.hollow.core.Vec3;
import com.hollow.scene.CameraComponent;
import com.hollow.scene.Entity;

/** View/projection + frustum culling data built from a camera Entity.
 *  View = inverse of the entity world matrix (SPEC: inverse-free construction would need
 *  a separate lookAt from entity basis; we invert the full TRS which also handles parents).
 *  Frustum planes extracted Gribb-Hartmann style from the column-major viewProj.
 */
public final class CameraData {
    public final Mat4 view = new Mat4();
    public final Mat4 proj = new Mat4();
    public final Mat4 viewProj = new Mat4();

    public final Vec3 eye = new Vec3();
    public final Vec3 forward = new Vec3(0, 0, -1);
    public final Vec3 up = new Vec3(0, 1, 0);
    public final Vec3 right = new Vec3(1, 0, 0);

    public float fovY = 60f, near = 0.1f, far = 300f, aspect = 16f / 9f;

    /** 6 frustum planes as (nx,ny,nz,d) — order: L,R,B,T,N,F */
    public final float[] planes = new float[6 * 4];

    /** Build from a camera entity. Returns null if the entity has no CameraComponent. */
    public static CameraData from(Entity cameraEntity, float aspect){
        if (cameraEntity == null) return null;
        CameraComponent cc = cameraEntity.get(CameraComponent.class);
        if (cc == null) return null;
        CameraData c = new CameraData();
        cameraEntity.updateWorld();

        c.fovY = cc.fovY; c.near = Math.max(0.01f, cc.near); c.far = Math.max(c.near + 0.1f, cc.far);
        c.aspect = Math.max(0.05f, aspect);

        c.view.set(cameraEntity.worldMatrix.invert());
        c.proj.set(Mat4.perspective(c.fovY, c.aspect, c.near, c.far));
        c.viewProj.set(c.proj).mul(c.view);

        c.eye.set(cameraEntity.worldMatrix.translation());
        c.forward.set(cameraEntity.rotation.forward()).norm();
        c.up.set(cameraEntity.rotation.up()).norm();
        c.right.set(cameraEntity.rotation.right()).norm();

        c.extractPlanes();
        return c;
    }

    /** Gribb-Hartmann extraction from column-major m[col*4+row]. */
    private void extractPlanes(){
        float[] m = viewProj.m;
        // rows of the matrix
        float r0x=m[0], r0y=m[4], r0z=m[8],  r0w=m[12];
        float r1x=m[1], r1y=m[5], r1z=m[9],  r1w=m[13];
        float r2x=m[2], r2y=m[6], r2z=m[10], r2w=m[14];
        float r3x=m[3], r3y=m[7], r3z=m[11], r3w=m[15];
        setPlane(0, r3x+r0x, r3y+r0y, r3z+r0z, r3w+r0w); // left
        setPlane(1, r3x-r0x, r3y-r0y, r3z-r0z, r3w-r0w); // right
        setPlane(2, r3x+r1x, r3y+r1y, r3z+r1z, r3w+r1w); // bottom
        setPlane(3, r3x-r1x, r3y-r1y, r3z-r1z, r3w-r1w); // top
        setPlane(4, r3x+r2x, r3y+r2y, r3z+r2z, r3w+r2w); // near
        setPlane(5, r3x-r2x, r3y-r2y, r3z-r2z, r3w-r2w); // far
    }

    private void setPlane(int i, float x, float y, float z, float w){
        float len = (float)Math.sqrt(x*x + y*y + z*z);
        if (len < 1e-9f) len = 1f;
        planes[i*4]   = x/len;
        planes[i*4+1] = y/len;
        planes[i*4+2] = z/len;
        planes[i*4+3] = w/len;
    }

    /** true = AABB is (possibly) visible. Positive-vertex test per plane. */
    public boolean cull(AABB box){
        if (box == null || !box.valid()) return false;
        for (int p = 0; p < 6; p++){
            float nx = planes[p*4], ny = planes[p*4+1], nz = planes[p*4+2], d = planes[p*4+3];
            // pick the corner most in the direction of the plane normal
            float px = nx > 0 ? box.max.x : box.min.x;
            float py = ny > 0 ? box.max.y : box.min.y;
            float pz = nz > 0 ? box.max.z : box.min.z;
            if (nx*px + ny*py + nz*pz + d < 0) return true; // outside -> culled
        }
        return false;
    }

    /** NDC-space point (x,y) unprojected to a world ray direction at depth 1 (for the sky pass). */
    public Vec3 rayDir(float ndcX, float ndcY, Mat4 invViewProj){
        Mat4 m = invViewProj;
        float x = ndcX, y = ndcY;
        float wx = m.m[0]*x + m.m[4]*y + m.m[8]*1f + m.m[12];
        float wy = m.m[1]*x + m.m[5]*y + m.m[9]*1f + m.m[13];
        float wz = m.m[2]*x + m.m[6]*y + m.m[10]*1f + m.m[14];
        float ww = m.m[3]*x + m.m[7]*y + m.m[11]*1f + m.m[15];
        if (Math.abs(ww) < 1e-9f) ww = 1e-9f;
        return new Vec3(wx/ww - eye.x, wy/ww - eye.y, wz/ww - eye.z);
    }
}
