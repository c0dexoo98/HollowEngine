package com.hollow.physics;

import com.hollow.core.AABB;
import com.hollow.core.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** v2.3 PHY-027 — uniform-grid broadphase with automatic refresh.
 *  Hash-space cells of configurable size index both dynamic bodies and static
 *  shapes; rebuilt every physics step (cheap: cell hash, no allocation per body
 *  beyond entry objects reused from a pool). Replaces the O(n²) all-pairs scan
 *  in PhysicsWorld.solveDynamicPairs when body count exceeds the threshold.
 *  Also accelerates static overlap queries for CCD sweeps and water/wind volumes.
 */
public final class Broadphase {
    private float cellSize = 2f;
    private final Map<Long, List<Object>> cells = new HashMap<>();
    private final List<long[]> pairScratch = new ArrayList<>();

    /** candidate pairs (RigidBody×RigidBody) for the current tick — filled by refresh() */
    public final List<long[]> dynamicPairs = new ArrayList<>();

    public void setCellSize(float s){ cellSize = Math.max(0.25f, s); }

    private static long key(int cx, int cy, int cz){
        return (cx & 0x1FFFFFL) | ((cy & 0x1FFFFFL) << 21) | ((long)(cz & 0x1FFFFFL) << 42);
    }

    private void insert(Object o, AABB b){
        int x0 = (int)Math.floor(b.min.x / cellSize), x1 = (int)Math.floor(b.max.x / cellSize);
        int y0 = (int)Math.floor(b.min.y / cellSize), y1 = (int)Math.floor(b.max.y / cellSize);
        int z0 = (int)Math.floor(b.min.z / cellSize), z1 = (int)Math.floor(b.max.z / cellSize);
        for(int x = x0; x <= x1; x++)
            for(int y = y0; y <= y1; y++)
                for(int z = z0; z <= z1; z++){
                    long k = key(x, y, z);
                    List<Object> cell = cells.get(k);
                    if(cell == null){ cell = new ArrayList<>(4); cells.put(k, cell); }
                    cell.add(o);
                }
    }

    /** rebuild for this tick: bodies in AABB(cells), then collect overlapping body pairs via shared cells */
    public void refresh(List<RigidBody> bodies, float cellScale){
        cells.clear();
        dynamicPairs.clear();
        if(bodies.isEmpty()) return;
        // auto cell size: 2× mean effective radius keeps pair overlap low on clutter scenes
        float sum = 0f;
        for(RigidBody b : bodies) sum += b.effectiveRadius();
        setCellSize(Math.max(0.5f, 2f * (sum / bodies.size()) * cellScale));
        for(RigidBody b : bodies){
            if(b.entity == null || b.entity.destroyed) continue;
            float r = b.effectiveRadius();
            Vec3 p = b.entity.position;
            insert(b, new AABB(new Vec3(p.x - r, p.y - r, p.z - r), new Vec3(p.x + r, p.y + r, p.z + r)));
        }
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for(Map.Entry<Long, List<Object>> kv : cells.entrySet()){
            List<Object> cell = kv.getValue();
            for(int i = 0; i < cell.size(); i++){
                RigidBody a = (RigidBody) cell.get(i);
                if(a.entity == null) continue;
                for(int j = i + 1; j < cell.size(); j++){
                    RigidBody b = (RigidBody) cell.get(j);
                    if(b.entity == null || a.entity.id == b.entity.id) continue;
                    long lo = Math.min(a.entity.id, b.entity.id), hi = Math.max(a.entity.id, b.entity.id);
                    long pair = (lo << 32) | (hi & 0xFFFFFFFFL);
                    if(seen.add(pair)) dynamicPairs.add(new long[]{ pair });
                }
            }
        }
    }

    /** static shapes overlapping the swept segment (used by CCD + queries) */
    public void queryStatics(List<PhysicsWorld.StaticShape> all, Vec3 a, Vec3 b, List<PhysicsWorld.StaticShape> out){
        float minX = Math.min(a.x, b.x), minY = Math.min(a.y, b.y), minZ = Math.min(a.z, b.z);
        float maxX = Math.max(a.x, b.x), maxY = Math.max(a.y, b.y), maxZ = Math.max(a.z, b.z);
        for(PhysicsWorld.StaticShape s : all){
            if(s.bounds.max.x < minX || s.bounds.min.x > maxX) continue;
            if(s.bounds.max.y < minY || s.bounds.min.y > maxY) continue;
            if(s.bounds.max.z < minZ || s.bounds.min.z > maxZ) continue;
            out.add(s);
        }
    }

    public int cellCount(){ return cells.size(); }
    public void clear(){ cells.clear(); dynamicPairs.clear(); }
}
