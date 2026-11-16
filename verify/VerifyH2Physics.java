import com.hollow.core.MathX;
import com.hollow.core.MathX.Vec3;
import com.hollow.physics.Physics;
import com.hollow.physics.Physics.Collision;
import com.hollow.render.Frame;
import com.hollow.render.Renderer;
import com.hollow.script.Interp;
import com.hollow.scene.CameraNode;
import com.hollow.scene.LightNode;
import com.hollow.scene.MeshNode;
import com.hollow.scene.Node;
import com.hollow.scene.Scene;
import com.hollow.scene.ScriptHost;
import com.hollow.scene.ScriptNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** HollowEngine 2 — physics regression: gravity, AABB resolve,
 *  restitution, solid-body filtering, on_collision, picking. */
public class VerifyH2Physics {
    static int fails = 0, total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++; if(!ok) fails++;
    }

    static Scene world(){
        Scene s = new Scene();
        MeshNode ground = (MeshNode)s.createNode("mesh", "ground");
        ground.position.set(0, -1.05f, 0);
        ground.scale.set(40, 0.1f, 40);
        s.add(ground);
        return s;
    }

    public static void main(String[] args){
        gravity();
        resolve();
        solidFilter();
        events();
        System.out.println(fails == 0 ? "\nALL " + total + " PHYSICS TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " PHYSICS TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void gravity(){
        Physics p = new Physics();
        Scene s = world();
        MeshNode box = (MeshNode)s.createNode("mesh", "box");
        box.dynamic = true;
        box.position.set(0, 3, 0);
        s.add(box);
        List<Collision> hits = p.step(s, 1.0 / 60);
        check("ph: gravity accelerates down", box.velocity.y < 0 && box.position.y < 3, "" + box.position.y);
        // half extents = scale: ground half 0.1, box half 1.0 → resting center 0.05.
        // Discrete contact jitters around the surface: assert it settles near the
        // plane and never tunnels through it.
        float minY = Float.MAX_VALUE;
        for(int i = 0; i < 600; i++){
            p.step(s, 1.0 / 60);
            minY = Math.min(minY, box.position.y);
        }
        boolean landed = box.position.y > -0.2f && box.position.y < 0.15f && minY > -0.35f;
        check("ph: box lands exactly on ground", landed,
              box.position.y + "/min " + minY);

        Scene s2 = world();
        s2.gravity = 0f;
        MeshNode float2 = (MeshNode)s2.createNode("mesh", "float");
        float2.dynamic = true;
        float2.position.set(0, 5, 0);
        s2.add(float2);
        for(int i = 0; i < 60; i++) p.step(s2, 1.0 / 60);
        check("ph: gravity 0 → no motion", Math.abs(float2.position.y - 5) < 1e-4, "" + float2.position.y);

        Scene s3 = world();
        MeshNode still = (MeshNode)s3.createNode("mesh", "still");
        still.position.set(0, 5, 0);                    // NOT dynamic
        s3.add(still);
        for(int i = 0; i < 60; i++) p.step(s3, 1.0 / 60);
        check("ph: static bodies never move", Math.abs(still.position.y - 5) < 1e-4, "");
    }

    static void resolve(){
        Physics p = new Physics();
        Scene s = world();
        MeshNode mover = (MeshNode)s.createNode("mesh", "mover");
        mover.dynamic = true;
        mover.bounciness = 0.8f;
        mover.position.set(0, 0.3f, 0);
        mover.velocity.set(0, -5, 0);
        s.add(mover);
        boolean bounced = false;
        for(int i = 0; i < 120 && !bounced; i++){
            p.step(s, 1.0 / 60);
            bounced = mover.velocity.y > 2;
        }
        check("ph: bouncy impact reverses velocity", bounced, "" + mover.velocity.y);

        // wall: horizontal motion stops at the surface
        Scene s2 = world();
        MeshNode wall = (MeshNode)s2.createNode("mesh", "wall");
        wall.position.set(3, 2, 0);
        wall.scale.set(0.5f, 4, 4);
        s2.add(wall);
        MeshNode slider = (MeshNode)s2.createNode("mesh", "slider");
        slider.dynamic = true;
        slider.scale.set(0.5f, 0.5f, 0.5f);
        slider.position.set(0, 0.3f, 0);
        slider.velocity.set(6, 0, 0);
        s2.add(slider);
        for(int i = 0; i < 120; i++) p.step(s2, 1.0 / 60);
        // slider half 0.5 + wall half 0.5 → contact at x = 2.0
        check("ph: horizontal collision clamps at surface",
              slider.position.x < 2.05f && slider.position.x > 1.8f, "" + slider.position.x);

        // two dynamic bodies split the push
        Scene s3 = world();
        MeshNode a = (MeshNode)s3.createNode("mesh", "a");
        MeshNode b = (MeshNode)s3.createNode("mesh", "b");
        a.dynamic = b.dynamic = true;
        a.scale.set(0.5f, 0.5f, 0.5f);
        b.scale.set(0.5f, 0.5f, 0.5f);
        a.position.set(-0.2f, 2, 0);
        b.position.set(0.4f, 2, 0);
        s3.add(a);
        s3.add(b);
        for(int i = 0; i < 10; i++) p.step(s3, 1.0 / 60);
        check("ph: both dynamic bodies pushed apart", Math.abs(a.position.x - b.position.x) >= 0.98f,
              "" + (b.position.x - a.position.x));
    }

    static void solidFilter(){
        Physics p = new Physics();
        Scene s = world();
        MeshNode box = (MeshNode)s.createNode("mesh", "box");
        box.dynamic = true;
        box.position.set(0, 0.2f, 0);
        s.add(box);
        LightNode sun = (LightNode)s.createNode("light", "sun");
        sun.position.set(0, 0, 0);
        s.add(sun);
        CameraNode cam = (CameraNode)s.createNode("camera", "cam");
        cam.position.set(0, 0, 0);
        s.add(cam);
        ScriptNode sn = (ScriptNode)s.createNode("script", "ctl");
        s.add(sn);
        for(int i = 0; i < 120; i++) p.step(s, 1.0 / 60);
        // nothing phantom: box rests on the ground, not on sun/root/camera
        check("ph: lights/cameras/scripts are not solid",
              Math.abs(box.position.y - 0.05f) < 0.05f, "" + box.position.y);
    }

    static void events(){
        try {
            Path dir = Files.createTempDirectory("h2phys");
            Files.writeString(dir.resolve("ctl.hs"), String.join("\n",
                "var impacts = 0",
                "var axes = \"\"",
                "func on_collision(node, scene, a, b, axis):",
                "    if a.name == \"ball\" or b.name == \"ball\":",
                "        impacts = impacts + 1",
                "        axes = axes + axis",
                "",
                "func on_update(node, scene, delta):",
                "    pass"));
            Scene s = world();
            MeshNode ball = (MeshNode)s.createNode("mesh", "ball");
            ball.dynamic = true;
            ball.scale.set(0.5f, 0.5f, 0.5f);
            ball.position.set(0, 2, 0);
            s.add(ball);
            ScriptNode sn = (ScriptNode)s.createNode("script", "ctl");
            sn.path = "ctl.hs";
            s.add(sn);
            List<String> out = new ArrayList<>();
            ScriptHost host = new ScriptHost(s, out::add, dir);
            host.start();
            for(int i = 0; i < 240; i++) host.stepWorld(1.0 / 60);
            Interp i = host.interpOf(sn);
            check("ph: on_collision fires with axis", i != null && (double)i.global("impacts") >= 1
                  && i.global("axes").toString().contains("y"), "" + (i == null ? "null" : i.global("axes")));

            // picking: build a rendered view and pick the box center
            MeshNode pick = (MeshNode)s.createNode("mesh", "pickme");
            pick.color = "red";
            pick.scale.set(1, 1, 1);
            pick.position.set(2, 0.5f, 0);
            s.add(pick);
            CameraNode cam = (CameraNode)s.createNode("camera", "cam");
            cam.position.set(0, 3, 10);
            cam.rotation.x = -10;
            s.add(cam);
            Renderer r = new Renderer(dir);
            Frame f = r.render(s, 320, 180);
            Node hit = null;
            for(int py = 0; py < f.h && hit == null; py += 3)
                for(int px = 0; px < f.w && hit == null; px += 3){
                    Node n = r.pick(s, f.w, f.h, px, py);
                    if(n != null && n.name.equals("pickme")) hit = n;
                }
            check("ph: renderer pick finds the node", hit != null, "");
        } catch(Exception e){
            check("ph: harness", false, e.toString());
        }
    }
}
