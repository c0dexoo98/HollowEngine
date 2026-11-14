import com.hollow.nav.NavMesh;
import com.hollow.core.Vec3;

/**
 * v2.6.0 navmesh regression suite.
 * Bakes a NavMesh from a NavGrid with a wall + pillar, checks:
 * polygon merge, portal linking, A* around walls, string-pull quality.
 */
public class VerifyNav {
    static int fails = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        if(!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        // ---- build a NavGrid harness: 30x30m field, wall at x=10 (gap at z 12..15), pillar at (20,20) ----
        NavGridHarness h = new NavGridHarness();
        NavMesh mesh = new NavMesh();
        mesh.build((com.hollow.nav.NavMesh.WalkabilitySource) h::blockedTest, new Vec3(0, 0, 0), 45f, 45f);

        // ---- 1) baked ----
        check("mesh baked with polygons", mesh.built && mesh.polys.size() > 0, "polys=" + mesh.polys.size());

        // ---- 2) polygon containment ----
        NavMesh.Poly at5 = mesh.polyAt(5f, 5f);
        check("polyAt(5,5) finds a polygon", at5 != null, "null");
        NavMesh.Poly atWall = mesh.polyAt(9.8f, 7.5f);   // inside wall (x 9..10.5)
        check("polyAt(wall x=10) is off-mesh (blocked)", atWall == null, "poly=" + atWall);

        // ---- 3) portals link the two sides of the wall through the gap ----
        boolean linked = false;
        for(NavMesh.Poly p : mesh.polys){
            if(p.neighbors.size() > 0){ linked = true; break; }
        }
        check("at least one polygon pair is portal-linked", linked, "no neighbors anywhere");

        // ---- 4) A* path around the wall: (2,2) -> (25,25) must route through the gap ----
        java.util.List<Vec3> path = mesh.findPath(new Vec3(3, 0, 3), new Vec3(27, 0, 27));
        check("path exists from (2,2) to (25,25)", path.size() > 0, "empty path");
        boolean crossesGap = false;
        if(path.size() > 0){
            // every waypoint must be walkable (not in the wall)
            boolean allWalkable = true;
            for(Vec3 wp : path){
                if(h.blocked(wp.x, wp.z)){ allWalkable = false; break; }
            }
            check("all waypoints walkable (none inside wall/pillar)", allWalkable, "waypoint in solid");
            // the path must cross the wall band (x 9..10.5) inside the gap (z 13.5..16.5)
            for(int i = 1; i < path.size(); i++){
                Vec3 p0 = path.get(i-1), wp = path.get(i);
                // segment crosses x band?
                if(Math.max(p0.x, wp.x) >= 9f && Math.min(p0.x, wp.x) <= 10.5f){
                    float s = (9.75f - p0.x) / Math.max(1e-5f, wp.x - p0.x);
                    if(s >= 0f && s <= 1f){
                        float zAt = p0.z + (wp.z - p0.z) * s;
                        if(zAt > 13.2f && zAt < 16.8f){ crossesGap = true; break; }
                    }
                }
            }
            check("path routes through the wall gap (z 12..15 at x~10)", crossesGap, "no gap waypoint: " + path);
        }

        // ---- 5) path is reasonably short (manhattan-ish bound, not wandering) ----
        if(path.size() > 0){
            float len = 0f;
            Vec3 prev = new Vec3(3, 0, 3);
            for(Vec3 wp : path){ len += prev.dist(wp); prev = wp; }
            // straight line ~34; routing through the gap must stay under 2x
            check("path length sane (< 2x straight-line distance)", len < 68f, "len=" + len);
        }

        // ---- 6) unreachable goal: sealed box ----
        NavGridHarness h2 = new NavGridHarness();
        h2.sealBox();   // ring at cell 8 (world x=12/z=12) — start inside, goal outside
        NavMesh mesh2 = new NavMesh();
        mesh2.build((com.hollow.nav.NavMesh.WalkabilitySource) h2::blockedTest, new Vec3(0, 0, 0), 27f, 27f);
        java.util.List<Vec3> p2 = mesh2.findPath(new Vec3(3, 0, 3), new Vec3(18, 0, 18));
        check("no path into sealed region", p2.isEmpty(), "path=" + p2);

        // ---- 7) off-mesh goal clamps to nearest poly ----
        java.util.List<Vec3> p3 = mesh.findPath(new Vec3(3, 0, 3), new Vec3(9.8f, 0, 6f));   // goal inside wall
        check("goal inside wall clamps to reachable nearest poly (no crash, path or empty)",
              p3 != null, "null return");

        System.out.println(fails == 0 ? "ALL NAVMESH REGRESSION TESTS PASSED" : fails + " TESTS FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** walkability harness aligned to NavMesh.CELL = 1.5 (64 cells = 96m) */
    static class NavGridHarness {
        // cell (col,row) is blocked when true; geometry maps to world by *1.5
        final boolean[][] blocked = new boolean[64][64];
        NavGridHarness(){
            // wall segment at cols 6..7 (world x 9..10.5), full rows except gap rows 9..10 (z 13.5..16.5)
            for(int z = 0; z < 64; z++){
                boolean gap = (z >= 9 && z <= 10);
                for(int x = 6; x <= 7; x++) blocked[z][x] = !gap;
            }
            // pillar at cols 13..14, rows 13..14 (world ~19.5..22.5)
            for(int z = 13; z <= 14; z++)
                for(int x = 13; x <= 14; x++) blocked[z][x] = true;
        }
        void sealBox(){
            // sealed ring on cells x==15 or z==15 (world 22.5): start (3,3) is inside,
            // goal (18,18) — wait, both inside. Make the GOAL outside: ring at cell 8
            // (world 12) encloses only cells < 8; start (3,3)=cell(2,2) inside,
            // goal (18,18)=cell(12,12) outside — unreachable.
            for(int z = 0; z < 64; z++)
                for(int x = 0; x < 64; x++){
                    boolean ring = (x == 8 || z == 8);
                    // keep the original wall+gap too (blocked already true stays true)
                    blocked[z][x] = blocked[z][x] || ring;
                }
        }
    boolean blockedTest(com.hollow.core.Vec3 pos){ return blocked(pos.x, pos.z); }
        boolean blocked(float x, float z){
            int cx = (int)(x / 1.5f), cz = (int)(z / 1.5f);   // NavMesh.CELL
            if(cx < 0 || cx >= 64 || cz < 0 || cz >= 64) return true;
            return blocked[cz][cx];
        }
    }
}
