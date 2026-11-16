import com.hollow.core.MathX;
import com.hollow.core.MathX.Mat4;
import com.hollow.core.MathX.Vec3;
import com.hollow.render.Colors;
import com.hollow.render.Frame;
import com.hollow.render.Mesh;
import com.hollow.render.Rasterizer;
import com.hollow.render.Renderer;
import com.hollow.scene.CameraNode;
import com.hollow.scene.LightNode;
import com.hollow.scene.MeshNode;
import com.hollow.scene.Scene;

import java.nio.file.Files;
import java.nio.file.Path;

/** HollowEngine 2 — renderer regression: obj, rasterizer, z-buffer, scene render. */
public class VerifyH2Render {
    static int fails = 0, total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++; if(!ok) fails++;
    }

    public static void main(String[] args){
        colors();
        mesh();
        raster();
        scene();
        fx();
        System.out.println(fails == 0 ? "\nALL " + total + " RENDER TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " RENDER TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** 2.1 features: bitmap font HUD, vignette, multi-light + reach, hud channel */
    static void fx(){
        // bitmap font draws opaque pixels
        Frame f = new Frame(200, 60);
        int bg = 0xFF101010;
        f.clear(bg);
        f.drawString("HOLLOW VALE 3:00", 5, 5, 0xFFFFFF00, 2);
        int lit = 0;
        for(int p : f.pixels) if(p != bg) lit++;
        check("fx: bitmap font", lit > 300, "" + lit);
        f.clear(bg);
        f.drawString("lowercase ok", 4, 4, 0xFFFFFFFF, 1);
        check("fx: string width math", f.stringWidth("lowercase ok", 1) == 12 * 6, "");
        // glyph orientation: '1' row 0 = 0b00100 → only the center column lit
        f.clear(bg);
        f.drawString("1", 10, 10, 0xFFFFFFFF, 1);
        int rowLit = 0;
        for(int x = 10; x < 15; x++) if(f.pixels[10 * f.w + x] != bg) rowLit++;
        check("fx: glyph bit order", rowLit == 1 && f.pixels[10 * f.w + 12] != bg, "lit=" + rowLit);

        // vignette: corner darker than center
        Frame v = new Frame(120, 120);
        v.clear(0xFF808080);
        v.vignette(0.9f);
        check("fx: vignette darkens edges", v.pixels[5 * 120 + 5] < v.pixels[60 * 120 + 60], "");

        // multi-light: red light adds warmth to the white base
        Frame m1 = new Frame(80, 80); m1.clear(0);
        Frame m2 = new Frame(80, 80); m2.clear(0);
        Mesh quad = Mesh.fromObj("v -10 -5 -20\nv 10 -5 -20\nv 10 5 -20\nv -10 5 -20\nf 1 2 3 4\n");
        Mat4 view = new Mat4().identity();
        Mat4 proj = new Mat4().identity().perspective(60, 1, 0.1f, 100f);
        Vec3 eye = new Vec3(0, 0, 0);
        Rasterizer.Light white = new Rasterizer.Light(new Vec3(0, 0, -1), 0xFFFFFF, 0.8f);
        Rasterizer.Light red = new Rasterizer.Light(new Vec3(0, 0, -1), 0xFF2020, 0.8f,
                                                    new Vec3(0, 0, 0), 30f);
        Rasterizer.drawMesh(m1, quad, new Mat4().identity(), view, proj, eye, white, 0x808080, 0.1f, 0, 0);
        Rasterizer.drawMesh(m2, quad, new Mat4().identity(), view, proj, eye,
                            java.util.List.of(white, red), 0x808080, 0.1f, 0, 0);
        int p1 = m1.pixels[40 * 80 + 40], p2 = m2.pixels[40 * 80 + 40];
        check("fx: second light warms the surface", ((p2 >> 16) & 0xFF) > ((p1 >> 16) & 0xFF),
              Integer.toHexString(p1) + "/" + Integer.toHexString(p2));

        // reach falloff: a light with reach 3 does not tint a quad 20 units away
        Frame m3 = new Frame(80, 80); m3.clear(0);
        Rasterizer.Light tiny = new Rasterizer.Light(new Vec3(0, 0, -1), 0xFF2020, 0.8f,
                                                     new Vec3(0, 0, 0), 3f);
        Rasterizer.drawMesh(m3, quad, new Mat4().identity(), view, proj, eye,
                            java.util.List.of(tiny), 0x808080, 0.1f, 0, 0);
        int p3 = m3.pixels[40 * 80 + 40];
        check("fx: reach falloff attenuates", ((p3 >> 16) & 0xFF) == ((p3 >> 8) & 0xFF), Integer.toHexString(p3));

        // scene-level: hud overlay drawn on top of the render
        Scene s = new Scene();
        MeshNode hero = (MeshNode)s.createNode("mesh", "hero");
        s.add(hero);
        CameraNode cam = (CameraNode)s.createNode("camera", "cam");
        cam.position.set(0, 0, 6);
        s.add(cam);
        java.util.List<String> hud = java.util.List.of("SANITY 87%", "M1: FIND THE NOTE");
        Frame fh = new Renderer(Path.of(".")).render(s, 320, 180, hud);
        Frame fn = new Renderer(Path.of(".")).render(s, 320, 180, null);
        check("fx: hud overlay renders", fh.fillRatio() > fn.fillRatio(), "");

        // hud() builtin reaches ScriptHost.hudLines through stepWorld
        try {
            Path dir = Files.createTempDirectory("h2hud");
            Files.writeString(dir.resolve("h.hs"),
                "func on_update(node, scene, delta):\n    hud(\"HP 100\")\n");
            Scene s2 = new Scene();
            com.hollow.scene.ScriptNode sn = (com.hollow.scene.ScriptNode)s2.createNode("script", "h");
            sn.path = "h.hs";
            s2.add(sn);
            com.hollow.scene.ScriptHost host = new com.hollow.scene.ScriptHost(s2, m -> {}, dir);
            host.start();
            host.stepWorld(1.0 / 60);
            check("fx: hud() builtin flows to host", host.hudLines.size() == 1 && "HP 100".equals(host.hudLines.get(0)),
                  "" + host.hudLines);
        } catch(Exception e){
            check("fx: hud() builtin flows to host", false, e.toString());
        }
    }

    static void colors(){
        check("cl: palette", Colors.parse("gold") == 0xFFD4AF37 && Colors.parse("sky") == 0xFF87CEEB, "");
        check("cl: hex + unknown", Colors.parse("#FF0000") == 0xFFFF0000 && Colors.parse("mystery") == Colors.parse("silver"), "");
        check("cl: case + empty", Colors.parse("GOLD") == 0xFFD4AF37 && Colors.parse("") == Colors.parse("silver"), "");
    }

    static void mesh(){
        Mesh cube = Mesh.cube();
        check("me: builtin cube", cube.vertexCount() == 24 && cube.triangleCount() == 12, "");
        Mesh m = Mesh.fromObj("v 0 0 0\nv 1 0 0\nv 0 1 0\nv 0 0 1\nf 1 2 3 4\n");
        check("me: quad fan-triangulated", m.vertexCount() == 4 && m.triangleCount() == 2, "");
        Mesh n = Mesh.fromObj("v 0 0 0\nv 1 0 0\nv 0 1 0\nvn 0 0 1\nf 1//1 2//1 3//1\n");
        check("me: vn form + normals", n.nrm != null && Math.abs(n.nrm[2] - 1) < 1e-6, "");
        Mesh neg = Mesh.fromObj("v 0 0 0\nv 1 0 0\nv 0 1 0\nf -3 -2 -1\n");
        check("me: negative indices", neg.triangleCount() == 1, "");
        Mesh skip = Mesh.fromObj("# only a comment\n\n");
        check("me: empty obj", skip.vertexCount() == 0 && skip.triangleCount() == 0, "");
        try {
            Mesh.fromObj("v notanumber 0 0\n");
            check("me: bad float throws", false, "");
        } catch(NumberFormatException e){
            check("me: bad float throws", true, "");
        }
    }

    static void raster(){
        Frame f = new Frame(48, 48);
        f.clear(0xFF0000FF);
        check("ra: clear + fillRatio zero", f.fillRatio() == 0 && f.pixels[10] == 0xFF0000FF, "");
        Mat4 view = new Mat4().identity().translate(0, 0, -5);
        Mat4 proj = new Mat4().identity().perspective(60, 1, 0.1f, 100f);
        Vec3 eye = new Vec3(0, 0, 5);
        Rasterizer.Light l = new Rasterizer.Light(new Vec3(-0.3f, -1, -0.2f).norm(), 0xFFFFFFFF, 1f);
        Rasterizer.drawMesh(f, Mesh.cube(), new Mat4().identity(), view, proj, eye, l, Colors.parse("gold"), 0.25f);
        check("ra: cube fills the frame", f.fillRatio() > 0.05 && f.fillRatio() < 0.9, "" + f.fillRatio());
        check("ra: shading per face", f.distinctColors() >= 3, "" + f.distinctColors());

        // depth: draw far then near — near wins in the middle
        Frame d = new Frame(32, 32);
        d.clear(0);
        Mat4 idv = new Mat4().identity();
        Rasterizer.Light dark = new Rasterizer.Light(new Vec3(0, -1, 0), 0xFFFFFF, 0f);
        Mesh farQ = Mesh.fromObj("v -9 -9 -8\nv 9 -9 -8\nv 9 9 -8\nv -9 9 -8\nf 1 2 3 4\n");
        Mesh nearQ = Mesh.fromObj("v -3 -3 -3\nv 3 -3 -3\nv 3 3 -3\nv -3 3 -3\nf 1 2 3 4\n");
        Rasterizer.drawMesh(d, farQ, new Mat4().identity(), idv, proj, eye, dark, Colors.parse("red"), 1f);
        Rasterizer.drawMesh(d, nearQ, new Mat4().identity(), idv, proj, eye, dark, Colors.parse("green"), 1f);
        int mid = d.pixels[16 * 32 + 16];
        check("ra: near wins (far first)", ((mid >> 8) & 0xFF) > ((mid >> 16) & 0xFF), Integer.toHexString(mid));
        // reversed order: same winner
        Frame d2 = new Frame(32, 32);
        d2.clear(0);
        Rasterizer.drawMesh(d2, nearQ, new Mat4().identity(), idv, proj, eye, dark, Colors.parse("green"), 1f);
        Rasterizer.drawMesh(d2, farQ, new Mat4().identity(), idv, proj, eye, dark, Colors.parse("red"), 1f);
        int mid2 = d2.pixels[16 * 32 + 16];
        check("ra: near wins (near first)", ((mid2 >> 8) & 0xFF) > ((mid2 >> 16) & 0xFF), Integer.toHexString(mid2));

        // off-screen mesh leaves the frame blank
        Frame blank = new Frame(32, 32);
        blank.clear(0xFF123456);
        Mesh behind = Mesh.fromObj("v -1 -1 2\nv 1 -1 2\nv 1 1 2\nv -1 1 2\nf 1 2 3 4\n");
        Rasterizer.drawMesh(blank, behind, new Mat4().identity(), idv, proj, eye, dark, Colors.parse("gold"), 1f);
        check("ra: behind camera ignored", blank.fillRatio() == 0, "" + blank.fillRatio());

        // light straight from the camera: this quad's normal faces it fully
        Frame lit = new Frame(32, 32);
        lit.clear(0);
        Rasterizer.drawMesh(lit, nearQ, new Mat4().identity(), idv, proj, eye,
                            new Rasterizer.Light(new Vec3(0, 0, -1), 0xFFFFFF, 1f), Colors.parse("white"), 0f);
        Frame dim = new Frame(32, 32);
        dim.clear(0);
        Rasterizer.drawMesh(dim, nearQ, new Mat4().identity(), idv, proj, eye,
                            new Rasterizer.Light(new Vec3(0, 0, -1), 0xFFFFFF, 0.1f), Colors.parse("white"), 0f);
        check("ra: intensity changes brightness", pixelLuma(lit) > pixelLuma(dim) && pixelLuma(lit) > 400,
              pixelLuma(lit) + " / " + pixelLuma(dim));
    }

    static double pixelLuma(Frame f){
        int p = f.pixels[16 * 32 + 16];
        return ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
    }

    static void scene(){
        Scene s = new Scene();
        MeshNode hero = (MeshNode)s.createNode("mesh", "hero");
        s.add(hero);
        CameraNode cam = (CameraNode)s.createNode("camera", "cam");
        cam.position.set(0, 0, 6);
        s.add(cam);
        LightNode sun = (LightNode)s.createNode("light", "sun");
        sun.rotation.set(-30, 25, 0);
        s.add(sun);
        Frame f = new Renderer(Path.of(".")).render(s, 320, 180);
        check("rn: camera + light + mesh render", f.fillRatio() > 0.02 && f.distinctColors() >= 3, "" + f.fillRatio());

        Frame noCam = new Renderer(Path.of(".")).render(new Scene(), 64, 64);
        check("rn: no camera → blank", noCam.fillRatio() == 0, "");

        // hero behind the camera: invisible
        Scene s2 = new Scene();
        MeshNode hidden = (MeshNode)s2.createNode("mesh", "hidden");
        hidden.position.set(0, 0, 20);
        s2.add(hidden);
        CameraNode cam2 = (CameraNode)s2.createNode("camera", "cam");
        cam2.position.set(0, 0, 6);
        s2.add(cam2);
        Frame f2 = new Renderer(Path.of(".")).render(s2, 320, 180);
        check("rn: invisible node skipped", f2.fillRatio() < 0.01, "" + f2.fillRatio());

        // child inherits parent transform: parent at x=2 shifts the projected cube
        Scene s3 = new Scene();
        MeshNode base = (MeshNode)s3.createNode("mesh", "base");
        base.position.set(0, 0, 0);
        s3.add(base);
        CameraNode cam3 = (CameraNode)s3.createNode("camera", "cam");
        cam3.position.set(0, 0, 6);
        s3.add(cam3);
        Frame f3 = new Renderer(Path.of(".")).render(s3, 320, 180);
        int[] c1 = centroid(f3);
        base.position.x = 3;
        Frame f4 = new Renderer(Path.of(".")).render(s3, 320, 180);
        int[] c2 = centroid(f4);
        check("rn: transform moves the projection", c2[0] > c1[0] + 20, c1[0] + " -> " + c2[0]);

        try {
            Path png = Files.createTempFile("h2r", ".png");
            boolean wrote = f.writePNG(png);
            check("rn: png written with content", wrote && Files.size(png) > 0, "" + (wrote ? Files.size(png) : -1));
            Files.deleteIfExists(png);
        } catch(Exception e){
            check("rn: png written with content", false, e.toString());
        }
    }

    static int[] centroid(Frame f){
        long sx = 0, sy = 0;
        int n = 0;
        for(int y = 0; y < f.h; y++)
            for(int x = 0; x < f.w; x++)
                if(f.pixels[y * f.w + x] != f.background){
                    sx += x; sy += y; n++;
                }
        if(n == 0) return new int[]{ -1, -1 };
        return new int[]{ (int)(sx / n), (int)(sy / n) };
    }
}
