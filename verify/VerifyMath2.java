import com.hollow.core.Mat4;
import com.hollow.core.Quat;
import com.hollow.core.Vec3;

/** Regression tests for the v2.5.1 math fixes (Mat4.mul order, Quat euler signs). */
public class VerifyMath2 {
    static int fails = 0;

    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        if(!ok) fails++;
    }

    public static void main(String[] a){
        // ---- 1) Mat4.mul must compute this * o (post-multiply) ----
        // A = rotZ90, B = translate(5,0,0): A.mul(B) => rotate(translate(v)) => t rotated by 90deg = (0,5,0)
        Mat4 A = new Mat4();
        A.set(Mat4.trs(new Vec3(0,0,0), Quat.fromAxisAngle(0,0,1,90f), new Vec3(1,1,1)));
        Mat4 B = new Mat4();
        B.set(Mat4.trs(new Vec3(5,0,0), new Quat(), new Vec3(1,1,1)));
        A.mul(B);
        Vec3 t = A.translation();
        check("Mat4.mul order (rotZ90 * translateX5 -> (0,5,0))",
              Math.abs(t.x) < 1e-4f && Math.abs(t.y - 5f) < 1e-4f && Math.abs(t.z) < 1e-4f, "got " + t.x + "," + t.y + "," + t.z);

        // ---- 2) transformPoint of the same product: v=(0,0,0) -> (0,5,0); v=(1,0,0) -> (0,6,0) ----
        Vec3 o1 = A.transformPoint(new Vec3(0,0,0));
        Vec3 o2 = A.transformPoint(new Vec3(1,0,0));
        check("Mat4.transformPoint origin", Math.abs(o1.x)<1e-4 && Math.abs(o1.y-5)<1e-4, "got " + o1.x + "," + o1.y);
        check("Mat4.transformPoint +x", Math.abs(o2.x)<1e-4 && Math.abs(o2.y-6)<1e-4, "got " + o2.x + "," + o2.y);

        // ---- 3) parent-child chain: world = parent * child ----
        Mat4 parent = Mat4.trs(new Vec3(10,0,0), Quat.fromAxisAngle(0,1,0,90f), new Vec3(1,1,1));
        Mat4 child  = Mat4.trs(new Vec3(0,0,-2), new Quat(), new Vec3(1,1,1));   // child 2m in front (local -Z)
        Mat4 world = new Mat4(); world.set(parent); world.mul(child);
        Vec3 wp = world.translation();
        // parent yaw90: local -Z maps to -X => child at (10,0,0) + (-2,0,0) = (8,0,0)
        check("Mat4 parent*child chain", Math.abs(wp.x-8)<1e-3 && Math.abs(wp.y)<1e-4 && Math.abs(wp.z)<1e-4, "got " + wp.x + "," + wp.y + "," + wp.z);

        // ---- 4) Quat.yawDeg round-trip ----
        float[] yaws = {0f, 30f, 90f, 170f, -45f, -170f};
        boolean yawOk = true; String yd = "";
        for(float yaw : yaws){
            Quat q = Quat.fromYaw(yaw);
            float got = q.yawDeg();
            if(Math.abs(got - yaw) > 0.01f){ yawOk = false; yd += yaw + "->" + got + " "; }
        }
        check("Quat.yawDeg round-trip", yawOk, yd);

        // ---- 5) Quat.pitchDeg round-trip (via fromEuler y-pitch) ----
        boolean pitchOk = true; String pd = "";
        for(float pitch : new float[]{0f, 20f, -20f, 60f}){
            Quat q = Quat.fromEuler(pitch, 0f, 0f);
            float got = q.pitchDeg();
            if(Math.abs(got - pitch) > 0.01f){ pitchOk = false; pd += pitch + "->" + got + " "; }
        }
        check("Quat.pitchDeg round-trip", pitchOk, pd);

        // ---- 6) yaw+pitch composed extract within principal ranges ----
        Quat q = Quat.fromEuler(15f, 40f, 0f);
        check("Quat yaw+pitch extract", Math.abs(q.yawDeg()-40f)<0.01f && Math.abs(q.pitchDeg()-15f)<0.01f,
              "yaw=" + q.yawDeg() + " pitch=" + q.pitchDeg());

        // ---- 7) forward() consistency: fromYaw(90) rotates -Z to -X? engine forward=-Z ----
        Vec3 f = Quat.fromYaw(90f).forward();
        // yaw90 about +Y maps -Z -> -X (right-handed)
        check("Quat.forward yaw90", Math.abs(f.x+1)<1e-4 && Math.abs(f.y)<1e-4 && Math.abs(f.z)<1e-4, "got " + f.x + "," + f.z);

        System.out.println(fails == 0 ? "ALL MATH REGRESSION TESTS PASSED" : fails + " TESTS FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
