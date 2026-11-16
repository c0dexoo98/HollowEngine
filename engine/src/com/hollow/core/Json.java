package com.hollow.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HollowEngine 2 — minimal JSON reader/writer (no dependencies).
 *  Values map to: LinkedHashMap (object), ArrayList (array),
 *  String, Double, Boolean, null. Writer keeps insertion order.
 */
public final class Json {

    private Json(){ }

    // ------------------------- parse -------------------------

    public static Object parse(String text){
        P p = new P(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if(p.i < p.s.length()) throw new IllegalArgumentException("trailing JSON at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> parseObj(String text){
        Object v = parse(text);
        if(!(v instanceof Map)) throw new IllegalArgumentException("expected JSON object");
        return (Map<String,Object>)v;
    }

    public static String str(Object v, String def){
        return v instanceof String ? (String)v : def;
    }
    public static double num(Object v, double def){
        return v instanceof Number ? ((Number)v).doubleValue() : def;
    }
    public static boolean bool(Object v, boolean def){
        return v instanceof Boolean ? (Boolean)v : def;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> obj(Object v){
        return v instanceof Map ? (Map<String,Object>)v : null;
    }
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v){
        return v instanceof List ? (List<Object>)v : null;
    }

    private static final class P {
        final String s;
        int i = 0;
        P(String s){ this.s = s; }
        void ws(){
            while(i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
        Object value(){
            ws();
            if(i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            if(c == '{') return object();
            if(c == '[') return array();
            if(c == '"') return string();
            if(c == 't'){ expect("true"); return Boolean.TRUE; }
            if(c == 'f'){ expect("false"); return Boolean.FALSE; }
            if(c == 'n'){ expect("null"); return null; }
            return number();
        }
        void expect(String lit){
            if(!s.startsWith(lit, i)) throw new IllegalArgumentException("bad literal at " + i);
            i += lit.length();
        }
        Map<String,Object> object(){
            Map<String,Object> m = new LinkedHashMap<>();
            i++; ws();
            if(i < s.length() && s.charAt(i) == '}'){ i++; return m; }
            while(true){
                ws();
                if(i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("key expected at " + i);
                String k = string();
                ws();
                if(i >= s.length() || s.charAt(i) != ':') throw new IllegalArgumentException(": expected at " + i);
                i++;
                m.put(k, value());
                ws();
                if(i < s.length() && s.charAt(i) == ','){ i++; continue; }
                if(i < s.length() && s.charAt(i) == '}'){ i++; return m; }
                throw new IllegalArgumentException("bad object at " + i);
            }
        }
        List<Object> array(){
            List<Object> l = new ArrayList<>();
            i++; ws();
            if(i < s.length() && s.charAt(i) == ']'){ i++; return l; }
            while(true){
                l.add(value());
                ws();
                if(i < s.length() && s.charAt(i) == ','){ i++; continue; }
                if(i < s.length() && s.charAt(i) == ']'){ i++; return l; }
                throw new IllegalArgumentException("bad array at " + i);
            }
        }
        String string(){
            i++; // opening quote
            StringBuilder b = new StringBuilder();
            while(i < s.length()){
                char c = s.charAt(i++);
                if(c == '"') return b.toString();
                if(c == '\\'){
                    if(i >= s.length()) break;
                    char e = s.charAt(i++);
                    if(e == '"') b.append('"');
                    else if(e == '\\') b.append('\\');
                    else if(e == '/') b.append('/');
                    else if(e == 'n') b.append('\n');
                    else if(e == 't') b.append('\t');
                    else if(e == 'r') b.append('\r');
                    else if(e == 'b') b.append('\b');
                    else if(e == 'f') b.append('\f');
                    else if(e == 'u'){
                        if(i + 4 > s.length()) throw new IllegalArgumentException("bad \\u");
                        b.append((char)Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } else throw new IllegalArgumentException("bad escape \\" + e);
                } else b.append(c);
            }
            throw new IllegalArgumentException("unterminated string");
        }
        Object number(){
            int st = i;
            if(i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while(i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.'
                  || s.charAt(i) == 'e' || s.charAt(i) == 'E' || s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            try {
                return Double.parseDouble(s.substring(st, i));
            } catch(NumberFormatException e){
                throw new IllegalArgumentException("bad number at " + st);
            }
        }
    }

    // ------------------------- write -------------------------

    public static final class JsonW {
        private final StringBuilder b = new StringBuilder();
        private final java.util.ArrayDeque<Boolean> first = new java.util.ArrayDeque<>();

        public JsonW obj(){
            sep();
            b.append('{');
            first.push(true);
            return this;
        }
        /** named object: "key": { ... } */
        public JsonW obj(String key){
            sep();
            key(key);
            b.append('{');
            first.push(true);
            return this;
        }
        public JsonW arr(String key){
            sep();
            key(key);
            b.append('[');
            first.push(true);
            return this;
        }
        public JsonW arr(){
            sep();
            b.append('[');
            first.push(true);
            return this;
        }
        public JsonW kv(String key, Object v){
            sep();
            key(key);
            writeVal(v);
            return this;
        }
        /** append a bare element inside an array */
        public JsonW item(Object v){
            sep();
            writeVal(v);
            return this;
        }
        public JsonW endObj(){
            if(Boolean.TRUE.equals(first.peek())) b.append(' ');
            first.pop();
            b.append('}');
            return this;
        }
        public JsonW endArr(){
            if(Boolean.TRUE.equals(first.peek())) b.append(' ');
            first.pop();
            b.append(']');
            return this;
        }
        public String toString(){ return b.toString(); }

        private void sep(){
            if(first.isEmpty()) return;
            boolean wasFirst = first.pop();
            first.push(false);
            if(!wasFirst) b.append(", ");
        }
        private void key(String key){
            writeStr(key);
            b.append(": ");
        }
        private void writeVal(Object v){
            if(v == null) b.append("null");
            else if(v instanceof String) writeStr((String)v);
            else if(v instanceof Boolean) b.append(v);
            else if(v instanceof Double){
                double d = (Double)v;
                if(d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) b.append((long)d);
                else b.append(d);
            }
            else if(v instanceof Number) b.append(v);
            else if(v instanceof Map){
                obj();
                for(Map.Entry<String,Object> kv : ((Map<String,Object>)v).entrySet()) kv(kv.getKey(), kv.getValue());
                endObj();
            }
            else if(v instanceof List){
                arr();
                for(Object o : (List<?>)v) writeVal(o);
                endArr();
            }
            else writeStr(String.valueOf(v));
        }
        private void writeStr(String s){
            b.append('"');
            for(int k = 0; k < s.length(); k++){
                char c = s.charAt(k);
                if(c == '"') b.append("\\\"");
                else if(c == '\\') b.append("\\\\");
                else if(c == '\n') b.append("\\n");
                else if(c == '\t') b.append("\\t");
                else if(c == '\r') b.append("\\r");
                else if(c < 0x20) b.append(String.format("\\u%04x", (int)c));
                else b.append(c);
            }
            b.append('"');
        }
    }
}
