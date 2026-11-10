package com.hollow.render;

import com.hollow.core.AABB;
import com.hollow.core.Color;
import com.hollow.core.Vec3;

/** Immediate-mode debug geometry queue. Games/AI/physics push lines/spheres/boxes;
 *  the renderer drains the queue each frame (GL_LINES, depth-tested) and calls clear().
 *  Pure Java — safe to call headless. Thread model: same thread as render (GL thread).
 */
public final class DebugDraw {
    private DebugDraw() {}

    private static float[] pos = new float[6 * 512];   // per line: ax,ay,az, bx,by,bz
    private static float[] col = new float[8 * 512];   // per line: ar,ag,ab,aa, br,bg,bb,ba
    private static int lineCount = 0;

    /** push a line segment */
    public static void line(Vec3 a, Vec3 b, Color c){
        pushLine(a.x, a.y, a.z, b.x, b.y, b.z, c);
    }

    public static void line(float ax, float ay, float az, float bx, float by, float bz, Color c){
        pushLine(ax, ay, az, bx, by, bz, c);
    }

    /** wireframe sphere: 3 orthogonal circles, 24 segments each */
    public static void sphere(Vec3 center, float radius, Color c){
        final int SEG = 24;
        for (int ring = 0; ring < 3; ring++){
            float px = 0, py = 0, pz = 0;
            for (int s = 0; s <= SEG; s++){
                float t = s / (float)SEG * 2f * (float)Math.PI;
                float x, y, z;
                switch (ring){
                    case 0: x = (float)Math.cos(t); y = (float)Math.sin(t); z = 0; break; // XY
                    case 1: x = 0; y = (float)Math.cos(t); z = (float)Math.sin(t); break; // YZ
                    default: x = (float)Math.cos(t); y = 0; z = (float)Math.sin(t); break; // XZ
                }
                x *= radius; y *= radius; z *= radius;
                if (s > 0){
                    pushLine(center.x + px, center.y + py, center.z + pz,
                             center.x + x,  center.y + y,  center.z + z, c);
                }
                px = x; py = y; pz = z;
            }
        }
    }

    /** wireframe box: 12 edges */
    public static void box(AABB b, Color c){
        if (b == null || !b.valid()) return;
        float x0=b.min.x, y0=b.min.y, z0=b.min.z, x1=b.max.x, y1=b.max.y, z1=b.max.z;
        // bottom ring
        line(x0,y0,z0, x1,y0,z0, c); line(x1,y0,z0, x1,y0,z1, c);
        line(x1,y0,z1, x0,y0,z1, c); line(x0,y0,z1, x0,y0,z0, c);
        // top ring
        line(x0,y1,z0, x1,y1,z0, c); line(x1,y1,z0, x1,y1,z1, c);
        line(x1,y1,z1, x0,y1,z1, c); line(x0,y1,z1, x0,y1,z0, c);
        // verticals
        line(x0,y0,z0, x0,y1,z0, c); line(x1,y0,z0, x1,y1,z0, c);
        line(x1,y0,z1, x1,y1,z1, c); line(x0,y0,z1, x0,y1,z1, c);
    }

    private static void pushLine(float ax, float ay, float az, float bx, float by, float bz, Color c){
        if (lineCount * 6 + 6 > pos.length){
            pos = java.util.Arrays.copyOf(pos, pos.length * 2);
            col = java.util.Arrays.copyOf(col, col.length * 2);
        }
        int p = lineCount * 6, q = lineCount * 8;
        pos[p]=ax; pos[p+1]=ay; pos[p+2]=az; pos[p+3]=bx; pos[p+4]=by; pos[p+5]=bz;
        col[q]=c.r; col[q+1]=c.g; col[q+2]=c.b; col[q+3]=c.a;
        col[q+4]=c.r; col[q+5]=c.g; col[q+6]=c.b; col[q+7]=c.a;
        lineCount++;
    }

    // ---------------- consumed by the renderer ----------------
    public static int lineCount(){ return lineCount; }
    /** 6 floats per line (two xyz endpoints) */
    public static float[] linePositions(){ return pos; }
    /** 8 floats per line (two rgba endpoints) */
    public static float[] lineColors(){ return col; }

    /** drop all queued geometry — called by the renderer after consuming */
    public static void clear(){ lineCount = 0; }
}
