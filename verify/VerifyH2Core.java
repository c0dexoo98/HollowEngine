import com.hollow.core.Json;
import com.hollow.core.MathX;
import com.hollow.core.MathX.Mat4;
import com.hollow.core.MathX.Vec3;

import java.util.Map;

/** HollowEngine 2 — core regression: JSON reader/writer + math. */
public class VerifyH2Core {
    static int fails = 0, total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++; if(!ok) fails++;
    }

    public static void main(String[] args){
        json();
        math();
        System.out.println(fails == 0 ? "\nALL " + total + " CORE TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " CORE TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void json(){
        Map<String,Object> deep = Json.parseObj("{\"a\": {\"b\": {\"c\": 7}}}");
        Map<String,Object> lvl2 = Json.obj(deep.get("a"));
        Map<String,Object> lvl3 = Json.obj(lvl2.get("b"));
        check("js: nested objects", lvl2 != null && lvl3 != null && Json.num(lvl3.get("c"), 0) == 7, "");
        Map<String,Object> m = Json.parseObj("{\"esc\": \"line\\nbreak\", \"u\": \"\\u0041\", \"neg\": -2.5, \"exp\": 1e2}");
        check("js: string escapes", Json.str(m.get("esc"), "").equals("line\nbreak"), "");
        check("js: unicode escape", Json.str(m.get("u"), "").equals("A"), "");
        check("js: negative + exponent", Json.num(m.get("neg"), 0) == -2.5 && Json.num(m.get("exp"), 0) == 100, "");
        check("js: array of objects", Json.arr(Json.parseObj("{\"x\": [{\"k\": 1}, {\"k\": 2}]}").get("x")).size() == 2, "");
        check("js: defaults on wrong types", Json.num("nope", 3) == 3 && Json.bool(null, true) && Json.str(42, "d").equals("d"), "");
        try {
            Json.parseObj("{\"a\": }");
            check("js: bad json throws", false, "no exception");
        } catch(IllegalArgumentException e){
            check("js: bad json throws", true, "");
        }
        try {
            Json.parseObj("{\"a\": 1} trailing");
            check("js: trailing garbage throws", false, "no exception");
        } catch(IllegalArgumentException e){
            check("js: trailing garbage throws", true, "");
        }
        Json.JsonW w = new Json.JsonW();
        String out = w.obj().kv("i", 2).kv("f", 2.5).arr("l").item("a").item(1).endArr()
            .obj("nested").kv("deep", true).endObj().endObj().toString();
        check("js: writer commas + nesting", out.equals("{\"i\": 2, \"f\": 2.5, \"l\": [\"a\", 1], \"nested\": {\"deep\": true}}"), out);
        check("js: writer output reparses", Json.parseObj(out).size() == 4, "");
        Json.JsonW s = new Json.JsonW();
        s.obj().kv("t", "quote\"inside").endObj();
        check("js: writer escapes quotes", s.toString().equals("{\"t\": \"quote\\\"inside\"}"), s.toString());
    }

    static void math(){
        check("mx: clamp", MathX.clamp(5f, 0f, 1f) == 1f && MathX.clamp(-1, 0, 9) == 0 && MathX.clamp(0.5f, 0f, 1f) == 0.5f, "");
        Vec3 c = new Vec3(1, 0, 0).cross(new Vec3(0, 1, 0));
        check("mx: cross product", Math.abs(c.z - 1) < 1e-6, c.toString());
        check("mx: dot + len + norm", Math.abs(new Vec3(3, 4, 0).dot(new Vec3(1, 0, 0)) - 3) < 1e-6
              && Math.abs(new Vec3(0, 3, 4).len() - 5) < 1e-6
              && Math.abs(new Vec3(0, 0, 9).norm().z - 1) < 1e-6, "");
        Mat4 r = new Mat4().identity().rotateZ(90).rotateZ(-90);
        Vec3 p = r.transformPoint(new Vec3(1, 0, 0));
        check("mx: rotation cancels", Math.abs(p.x - 1) < 1e-4 && Math.abs(p.y) < 1e-4, p.toString());
        Mat4 t = new Mat4().identity().translate(2, 0, 0).rotateY(90);
        Vec3 q = t.transformPoint(new Vec3(0, 0, -1));
        // OpenGL-convention rotateY(+90°): -Z maps to -X, then translate(2,0,0) → (1,0,0)
        check("mx: translate∘rotate order", Math.abs(q.x - 1) < 1e-4 && Math.abs(q.z) < 1e-4, q.toString());
        Mat4 sc = new Mat4().identity().scale(2, 3, 4);
        Vec3 sp = sc.transformPoint(new Vec3(1, 1, 1));
        check("mx: scale", Math.abs(sp.x - 2) < 1e-5 && Math.abs(sp.y - 3) < 1e-5 && Math.abs(sp.z - 4) < 1e-5, "");
        Mat4 v = new Mat4().lookAt(new Vec3(0, 0, 5), new Vec3(0, 0, 0), new Vec3(0, 1, 0));
        Vec3 vp = v.transformPoint(new Vec3(0, 0, 0));
        check("mx: lookAt centers target", Math.abs(vp.x) < 1e-5 && Math.abs(vp.y) < 1e-5 && Math.abs(vp.z + 5) < 1e-4, vp.toString());
        Mat4 pr = new Mat4().identity().perspective(60, 1, 1, 101);
        Vec3 near = pr.transformPoint(new Vec3(0, 0, -1.2f));
        Vec3 far = pr.transformPoint(new Vec3(0, 0, -50));
        check("mx: perspective near < far in ndc", near.z < far.z && near.z > -1.2f && far.z < 1f, near + " / " + far);
        MathX.Rng r1 = new MathX.Rng(0x5EED);
        MathX.Rng r2 = new MathX.Rng(0x5EED);
        boolean same = true;
        for(int i = 0; i < 100; i++) if(r1.nextDouble() != r2.nextDouble()) same = false;
        check("mx: rng deterministic across instances", same, "");
        MathX.Rng r3 = new MathX.Rng(1);
        int min = 10, max = -1;
        for(int i = 0; i < 1000; i++){
            int x = r3.nextInt(10);
            if(x < min) min = x;
            if(x > max) max = x;
        }
        check("mx: nextInt bounds", min >= 0 && max < 10 && min == 0 && max == 9, min + ".." + max);
        MathX.Rng r4 = new MathX.Rng(2);
        boolean inRange = true;
        for(int i = 0; i < 500; i++){
            double f = r4.range(3.5, 7.5);
            if(f < 3.5 || f >= 7.5) inRange = false;
        }
        check("mx: range bounds", inRange, "");
        check("mx: different seeds diverge", new MathX.Rng(1).nextLong() != new MathX.Rng(2).nextLong(), "");
    }
}
