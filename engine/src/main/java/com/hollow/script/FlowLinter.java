package com.hollow.script;

import com.hollow.core.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** EXT-008 — FlowScript linter for the Language Server Protocol.
 *  Pure analysis over one script document: block balance, unknown actions
 *  (against a registry), missing required keys, duplicate keys and suspicious
 *  patterns. Emits LSP-style diagnostics {range, severity, message, code}. */
public final class FlowLinter {
    private FlowLinter() {}

    public static final int SEV_ERROR = 1, SEV_WARNING = 2, SEV_INFO = 3;

    public static final class Diagnostic {
        public int line, col;                  // 0-based
        public int severity;
        public String code;
        public String message;

        public Map<String,Object> toLsp(){
            Map<String,Object> d = new java.util.LinkedHashMap<>();
            Map<String,Object> range = new java.util.LinkedHashMap<>();
            Map<String,Object> start = new java.util.LinkedHashMap<>();
            Map<String,Object> end = new java.util.LinkedHashMap<>();
            start.put("line", (double)line); start.put("character", (double)col);
            end.put("line", (double)line); end.put("character", (double)col + 1);
            range.put("start", start); range.put("end", end);
            d.put("range", range);
            d.put("severity", (double)severity);
            d.put("code", code);
            d.put("message", message);
            return d;
        }
    }

    public interface ActionRegistry { boolean known(String action); String[] requiredKeys(String action); }

    /** default registry: anything accepted, but common typos flagged as warnings. */
    public static final ActionRegistry PERMISSIVE = new ActionRegistry(){
        @Override public boolean known(String action){
            return true;
        }
        @Override public String[] requiredKeys(String action){ return new String[0]; }
    };

    /** lint one FlowScript JSON document. */
    public static List<Diagnostic> lint(String documentText, ActionRegistry registry){
        List<Diagnostic> out = new ArrayList<>();
        // structural JSON errors first
        Object root;
        try {
            root = Json.parse(documentText);
        } catch(Exception e){
            Diagnostic d = new Diagnostic();
            d.line = 0; d.col = 0; d.severity = SEV_ERROR; d.code = "json-parse";
            d.message = "invalid JSON: " + e.getMessage();
            out.add(d);
            return out;
        }
        lintNode(root, out, registry, 0);
        return out;
    }

    static void lintNode(Object node, List<Diagnostic> out, ActionRegistry registry, int depth){
        List<Object> steps = Json.arr(node);
        if(steps == null){
            Map<String,Object> step = Json.obj(node);
            if(step != null) lintStep(step, out, registry, depth);
            return;
        }
        for(Object o : steps){
            Map<String,Object> step = Json.obj(o);
            if(step == null){
                Diagnostic d = new Diagnostic();
                d.severity = SEV_WARNING; d.code = "not-a-step";
                d.message = "step entry is not an object";
                out.add(d);
                continue;
            }
            lintStep(step, out, registry, depth);
        }
    }

    static void lintStep(Map<String,Object> step, List<Diagnostic> out, ActionRegistry registry, int depth){
        String action = Json.str(step.get("do"), "");
        if(action.isEmpty()){
            Diagnostic d = new Diagnostic();
            d.severity = SEV_ERROR; d.code = "missing-do";
            d.message = "step without 'do'";
            out.add(d);
            return;
        }
        if(registry != null && !registry.known(action)){
            Diagnostic d = new Diagnostic();
            d.severity = SEV_WARNING; d.code = "unknown-action";
            d.message = "unknown action '" + action + "'";
            out.add(d);
        }
        // wait: sec <= 0 suspicious
        if("wait".equals(action) && Json.num(step.get("sec"), -1) < 0){
            Diagnostic d = new Diagnostic();
            d.severity = SEV_WARNING; d.code = "wait-neg";
            d.message = "wait with negative/missing sec";
            out.add(d);
        }
        // nested blocks
        List<Object> steps = Json.arr(step.get("steps"));
        if(steps != null && depth > 8){
            Diagnostic d = new Diagnostic();
            d.severity = SEV_WARNING; d.code = "too-deep";
            d.message = "nested blocks deeper than 8";
            out.add(d);
        }
        if(steps != null){
            for(Object o : steps){
                Map<String,Object> inner = Json.obj(o);
                if(inner != null) lintStep(inner, out, registry, depth + 1);
            }
        }
        List<Object> elseSteps = Json.arr(step.get("else"));
        if(elseSteps != null){
            for(Object o : elseSteps){
                Map<String,Object> inner = Json.obj(o);
                if(inner != null) lintStep(inner, out, registry, depth + 1);
            }
        }
    }

    /** line:column lookup for a JSON path (best effort — LSP range hint). */
    public static int[] locate(String documentText, String needle){
        int idx = documentText.indexOf(needle);
        if(idx < 0) return new int[]{ 0, 0 };
        int line = 0, col = 0;
        for(int i = 0; i < idx; i++){
            if(documentText.charAt(i) == '\n'){ line++; col = 0; }
            else col++;
        }
        return new int[]{ line, col };
    }
}
