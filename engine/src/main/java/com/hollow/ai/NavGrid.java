package com.hollow.ai;

import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.physics.PhysicsWorld;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Walkability grid + A* pathfinding for agents (Agent C).
 *
 *  Built lazily by AISystem from PhysicsWorld static colliders:
 *  - 1 m cells over the world AABB of statics (clamped to 200x200 cells),
 *  - a cell is blocked when any static (non-trigger) shape overlaps its footprint and
 *    rises above walkLevel + 0.3 m while its bottom is below walkLevel + 1.8 m
 *    (walkLevel = lowest static top surface — floors don't block, walls do; low steps
 *    up to 0.3 m stay walkable),
 *  - A* with 8 directions and corner-cut prevention (both orthogonal neighbors must be
 *    free for a diagonal move),
 *  - path smoothing via supercover grid line-of-sight string pulling.
 *  Deterministic: no RNG, fixed neighbor order, heap ties broken by node id.
 */
public final class NavGrid {
    public static final float CELL = 1f;
    public static final int MAX_DIM = 200;

    public final int w, h;
    public final float originX, originZ;
    public final float walkY;
    final boolean[] blocked;

    private NavGrid(int w, int h, float originX, float originZ, float walkY){
        this.w = w; this.h = h;
        this.originX = originX; this.originZ = originZ;
        this.walkY = walkY;
        this.blocked = new boolean[w * h];
    }

    public static NavGrid build(List<PhysicsWorld.StaticShape> shapes){
        if(shapes == null || shapes.isEmpty()) return new NavGrid(1, 1, 0, 0, 0);
        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        float walkY = Float.MAX_VALUE;
        int solids = 0;
        for(PhysicsWorld.StaticShape s : shapes){
            if(s.isTrigger || !s.bounds.valid()) continue;
            solids++;
            minX = Math.min(minX, s.bounds.min.x); maxX = Math.max(maxX, s.bounds.max.x);
            minZ = Math.min(minZ, s.bounds.min.z); maxZ = Math.max(maxZ, s.bounds.max.z);
            walkY = Math.min(walkY, s.bounds.max.y);
        }
        if(solids == 0) return new NavGrid(1, 1, 0, 0, 0);
        minX -= 2; minZ -= 2; maxX += 2; maxZ += 2;
        int w = MathX.clamp((int)Math.ceil(maxX - minX) + 1, 1, MAX_DIM);
        int h = MathX.clamp((int)Math.ceil(maxZ - minZ) + 1, 1, MAX_DIM);
        // clamp coverage around the center when the world exceeds MAX_DIM
        float ox = (maxX - minX) > MAX_DIM ? (minX + maxX) * 0.5f - w * 0.5f : minX;
        float oz = (maxZ - minZ) > MAX_DIM ? (minZ + maxZ) * 0.5f - h * 0.5f : minZ;
        NavGrid g = new NavGrid(w, h, ox, oz, walkY == Float.MAX_VALUE ? 0f : walkY);
        float blockTop = g.walkY + 0.3f;
        float blockBottom = g.walkY + 1.8f;
        for(PhysicsWorld.StaticShape s : shapes){
            if(s.isTrigger || !s.bounds.valid()) continue;
            if(!(s.bounds.max.y > blockTop && s.bounds.min.y < blockBottom)) continue;
            int x0 = MathX.clamp(g.cellX(s.bounds.min.x), 0, g.w - 1);
            int x1 = MathX.clamp(g.cellX(s.bounds.max.x), 0, g.w - 1);
            int z0 = MathX.clamp(g.cellZ(s.bounds.min.z), 0, g.h - 1);
            int z1 = MathX.clamp(g.cellZ(s.bounds.max.z), 0, g.h - 1);
            for(int z = z0; z <= z1; z++)
                for(int x = x0; x <= x1; x++)
                    g.blocked[z * g.w + x] = true;
        }
        return g;
    }

    private static int toCell(float origin, float v){ return (int)Math.floor((v - origin) / CELL); }

    public boolean blocked(int cx, int cz){
        if(cx < 0 || cz < 0 || cx >= w || cz >= h) return true;
        return blocked[cz * w + cx];
    }

    public Vec3 cellCenter(int cx, int cz){
        return new Vec3(originX + (cx + 0.5f) * CELL, walkY, originZ + (cz + 0.5f) * CELL);
    }

    public int cellX(float x){ return toCell(originX, x); }
    public int cellZ(float z){ return toCell(originZ, z); }

    // ------------------------------------------------------------------
    // pathfinding
    // ------------------------------------------------------------------
    private static final int[] DX = { 1, -1, 0, 0, 1, 1, -1, -1 };
    private static final int[] DZ = { 0, 0, 1, -1, 1, -1, 1, -1 };
    private static final float[] COST = { 1f, 1f, 1f, 1f, 1.4142f, 1.4142f, 1.4142f, 1.4142f };

    public List<Vec3> findPath(Vec3 from, Vec3 to){
        List<Vec3> out = new ArrayList<>();
        if(from == null || to == null) return out;
        int s = snap(cellX(from.x), cellZ(from.z));
        int t = snap(cellX(to.x), cellZ(to.z));
        if(s < 0 || t < 0) return out;
        int sX = s / h, sZ = s % h;
        int tX = t / h, tZ = t % h;

        if(sX == tX && sZ == tZ){ out.add(to.cpy()); return out; }

        int n = w * h;
        float[] g = new float[n];
        int[] came = new int[n];
        Arrays.fill(g, Float.MAX_VALUE);
        Arrays.fill(came, -1);
        int start = sZ * w + sX, goal = tZ * w + tX;
        g[start] = 0f;
        Heap heap = new Heap(n + 8);
        heap.push(heur(sX, sZ, tX, tZ), start);

        boolean found = false;
        int guard = n * 8 + 16;
        while(heap.size > 0 && guard-- > 0){
            int cur = heap.pop();
            if(cur == goal){ found = true; break; }
            int cx = cur % w, cz = cur / w;
            float gc = g[cur];
            for(int d = 0; d < 8; d++){
                int nx = cx + DX[d], nz = cz + DZ[d];
                if(blocked(nx, nz)) continue;
                if(d >= 4 && (blocked(cx + DX[d], cz) || blocked(cx, cz + DZ[d]))) continue; // no corner cutting
                int ni = nz * w + nx;
                float ng = gc + COST[d];
                if(ng < g[ni]){
                    g[ni] = ng;
                    came[ni] = cur;
                    heap.push(ng + heur(nx, nz, tX, tZ), ni);
                }
            }
        }
        if(!found) return out;
        // reconstruct cells
        List<Integer> cells = new ArrayList<>();
        for(int c = goal; c >= 0; c = came[c]){
            cells.add(c);
            if(c == start) break;
        }
        java.util.Collections.reverse(cells);
        // string pulling (supercover LOS)
        out.add(cellCenter(sX, sZ));
        int i = 0;
        while(i < cells.size() - 1){
            int j = cells.size() - 1;
            while(j > i + 1 && !losCells(cells.get(i), cells.get(j))) j--;
            int cj = cells.get(j);
            out.add(cellCenter(cj % w, cj / w));
            i = j;
        }
        Vec3 last = out.get(out.size() - 1);
        int gx = cellX(to.x), gz = cellZ(to.z);
        if(last.dist2(to) > 0.01f && !blocked(gx, gz)) out.add(to.cpy());
        return out;
    }

    private float heur(int ax, int az, int bx, int bz){
        float dx = Math.abs(ax - bx), dz = Math.abs(az - bz);
        return dx > dz ? (dx - dz) + 1.4142f * dz : (dz - dx) + 1.4142f * dx;
    }

    /** nearest free cell within radius 4 (deterministic ring order); packed = x*h + z */
    private int snap(int cx, int cz){
        if(!blocked(cx, cz)) return cx * h + cz;
        for(int r = 1; r <= 4; r++){
            for(int dz = -r; dz <= r; dz++){
                for(int dx = -r; dx <= r; dx++){
                    if(Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int nx = cx + dx, nz = cz + dz;
                    if(!blocked(nx, nz)) return nx * h + nz;
                }
            }
        }
        return -1;
    }

    /** deterministic binary min-heap: key asc, node id asc on ties */
    private static final class Heap {
        float[] keys; int[] nodes; int size;
        Heap(int cap){ keys = new float[Math.max(16, cap)]; nodes = new int[keys.length]; }
        void push(float key, int node){
            if(size >= keys.length) grow();
            int i = size++;
            keys[i] = key; nodes[i] = node;
            while(i > 0){
                int p = (i - 1) >> 1;
                if(!less(i, p)) break;
                swap(p, i);
                i = p;
            }
        }
        int pop(){
            int top = nodes[0];
            size--;
            if(size > 0){
                keys[0] = keys[size]; nodes[0] = nodes[size];
                int i = 0;
                while(true){
                    int l = i * 2 + 1, r = l + 1, m = i;
                    if(l < size && less(l, m)) m = l;
                    if(r < size && less(r, m)) m = r;
                    if(m == i) break;
                    swap(m, i);
                    i = m;
                }
            }
            return top;
        }
        boolean less(int a, int b){
            if(keys[a] != keys[b]) return keys[a] < keys[b];
            return nodes[a] < nodes[b];
        }
        void swap(int a, int b){
            float tk = keys[a]; keys[a] = keys[b]; keys[b] = tk;
            int tn = nodes[a]; nodes[a] = nodes[b]; nodes[b] = tn;
        }
        void grow(){
            float[] nk = new float[keys.length * 2];
            int[] nn = new int[keys.length * 2];
            System.arraycopy(keys, 0, nk, 0, keys.length);
            System.arraycopy(nodes, 0, nn, 0, nodes.length);
            keys = nk; nodes = nn;
        }
    }

    // ------------------------------------------------------------------
    // line of sight (supercover DDA over the grid)
    // ------------------------------------------------------------------
    boolean losCells(int cellA, int cellB){
        return lineOfSight(originX + (cellA % w + 0.5f) * CELL, originZ + (cellA / w + 0.5f) * CELL,
                           originX + (cellB % w + 0.5f) * CELL, originZ + (cellB / w + 0.5f) * CELL);
    }

    public boolean lineOfSight(float ax, float az, float bx, float bz){
        int x = cellX(ax), z = cellZ(az);
        int ex = cellX(bx), ez = cellZ(bz);
        if(blocked(x, z) || blocked(ex, ez)) return false;
        float dx = bx - ax, dz = bz - az;
        int stepX = dx > 1e-9f ? 1 : (dx < -1e-9f ? -1 : 0);
        int stepZ = dz > 1e-9f ? 1 : (dz < -1e-9f ? -1 : 0);
        float tMaxX = stepX != 0 ? ((stepX > 0 ? (originX + (x + 1) * CELL) : (originX + x * CELL)) - ax) / dx : Float.MAX_VALUE;
        float tMaxZ = stepZ != 0 ? ((stepZ > 0 ? (originZ + (z + 1) * CELL) : (originZ + z * CELL)) - az) / dz : Float.MAX_VALUE;
        float tDX = stepX != 0 ? CELL / Math.abs(dx) : Float.MAX_VALUE;
        float tDZ = stepZ != 0 ? CELL / Math.abs(dz) : Float.MAX_VALUE;
        int guard = w + h + 8;
        while(guard-- > 0){
            if(x == ex && z == ez) return true;
            if(tMaxX < tMaxZ - 1e-9f){
                x += stepX; tMaxX += tDX;
            } else if(tMaxZ < tMaxX - 1e-9f){
                z += stepZ; tMaxZ += tDZ;
            } else {
                // exact corner crossing: conservative — both side cells must be free
                if(blocked(x + stepX, z) || blocked(x, z + stepZ)) return false;
                x += stepX; z += stepZ;
                tMaxX += tDX; tMaxZ += tDZ;
            }
            if(x == ex && z == ez) return true;
            if(blocked(x, z)) return false;
        }
        return false;
    }
}
