package com.hollow.physics;

import com.hollow.core.AABB;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Grid-based navigation (A*, 8-dir, no corner cutting) built from static colliders. */
public final class NavGrid {
    public static final float CELL = 1f;

    private int cols, rows;
    private float originX, originZ;
    private boolean[] blocked;
    private boolean built = false;
    private final PhysicsWorld physics;

    public NavGrid(PhysicsWorld physics){ this.physics = physics; }

    public synchronized void buildIfNeeded(){
        if(built) return;
        built = true;
        AABB world = new AABB().reset();
        List<PhysicsWorld.StaticShape> shapes = physics.staticShapes();
        boolean any = false;
        for(PhysicsWorld.StaticShape s : shapes){
            if(s.isTrigger || s.kind == PhysicsWorld.KIND_SPHERE) continue;
            // only walls/obstacles tall enough to block walking
            if(s.bounds.max.y < 0.4f) continue;
            world.expand(s.bounds);
            any = true;
        }
        if(!any){ cols = 1; rows = 1; blocked = new boolean[1]; return; }
        // margin so paths hug obstacles
        originX = world.min.x - CELL;
        originZ = world.min.z - CELL;
        cols = Math.min(240, (int)Math.ceil((world.max.x - world.min.x + 2*CELL) / CELL) + 1);
        rows = Math.min(240, (int)Math.ceil((world.max.z - world.min.z + 2*CELL) / CELL) + 1);
        cols = Math.max(1, cols); rows = Math.max(1, rows);
        blocked = new boolean[cols*rows];
        for(PhysicsWorld.StaticShape s : shapes){
            if(s.isTrigger) continue;
            if(s.bounds.max.y < 0.4f) continue;             // low geometry = walkable
            if(s.bounds.min.y > 1.6f) continue;             // overhead = walkable
            int c0 = colOf(s.bounds.min.x), c1 = colOf(s.bounds.max.x);
            int r0 = rowOf(s.bounds.min.z), r1 = rowOf(s.bounds.max.z);
            for(int r=Math.max(0,r0); r<=Math.min(rows-1,r1); r++)
                for(int c=Math.max(0,c0); c<=Math.min(cols-1,c1); c++)
                    blocked[r*cols+c] = true;
        }
    }

    private int colOf(float x){ return (int)Math.floor((x - originX) / CELL); }
    private int rowOf(float z){ return (int)Math.floor((z - originZ) / CELL); }
    private float xOf(int c){ return originX + (c + 0.5f) * CELL; }
    private float zOf(int r){ return originZ + (r + 0.5f) * CELL; }
    private boolean inBounds(int c, int r){ return c >= 0 && r >= 0 && c < cols && r < rows; }
    private boolean walkable(int c, int r){ return inBounds(c, r) && !blocked[r*cols+c]; }

    public boolean isBlocked(Vec3 pos){
        buildIfNeeded();
        int c = colOf(pos.x), r = rowOf(pos.z);
        return !walkable(c, r);
    }

    /** A* 8-directional; returns smoothed world-space waypoints (may be empty if no path) */
    public List<Vec3> findPath(Vec3 from, Vec3 to){
        buildIfNeeded();
        int sc = clampCol(colOf(from.x)), sr = clampRow(rowOf(from.z));
        int tc = clampCol(colOf(to.x)), tr = clampRow(rowOf(to.z));
        final int ftc = tc, ftr = tr;
        List<Vec3> out = new ArrayList<>();
        if(!walkable(tc, tr)){
            // find nearest walkable target cell (spiral)
            int[] found = nearestWalkable(tc, tr, 6);
            if(found == null) return out;
            tc = found[0]; tr = found[1];
        }
        if(!walkable(sc, sr)){
            int[] found = nearestWalkable(sc, sr, 6);
            if(found != null){ sc = found[0]; sr = found[1]; }
        }
        // A*
        Map<Integer, Float> g = new HashMap<>();
        Map<Integer, Integer> came = new HashMap<>();
        java.util.PriorityQueue<int[]> open = new java.util.PriorityQueue<>(
                (a, b) -> Float.compare(g.getOrDefault(a[0], Float.MAX_VALUE) + h(a[1]%cols, a[1]/cols, ftc, ftr),
                                        g.getOrDefault(b[0], Float.MAX_VALUE) + h(b[1]%cols, b[1]/cols, ftc, ftr)));
        int start = sr*cols+sc, goal = tr*cols+tc;
        g.put(start, 0f);
        open.add(new int[]{start, start});
        int guard = 0;
        while(!open.isEmpty() && guard++ < 20000){
            int[] cur = open.poll();
            int node = cur[0];
            if(node == goal) break;
            int cc = node % cols, cr = node / cols;
            for(int dr=-1; dr<=1; dr++){
                for(int dc=-1; dc<=1; dc++){
                    if(dc==0 && dr==0) continue;
                    int nc = cc+dc, nr = cr+dr;
                    if(!walkable(nc, nr)) continue;
                    if(dc != 0 && dr != 0){ // no corner cutting
                        if(!walkable(cc+dc, cr) || !walkable(cc, cr+dr)) continue;
                    }
                    int next = nr*cols+nc;
                    float step = (dc != 0 && dr != 0) ? 1.4142f : 1f;
                    float ng = g.getOrDefault(node, Float.MAX_VALUE) + step;
                    if(ng < g.getOrDefault(next, Float.MAX_VALUE)){
                        g.put(next, ng);
                        came.put(next, node);
                        open.add(new int[]{next, next});
                    }
                }
            }
        }
        if(!came.containsKey(goal) && start != goal) return out;
        // reconstruct
        List<Integer> cells = new ArrayList<>();
        int node = goal;
        while(node != start){
            cells.add(node);
            Integer p = came.get(node);
            if(p == null) break;
            node = p;
        }
        Collections.reverse(cells);
        // string-pull smoothing: keep node only if LOS broken
        Vec3 prev = new Vec3(xOf(sc), 0, zOf(sr));
        for(int i=0;i<cells.size();i++){
            int cell = cells.get(i);
            Vec3 wp = new Vec3(xOf(cell % cols), 0, zOf(cell / cols));
            boolean last = i == cells.size()-1;
            if(last || !lineClear(prev, wp)){
                out.add(new Vec3(wp.x, from.y, wp.z));
                prev = wp;
            }
        }
        if(!out.isEmpty()) out.get(out.size()-1).set(to.x, from.y, to.z);
        return out;
    }

    private float h(int c, int r, int tc, int tr){
        int dx = Math.abs(c-tc), dz = Math.abs(r-tr);
        return Math.max(dx, dz) + 0.4142f * Math.min(dx, dz);
    }
    private int clampCol(int c){ return Math.max(0, Math.min(cols-1, c)); }
    private int clampRow(int r){ return Math.max(0, Math.min(rows-1, r)); }

    private int[] nearestWalkable(int c, int r, int radius){
        for(int rad=1; rad<=radius; rad++){
            for(int dr=-rad; dr<=rad; dr++){
                for(int dc=-rad; dc<=rad; dc++){
                    if(Math.abs(dr)!=rad && Math.abs(dc)!=rad) continue;
                    if(walkable(c+dc, r+dr)) return new int[]{c+dc, r+dr};
                }
            }
        }
        return null;
    }

    /** coarse LOS on grid (Bresenham) */
    private boolean lineClear(Vec3 a, Vec3 b){
        int c0 = colOf(a.x), r0 = rowOf(a.z), c1 = colOf(b.x), r1 = rowOf(b.z);
        int dc = Math.abs(c1-c0), dr = Math.abs(r1-r0);
        int sc = c0 < c1 ? 1 : -1, sr = r0 < r1 ? 1 : -1;
        int err = dc - dr, c = c0, r = r0;
        int guard = 0;
        while((c != c1 || r != r1) && guard++ < 512){
            int e2 = err*2;
            if(e2 > -dr){ err -= dr; c += sc; }
            if(e2 < dc){ err += dc; r += sr; }
            if(!walkable(c, r)) return false;
        }
        return true;
    }
}
