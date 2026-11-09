package com.hollow.assets;

import com.hollow.core.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Node-based material graph, text form (MAT-020): a small DAG of nodes
 *  (const/vector/texture/multiply/add/lerp/power/mix-chain) evaluated offline to
 *  flattened MaterialDef values — the "editor" is JSON, the runtime cost is zero.
 *  Graph JSON:
 *  { "output": { "baseColor": {"node":"tint"}, "roughness": {"value":0.35} },
 *    "nodes": { "tint": {"op":"multiply", "a":{"value":[1,0.6,0.3,1]}, "b":{"texture":"tex/rust.hvt", "uv":0}} } }
 */
public final class MaterialGraph {

    /** tiny value model: either a constant rgba, a texture ref, a node op, or a named node reference */
    public static final class Socket {
        public String op;              // null for leaf
        public String texture;         // leaf: texture path
        public String ref;             // reference to a named node in the graph
        public float[] value;          // leaf: constant (1-4 floats)
        public float uv = 0f;          // texture uv set
        public Socket a, b, t;         // op inputs

        boolean isTexture(){ return texture != null; }
        boolean isConst(){ return op == null && texture == null && ref == null && value != null; }
    }

    public final Map<String, Socket> nodes = new java.util.LinkedHashMap<>();
    public final Map<String, Socket> outputs = new java.util.LinkedHashMap<>();

    public static MaterialGraph fromJson(String text){
        Map<String,Object> root = Json.parseObj(text);
        if(root == null) throw new IllegalArgumentException("material graph JSON invalid");
        MaterialGraph g = new MaterialGraph();
        Map<String,Object> nodes = Json.obj(root.get("nodes"));
        if(nodes != null){
            for(Map.Entry<String,Object> e : nodes.entrySet()){
                g.nodes.put(e.getKey(), parseSocket(Json.obj(e.getValue())));
            }
        }
        Map<String,Object> outs = Json.obj(root.get("output"));
        if(outs != null){
            for(Map.Entry<String,Object> e : outs.entrySet()){
                g.outputs.put(e.getKey(), parseSocket(Json.obj(e.getValue())));
            }
        }
        return g;
    }

    private static Socket parseSocket(Map<String,Object> n){
        if(n == null) return null;
        Socket s = new Socket();
        String ref = Json.str(n.get("node"), null);
        if(ref != null){ s.ref = ref; return s; }
        String op = Json.str(n.get("op"), null);
        String tex = Json.str(n.get("texture"), null);
        Object val = n.get("value");
        if(op != null){
            s.op = op.toLowerCase();
            s.a = parseSocket(Json.obj(n.get("a")));
            s.b = parseSocket(Json.obj(n.get("b")));
            s.t = parseSocket(Json.obj(n.get("t")));
        } else if(tex != null){
            s.texture = tex;
            s.uv = (float) Json.num(n.get("uv"), 0);
        } else if(val instanceof List<?> l && !l.isEmpty()){
            s.value = new float[Math.min(4, l.size())];
            for(int i=0;i<s.value.length;i++) s.value[i] = (float) Json.num(l.get(i), 0);
        } else if(val instanceof Number num){
            s.value = new float[]{ num.floatValue() };
        }
        return s;
    }

    // ---------------- evaluation (offline bake to MaterialDef) ----------------

    /** bake the graph into an effective MaterialDef (textures kept as paths) */
    public MaterialDef bake(){
        MaterialDef d = new MaterialDef();
        d.name = "graph";
        Socket bc = outputs.get("baseColor");
        if(bc != null){
            float[] v = evalRGBA(bc);
            System.arraycopy(v, 0, d.baseColorFactor, 0, 4);
            String tex = findTexture(bc);
            if(tex != null) d.baseColorTexture = tex;
        }
        Socket r = outputs.get("roughness");
        if(r != null) d.roughnessFactor = clamp01(evalScalar(r, 1f));
        Socket m = outputs.get("metallic");
        if(m != null) d.metallicFactor = clamp01(evalScalar(m, 0f));
        Socket em = outputs.get("emissive");
        if(em != null){
            float[] v = evalRGBA(em);
            d.emissiveFactor[0] = v[0]; d.emissiveFactor[1] = v[1]; d.emissiveFactor[2] = v[2];
            String tex = findTexture(em);
            if(tex != null) d.emissiveTexture = tex;
        }
        Socket n = outputs.get("normal");
        if(n != null){
            String tex = findTexture(n);
            if(tex != null) d.normalTexture = tex;
            d.normalScale = evalScalar(n, 1f) <= 0f ? 1f : d.normalScale;
        }
        return d;
    }

    /** cycle-safe recursive evaluation to rgba */
    private float[] evalRGBA(Socket s){
        return evalRGBA(s, 0);
    }
    private float[] evalRGBA(Socket s, int depth){
        if(s == null || depth > 16) return new float[]{1,1,1,1};
        if(s.ref != null){
            Socket target = nodes.get(s.ref);
            if(target == s) return new float[]{1,1,1,1};
            return evalRGBA(target, depth + 1);
        }
        if(s.isConst() || s.isTexture()){
            float[] v = s.value != null ? new float[4] : new float[4];
            if(s.value != null){
                for(int i=0;i<4;i++) v[i] = s.value[Math.min(i, s.value.length-1)];
                if(s.value.length < 4) v[3] = 1f;
            } else {
                java.util.Arrays.fill(v, 1f);          // texture socket: factor white
            }
            return v;
        }
        float[] a = evalRGBA(s.a, depth+1), b = evalRGBA(s.b, depth+1), t = evalRGBA(s.t, depth+1);
        return switch(s.op){
            case "multiply" -> { float[] o = new float[4]; for(int i=0;i<4;i++) o[i]=a[i]*b[i]; yield o; }
            case "add" -> { float[] o = new float[4]; for(int i=0;i<4;i++) o[i]=a[i]+b[i]; yield o; }
            case "subtract" -> { float[] o = new float[4]; for(int i=0;i<4;i++) o[i]=a[i]-b[i]; yield o; }
            case "lerp" -> { float[] o = new float[4]; for(int i=0;i<4;i++) o[i]=a[i]+(b[i]-a[i])*t[i]; yield o; }
            case "min" -> { float[] o = new float[4]; for(int i=0;i<4;i++) o[i]=Math.min(a[i],b[i]); yield o; }
            case "max" -> { float[] o = new float[4]; for(int i=0;i<4;i++) o[i]=Math.max(a[i],b[i]); yield o; }
            case "power" -> { float[] o = new float[4]; for(int i=0;i<4;i++) o[i]=(float)Math.pow(Math.max(0f,a[i]), b[i]); yield o; }
            case "saturate" -> { float[] o = new float[4]; for(int i=0;i<4;i++) o[i]=clamp01(a[i]); yield o; }
            case "one_minus" -> { float[] o = new float[4]; for(int i=0;i<4;i++) o[i]=1f-a[i]; yield o; }
            default -> new float[]{1,1,1,1};
        };
    }

    private float evalScalar(Socket s, float def){
        float[] v = evalRGBA(s);
        return v[0];
    }

    /** depth-first texture discovery (first texture wins) */
    private String findTexture(Socket s){
        return findTexture(s, 0);
    }
    private String findTexture(Socket s, int depth){
        if(s == null || depth > 16) return null;
        if(s.isTexture()) return s.texture;
        if(s.ref != null) return findTexture(nodes.get(s.ref), depth + 1);
        String t = findTexture(s.a, depth+1);
        if(t == null) t = findTexture(s.b, depth+1);
        return t;
    }

    private static float clamp01(float v){ return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** validation: unknown ops, missing inputs, unknown refs, cycles — `hollow validate` */
    public List<String> validate(){
        List<String> errs = new ArrayList<>();
        for(Map.Entry<String, Socket> e : nodes.entrySet()){
            Socket s = e.getValue();
            if(s.op != null && !List.of("multiply","add","subtract","lerp","min","max","power","saturate","one_minus").contains(s.op)){
                errs.add("node '" + e.getKey() + "': unknown op '" + s.op + "'");
            }
            if(s.op != null && (s.a == null || s.b == null) && !s.op.equals("saturate") && !s.op.equals("one_minus")){
                errs.add("node '" + e.getKey() + "': op '" + s.op + "' missing inputs");
            }
            if(s.ref != null && !nodes.containsKey(s.ref)){
                errs.add("node '" + e.getKey() + "': reference to unknown node '" + s.ref + "'");
            }
        }
        for(Map.Entry<String, Socket> e : outputs.entrySet()){
            if(e.getValue() == null){ errs.add("output '" + e.getKey() + "': empty socket"); continue; }
            if(e.getValue().ref != null && !nodes.containsKey(e.getValue().ref)){
                errs.add("output '" + e.getKey() + "': reference to unknown node '" + e.getValue().ref + "'");
            }
        }
        // cycle detection over ref edges + node-input edges
        for(String start : nodes.keySet()){
            if(hasCycle(start, new java.util.HashSet<>(), new java.util.HashSet<>())){
                errs.add("node '" + start + "': cycle detected");
                break;
            }
        }
        return errs;
    }

    private boolean hasCycle(String current, java.util.Set<String> visiting, java.util.Set<String> done){
        if(done.contains(current)) return false;
        if(!visiting.add(current)) return true;      // back-edge
        Socket s = nodes.get(current);
        boolean cyc = false;
        if(s != null){
            if(s.ref != null && nodes.containsKey(s.ref)) cyc = hasCycle(s.ref, visiting, done);
            if(!cyc && s.a != null) cyc = socketCycle(s.a, visiting, done);
            if(!cyc && s.b != null) cyc = socketCycle(s.b, visiting, done);
            if(!cyc && s.t != null) cyc = socketCycle(s.t, visiting, done);
        }
        visiting.remove(current);
        done.add(current);
        return cyc;
    }
    private boolean socketCycle(Socket s, java.util.Set<String> visiting, java.util.Set<String> done){
        if(s == null) return false;
        if(s.ref != null && nodes.containsKey(s.ref)) return hasCycle(s.ref, visiting, done);
        if(socketCycle(s.a, visiting, done)) return true;
        if(socketCycle(s.b, visiting, done)) return true;
        return socketCycle(s.t, visiting, done);
    }
}
