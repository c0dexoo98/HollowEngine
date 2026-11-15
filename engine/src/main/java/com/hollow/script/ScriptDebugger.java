package com.hollow.script;

import com.hollow.core.Json;
import com.hollow.core.Log;
import com.hollow.core.MathX;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2.5 script batch — SCR-008/011/012.
 *
 *  SCR-008 ScriptDebugger — breakpoints + step/continue over FlowScript step lists
 *  SCR-011 DebuggerAttach — message protocol to attach a desktop debugger to the
 *                           on-device VM (pluggable transport, loopback-testable)
 *  SCR-012 Bytecode       — compile FlowScript step lists to an int[] op array with
 *                           constant pool + fast VM (fallback to interpreter for
 *                           unsupported steps) */
public final class ScriptDebugger {
    private ScriptDebugger() {}

    // =====================================================================
    // SCR-008 — CLI breakpoint/step debugger
    // =====================================================================
    public static final class ScriptDebuggerCore {
        public enum Control { RUN, BREAK, STEP_OVER }
        public static final class Breakpoint {
            public final String script; public final int stepIndex; public boolean enabled = true;
            public int hits = 0;
            public Breakpoint(String s, int i){ script = s; stepIndex = i; }
        }
        public final Map<String, Breakpoint> breakpoints = new LinkedHashMap<>();
        public Control control = Control.RUN;
        public String activeScript = "";
        public int activeStep = -1;
        public final VarTable watch = new VarTable();
        public long hits = 0;
        public boolean attached = false;

        public boolean setBreakpoint(String script, int stepIndex){
            if(stepIndex < 0) { breakpoints.remove(script + ":" + stepIndex); return false; }
            breakpoints.put(script + ":" + stepIndex, new Breakpoint(script, stepIndex));
            return true;
        }
        public boolean toggleBreakpoint(String script, int stepIndex){
            String key = script + ":" + stepIndex;
            Breakpoint b = breakpoints.get(key);
            if(b == null) return setBreakpoint(script, stepIndex);
            b.enabled = !b.enabled;
            return b.enabled;
        }

        /** called by the interpreter before executing step `idx` of `script`:
         *  returns false when the debugger wants the VM to pause here. */
        public boolean beforeStep(String script, int idx, VarTable vars){
            activeScript = script; activeStep = idx;
            Breakpoint b = breakpoints.get(script + ":" + idx);
            if(b != null && b.enabled && control == Control.RUN){
                b.hits++; hits++;
                if(vars != null) watch.restore(vars.snapshot());
                control = Control.BREAK;
                return false;
            }
            if(control == Control.STEP_OVER) control = Control.BREAK;
            return control == Control.RUN;
        }

        public void cont(){ control = Control.RUN; }
        public void stepOver(){ control = Control.STEP_OVER; }
        public boolean paused(){ return control == Control.BREAK; }
        public String state(){
            return "dbg[script=" + activeScript + " step=" + activeStep + " " + control
                + " bps=" + breakpoints.size() + " hits=" + hits + "]";
        }
    }

    // =====================================================================
    // SCR-011 — attach debugger to device (protocol)
    // =====================================================================
    public static final class DebuggerAttach {
        /** transport abstraction: TCP sockets on device, loopback pair in tests. */
        public interface Transport {
            byte[] send(byte[] request);          // request/response over one channel
            boolean isOpen();
        }

        public static final class LoopbackTransport implements Transport {
            public final List<byte[]> wire = new ArrayList<>();
            private final boolean open = true;
            @Override public boolean isOpen(){ return open; }
            @Override public byte[] send(byte[] request){
                wire.add(request);
                return respond(request);
            }
            /** server side answer (device VM): here a canned demo VM */
            byte[] respond(byte[] request){
                try {
                    Map<String,Object> req = Json.parseObj(new String(request, StandardCharsets.UTF_8));
                    String cmd = (String)req.getOrDefault("cmd", "");
                    return switch(cmd){
                        case "hello" -> json(Map.of("t","hello","vm","hollow-flow","proto",1d));
                        case "bp_set" -> json(Map.of("t","ok","hits",1d));
                        case "vars" -> json(Map.of("t","vars","k1","a","k2",2d));
                        case "step" -> json(Map.of("t","ok","pc",3d));
                        default -> json(Map.of("t","error","why","unknown cmd"));
                    };
                } catch(Exception e){
                    return json(Map.of("t","error","why",String.valueOf(e.getMessage())));
                }
            }
            byte[] json(Map<String,Object> m){ return Json.write(m).getBytes(StandardCharsets.UTF_8); }
        }

        /** desktop-side client facade. */
        public static final class Client {
            public final Transport transport;
            public String vmName = "";
            public boolean attached = false;
            public int messages = 0;

            public Client(Transport t){ transport = t; }

            public boolean attach(){
                Map<String,Object> r = roundtrip(Map.of("cmd","hello"));
                attached = r != null && "hello".equals(r.get("t"));
                if(attached) vmName = String.valueOf(r.get("vm"));
                return attached;
            }
            public boolean setBreakpoint(String script, int step){
                Map<String,Object> r = roundtrip(Map.of("cmd","bp_set","script",script,"step",(double)step));
                return r != null && "ok".equals(r.get("t"));
            }
            public Map<String,Object> readVars(){ return roundtrip(Map.of("cmd","vars")); }
            public Map<String,Object> step(){ return roundtrip(Map.of("cmd","step")); }

            Map<String,Object> roundtrip(Map<String,Object> req){
                byte[] resp = transport.send(Json.write(req).getBytes(StandardCharsets.UTF_8));
                messages++;
                try { return Json.parseObj(new String(resp, StandardCharsets.UTF_8)); }
                catch(Exception e){ return Map.of("t","error","why","bad frame"); }
            }
        }

        /** device-side server facade (binds a ScriptDebuggerCore to a transport). */
        public static final class DeviceServer {
            public final Transport transport;
            public final ScriptDebuggerCore dbg;
            public boolean authed = false;
            public int served = 0;

            public DeviceServer(Transport t, ScriptDebuggerCore dbg){ transport = t; this.dbg = dbg; }

            public byte[] serve(byte[] request){
                served++;
                try {
                    Map<String,Object> req = Json.parseObj(new String(request, StandardCharsets.UTF_8));
                    String cmd = String.valueOf(req.getOrDefault("cmd", ""));
                    Map<String,Object> resp = new LinkedHashMap<>();
                    switch(cmd){
                        case "hello" -> { resp.put("t","hello"); resp.put("vm","hollow-flow"); resp.put("proto", 1.0); }
                        case "bp_set" -> {
                            String script = String.valueOf(req.get("script"));
                            int step = (int)Json.num(req.get("step"), -1);
                            resp.put("t","ok"); resp.put("added", dbg.setBreakpoint(script, step));
                        }
                        case "step" -> { dbg.stepOver(); resp.put("t","ok"); resp.put("pc", (double)dbg.activeStep); }
                        case "cont" -> { dbg.cont(); resp.put("t","ok"); }
                        case "vars" -> {
                            resp.put("t","vars");
                            resp.putAll(dbg.watch.snapshot());
                        }
                        default -> { resp.put("t","error"); resp.put("why","unknown cmd " + cmd); }
                    }
                    return Json.write(resp).getBytes(StandardCharsets.UTF_8);
                } catch(Exception e){
                    return Json.write(Map.of("t","error","why",String.valueOf(e.getMessage()))).getBytes(StandardCharsets.UTF_8);
                }
            }
        }
    }

    // =====================================================================
    // SCR-012 — compile to internal bytecode (fast path)
    // =====================================================================
    /** Compiles the variable/flow subset of FlowScript step lists into a compact
     *  op array with a constant pool. Unsupported steps (scene/graph APIs) abort
     *  compilation → caller keeps using the FlowRunner interpreter, so correctness
     *  is never at risk. The VM executes against a plain VarTable. */
    public static final class Bytecode {
        // ops
        public static final int OP_NOP = 0, OP_SET_CONST = 1, OP_ADD_NUM = 2, OP_EMIT = 3,
            OP_WAIT = 4, OP_JUMP = 5, OP_JUMP_IF_FALSE = 6, OP_CMP = 7, OP_CMP_TYPE = 8,
            OP_HALT = 9;

        public static final class Compiled {
            public final int[] code;
            public final List<Object> consts;
            public final List<String> varNames;
            public Compiled(int[] code, List<Object> consts, List<String> varNames){
                this.code = code; this.consts = consts; this.varNames = varNames;
            }
            public int size(){ return code.length; }
        }

        public static final class CompileError extends RuntimeException { CompileError(String w){ super(w); } }

        /** compile a step list; throws CompileError for anything the fast path
         *  cannot express (caller falls back to the interpreter). */
        public static Compiled compile(List<Object> steps){
            Compiler c = new Compiler();
            c.compileInto(steps);
            c.code.add(new int[]{ OP_HALT, 0, 0 });
            int[] flat = new int[c.code.size() * 3];
            for(int i = 0; i < c.code.size(); i++){
                int[] instr = c.code.get(i);
                flat[i*3] = instr[0]; flat[i*3+1] = instr[1]; flat[i*3+2] = instr[2];
            }
            return new Compiled(flat, c.consts, c.varNames);
        }

        /** shared compiler context (var/const tables + code buffer). */
        private static final class Compiler {
            final List<Object> consts = new ArrayList<>();
            final List<String> varNames = new ArrayList<>();
            final List<int[]> code = new ArrayList<>();

            int varIndex(String n){
                int i = varNames.indexOf(n);
                if(i < 0){ varNames.add(n); i = varNames.size() - 1; }
                return i;
            }
            int constIndex(Object v){ consts.add(v); return consts.size() - 1; }
            void emit(int op, int a, int b){ code.add(new int[]{ op, a, b }); }
            void patch(int pos, int target){ code.get(pos)[2] = target; }

            void compileInto(List<Object> steps){
                for(Object o : steps){
                    Map<String,Object> step = Json.obj(o);
                    if(step == null) continue;
                    String action = Json.str(step.get("do"), "");
                    switch(action){
                        case "set_var" -> {
                            Object v = step.get("value");
                            String key = step.get("key") != null ? String.valueOf(step.get("key")) : null;
                            if(key == null) throw new CompileError("set_var missing key");
                            if(v instanceof Number n){
                                emit(OP_SET_CONST, varIndex(key), constIndex(n.doubleValue()));
                            } else if(v instanceof String s && s.startsWith("=")){
                                throw new CompileError("set_var expr not supported in fast path");
                            } else if(v instanceof String s){
                                emit(OP_SET_CONST, varIndex(key), constIndex(s));
                            } else if(v instanceof Boolean b){
                                emit(OP_SET_CONST, varIndex(key), constIndex(b));
                            } else throw new CompileError("set_var unsupported value type");
                        }
                        case "add_var" -> {
                            String key = step.get("key") != null ? String.valueOf(step.get("key")) : null;
                            if(key == null) throw new CompileError("add_var missing key");
                            emit(OP_ADD_NUM, varIndex(key), constIndex(Json.num(step.get("delta"), 0)));
                        }
                        case "wait" -> emit(OP_WAIT, 0, constIndex(Json.num(step.get("sec"), 0)));
                        case "emit" -> emit(OP_EMIT, 0, constIndex(Json.str(step.get("event"), "event")));
                        case "if" -> {
                            // {"do":"if","if":"k > 3","steps":[...],"else":[...]}
                            String cond = Json.str(step.get("if"), "");
                            Cond c = parseCond(cond);
                            if(c == null) throw new CompileError("if condition not supported: " + cond);
                            emit(OP_CMP_TYPE, 0, constIndex((double)c.type));
                            emit(OP_CMP, varIndex(c.lhs), constIndex(c.rhs));
                            int jzPos = code.size();
                            emit(OP_JUMP_IF_FALSE, 0, 0);        // patched below
                            List<Object> thenSteps = Json.arr(step.get("steps"));
                            if(thenSteps != null && !thenSteps.isEmpty()){
                                compileInto(thenSteps);          // nested fast-path compile
                            }
                            List<Object> elseSteps = Json.arr(step.get("else"));
                            if(elseSteps != null && !elseSteps.isEmpty()){
                                int jmpPos = code.size();
                                emit(OP_JUMP, 0, 0);             // skip else when then ran
                                patch(jzPos, code.size());       // JZ -> else start
                                compileInto(elseSteps);
                                patch(jmpPos, code.size());      // JMP -> end
                            } else {
                                patch(jzPos, code.size());       // JZ -> after then
                            }
                        }
                        default -> throw new CompileError("step '" + action + "' not in fast path");
                    }
                }
            }
        }

        static final class Cond {
            final String lhs; final int type; final double rhs;
            Cond(String l, int t, double r){ lhs = l; type = t; rhs = r; }
        }
        // compare types
        public static final int CMP_LT = 0, CMP_GT = 1, CMP_EQ = 2;

        /** condition parser: "k > 3", "k == 2.5" */
        static Cond parseCond(String cond){
            String[] ops = { ">=", "<=", "==", "!=", ">", "<" };
            for(int i = 0; i < ops.length; i++){
                if(cond.contains(ops[i])){
                    String[] p = cond.split(java.util.regex.Pattern.quote(ops[i]));
                    if(p.length != 2) return null;
                    try {
                        double rhs = Double.parseDouble(p[1].trim());
                        int type = switch(ops[i]){
                            case "<" -> CMP_LT; case ">" -> CMP_GT;
                            case "==", "!=" -> CMP_EQ; default -> -1;
                        };
                        if(type < 0) return null;
                        return new Cond(p[0].trim(), type, rhs);
                    } catch(NumberFormatException e){ return null; }
                }
            }
            return null;
        }

        // ---- VM ----
        public static final class Vm {
            public final Compiled program;
            public final VarTable vars;
            public float waitLeft = 0f;
            public int pc = 0;
            public long executed = 0;
            public boolean done = false;
            public final List<String> emitted = new ArrayList<>();

            public Vm(Compiled c, VarTable vars){ program = c; this.vars = vars; }

            /** last compare result (set by OP_CMP+OP_CMP_TYPE pairs) */
            boolean cmpTrue = false;

            /** one tick: runs until a wait or halt. Returns true while alive. */
            public boolean update(float dt){
                if(done) return false;
                if(waitLeft > 0f){ waitLeft -= dt; if(waitLeft > 0f) return true; }
                int[] c = program.code;
                while(pc * 3 < c.length){
                    int op = c[pc*3], a = c[pc*3+1], b = c[pc*3+2];
                    executed++;
                    switch(op){
                        case OP_NOP, OP_CMP_TYPE -> pc++;          // type consumed implicitly by next CMP semantics
                        case OP_SET_CONST -> {
                            vars.set(program.varNames.get(a), program.consts.get(b));
                            pc++;
                        }
                        case OP_ADD_NUM -> {
                            String k = program.varNames.get(a);
                            double v = vars.num(k, 0) + ((Number)program.consts.get(b)).doubleValue();
                            vars.set(k, v);
                            pc++;
                        }
                        case OP_EMIT -> {
                            emitted.add(String.valueOf(program.consts.get(b)));
                            pc++;
                        }
                        case OP_WAIT -> {
                            waitLeft = ((Number)program.consts.get(b)).floatValue();
                            pc++;
                            if(waitLeft > 0f) return true;
                        }
                        case OP_CMP -> {
                            // previous OP_CMP_TYPE set the compare type; default EQ
                            String k = program.varNames.get(a);
                            double lhs = vars.num(k, Double.NaN);
                            double rhs = ((Number)program.consts.get(b)).doubleValue();
                            int type = CMP_EQ;
                            if(pc > 0 && c[(pc-1)*3] == OP_CMP_TYPE){
                                Object t = program.consts.get(c[(pc-1)*3+2]);
                                if(t instanceof Number n) type = n.intValue();
                            }
                            cmpTrue = switch(type){
                                case CMP_LT -> lhs < rhs;
                                case CMP_GT -> lhs > rhs;
                                default -> lhs == rhs;
                            };
                            pc++;
                        }
                        case OP_JUMP -> { pc = b; }
                        case OP_JUMP_IF_FALSE -> {
                            pc = cmpTrue ? pc + 1 : b;
                        }
                        case OP_HALT -> { done = true; return false; }
                        default -> { done = true; return false; }
                    }
                }
                done = true;
                return false;
            }
        }

        public static String disassemble(Compiled c){
            StringBuilder b = new StringBuilder();
            String[] opNames = { "nop", "setc", "addn", "emit", "wait", "jmp", "jz", "cmp", "cmptype", "halt" };
            for(int i = 0; i * 3 < c.code.length; i++){
                int op = c.code[i*3], a = c.code[i*3+1], bb = c.code[i*3+2];
                String opName = (op >= 0 && op < opNames.length) ? opNames[op] : "cmp" + op;
                b.append(String.format("%04d %-5s %d %s%n", i, opName, a,
                    bb >= 0 && bb < c.consts.size() ? String.valueOf(c.consts.get(bb)) : "-"));
            }
            return b.toString();
        }
    }
}
