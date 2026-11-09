package com.hollow.script;

import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.MathX;
import com.hollow.core.Vec3;
import com.hollow.scene.Entity;

import java.util.List;
import java.util.Map;

/** FlowScript condition mini-language (recursive-descent parser, zero deps, deterministic).
 *
 *  Grammar:
 *    expr   := or
 *    or     := and ( '||' and )*
 *    and    := not ( '&&' not )*
 *    not    := '!' not | cmp
 *    cmp    := atom ( ('=='|'!='|'>='|'<='|'>'|'<') atom )?
 *    atom   := number | string | true | false | ident | func '(' args ')' | '(' expr ')' | '-' atom
 *
 *  Values: Double / String / Boolean / null (unknown var).
 *  Functions:
 *    has_item(key)         → save inventory contains key
 *    dist_to(tag)          → min distance (m) from self to active entities with tag (1e9 if none)
 *    dist_to_entity(name)  → distance from self to named entity (1e9 if absent)
 *    chance(p)             → deterministic rng < p  (seeded from svc.frameSeed ^ self.id)
 *    count(tag)            → number of active entities with tag
 *    var_set(key)          → true if the global var exists (alias: var_exists)
 */
public final class Expr {
    private Expr() {}

    /** frame-stable call counter: bumped per chance() call, reset by FlowRunner each tick */
    public static int rngCalls = 0;

    public static boolean evalBool(String expr, Entity self){
        Object v = eval(expr, self);
        return truthy(v);
    }

    /** evaluate; any parse/runtime error → Log.w + FALSE (scripts never crash the loop) */
    public static Object eval(String expr, Entity self){
        if(expr == null) return Boolean.FALSE;
        try {
            return new P(expr, self).parse();
        } catch(Exception e){
            Log.w("Script", "condition '" + expr + "' failed: " + e.getMessage());
            return Boolean.FALSE;
        }
    }

    public static boolean truthy(Object v){
        if(v instanceof Boolean b) return b;
        if(v instanceof Number n) return n.doubleValue() != 0;
        if(v instanceof String s) return !s.isEmpty() && !s.equalsIgnoreCase("false");
        return false;
    }

    // ---------------- parser ----------------

    private static final class P {
        final String s;
        final Entity self;
        int i = 0;

        P(String s, Entity self){ this.s = s; this.self = self; }

        Object parse(){
            Object v = or();
            ws();
            if(i < s.length()) throw new IllegalStateException("trailing input at " + i);
            return v;
        }

        void ws(){ while(i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek(){ return i < s.length() ? s.charAt(i) : '\0'; }
        boolean eat(String tok){
            ws();
            if(s.startsWith(tok, i)){
                // avoid eating '&&' when checking '&', and '=' vs '=='
                i += tok.length();
                return true;
            }
            return false;
        }

        Object or(){
            Object l = and();
            while(true){
                ws();
                if(s.startsWith("||", i)){ i += 2; Object r = and(); l = truthy(l) || truthy(r) ? Boolean.TRUE : Boolean.FALSE; }
                else return l;
            }
        }
        Object and(){
            Object l = not();
            while(true){
                ws();
                if(s.startsWith("&&", i)){ i += 2; Object r = not(); l = truthy(l) && truthy(r) ? Boolean.TRUE : Boolean.FALSE; }
                else return l;
            }
        }
        Object not(){
            ws();
            if(peek() == '!'){ i++; return truthy(not()) ? Boolean.FALSE : Boolean.TRUE; }
            return cmp();
        }
        Object cmp(){
            Object l = atom();
            ws();
            String op = null;
            for(String o : new String[]{"==", "!=", ">=", "<=", ">", "<"}){
                if(s.startsWith(o, i)){ op = o; i += o.length(); break; }
            }
            if(op == null) return l;
            Object r = atom();
            return compare(l, op, r);
        }
        Object atom(){
            ws();
            char c = peek();
            if(c == '('){ i++; Object v = or(); ws(); if(peek() != ')') throw new IllegalStateException("expected ')'"); i++; return v; }
            if(c == '-'){ i++; Object v = atom(); return -(v instanceof Number n ? n.doubleValue() : Double.NaN); }
            if(c == '"' || c == '\''){
                StringBuilder b = new StringBuilder();
                char quote = c; i++;
                while(i < s.length() && s.charAt(i) != quote){
                    char ch = s.charAt(i++);
                    if(ch == '\\' && i < s.length()){ ch = s.charAt(i++); if(ch == 'n') ch = '\n'; }
                    b.append(ch);
                }
                if(i >= s.length()) throw new IllegalStateException("unterminated string");
                i++;
                return b.toString();
            }
            if(c == '.' || (c >= '0' && c <= '9')) return number();
            if(Character.isJavaIdentifierStart(c)) return ident();
            throw new IllegalStateException("unexpected char '" + c + "' at " + i);
        }

        Double number(){
            int st = i;
            while(i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            return Double.parseDouble(s.substring(st, i));
        }

        Object ident(){
            int st = i;
            while(i < s.length() && Character.isJavaIdentifierPart(s.charAt(i))) i++;
            String name = s.substring(st, i);
            ws();
            if(peek() == '(') return function(name);
            if(name.equals("true")) return Boolean.TRUE;
            if(name.equals("false")) return Boolean.FALSE;
            return lookup(name);
        }

        Object function(String name){
            i++; // '('
            List<Object> argsRaw = new java.util.ArrayList<>();
            ws();
            if(peek() != ')'){
                while(true){
                    argsRaw.add(atom());
                    ws();
                    if(peek() == ','){ i++; continue; }
                    break;
                }
            }
            if(peek() != ')') throw new IllegalStateException("expected ')' after args of " + name);
            i++;
            return call(name, argsRaw);
        }

        com.hollow.game.EngineServices svc(){
            return com.hollow.game.GameApp.instance != null ? com.hollow.game.GameApp.instance.services() : null;
        }
        com.hollow.scene.World world(){
            com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
            return app != null ? app.world : null;
        }

        Object lookup(String name){
            com.hollow.game.EngineServices s = svc();
            if(s != null && s.vars != null) return s.vars.get(name);
            return null;
        }

        Object call(String name, List<Object> args){
            com.hollow.game.EngineServices s = svc();
            String a0 = args.size() > 0 ? Json.str(args.get(0), null) : null;
            switch(name){
                case "has_item":
                    return s != null && s.save != null && a0 != null && s.save.inventory.contains(a0) ? Boolean.TRUE : Boolean.FALSE;
                case "dist_to": {
                    Vec3 from = self != null ? self.worldPosition() : (s != null && s.audio != null ? s.audio.listenerPos : null);
                    return minDist(world(), from, null, a0);
                }
                case "dist_to_entity":
                    return minDist(world(), self != null ? self.worldPosition() : null, a0, null);
                case "chance": {
                    float p = args.size() > 0 ? (float) Json.num(args.get(0), 0) : 0f;
                    long seed = (s != null ? s.frameSeed : 0x2545F4914F6CDD1DL)
                            ^ (self != null ? self.id * 0x9E3779B97F4A7C15L : 0x5DEECE66DL)
                            ^ (rngCalls++ * 0xBB67AE8584CAA73BL);
                    return new MathX.Rng(seed).nextFloat() < p ? Boolean.TRUE : Boolean.FALSE;
                }
                case "count": {
                    com.hollow.scene.World w = world();
                    if(w == null || a0 == null) return 0d;
                    int n = 0;
                    for(Entity e : w.byTag(a0)) if(e.active && !e.destroyed) n++;
                    return (double) n;
                }
                case "var_set":
                case "var_exists":
                    return s != null && s.vars != null && s.vars.has(a0) ? Boolean.TRUE : Boolean.FALSE;
                default:
                    throw new IllegalStateException("unknown function '" + name + "'");
            }
        }

        static double minDist(com.hollow.scene.World w, Vec3 from, String entityName, String tag){
            final double FAR = 1e9;
            if(w == null || from == null) return FAR;
            double best = FAR;
            for(Entity e : w.entities){
                if(e.destroyed || !e.active) continue;
                if(entityName != null ? !e.name.equals(entityName) : !e.tag.equals(tag)) continue;
                double d = e.worldPosition().dist(from);
                if(d < best) best = d;
            }
            return best;
        }
    }

    // ---------------- comparison semantics ----------------

    static Object compare(Object l, String op, Object r){
        Double ln = asNum(l), rn = asNum(r);
        if(ln != null && rn != null){
            switch(op){
                case "==": return ln.doubleValue() == rn.doubleValue();
                case "!=": return ln.doubleValue() != rn.doubleValue();
                case ">":  return ln.doubleValue() >  rn.doubleValue();
                case ">=": return ln.doubleValue() >= rn.doubleValue();
                case "<":  return ln.doubleValue() <  rn.doubleValue();
                case "<=": return ln.doubleValue() <= rn.doubleValue();
            }
        }
        if(op.equals("==")) return eq(l, r);
        if(op.equals("!=")) return !eq(l, r);
        Log.w("Script", "ordered comparison on non-numeric operand (" + l + " " + op + " " + r + ")");
        return Boolean.FALSE;
    }

    private static boolean eq(Object l, Object r){
        if(l == null && r == null) return true;
        if(l == null || r == null) return false;
        return String.valueOf(l).equals(String.valueOf(r));
    }

    static Double asNum(Object v){
        if(v instanceof Number n) return n.doubleValue();
        if(v instanceof Boolean b) return b ? 1d : 0d;
        if(v instanceof String str){
            try { return Double.parseDouble(str.trim()); } catch(Exception e){ return null; }
        }
        return null;
    }
}
