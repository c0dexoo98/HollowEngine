import com.hollow.core.Mat4;
import com.hollow.core.Quat;
import com.hollow.core.Vec3;
import com.hollow.core.Sampling;

/** v2.5.2 micro-benchmarks — matrix math, quaternion chains, poisson sampling. */
public class BenchCore {
    public static void main(String[] a){
        // ---- Mat4.mul throughput ----
        Mat4 m1 = Mat4.trs(new Vec3(1,2,3), Quat.fromEuler(15f, 40f, 0f), new Vec3(1,1,1));
        Mat4 m2 = Mat4.trs(new Vec3(4,5,6), Quat.fromEuler(-20f, 10f, 5f), new Vec3(2,1,1));
        for(int warm = 0; warm < 20000; warm++) m1.mul(m2);
        long t0 = System.nanoTime();
        final int N = 5_000_000;
        for(int i = 0; i < N; i++) m1.mul(m2);
        long t1 = System.nanoTime();
        System.out.printf("Mat4.mul: %.1f M ops/s (%d in %d ms)%n", N / ((t1-t0)/1e6) / 1e6, N, (t1-t0)/1_000_000);

        // ---- Quat slerp throughput ----
        Quat qa = Quat.fromEuler(10f, 20f, 30f), qb = Quat.fromEuler(-10f, -20f, -30f);
        for(int warm = 0; warm < 20000; warm++) qa.slerp(qb, 0.5f);
        t0 = System.nanoTime();
        for(int i = 0; i < N; i++) qa.slerp(qb, 0.5f);
        t1 = System.nanoTime();
        System.out.printf("Quat.slerp: %.1f M ops/s (%d in %d ms)%n", N / ((t1-t0)/1e6) / 1e6, N, (t1-t0)/1_000_000);

        // ---- transformPoint throughput ----
        Vec3 v = new Vec3(1, 2, 3);
        Mat4 vp = Mat4.perspective(70f, 16f/9f, 0.1f, 100f);
        t0 = System.nanoTime();
        for(int i = 0; i < N; i++) vp.transformPoint(v);
        t1 = System.nanoTime();
        System.out.printf("Mat4.transformPoint: %.1f M ops/s (%d in %d ms)%n", N / ((t1-t0)/1e6) / 1e6, N, (t1-t0)/1_000_000);

        // ---- poisson disk sampling scale ----
        t0 = System.nanoTime();
        int pts = Sampling.poissonDisk(0.06f, new com.hollow.core.RandomStream(12345L), 20).size();
        t1 = System.nanoTime();
        System.out.printf("poissonDisk(r=0.06, 20 tries): %d points in %d ms%n", pts, (t1-t0)/1_000_000);

        // ---- inverse throughput ----
        t0 = System.nanoTime();
        final int NI = 1_000_000;
        for(int i = 0; i < NI; i++) m1.invert();
        t1 = System.nanoTime();
        System.out.printf("Mat4.invert: %.1f M ops/s (%d in %d ms)%n", NI / ((t1-t0)/1e6) / 1e6, NI, (t1-t0)/1_000_000);
        System.out.println("BENCH DONE");
    }
}
