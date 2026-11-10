package com.hollow.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal, dependency-free JSON parser/writer.
 *  Representation: Map&lt;String,Object&gt;, List&lt;Object&gt;, String, Double, Boolean, null. */
public final class Json {
    private Json() {}

    // ---------------- parse ----------------
    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.n) throw p.err("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> parseObj(String s){ return (Map<String,Object>) parse(s); }

    private static final class P {
        final String s; final int n; int i;
        P(String s){ this.s=s; this.n=s.length(); }
        Err err(String m){ return new Err(m + " at " + i + " near \"" + s.substring(Math.max(0,Math.min(i,n-1)), Math.min(n, i+20)) + "\""); }
        void ws(){ while(i<n){ char c=s.charAt(i); if(c==' '||c=='\t'||c=='\n'||c=='\r') i++; else break; } }
        char peek(){ if(i>=n) throw err("unexpected end"); return s.charAt(i); }
        Object value(){
            ws();
            char c = peek();
            switch(c){
                case '{': return obj();
                case '[': return arr();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }
        void expect(String w){
            if(!s.startsWith(w, i)) throw err("invalid token");
            i += w.length();
        }
        Map<String,Object> obj(){
            Map<String,Object> m = new LinkedHashMap<>();
            i++; ws();
            if(peek()=='}'){ i++; return m; }
            while(true){
                ws();
                if(peek()!='"') throw err("expected key string");
                String k = string(); ws();
                if(peek()!=':') throw err("expected ':'");
                i++; ws(); // value() skips ws anyway
                m.put(k, value());
                ws();
                char c = peek();
                if(c==','){ i++; continue; }
                if(c=='}'){ i++; return m; }
                throw err("expected ',' or '}'");
            }
        }
        List<Object> arr(){
            List<Object> l = new ArrayList<>();
            i++; ws();
            if(peek()==']'){ i++; return l; }
            while(true){
                l.add(value()); ws();
                char c = peek();
                if(c==','){ i++; continue; }
                if(c==']'){ i++; return l; }
                throw err("expected ',' or ']'");
            }
        }
        String string(){
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while(true){
                if(i>=n) throw err("unterminated string");
                char c = s.charAt(i++);
                if(c=='"') return b.toString();
                if(c=='\\'){
                    if(i>=n) throw err("bad escape");
                    char e = s.charAt(i++);
                    switch(e){
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if(i+4>n) throw err("bad unicode escape");
                            b.append((char) Integer.parseInt(s.substring(i, i+4), 16)); i+=4; break;
                        default: throw err("bad escape '\\"+e+"'");
                    }
                } else b.append(c);
            }
        }
        Object number(){
            int st = i;
            if(peek()=='-') i++;
            while(i<n){ char c=s.charAt(i); if((c>='0'&&c<='9')||c=='+'||c=='-'||c=='.'||c=='e'||c=='E') i++; else break; }
            String t = s.substring(st,i);
            if(t.isEmpty()||t.equals("-")) throw err("invalid number");
            try { return Double.parseDouble(t); } catch(Exception e){ throw err("invalid number '"+t+"'"); }
        }
    }

    static final class Err extends RuntimeException { Err(String m){ super(m); } }

    // ---------------- write ----------------
    public static String write(Object v){ StringBuilder b=new StringBuilder(); wv(v,b); return b.toString(); }
    public static String pretty(Object v){ StringBuilder b=new StringBuilder(); wp(v,b,0); return b.toString(); }

    private static void wv(Object v, StringBuilder b){
        if(v==null){ b.append("null"); return; }
        if(v instanceof String s){ str(s,b); return; }
        if(v instanceof Boolean bo){ b.append(bo); return; }
        if(v instanceof Double d){
            if(d.isNaN()||d.isInfinite()) b.append("null");
            else if(d==Math.floor(d) && Math.abs(d)<1e15 && !d.toString().contains("E")) b.append((long)(double)d);
            else b.append(d);
            return;
        }
        if(v instanceof Number num){ b.append(wvNum(num)); return; }
        if(v instanceof Map<?,?> m){
            b.append('{'); boolean f=true;
            for(Map.Entry<?,?> e : m.entrySet()){
                if(!f) b.append(',');
                f=false; str(String.valueOf(e.getKey()), b); b.append(':'); wv(e.getValue(), b);
            }
            b.append('}'); return;
        }
        if(v instanceof List<?> l){
            b.append('['); boolean f=true;
            for(Object o : l){ if(!f) b.append(','); f=false; wv(o,b); }
            b.append(']'); return;
        }
        str(v.toString(), b);
    }
    private static String wvNum(Number n){
        if(n instanceof Double d) return numRaw(d);
        if(n instanceof Float f) return numRaw(f);
        return n.toString();
    }
    private static void wp(Object v, StringBuilder b, int ind){
        if(v instanceof Map<?,?> m){
            if(m.isEmpty()){ b.append("{}"); return; }
            b.append("{\n");
            int k=0, sz=m.size();
            for(Map.Entry<?,?> e : m.entrySet()){
                pad(b, ind+1); str(String.valueOf(e.getKey()), b); b.append(": ");
                wp(e.getValue(), b, ind+1);
                if(++k<sz) b.append(',');
                b.append('\n');
            }
            pad(b, ind); b.append('}'); return;
        }
        if(v instanceof List<?> l){
            if(l.isEmpty()){ b.append("[]"); return; }
            b.append("[\n");
            for(int k=0;k<l.size();k++){
                pad(b, ind+1); wp(l.get(k), b, ind+1);
                if(k<l.size()-1) b.append(',');
                b.append('\n');
            }
            pad(b, ind); b.append(']'); return;
        }
        wv(v, b);
    }
    private static void pad(StringBuilder b, int n){ for(int i=0;i<n;i++) b.append("  "); }
    private static void str(String s, StringBuilder b){
        b.append('"');
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            switch(c){
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if(c<0x20) b.append(String.format("\\u%04x",(int)c));
                    else b.append(c);
            }
        }
        b.append('"');
    }

    // ---------------- raw helpers (for streaming writers) ----------------
    public static void keyRaw(String key, StringBuilder b){ str(key, b); b.append(':'); }
    public static void valRaw(String v, StringBuilder b){ str(v, b); }
    public static String numRaw(double d){
        if(Double.isNaN(d)||Double.isInfinite(d)) return "null";
        if(d==Math.floor(d) && Math.abs(d)<1e15) return String.valueOf((long)d);
        return String.valueOf(d);
    }

    // ---------------- typed accessors ----------------
    public static double num(Object o, double def){ return (o instanceof Number n) ? n.doubleValue() : def; }
    public static int int32(Object o, int def){ return (o instanceof Number n) ? n.intValue() : def; }
    public static boolean bool(Object o, boolean def){ return (o instanceof Boolean b) ? b : def; }
    public static String str(Object o, String def){ return (o instanceof String s) ? s : def; }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> obj(Object o){ return (o instanceof Map) ? (Map<String,Object>)o : null; }
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o){ return (o instanceof List) ? (List<Object>)o : null; }
    /** [a, b] pair helper */
    public static Vec2 vec2range(Object o, Vec2 def){
        List<Object> a = arr(o);
        if(a==null || a.size()<2) return def;
        return new Vec2((float)num(a.get(0),0), (float)num(a.get(1),0));
    }
    public static Vec3 vec3(Object o, Vec3 def){
        List<Object> a = arr(o);
        if(a==null || a.size()<3) return def;
        return new Vec3((float)num(a.get(0),0), (float)num(a.get(1),0), (float)num(a.get(2),0));
    }
    public static Color color(Object o, Color def){
        if(o instanceof String s) return Color.parse(s, def);
        List<Object> a = arr(o);
        if(a==null) return def;
        if(a.size()>=4) return new Color((float)num(a.get(0),0),(float)num(a.get(1),0),(float)num(a.get(2),0),(float)num(a.get(3),1));
        if(a.size()>=3) return new Color((float)num(a.get(0),0),(float)num(a.get(1),0),(float)num(a.get(2),0),1);
        return def;
    }
}
