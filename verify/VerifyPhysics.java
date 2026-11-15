import com.hollow.scene.Entity;
import com.hollow.scene.World;
import com.hollow.physics.RigidBody;
import com.hollow.physics.Joints;
import com.hollow.game.GameApp;
import com.hollow.game.EngineServices;
import com.hollow.core.FileSystem;
import com.hollow.core.NativeFS;
import com.hollow.core.Log;
import com.hollow.core.Vec3;

/**
 * v2.5.2 physics regression suite — runs through the real GameApp fixed-step pipeline
 * (anim -> ai -> physics -> particles), exactly like the runtime.
 */
public class VerifyPhysics {
    static int fails = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        if(!ok) fails++;
    }

    /** minimal GameApp that boots with an empty in-memory project and spawns programmatically */
    static class PhysApp extends GameApp {
        int ticksRun = 0;
        @Override public void onStart(){ Log.i("PhysApp", "start"); }
        @Override public void onFixedUpdate(float dt){ ticksRun++; }
    }

    static PhysApp boot() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("hollowphys");
        NativeFS fs = new NativeFS(tmp.toString());
        EngineServices svc = com.hollow.game.EngineBootstrap.boot(fs, ".", null);
        svc.config.headless = true;
        svc.time.headless = true;
        svc.config.entryScene = "";          // no scene file: keep our programmatic world
        PhysApp app = new PhysApp();
        app.boot(svc);
        // give it a world
        app.world = new World("test");
        com.hollow.game.EngineBootstrap.onWorldLoaded(svc, app.world);
        return app;
    }

    /** advance the app `seconds` of simulated time in 1/60 fixed steps */
    static void run(PhysApp app, float seconds){
        int steps = (int)(seconds * 60f);
        for(int i = 0; i < steps; i++) app.frame();
    }

    static PhysApp fresh() throws Exception {
        return boot();
    }

    public static void main(String[] a) throws Exception {
        // ---- 1) free fall ----
        PhysApp app = boot();
        Entity b = app.world.spawn("ball");
        b.add(new RigidBody());
        RigidBody rb = b.get(RigidBody.class);
        rb.shape = RigidBody.Shape.SPHERE; rb.radius = 0.5f;
        float y0 = b.position.y = 10f;
        run(app, 1f);
        float drop = y0 - b.position.y;
        check("free fall ~1s drops 3.5..5.5m", drop > 3.5f && drop < 5.5f, "drop=" + drop);

        // ---- 2) settle on ground ----
        app = fresh();
        Entity ground = app.world.spawn("ground");
        ground.add(new com.hollow.physics.BoxCollider());
        com.hollow.physics.BoxCollider gc = ground.get(com.hollow.physics.BoxCollider.class);
        gc.size.set(50f, 1f, 50f);
        ground.position.y = -0.5f;
        Entity s = app.world.spawn("sball");
        s.add(new RigidBody());
        RigidBody srb = s.get(RigidBody.class);
        srb.shape = RigidBody.Shape.SPHERE; srb.radius = 0.5f;
        s.position.y = 8f;
        run(app, 10f);
        check("sphere settles on ground (0.4..0.8, grounded)",
              s.position.y > 0.4f && s.position.y < 0.8f, "y=" + s.position.y);
        check("settled body sleeps", srb.sleeping, "sleeping=" + srb.sleeping);
        check("rest velocity ~0", srb.velocity.len() < 0.05f, "v=" + srb.velocity.len());

        // ---- 3) distance joint ----
        app = fresh();
        Entity a1 = app.world.spawn("j1");
        a1.add(new RigidBody());
        RigidBody a1rb = a1.get(RigidBody.class);
        a1rb.shape = RigidBody.Shape.SPHERE; a1rb.radius = 0.25f; a1rb.mass = 1f;
        a1.position.set(0f, 6f, 0f);
        Entity a2 = app.world.spawn("j2");
        a2.add(new RigidBody());
        RigidBody a2rb = a2.get(RigidBody.class);
        a2rb.shape = RigidBody.Shape.SPHERE; a2rb.radius = 0.25f; a2rb.mass = 1f;
        a2.position.set(3f, 6f, 0f);
        a1.add(new Joints.Distance(1f, 1f));
        Joints.Distance dj = (Joints.Distance) a1.get("DistanceJoint");
        dj.other = "j2";
        run(app, 4f);
        float sep = a2.position.cpy().sub(a1.position).len();
        check("distance joint pulls 3m pair to ~1m (0.7..1.3)", sep > 0.7f && sep < 1.3f, "sep=" + sep);

        // ---- 4) impulse travel ----
        app = fresh();
        Entity p = app.world.spawn("puck");
        p.add(new RigidBody());
        RigidBody prb = p.get(RigidBody.class);
        prb.shape = RigidBody.Shape.SPHERE; prb.radius = 0.25f; prb.linearDrag = 0f;
        p.position.set(0f, 20f, 0f);
        prb.addImpulse(new Vec3(10f, 0f, 0f));
        run(app, 1f);
        check("impulse 10m/s travels ~10m in 1s (drag=0)", p.position.x > 8.5f && p.position.x < 10.5f, "dx=" + p.position.x);

        // ---- 5) head-on collision separates ----
        app = fresh();
        Entity c1 = app.world.spawn("c1");
        c1.add(new RigidBody());
        RigidBody c1rb = c1.get(RigidBody.class);
        c1rb.shape = RigidBody.Shape.SPHERE; c1rb.radius = 0.5f; c1rb.bounciness = 0.5f;
        c1.position.set(-2f, 25f, 0f); c1rb.setVelocity(new Vec3(3f, 0f, 0f));
        Entity c2 = app.world.spawn("c2");
        c2.add(new RigidBody());
        RigidBody c2rb = c2.get(RigidBody.class);
        c2rb.shape = RigidBody.Shape.SPHERE; c2rb.radius = 0.5f; c2rb.bounciness = 0.5f;
        c2.position.set(2f, 25f, 0f); c2rb.setVelocity(new Vec3(-3f, 0f, 0f));
        run(app, 2f);
        float after = c2.position.cpy().sub(c1.position).len();
        check("head-on spheres separate (dist > 2.5)", after > 2.5f, "dist=" + after);

        // ---- 6) gravityScale=0 floats ----
        app = fresh();
        Entity f = app.world.spawn("float");
        f.add(new RigidBody());
        RigidBody frb = f.get(RigidBody.class);
        frb.shape = RigidBody.Shape.SPHERE; frb.radius = 0.25f; frb.gravityScale = 0f;
        f.position.set(0f, 30f, 0f);
        run(app, 2f);
        check("gravityScale=0 keeps y constant", Math.abs(f.position.y - 30f) < 0.05f, "y=" + f.position.y);

        System.out.println(fails == 0 ? "ALL PHYSICS REGRESSION TESTS PASSED" : fails + " TESTS FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
