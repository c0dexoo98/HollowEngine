package com.hollow.render;

import com.hollow.core.MathX.Mat4;
import com.hollow.core.MathX.Vec3;

/** HollowEngine 2 — software rasterizer.
 *  Real 3D pipeline: model/view/projection, perspective divide,
 *  viewport mapping, triangle rasterization with a z-buffer,
 *  Lambert diffuse + Blinn specular + ambient, double-sided normals,
 *  distance fog, planar ground shadows, and camera ray picking.
 *  No GPU, no dependencies — the same code drives the CLI, the
 *  editor viewport and the test suites. */
public final class Rasterizer {

    public static final float FOG_NEAR = 12f;
    public static final float FOG_FAR = 50f;
    public static final float SHADOW_DARKEN = 0.55f;
    static final float SHADOW_BIAS = 2e-3f;

    /** lights: direction (pointing from sky toward ground), color, intensity */
    public static final class Light {
        public final Vec3 dir;
        public final int color;
        public final float intensity;
        public Light(Vec3 dir, int color, float intensity){
            this.dir = dir; this.color = color; this.intensity = intensity;
        }
    }

    // ------------------------- draw one mesh -------------------------

    public static void drawMesh(Frame f, Mesh mesh, Mat4 model, Mat4 view, Mat4 proj,
                                Vec3 eye, Light light, int albedo, float ambient){
        drawMesh(f, mesh, model, view, proj, eye, light, albedo, ambient, 0f, 0xFF000000);
    }

    /** full path with distance fog (fog 0..1, blended toward fogColor) */
    public static void drawMesh(Frame f, Mesh mesh, Mat4 model, Mat4 view, Mat4 proj,
                                Vec3 eye, Light light, int albedo, float ambient,
                                float fog, int fogColor){
        int n = mesh.vertexCount();
        float[] wx = new float[n], wy = new float[n], wz = new float[n];
        float[] sx = new float[n], sy = new float[n], sz = new float[n];
        float[] dist = new float[n];
        boolean visible = false;
        Mat4 vp = proj.copy().mul(view);
        for(int i = 0; i < n; i++){
            Vec3 w = model.transformPoint(new Vec3(mesh.pos[i * 3], mesh.pos[i * 3 + 1], mesh.pos[i * 3 + 2]));
            wx[i] = w.x; wy[i] = w.y; wz[i] = w.z;
            dist[i] = w.copy().sub(eye).len();
            Vec3 c = vp.transformPoint(w);
            if(c.z >= 1f || c.z <= -1f){ sx[i] = Float.NaN; sy[i] = Float.NaN; sz[i] = Float.NaN; continue; }
            sx[i] = (c.x * 0.5f + 0.5f) * f.w;
            sy[i] = (1f - (c.y * 0.5f + 0.5f)) * f.h;
            sz[i] = c.z;
            visible = true;
        }
        if(!visible) return;

        int[] al = new int[]{ (albedo >> 16) & 0xFF, (albedo >> 8) & 0xFF, albedo & 0xFF };
        int[] lc = new int[]{ (light.color >> 16) & 0xFF, (light.color >> 8) & 0xFF, light.color & 0xFF };
        int[] fc = new int[]{ (fogColor >> 16) & 0xFF, (fogColor >> 8) & 0xFF, fogColor & 0xFF };

        for(int t = 0; t + 2 < mesh.idx.length; t += 3){
            int a = mesh.idx[t], b = mesh.idx[t + 1], c = mesh.idx[t + 2];
            if(Float.isNaN(sx[a]) || Float.isNaN(sx[b]) || Float.isNaN(sx[c])) continue;
            fillTriangle(f, sx[a], sy[a], sz[a], sx[b], sy[b], sz[b], sx[c], sy[c], sz[c],
                         wx, wy, wz, dist, a, b, c, eye, light, al, lc, ambient, fc, fog);
        }
    }

    // ------------------------- triangle fill -------------------------

    private static void fillTriangle(Frame f, float ax, float ay, float az,
                                     float bx, float by, float bz,
                                     float cx, float cy, float cz,
                                     float[] wx, float[] wy, float[] wz, float[] dist,
                                     int ia, int ib, int ic,
                                     Vec3 eye, Light light, int[] al, int[] lc,
                                     float ambient, int[] fc, float fog){
        float minX = Math.max(0, (float)Math.floor(Math.min(ax, Math.min(bx, cx))));
        float maxX = Math.min(f.w - 1, (float)Math.ceil(Math.max(ax, Math.max(bx, cx))));
        float minY = Math.max(0, (float)Math.floor(Math.min(ay, Math.min(by, cy))));
        float maxY = Math.min(f.h - 1, (float)Math.ceil(Math.max(ay, Math.max(by, cy))));
        if(minX > maxX || minY > maxY) return;

        float area = edge(ax, ay, bx, by, cx, cy);
        if(Math.abs(area) < 1e-9f) return;

        for(int y = (int)minY; y <= (int)maxY; y++){
            for(int x = (int)minX; x <= (int)maxX; x++){
                float px = x + 0.5f, py = y + 0.5f;
                float w0 = edge(ax, ay, bx, by, px, py);
                float w1 = edge(bx, by, cx, cy, px, py);
                float w2 = edge(cx, cy, ax, ay, px, py);
                if((w0 < 0 || w1 < 0 || w2 < 0) && (w0 > 0 || w1 > 0 || w2 > 0)) continue;  // outside (any sign)
                w0 /= area; w1 /= area; w2 /= area;
                if(w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
                float z = w0 * az + w1 * bz + w2 * cz;
                int di = y * f.w + x;
                if(z >= f.depth[di]) continue;

                // face normal in world space (double-sided: flip toward viewer)
                float e1x = wx[ib] - wx[ia], e1y = wy[ib] - wy[ia], e1z = wz[ib] - wz[ia];
                float e2x = wx[ic] - wx[ia], e2y = wy[ic] - wy[ia], e2z = wz[ic] - wz[ia];
                float nx = e1y * e2z - e1z * e2y;
                float ny = e1z * e2x - e1x * e2z;
                float nz = e1x * e2y - e1y * e2x;
                Vec3 n = new Vec3(nx, ny, nz).norm();
                Vec3 v = new Vec3(eye.x - wx[ia], eye.y - wy[ia], eye.z - wz[ia]).norm();
                if(n.dot(v) < 0) n.scale(-1);

                float shade = ambient + Math.max(0, n.dot(new Vec3(-light.dir.x, -light.dir.y, -light.dir.z))) * light.intensity;
                // Blinn specular
                Vec3 h = new Vec3(-light.dir.x + v.x, -light.dir.y + v.y, -light.dir.z + v.z).norm();
                float spec = (float)Math.pow(Math.max(0, n.dot(h)), 32) * 0.45f * light.intensity;

                int r = clampByte((int)(al[0] * shade + lc[0] * spec));
                int g = clampByte((int)(al[1] * shade + lc[1] * spec));
                int bl = clampByte((int)(al[2] * shade + lc[2] * spec));

                // distance fog toward the horizon color
                if(fog > 0){
                    float d = w0 * dist[ia] + w1 * dist[ib] + w2 * dist[ic];
                    float fb = fog * Math.max(0, Math.min(1, (d - FOG_NEAR) / (FOG_FAR - FOG_NEAR)));
                    if(fb > 0){
                        r = clampByte((int)(r + (fc[0] - r) * fb));
                        g = clampByte((int)(g + (fc[1] - g) * fb));
                        bl = clampByte((int)(bl + (fc[2] - bl) * fb));
                    }
                }

                f.pixels[di] = 0xFF000000 | (r << 16) | (g << 8) | bl;
                f.depth[di] = z;
            }
        }
    }

    // ------------------------- planar shadows -------------------------

    /** project the mesh onto the horizontal plane y = planeY along the
     *  light direction, then darken covered pixels (depth-tested with a
     *  small bias so the shadow lands exactly on the surface). */
    public static void drawShadow(Frame f, Mesh mesh, Mat4 model, Mat4 view, Mat4 proj,
                                  Light light, float planeY){
        if(light.dir.y > -1e-4f) return;                 // light parallel to the plane
        int n = mesh.vertexCount();
        float[] sx = new float[n], sy = new float[n], sz = new float[n];
        boolean visible = false;
        Mat4 vp = proj.copy().mul(view);
        for(int i = 0; i < n; i++){
            Vec3 w = model.transformPoint(new Vec3(mesh.pos[i * 3], mesh.pos[i * 3 + 1], mesh.pos[i * 3 + 2]));
            float t = (planeY - w.y) / light.dir.y;
            Vec3 p = new Vec3(w.x + light.dir.x * t, planeY, w.z + light.dir.z * t);
            Vec3 c = vp.transformPoint(p);
            if(c.z >= 1f || c.z <= -1f){ sx[i] = Float.NaN; continue; }
            sx[i] = (c.x * 0.5f + 0.5f) * f.w;
            sy[i] = (1f - (c.y * 0.5f + 0.5f)) * f.h;
            sz[i] = c.z - SHADOW_BIAS;
            visible = true;
        }
        if(!visible) return;

        for(int t = 0; t + 2 < mesh.idx.length; t += 3){
            int a = mesh.idx[t], b = mesh.idx[t + 1], c = mesh.idx[t + 2];
            if(Float.isNaN(sx[a]) || Float.isNaN(sx[b]) || Float.isNaN(sx[c])) continue;
            fillShadow(f, sx[a], sy[a], sz[a], sx[b], sy[b], sz[b], sx[c], sy[c], sz[c]);
        }
    }

    private static void fillShadow(Frame f, float ax, float ay, float az,
                                   float bx, float by, float bz,
                                   float cx, float cy, float cz){
        float minX = Math.max(0, (float)Math.floor(Math.min(ax, Math.min(bx, cx))));
        float maxX = Math.min(f.w - 1, (float)Math.ceil(Math.max(ax, Math.max(bx, cx))));
        float minY = Math.max(0, (float)Math.floor(Math.min(ay, Math.min(by, cy))));
        float maxY = Math.min(f.h - 1, (float)Math.ceil(Math.max(ay, Math.max(by, cy))));
        if(minX > maxX || minY > maxY) return;
        float area = edge(ax, ay, bx, by, cx, cy);
        if(Math.abs(area) < 1e-9f) return;

        for(int y = (int)minY; y <= (int)maxY; y++){
            for(int x = (int)minX; x <= (int)maxX; x++){
                float px = x + 0.5f, py = y + 0.5f;
                float w0 = edge(ax, ay, bx, by, px, py);
                float w1 = edge(bx, by, cx, cy, px, py);
                float w2 = edge(cx, cy, ax, ay, px, py);
                if((w0 < 0 || w1 < 0 || w2 < 0) && (w0 > 0 || w1 > 0 || w2 > 0)) continue;
                w0 /= area; w1 /= area; w2 /= area;
                if(w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
                float z = w0 * az + w1 * bz + w2 * cz;
                int di = y * f.w + x;
                if(z >= f.depth[di]) continue;
                int p = f.pixels[di];
                int r = (int)(((p >> 16) & 0xFF) * SHADOW_DARKEN);
                int g = (int)(((p >> 8) & 0xFF) * SHADOW_DARKEN);
                int bl = (int)((p & 0xFF) * SHADOW_DARKEN);
                f.pixels[di] = 0xFF000000 | (r << 16) | (g << 8) | bl;
                f.depth[di] = z;                          // one darken per pixel
            }
        }
    }

    private static float edge(float ax, float ay, float bx, float by, float px, float py){
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    private static int clampByte(int v){
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ------------------------- camera helpers -------------------------

    public static Mat4 cameraView(Vec3 eye, Vec3 forwardDeg){
        // a camera with rotation (0,0,0) looks down -Z
        Vec3 fwd = forwardFromDeg(forwardDeg);
        Vec3 target = new Vec3(eye.x + fwd.x, eye.y + fwd.y, eye.z + fwd.z);
        return new Mat4().lookAt(eye, target, new Vec3(0, 1, 0));
    }

    public static Vec3 forwardFromDeg(Vec3 rotDeg){
        double ax = Math.toRadians(rotDeg.x);
        double ay = Math.toRadians(rotDeg.y);
        return new Vec3((float)(Math.sin(ay) * Math.cos(ax) * -1f),
                        (float)Math.sin(ax),
                        (float)(-Math.cos(ay) * Math.cos(ax))).norm();
    }

    public static final class Ray {
        public final Vec3 origin, dir;
        Ray(Vec3 o, Vec3 d){ origin = o; dir = d; }
    }

    /** world-space ray through a viewport pixel — editor picking */
    public static Ray cameraRay(int w, int h, Vec3 eye, Vec3 forwardDeg,
                                float fovDeg, float px, float py){
        Vec3 fwd = forwardFromDeg(forwardDeg);
        Vec3 right = fwd.cross(new Vec3(0, 1, 0)).norm();
        Vec3 up = right.cross(fwd).norm();
        float ndcX = (px / w) * 2f - 1f;
        float ndcY = 1f - (py / h) * 2f;
        float tan = (float)Math.tan(Math.toRadians(fovDeg) * 0.5f);
        Vec3 d = fwd.copy()
            .add(right.copy().scale(ndcX * tan))
            .add(up.copy().scale(ndcY * tan))
            .norm();
        return new Ray(eye, d);
    }
}
