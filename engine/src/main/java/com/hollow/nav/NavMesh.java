package com.hollow.nav;

import com.hollow.core.Vec3;
import com.hollow.physics.NavGrid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v2.6.0 NAV-NAVMESH: polygon navigation mesh (replaces grid-only NavGrid for
 * open worlds; NavGrid stays as the cheap fallback).
 *
 * Pipeline:
 *   1. rasterize walkability into a coarse grid (from PhysicsWorld statics)
 *   2. march squares -> convex-ish polygons (greedy rect merge)
 *   3. A* over polygon adjacency (portal midpoints as edge costs)
 *   4. string-pull the portal sequence into a smooth world-space path
 *
 * Everything runs single-threaded and allocation-light: build() is called on
 * level load or editor bake, findPath() at AI decision rate.
 */
public final class NavMesh {

    /** walkability oracle: true = cannot walk (walls, ledges, hazards) */
    public interface WalkabilitySource { boolean isBlocked(Vec3 pos); }

    public static final float CELL = 1.5f;      // rasterization resolution
    public static final int MAX_DIM = 512;      // 768m x 768m coverage cap

    public static final class Poly {
        public final List<Vec3> verts = new ArrayList<>();      // world space, CCW, y=0 plane
        public final Vec3 center = new Vec3();                   // centroid (A* node)
        public final List<Integer> neighbors = new ArrayList<>(); // adjacent poly ids
        public final List<int[]> portals = new ArrayList<>();    // per neighbor: shared edge vertex ids
        public float area;

        boolean contains(float x, float z){
            // point-in-polygon (ray cast, even-odd)
            boolean inside = false;
            int n = verts.size();
            for(int i = 0, j = n - 1; i < n; j = i++){
                Vec3 a = verts.get(i), b = verts.get(j);
                if((a.z > z) != (b.z > z) &&
                   x < (b.x - a.x) * (z - a.z) / (b.z - a.z) + a.x) inside = !inside;
            }
            return inside;
        }

        float distanceToEdge(float x, float z){
            float best = Float.MAX_VALUE;
            int n = verts.size();
            for(int i = 0, j = n - 1; i < n; j = i++){
                Vec3 a = verts.get(i), b = verts.get(j);
                float ex = b.x - a.x, ez = b.z - a.z;
                float len2 = ex*ex + ez*ez;
                float s = len2 < 1e-9f ? 0f : ((x-a.x)*ex + (z-a.z)*ez) / len2;
                s = Math.max(0f, Math.min(1f, s));
                float dx = x - (a.x + ex*s), dz = z - (a.z + ez*s);
                best = Math.min(best, dx*dx + dz*dz);
            }
            return (float)Math.sqrt(best);
        }
    }

    public final List<Poly> polys = new ArrayList<>();
    private final Map<Long, Integer> portalPair = new HashMap<>();  // polyA<<16|polyB -> portal vert ids
    public int gridW, gridH;
    public boolean built = false;

    // ------------------------------------------------------------------
    // bake
    // ------------------------------------------------------------------

    /** rasterize walkability from the physics world (NavGrid), then merge into polygons */
    public void build(NavGrid nav, Vec3 origin, float sizeX, float sizeZ){
        build((WalkabilitySource) nav::isBlocked, origin, sizeX, sizeZ);
    }

    /** rasterize from any walkability oracle (tests, procedural levels) */
    public void build(WalkabilitySource src, Vec3 origin, float sizeX, float sizeZ){
        gridW = (int)Math.min(MAX_DIM, Math.ceil(sizeX / CELL));
        gridH = (int)Math.min(MAX_DIM, Math.ceil(sizeZ / CELL));
        boolean[][] walk = new boolean[gridH][gridW];

        // sample physics blocking at cell centers
        for(int r = 0; r < gridH; r++){
            for(int c = 0; c < gridW; c++){
                Vec3 p = new Vec3(origin.x + (c + 0.5f) * CELL, origin.y, origin.z + (r + 0.5f) * CELL);
                walk[r][c] = !src.isBlocked(p);
            }
        }

        polys.clear();
        portalPair.clear();
        portalVerts.clear();
        mergeRects(walk, origin);
        linkPolygons();
        built = !polys.isEmpty();
    }

    /** greedy maximal-rectangle merge over walkable cells (produces axis-aligned convex polys) */
    private void mergeRects(boolean[][] walk, Vec3 origin){
        int h = gridH, w = gridW;
        boolean[][] used = new boolean[h][w];
        for(int r = 0; r < h; r++){
            for(int c = 0; c < w; c++){
                if(used[r][c] || !walk[r][c]) continue;
                // grow width
                int cw = 1;
                while(c + cw < w && walk[r][c + cw] && !used[r][c + cw]) cw++;
                // grow height while full row fits
                int ch = 1;
                outer:
                while(r + ch < h){
                    for(int i = 0; i < cw; i++){
                        if(!walk[r + ch][c + i] || used[r + ch][c + i]) break outer;
                    }
                    ch++;
                }
                // emit poly (CCW on XZ plane with +Y up): world coords
                Poly p = new Poly();
                float x0 = origin.x + c * CELL, x1 = origin.x + (c + cw) * CELL;
                float z0 = origin.z + r * CELL, z1 = origin.z + (r + ch) * CELL;
                p.verts.add(new Vec3(x0, origin.y, z0));
                p.verts.add(new Vec3(x1, origin.y, z0));
                p.verts.add(new Vec3(x1, origin.y, z1));
                p.verts.add(new Vec3(x0, origin.y, z1));
                p.center.set((x0 + x1) * 0.5f, origin.y, (z0 + z1) * 0.5f);
                p.area = (x1 - x0) * (z1 - z0);
                polys.add(p);
                for(int rr = r; rr < r + ch; rr++)
                    for(int cc = c; cc < c + cw; cc++)
                        used[rr][cc] = true;
            }
        }
    }

    /** connect polygons sharing a boundary segment; the portal is the OVERLAP segment
     *  in world coords (start/end points), so its midpoint sits inside the actual
     *  shared opening (a gap between walls), never along the full edge of one poly. */
    private void linkPolygons(){
        for(int a = 0; a < polys.size(); a++){
            for(int b = a + 1; b < polys.size(); b++){
                Vec3[] overlap = sharedOverlapSegment(polys.get(a), polys.get(b));
                if(overlap != null){
                    polys.get(a).neighbors.add(b);
                    polys.get(a).portals.add(new int[]{portalIndex(overlap[0], overlap[1])});
                    polys.get(b).neighbors.add(a);
                    polys.get(b).portals.add(new int[]{portalIndex(overlap[0], overlap[1])});
                }
            }
        }
    }

    /** overlap segment of two rect polys' colinear edges, or null when they don't touch */
    private static Vec3[] sharedOverlapSegment(Poly a, Poly b){
        int n = a.verts.size(), m = b.verts.size();
        for(int i = 0; i < n; i++){
            Vec3 a1 = a.verts.get(i), a2 = a.verts.get((i + 1) % n);
            for(int j = 0; j < m; j++){
                Vec3 b1 = b.verts.get(j), b2 = b.verts.get((j + 1) % m);
                // horizontal edges on the same z line?
                if(Math.abs(a1.z - a2.z) < 1e-4f && Math.abs(b1.z - b2.z) < 1e-4f && Math.abs(a1.z - b1.z) < 1e-4f){
                    float lo = Math.max(Math.min(a1.x, a2.x), Math.min(b1.x, b2.x));
                    float hi = Math.min(Math.max(a1.x, a2.x), Math.max(b1.x, b2.x));
                    if(hi - lo > 1e-4f) return new Vec3[]{ new Vec3(lo, a1.y, a1.z), new Vec3(hi, a1.y, a1.z) };
                }
                // vertical edges on the same x line?
                if(Math.abs(a1.x - a2.x) < 1e-4f && Math.abs(b1.x - b2.x) < 1e-4f && Math.abs(a1.x - b1.x) < 1e-4f){
                    float lo = Math.max(Math.min(a1.z, a2.z), Math.min(b1.z, b2.z));
                    float hi = Math.min(Math.max(a1.z, a2.z), Math.max(b1.z, b2.z));
                    if(hi - lo > 1e-4f) return new Vec3[]{ new Vec3(a1.x, a1.y, lo), new Vec3(a1.x, a1.y, hi) };
                }
            }
        }
        return null;
    }

    /** portals list stores segment endpoints as an id pair into a shared vertex pool */
    private int portalIndex(Vec3 p1, Vec3 p2){ return addPortalVerts(p1, p2); }

    // shared vertex pool for portal endpoints (small; avoids per-portal arrays churn)
    private final List<Vec3> portalVerts = new ArrayList<>();
    private int addPortalVerts(Vec3 p1, Vec3 p2){
        portalVerts.add(p1); portalVerts.add(p2);
        return portalVerts.size() - 2;   // index of p1; p2 = index+1
    }
    private Vec3 portalP1(int idx){ return portalVerts.get(idx); }
    private Vec3 portalP2(int idx){ return portalVerts.get(idx + 1); }

    /** shared boundary segment between two axis-aligned rects, as edge-id INTO poly A */
    private static int sharedPortalEdge(Poly a, Poly b){
        int n = a.verts.size(), m = b.verts.size();
        for(int i = 0; i < n; i++){
            Vec3 a1 = a.verts.get(i), a2 = a.verts.get((i + 1) % n);
            for(int j = 0; j < m; j++){
                Vec3 b1 = b.verts.get(j), b2 = b.verts.get((j + 1) % m);
                if(edgesOverlap(a1, a2, b1, b2)) return i;   // edge i of A is the portal
            }
        }
        return -1;
    }

    /** true when colinear edges a1a2 and b1b2 overlap by more than epsilon */
    private static boolean edgesOverlap(Vec3 a1, Vec3 a2, Vec3 b1, Vec3 b2){
        boolean aHoriz = Math.abs(a1.z - a2.z) < 1e-4f, bHoriz = Math.abs(b1.z - b2.z) < 1e-4f;
        boolean aVert = Math.abs(a1.x - a2.x) < 1e-4f, bVert = Math.abs(b1.x - b2.x) < 1e-4f;
        if(aHoriz && bHoriz && Math.abs(a1.z - b1.z) < 1e-4f){
            float lo = Math.max(Math.min(a1.x, a2.x), Math.min(b1.x, b2.x));
            float hi = Math.min(Math.max(a1.x, a2.x), Math.max(b1.x, b2.x));
            return hi - lo > 1e-4f;
        }
        if(aVert && bVert && Math.abs(a1.x - b1.x) < 1e-4f){
            float lo = Math.max(Math.min(a1.z, a2.z), Math.min(b1.z, b2.z));
            float hi = Math.min(Math.max(a1.z, a2.z), Math.max(b1.z, b2.z));
            return hi - lo > 1e-4f;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // query
    // ------------------------------------------------------------------

    public Poly polyAt(float x, float z){
        for(int i = 0; i < polys.size(); i++)
            if(polys.get(i).contains(x, z)) return polys.get(i);
        return null;
    }

    /** nearest polygon by center distance (fallback when start/goal is off-mesh) */
    public Poly nearestPoly(float x, float z){
        Poly best = null; float bd = Float.MAX_VALUE;
        for(Poly p : polys){
            float d = p.center.dist(new Vec3(x, p.center.y, z));
            if(d < bd){ bd = d; best = p; }
        }
        return best;
    }

    /** A* over polygons + string-pull smoothing through portal midpoints */
    public List<Vec3> findPath(Vec3 from, Vec3 to){
        List<Vec3> out = new ArrayList<>();
        if(!built) return out;
        Poly start = polyAt(from.x, from.z);
        if(start == null) start = nearestPoly(from.x, from.z);
        Poly goal = polyAt(to.x, to.z);
        if(goal == null) goal = nearestPoly(to.x, to.z);
        if(start == null || goal == null) return out;

        // A* on polygon graph
        Map<Integer, Float> g = new HashMap<>();
        Map<Integer, Integer> came = new HashMap<>();
        List<Integer> open = new ArrayList<>();
        int s = polys.indexOf(start), t = polys.indexOf(goal);
        g.put(s, 0f); open.add(s);
        int goalId = t;
        java.util.HashSet<Integer> closed = new java.util.HashSet<>();
        while(!open.isEmpty()){
            // pop lowest f
            int best = 0;
            float bestF = Float.MAX_VALUE;
            for(int i = 0; i < open.size(); i++){
                int n = open.get(i);
                float f = g.getOrDefault(n, Float.MAX_VALUE) + hCost(n, goalId);
                if(f < bestF){ bestF = f; best = i; }
            }
            int cur = open.remove(best);
            if(cur == goalId) break;
            if(!closed.add(cur)) continue;
            Poly cp = polys.get(cur);
            for(int nb : cp.neighbors){
                if(closed.contains(nb)) continue;
                float step = cp.center.dist(polys.get(nb).center);
                float ng = g.getOrDefault(cur, Float.MAX_VALUE) + step;
                if(ng < g.getOrDefault(nb, Float.MAX_VALUE)){
                    g.put(nb, ng);
                    came.put(nb, cur);
                    if(!open.contains(nb)) open.add(nb);
                }
            }
        }
        if(!came.containsKey(goalId) && s != goalId) return out;

        // reconstruct polygon chain
        List<Integer> chain = new ArrayList<>();
        int node = goalId;
        chain.add(node);
        while(node != s){
            Integer p = came.get(node);
            if(p == null) break;
            node = p;
            chain.add(node);
        }
        Collections.reverse(chain);

        // portal waypoints: midpoint of each crossed portal OVERLAP segment
        List<Vec3> raw = new ArrayList<>();
        for(int i = 0; i < chain.size() - 1; i++){
            Poly a = polys.get(chain.get(i));
            int nb = a.neighbors.indexOf(chain.get(i + 1));
            int poolIdx = a.portals.get(nb)[0];
            Vec3 p1 = portalP1(poolIdx), p2 = portalP2(poolIdx);
            raw.add(new Vec3((p1.x + p2.x) * 0.5f, from.y, (p1.z + p2.z) * 0.5f));
        }
        raw.add(new Vec3(to.x, from.y, to.z));

        // funnel-lite: greedily skip waypoints visible from the last kept point
        Vec3 prev = new Vec3(from.x, from.y, from.z);
        int i = 0;
        while(i < raw.size()){
            boolean last = i == raw.size() - 1;
            if(last){ out.add(new Vec3(raw.get(i).x, from.y, raw.get(i).z)); break; }
            // can we skip raw[i] and still see raw[i+1] from prev?
            if(!segmentCrossesBlocked(prev, raw.get(i + 1))){
                i++;   // skip the intermediate portal, keep pulling
                continue;
            }
            out.add(new Vec3(raw.get(i).x, from.y, raw.get(i).z));
            prev = raw.get(i);
            i++;
        }
        return out;
    }

    /** conservative on-mesh LOS: sample the segment interior (endpoints sit on portal
     *  edges — polygon boundaries — and would falsely test off-mesh); every interior
     *  sample must fall in SOME polygon, boundary contact allowed via epsilon inset. */
    private boolean segmentCrossesBlocked(Vec3 a, Vec3 b){
        float len = a.dist(b);
        int samples = Math.max(3, (int)(len / (CELL * 0.5f)) + 1);
        for(int i = 1; i < samples; i++){
            float s = i / (float)samples;
            // epsilon pull toward segment interior midpoint avoids on-boundary misses
            float x = a.x + (b.x - a.x) * s, z = a.z + (b.z - a.z) * s;
            if(!walkableAt(x, z, a, b)) return true;
        }
        return false;
    }

    /** a point is walkable when strictly inside a polygon OR within epsilon of one
     *  (portal-edge points live exactly on shared boundaries). */
    private boolean walkableAt(float x, float z, Vec3 segA, Vec3 segB){
        if(polyAt(x, z) != null) return true;
        // boundary tolerance: near any polygon edge?
        final float eps = 0.05f;
        for(Poly p : polys){
            if(p.distanceToEdge(x, z) <= eps) return true;
        }
        return false;
    }

    private float hCost(int polyId, int goalId){
        return polys.get(polyId).center.dist(polys.get(goalId).center);
    }
}
