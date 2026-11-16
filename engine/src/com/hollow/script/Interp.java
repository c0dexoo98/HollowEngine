package com.hollow.script;

import com.hollow.core.MathX;
import com.hollow.script.Parser.*;
import com.hollow.script.Parser.Stmt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** HollowScript — tree-walking interpreter.
 *  Values: Double, String, Boolean, null, List&lt;Object&gt;, Map&lt;String,Object&gt;,
 *  and Native (engine objects like scene nodes, exposed to scripts with
 *  dot get/set/call). Functions are global (GDScript style); control
 *  flow uses lightweight exceptions. `rand` is deterministic (seeded). */
public final class Interp {

    /** engine object surface for scripts: node.rotation_y = node.rotation_y + 1 */
    public interface Native {
        Object hGet(String key);
        void hSet(String key, Object value);
        Object hCall(String name, List<Object> args);
        boolean hHas(String key);
        String hTypeName();
    }

    public static final class ScriptError extends RuntimeException {
        public final int line;
        public ScriptError(int line, String msg){ super("HollowScript runtime error, line " + line + ": " + msg); this.line = line; }
    }

    private static final class ReturnEx extends RuntimeException {
        final Object value;
        ReturnEx(Object value){ this.value = value; }
    }
    private static final class BreakEx extends RuntimeException { }
    private static final class ContinueEx extends RuntimeException { }

    private static final class Env {
        final Env parent;
        final Map<String,Object> vars = new LinkedHashMap<>();
        Env(Env parent){ this.parent = parent; }
        boolean has(String k){
            if(vars.containsKey(k)) return true;
            return parent != null && parent.has(k);
        }
        Object get(String k, int line){
            if(vars.containsKey(k)) return vars.get(k);
            if(parent != null) return parent.get(k, line);
            throw new ScriptError(line, "unknown variable '" + k + "'");
        }
        void assign(String k, Object v){
            if(vars.containsKey(k) || parent == null){ vars.put(k, v); return; }
            parent.assign(k, v);
        }
        void declare(String k, Object v){ vars.put(k, v); }
    }

    private final Program program;
    private final Map<String, FuncDef> funcs = new LinkedHashMap<>();
    private final Env globals = new Env(null);
    private Consumer<String> out = System.out::println;
    private MathX.Rng rng = new MathX.Rng(0x1D3L);
    private long seed = 0x1D3L;

    public Interp(Program program){
        this.program = program;
        for(FuncDef f : program.funcs)
            funcs.put(f.name, f);
    }

    public static Interp fromSource(String src){
        return new Interp(Parser.parse(src));
    }

    public void setOut(Consumer<String> out){ this.out = out; }
    public void setSeed(long s){
        seed = s;
        rng = new MathX.Rng(s);
    }
    public long seed(){ return seed; }

    /** run top-level statements (declares globals); safe to call once */
    public void runBody(){
        execBlock(program.body, globals);
    }

    /** call a user function; script errors propagate as ScriptError */
    public Object callFunc(String name, Object... args){
        FuncDef f = funcs.get(name);
        if(f == null) throw new ScriptError(0, "unknown function '" + name + "'");
        if(args.length != f.params.size())
            throw new ScriptError(f.line, "function '" + name + "' expects " + f.params.size()
                + " args, got " + args.length);
        Env env = new Env(globals);
        for(int i = 0; i < args.length; i++)
            env.declare(f.params.get(i), args[i]);
        try {
            execBlock(f.body, env);
        } catch(ReturnEx r){
            return r.value;
        }
        return null;
    }

    public boolean hasFunc(String name){ return funcs.containsKey(name); }

    /** global variable surface (selftest + tooling) */
    public boolean hasGlobal(String name){ return globals.vars.containsKey(name); }
    public Object global(String name){ return globals.vars.get(name); }

    // ------------------------- statements -------------------------

    private void execBlock(List<Stmt> stmts, Env env){
        for(Stmt s : stmts) exec(s, env);
    }

    private void exec(Stmt s, Env env){
        if(s instanceof VarStmt){
            VarStmt v = (VarStmt)s;
            env.declare(v.name, v.init == null ? null : eval(v.init, env));
        } else if(s instanceof AssignStmt){
            AssignStmt a = (AssignStmt)s;
            Object val = eval(a.value, env);
            if(!a.op.equals("=")){
                Object old = evalRead(a.target, env);
                String binOp = a.op.substring(0, 1);
                val = binop(binOp, old, val, a.line);
            }
            assignTo(a.target, val, env);
        } else if(s instanceof IfStmt){
            IfStmt i = (IfStmt)s;
            if(truthy(eval(i.cond, env))) execBlock(i.then, new Env(env));
            else {
                boolean done = false;
                for(Object[] e : i.elifs)
                    if(truthy(eval((Expr)e[0], env))){
                        execBlock((List<Stmt>)e[1], new Env(env));
                        done = true;
                        break;
                    }
                if(!done && !i.els.isEmpty()) execBlock(i.els, new Env(env));
            }
        } else if(s instanceof WhileStmt){
            WhileStmt w = (WhileStmt)s;
            while(truthy(eval(w.cond, env))){
                try {
                    execBlock(w.body, new Env(env));
                } catch(BreakEx b){ break; }
                  catch(ContinueEx c){ /* next iteration */ }
            }
        } else if(s instanceof ForStmt){
            ForStmt f = (ForStmt)s;
            Object it = eval(f.iter, env);
            List<Object> items = new ArrayList<>();
            if(it instanceof List) items.addAll((List<?>)it);
            else if(it instanceof Map) items.addAll(((Map<?,?>)it).keySet());
            else if(it instanceof String){
                String str = (String)it;
                for(int i = 0; i < str.length(); i++) items.add(String.valueOf(str.charAt(i)));
            } else if(it instanceof RangeVal){
                items.addAll(((RangeVal)it).items);
            } else throw new ScriptError(f.line, "cannot iterate over " + typeName(it));
            for(Object o : items){
                Env inner = new Env(env);
                inner.declare(f.name, o);
                try {
                    execBlock(f.body, inner);
                } catch(BreakEx b){ break; }
                  catch(ContinueEx c){ /* next */ }
            }
        } else if(s instanceof ReturnStmt){
            ReturnStmt r = (ReturnStmt)s;
            throw new ReturnEx(r.value == null ? null : eval(r.value, env));
        } else if(s instanceof BreakStmt){
            throw new BreakEx();
        } else if(s instanceof ContinueStmt){
            throw new ContinueEx();
        } else if(s instanceof PassStmt){
            // nothing
        } else if(s instanceof ExprStmt){
            eval(((ExprStmt)s).expr, env);
        } else {
            throw new ScriptError(s.line, "unknown statement");
        }
    }

    /** tiny sentinel so `range()` stays lazy until for-in materializes it */
    public static final class RangeVal {
        public final List<Object> items;
        RangeVal(List<Object> items){ this.items = items; }
    }

    private Object evalRead(Expr e, Env env){ return eval(e, env); }

    private void assignTo(Expr target, Object val, Env env){
        if(target instanceof Ident){
            String name = ((Ident)target).name;
            if(env.has(name)) env.assign(name, val);
            else env.declare(name, val);
        } else if(target instanceof Dot){
            Dot d = (Dot)target;
            Object obj = eval(d.obj, env);
            if(obj instanceof Native){
                ((Native)obj).hSet(d.name, val);
            } else if(obj instanceof Map){
                @SuppressWarnings("unchecked")
                java.util.Map<String,Object> m = (java.util.Map<String,Object>)obj;
                m.put(d.name, val);
            } else throw new ScriptError(d.line, "cannot set property on " + typeName(obj));
        } else if(target instanceof Index){
            Index ix = (Index)target;
            Object obj = eval(ix.obj, env);
            Object key = eval(ix.idx, env);
            if(obj instanceof List){
                int i = (int)asNum(key, ix.line);
                List<Object> l = asList(obj, ix.line);
                if(i < 0 || i >= l.size()) throw new ScriptError(ix.line, "index out of bounds: " + i);
                l.set(i, val);
            } else if(obj instanceof Map){
                ((Map<String,Object>)obj).put(String.valueOf(key), val);
            } else throw new ScriptError(ix.line, "cannot index-assign into " + typeName(obj));
        } else throw new ScriptError(target.line, "invalid assignment target");
    }

    // ------------------------- expressions -------------------------

    private Object eval(Expr e, Env env){
        if(e instanceof NumLit) return ((NumLit)e).v;
        if(e instanceof StrLit) return ((StrLit)e).v;
        if(e instanceof BoolLit) return ((BoolLit)e).v;
        if(e instanceof NullLit) return null;
        if(e instanceof Ident) return env.get(((Ident)e).name, e.line);
        if(e instanceof Bin){
            Bin b = (Bin)e;
            if(b.op.equals("and")) return truthy(eval(b.l, env)) ? truthy(eval(b.r, env)) : Boolean.FALSE;
            if(b.op.equals("or")) return truthy(eval(b.l, env)) ? Boolean.TRUE : truthy(eval(b.r, env));
            return binop(b.op, eval(b.l, env), eval(b.r, env), e.line);
        }
        if(e instanceof Un){
            Un u = (Un)e;
            if(u.op.equals("not")) return !truthy(eval(u.e, env));
            return -asNum(eval(u.e, env), e.line);
        }
        if(e instanceof Call){
            Call c = (Call)e;
            List<Object> args = new ArrayList<>();
            for(Expr a : c.args) args.add(eval(a, env));
            return callBuiltin(c.name, args, e.line);
        }
        if(e instanceof MethodCall){
            MethodCall mc = (MethodCall)e;
            Object obj = eval(mc.obj, env);
            List<Object> args = new ArrayList<>();
            for(Expr a : mc.args) args.add(eval(a, env));
            if(obj instanceof Native) return ((Native)obj).hCall(mc.name, args);
            if(obj instanceof String) return stringMethod((String)obj, mc.name, args, e.line);
            if(obj instanceof List){
                switch(mc.name){
                    case "append": asObjectList((List<?>)obj).add(args.isEmpty() ? null : args.get(0)); return null;
                    case "pop": {
                        List<Object> l = asObjectList((List<?>)obj);
                        if(l.isEmpty()) throw new ScriptError(e.line, "pop() of an empty list");
                        return l.remove(l.size() - 1);
                    }
                    case "len": return (double)((List<?>)obj).size();
                    default: throw new ScriptError(e.line, "list has no method '" + mc.name + "'");
                }
            }
            if(obj instanceof Map)
                throw new ScriptError(e.line, "map has no method '" + mc.name + "' (use keys(), has())");
            throw new ScriptError(e.line, "cannot call '" + mc.name + "' on " + typeName(obj));
        }
        if(e instanceof Dot){
            Dot d = (Dot)e;
            Object obj = eval(d.obj, env);
            if(obj instanceof Native){
                Native n = (Native)obj;
                if(n.hHas(d.name)) return n.hGet(d.name);
                // call member: node.find("light") — dot followed by call not parsed as method;
                // expose callable members through hGet returning nothing → error mentions it
                throw new ScriptError(d.line, "native '" + n.hTypeName() + "' has no property '" + d.name + "'");
            }
            if(obj instanceof Map){
                Map<?,?> m = (Map<?,?>)obj;
                if(m.containsKey(d.name)) return m.get(d.name);
                throw new ScriptError(d.line, "map has no key '" + d.name + "'");
            }
            throw new ScriptError(d.line, "cannot read property '" + d.name + "' of " + typeName(obj));
        }
        if(e instanceof Index){
            Index ix = (Index)e;
            Object obj = eval(ix.obj, env);
            Object key = eval(ix.idx, env);
            if(obj instanceof List){
                int i = (int)asNum(key, ix.line);
                List<Object> l = asList(obj, ix.line);
                if(i < 0 || i >= l.size()) throw new ScriptError(ix.line, "index out of bounds: " + i);
                return l.get(i);
            }
            if(obj instanceof Map) return ((Map<?,?>)obj).get(String.valueOf(key));
            if(obj instanceof String){
                int i = (int)asNum(key, ix.line);
                String s = (String)obj;
                if(i < 0 || i >= s.length()) throw new ScriptError(ix.line, "index out of bounds: " + i);
                return String.valueOf(s.charAt(i));
            }
            throw new ScriptError(ix.line, "cannot index into " + typeName(obj));
        }
        if(e instanceof ListLit){
            List<Object> out = new ArrayList<>();
            for(Expr i : ((ListLit)e).items) out.add(eval(i, env));
            return out;
        }
        if(e instanceof MapLit){
            MapLit ml = (MapLit)e;
            Map<String,Object> m = new LinkedHashMap<>();
            for(int i = 0; i < ml.keys.size(); i++) m.put(ml.keys.get(i), eval(ml.vals.get(i), env));
            return m;
        }
        throw new ScriptError(e.line, "unknown expression");
    }

    // ------------------------- operators & builtins -------------------------

    private Object binop(String op, Object a, Object b, int line){
        if(op.equals("==")) return eq(a, b);
        if(op.equals("!=")) return !eq(a, b);
        if(op.equals("+")){
            if(a instanceof String || b instanceof String) return strOf(a) + strOf(b);
            if(a instanceof List && b instanceof List){
                List<Object> out = new ArrayList<>((List<?>)a);
                out.addAll((List<?>)b);
                return out;
            }
            return asNum(a, line) + asNum(b, line);
        }
        if(op.equals("-")){
            if(a instanceof List && b instanceof List){
                List<Object> out = new ArrayList<>((List<?>)a);
                out.removeAll((List<?>)b);
                return out;
            }
            return asNum(a, line) - asNum(b, line);
        }
        if(op.equals("*")){
            if(a instanceof String && b instanceof Double){
                int n = (int)asNum(b, line);
                StringBuilder s = new StringBuilder();
                for(int i = 0; i < Math.max(0, n); i++) s.append(a);
                return s.toString();
            }
            return asNum(a, line) * asNum(b, line);
        }
        if(op.equals("/")){
            double d = asNum(b, line);
            if(d == 0) throw new ScriptError(line, "division by zero");
            return asNum(a, line) / d;
        }
        if(op.equals("%")){
            double d = asNum(b, line);
            if(d == 0) throw new ScriptError(line, "modulo by zero");
            return asNum(a, line) % d;
        }
        if(op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">=")){
            if(a instanceof String && b instanceof String){
                int c = ((String)a).compareTo((String)b);
                switch(op){
                    case "<": return c < 0;
                    case "<=": return c <= 0;
                    case ">": return c > 0;
                    default: return c >= 0;
                }
            }
            double x = asNum(a, line), y = asNum(b, line);
            switch(op){
                case "<": return x < y;
                case "<=": return x <= y;
                case ">": return x > y;
                default: return x >= y;
            }
        }
        throw new ScriptError(line, "unknown operator '" + op + "'");
    }

    private boolean eq(Object a, Object b){
        if(a instanceof Number && b instanceof Number)
            return ((Number)a).doubleValue() == ((Number)b).doubleValue();
        if(a instanceof Boolean || b instanceof Boolean)
            return Boolean.TRUE.equals(a) == Boolean.TRUE.equals(b);
        if(a == null || b == null) return a == b;
        return a.equals(b);
    }

    private boolean truthy(Object v){
        if(v == null) return false;
        if(v instanceof Boolean) return (Boolean)v;
        if(v instanceof Double) return (Double)v != 0.0;
        if(v instanceof String) return !((String)v).isEmpty();
        if(v instanceof List) return !((List<?>)v).isEmpty();
        if(v instanceof Map) return !((Map<?,?>)v).isEmpty();
        return true;
    }

    private double asNum(Object v, int line){
        if(v instanceof Double) return (Double)v;
        if(v instanceof Boolean) return (Boolean)v ? 1 : 0;
        throw new ScriptError(line, "expected a number, got " + typeName(v));
    }
    private List<Object> asList(Object v, int line){
        if(v instanceof List) return asObjectList((List<?>)v);
        throw new ScriptError(line, "expected a list, got " + typeName(v));
    }
    @SuppressWarnings("unchecked")
    private List<Object> asObjectList(List<?> l){ return (List<Object>)l; }

    public static String typeName(Object v){
        if(v == null) return "null";
        if(v instanceof Double) return "number";
        if(v instanceof String) return "string";
        if(v instanceof Boolean) return "bool";
        if(v instanceof List || v instanceof RangeVal) return "list";
        if(v instanceof Map) return "map";
        if(v instanceof Native) return ((Native)v).hTypeName();
        return v.getClass().getSimpleName();
    }
    public static String strOf(Object v){
        if(v == null) return "null";
        if(v instanceof Double){
            double d = (Double)v;
            if(d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long)d);
            return String.valueOf(d);
        }
        if(v instanceof List){
            StringBuilder b = new StringBuilder("[");
            List<?> l = (List<?>)v;
            for(int i = 0; i < l.size(); i++){
                if(i > 0) b.append(", ");
                b.append(strOf(l.get(i)));
            }
            return b.append("]").toString();
        }
        if(v instanceof Map){
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for(Map.Entry<?,?> kv : ((Map<?,?>)v).entrySet()){
                if(!first) b.append(", ");
                first = false;
                b.append(kv.getKey()).append(": ").append(strOf(kv.getValue()));
            }
            return b.append("}").toString();
        }
        if(v instanceof Boolean) return (Boolean)v ? "true" : "false";
        return String.valueOf(v);
    }

    private Object stringMethod(String s, String name, List<Object> args, int line){
        switch(name){
            case "upper": return s.toUpperCase();
            case "lower": return s.toLowerCase();
            case "trim": return s.trim();
            case "contains": return s.contains(strOf(arg(args, 0, "contains")));
            case "split": {
                String sep = args.isEmpty() ? " " : strOf(args.get(0));
                List<Object> out = new ArrayList<>();
                for(String p : s.split(java.util.regex.Pattern.quote(sep))) out.add(p);
                return out;
            }
            case "len": return (double)s.length();
            default: throw new ScriptError(line, "string has no method '" + name + "'");
        }
    }

    private Object callBuiltin(String name, List<Object> args, int line){
        switch(name){
            case "print":
                StringBuilder b = new StringBuilder();
                for(int i = 0; i < args.size(); i++){
                    if(i > 0) b.append(" ");
                    b.append(strOf(args.get(i)));
                }
                out.accept(b.toString());
                return null;
            case "len": {
                Object v = arg(args, 0, "len");
                if(v instanceof String) return (double)((String)v).length();
                if(v instanceof List) return (double)((List<?>)v).size();
                if(v instanceof Map) return (double)((Map<?,?>)v).size();
                throw new ScriptError(line, "len() of " + typeName(v));
            }
            case "abs": return Math.abs(asNum(arg(args, 0, "abs"), line));
            case "min": {
                double m = asNum(arg(args, 0, "min"), line);
                for(int i = 1; i < args.size(); i++) m = Math.min(m, asNum(args.get(i), line));
                return m;
            }
            case "max": {
                double m = asNum(arg(args, 0, "max"), line);
                for(int i = 1; i < args.size(); i++) m = Math.max(m, asNum(args.get(i), line));
                return m;
            }
            case "floor": return Math.floor(asNum(arg(args, 0, "floor"), line));
            case "ceil": return Math.ceil(asNum(arg(args, 0, "ceil"), line));
            case "round": return Math.round(asNum(arg(args, 0, "round"), line));
            case "sqrt": {
                double v = asNum(arg(args, 0, "sqrt"), line);
                if(v < 0) throw new ScriptError(line, "sqrt() of a negative number");
                return Math.sqrt(v);
            }
            case "sin": return Math.sin(asNum(arg(args, 0, "sin"), line));
            case "cos": return Math.cos(asNum(arg(args, 0, "cos"), line));
            case "tan": return Math.tan(asNum(arg(args, 0, "tan"), line));
            case "pow": return Math.pow(asNum(arg(args, 0, "pow"), line), asNum(arg(args, 1, "pow"), line));
            case "str": return strOf(arg(args, 0, "str"));
            case "num": {
                Object v = arg(args, 0, "num");
                if(v instanceof Double) return v;
                if(v instanceof Boolean) return (Boolean)v ? 1.0 : 0.0;
                try { return Double.parseDouble(strOf(v).trim()); }
                catch(NumberFormatException e){ throw new ScriptError(line, "cannot convert '" + v + "' to number"); }
            }
            case "int": return Math.floor(asNum(arg(args, 0, "int"), line));
            case "upper": return strOf(arg(args, 0, "upper")).toUpperCase();
            case "lower": return strOf(arg(args, 0, "lower")).toLowerCase();
            case "rand": {
                if(args.isEmpty()) return rng.nextDouble();
                if(args.size() == 1){
                    int n = (int)asNum(args.get(0), line);
                    if(n <= 0) throw new ScriptError(line, "rand(n) needs n > 0");
                    return (double)rng.nextInt(n);
                }
                double lo = asNum(args.get(0), line), hi = asNum(args.get(1), line);
                return rng.range((float)lo, (float)hi);
            }
            case "srand": {
                seed = (long)asNum(arg(args, 0, "srand"), line);
                rng = new MathX.Rng(seed);
                return null;
            }
            case "range": {
                int lo = 0, hi, step = 1;
                if(args.size() == 1) hi = (int)asNum(arg(args, 0, "range"), line);
                else {
                    lo = (int)asNum(arg(args, 0, "range"), line);
                    hi = (int)asNum(arg(args, 1, "range"), line);
                    if(args.size() == 3) step = (int)asNum(arg(args, 2, "range"), line);
                }
                if(step == 0) throw new ScriptError(line, "range step cannot be 0");
                List<Object> items = new ArrayList<>();
                if(step > 0) for(double i = lo; i < hi; i += step) items.add(i);
                else for(double i = lo; i > hi; i += step) items.add(i);
                return new RangeVal(items);
            }
            case "append": {
                Object v = arg(args, 0, "append");
                if(!(v instanceof List)) throw new ScriptError(line, "append() needs a list");
                asObjectList((List<?>)v).add(args.size() > 1 ? args.get(1) : null);
                return null;
            }
            case "pop": {
                Object v = arg(args, 0, "pop");
                if(!(v instanceof List)) throw new ScriptError(line, "pop() needs a list");
                List<Object> l = asObjectList((List<?>)v);
                if(l.isEmpty()) throw new ScriptError(line, "pop() of an empty list");
                return l.remove(l.size() - 1);
            }
            case "keys": {
                Object v = arg(args, 0, "keys");
                if(!(v instanceof Map)) throw new ScriptError(line, "keys() needs a map");
                return new ArrayList<Object>(((Map<?,?>)v).keySet());
            }
            case "has": {
                Object v = arg(args, 0, "has");
                Object k = args.size() > 1 ? args.get(1) : null;
                if(v instanceof Map) return ((Map<?,?>)v).containsKey(String.valueOf(k));
                if(v instanceof List) return ((List<?>)v).contains(k);
                throw new ScriptError(line, "has() needs a map or list");
            }
            case "typeof": return typeName(arg(args, 0, "typeof"));
            default:
                FuncDef f = funcs.get(name);
                if(f != null) return callFunc(name, args.toArray());
                throw new ScriptError(line, "unknown function '" + name + "'");
        }
    }

    private Object arg(List<Object> args, int i, String fn){
        if(i >= args.size())
            throw new ScriptError(0, fn + "() missing argument " + (i + 1));
        return args.get(i);
    }
}
