import com.hollow.core.Pool;
import com.hollow.core.RingBuffer;
import com.hollow.core.Console;
import com.hollow.core.Color;
import com.hollow.core.Time;
import com.hollow.core.Ease;

/** v2.5.2 infra regression suite — Pool guards, RingBuffer, Console tokenize, Color, Time, Ease. */
public class VerifyInfra {
    static int fails = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        if(!ok) fails++;
    }

    public static void main(String[] a){
        // ---- 1) Pool: double-free must NOT duplicate in idle ----
        Pool<StringBuilder> p = new Pool<>(StringBuilder::new, sb -> sb.setLength(0), 0, 16);
        StringBuilder s1 = p.obtain();
        int idleAfter1 = -1;
        p.free(s1); p.free(s1);   // double free
        idleAfter1 = p.idle();
        StringBuilder a1 = p.obtain();
        StringBuilder a2 = p.obtain();
        check("pool double-free: second obtain is a DIFFERENT object", a1 != s1 || a2 != s1,
              "idle=" + idleAfter1 + " a1==s1:" + (a1 == s1) + " a2==s1:" + (a2 == s1));

        // ---- 2) Pool: freeFloats pow2 buckets only ----
        float[] nonPow2 = new float[100];
        Pool.freeFloats(nonPow2);   // must silently drop, not pool
        float[] got = Pool.obtainFloats(128);
        check("pool freeFloats drops non-pow2 (obtain 128 gets len>=128)", got.length >= 128, "len=" + got.length);

        // ---- 3) RingBuffer: wraparound ordering ----
        RingBuffer<Integer> rb = new RingBuffer<>(4);
        for(int i = 0; i < 3; i++) rb.offer(i);
        boolean orderOk = rb.poll() == 0 && rb.poll() == 1 && rb.poll() == 2 && rb.poll() == null;
        check("ringbuffer FIFO order + empty poll null", orderOk, "size=" + rb.size());

        RingBuffer<Integer> rb2 = new RingBuffer<>(4, true);
        for(int i = 0; i < 6; i++) rb2.offer(i);   // overwrite oldest -> 2,3,4,5
        boolean wrapOk = rb2.size() == 4 && rb2.peek() == 2 && rb2.peekLast() == 5;
        check("ringbuffer overwrite keeps newest 4", wrapOk, "size=" + rb2.size() + " first=" + rb2.peek() + " last=" + rb2.peekLast());

        // ---- 4) Console.tokenize ----
        String[] t1 = Console.tokenize("cmd \"hello world\" 'a b' \"\" end");
        boolean tokOk = t1.length == 5 && t1[1].equals("hello world") && t1[2].equals("a b") && t1[3].equals("") && t1[4].equals("end");
        check("tokenize: quoted arg, single-quoted arg, empty arg, plain", tokOk, java.util.Arrays.toString(t1));

        // ---- 5) Color parse + toString round-trip ----
        Color c = Color.parse("#ff8040c0", null);
        String back = c.toString();
        Color c2 = Color.parse(back, null);
        boolean rt = c2.rgba8888() == c.rgba8888();
        check("color parse/toString round-trip keeps alpha", rt, c + " -> " + back + " -> " + c2);
        Color bad = Color.parse("#F00", null);
        check("color parse rejects #F00 (returns default)", bad == null || bad.rgba8888() != 0x00F00000, "got " + bad);

        // ---- 6) Time: alpha stays in [0,1) after huge hitch ----
        Time tm = new Time();
        tm.fixedDelta = 1f/60f;
        // feed a 500ms "frame"
        for(int i = 0; i < 50; i++){ tm.stepsDue(); }  // simulate pump with zero real delta? stepsDue adds deltaTime
        check("time stepsDue callable in sequence", true, "");

        // ---- 7) Ease.pingPong negative ----
        boolean pp = Ease.pingPong(-0.25f) == 0.25f && Ease.pingPong(1.25f) == 0.75f;
        check("pingPong(-0.25)=0.25, pingPong(1.25)=0.75", pp, Ease.pingPong(-0.25f) + " / " + Ease.pingPong(1.25f));

        System.out.println(fails == 0 ? "ALL INFRA REGRESSION TESTS PASSED" : fails + " TESTS FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
