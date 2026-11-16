package com.hollow.physics;

import com.hollow.core.MathX.Vec3;
import com.hollow.scene.Node;
import com.hollow.scene.Scene;

import java.util.ArrayList;
import java.util.List;

/** HollowEngine 2 — deterministic physics: AABB volumes derived from
 *  node scale, gravity integration, minimal-axis penetration resolve,
 *  restitution (bounciness). Static (non-dynamic) nodes never move;
 *  two dynamic nodes split the push half/half. Order follows the
 *  scene tree — same input, same result. */
public final class Physics {

    public static final class Collision {
        public final Node a, b;
        public final String axis;                   // "x" | "y" | "z"
        public Collision(Node a, Node b, String axis){
            this.a = a; this.b = b; this.axis = axis;
        }
    }

    /** integrate dynamics + resolve overlaps; returns collisions in tree order */
    public List<Collision> step(Scene scene, double dt){
        List<Collision> hits = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        scene.root.walk(nodes::add);

        for(Node n : nodes){
            if(!n.dynamic || !n.visible) continue;
            n.velocity.y -= scene.gravity * dt;
            n.position.add(new Vec3((float)(n.velocity.x * dt), (float)(n.velocity.y * dt), (float)(n.velocity.z * dt)));
        }

        for(int pass = 0; pass < 2; pass++){         // two passes settle stacked pairs
            for(Node a : nodes){
                if(!a.dynamic || !a.visible) continue;
                for(Node b : nodes){
                    if(b == a || b == scene.root || !b.visible) continue;
                    // solid bodies: meshes and plain nodes only —
                    // cameras, lights and scripts have no physical volume
                    if(!(b.type().equals("mesh") || b.type().equals("node"))) continue;
                    if(a.worldPosition().y < -500) continue;    // fell out of the world
                    Vec3 pa = a.position, pb = b.position;
                    float ax = a.scale.x, ay = a.scale.y, az = a.scale.z;
                    float bx = b.scale.x, by = b.scale.y, bz = b.scale.z;
                    float ox = ax + bx - Math.abs(pa.x - pb.x);
                    float oy = ay + by - Math.abs(pa.y - pb.y);
                    float oz = az + bz - Math.abs(pa.z - pb.z);
                    if(ox <= 0 || oy <= 0 || oz <= 0) continue;

                    // minimal penetration axis
                    String axis; float depth, dir;
                    if(oy <= ox && oy <= oz){ axis = "y"; depth = oy; dir = pa.y >= pb.y ? 1 : -1; }
                    else if(ox <= oz){ axis = "x"; depth = ox; dir = pa.x >= pb.x ? 1 : -1; }
                    else { axis = "z"; depth = oz; dir = pa.z >= pb.z ? 1 : -1; }

                    boolean bothDynamic = b.dynamic;
                    float pushA = bothDynamic ? depth / 2 : depth;
                    float pushB = bothDynamic ? depth / 2 : 0;
                    if(axis.equals("y")){
                        a.position.y += dir * pushA;
                        if(bothDynamic) b.position.y -= dir * pushB;
                        float vy = a.velocity.y;
                        if(dir > 0 ? vy < 0 : vy > 0)
                            a.velocity.y = Math.abs(vy) < 0.5f ? 0 : -vy * a.bounciness;
                    } else if(axis.equals("x")){
                        a.position.x += dir * pushA;
                        if(bothDynamic) b.position.x -= dir * pushB;
                        float vx = a.velocity.x;
                        if(dir > 0 ? vx < 0 : vx > 0)
                            a.velocity.x = Math.abs(vx) < 0.5f ? 0 : -vx * a.bounciness;
                    } else {
                        a.position.z += dir * pushA;
                        if(bothDynamic) b.position.z -= dir * pushB;
                        float vz = a.velocity.z;
                        if(dir > 0 ? vz < 0 : vz > 0)
                            a.velocity.z = Math.abs(vz) < 0.5f ? 0 : -vz * a.bounciness;
                    }
                    hits.add(new Collision(a, b, axis));
                }
            }
        }
        return hits;
    }

    /** ray vs AABB (slab method) — used by the editor's picking.
     *  Returns distance along dir, or -1 if no hit. */
    public static float rayBox(Vec3 origin, Vec3 dir, Vec3 center, Vec3 half){
        float tmin = 0, tmax = Float.MAX_VALUE;
        float[] o = { origin.x, origin.y, origin.z };
        float[] d = { dir.x, dir.y, dir.z };
        float[] c = { center.x, center.y, center.z };
        float[] h = { half.x, half.y, half.z };
        for(int i = 0; i < 3; i++){
            if(Math.abs(d[i]) < 1e-8f){
                if(Math.abs(o[i] - c[i]) > h[i]) return -1;
                continue;
            }
            float t1 = (c[i] - h[i] - o[i]) / d[i];
            float t2 = (c[i] + h[i] - o[i]) / d[i];
            if(t1 > t2){ float t = t1; t1 = t2; t2 = t; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if(tmin > tmax) return -1;
        }
        return tmin;
    }
}
