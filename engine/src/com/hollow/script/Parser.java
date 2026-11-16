package com.hollow.script;

import com.hollow.script.Lexer.Tok;
import com.hollow.script.Lexer.Token;

import java.util.ArrayList;
import java.util.List;

/** HollowScript — recursive-descent parser producing a typed AST.
 *  Precedence (low→high): or, and, not, comparison, + -, * / %,
 *  unary -, postfix (. [] call). Blocks use NEWLINE INDENT ... DEDENT
 *  or a single statement after ':'.
 */
public final class Parser {

    // ------------------------- AST -------------------------

    public static final class Program {
        public final List<Stmt> body = new ArrayList<>();
        public final List<FuncDef> funcs = new ArrayList<>();
        public final List<ClassDef> classes = new ArrayList<>();
    }

    /** class Name (extends Super)?: fields + methods, GDScript-style */
    public static final class ClassDef {
        public final String name;
        public final String superName;                  // null if none
        public final List<VarStmt> fields = new ArrayList<>();
        public final java.util.LinkedHashMap<String, FuncDef> methods = new java.util.LinkedHashMap<>();
        public final int line;
        public ClassDef(String name, String superName, int line){
            this.name = name; this.superName = superName; this.line = line;
        }
    }

    public static final class FuncDef {
        public final String name;
        public final List<String> params;
        public final List<Stmt> body;
        public final int line;
        public FuncDef(String name, List<String> params, List<Stmt> body, int line){
            this.name = name; this.params = params; this.body = body; this.line = line;
        }
    }

    public static abstract class Stmt {
        public final int line;
        Stmt(int line){ this.line = line; }
    }
    public static final class VarStmt extends Stmt {
        public final String name; public final Expr init;
        public VarStmt(String name, Expr init, int line){ super(line); this.name = name; this.init = init; }
    }
    public static final class AssignStmt extends Stmt {
        public final Expr target; public final String op; public final Expr value;
        public AssignStmt(Expr target, String op, Expr value, int line){ super(line); this.target = target; this.op = op; this.value = value; }
    }
    public static final class IfStmt extends Stmt {
        public final Expr cond; public final List<Stmt> then;
        public final List<Object[]> elifs;              // {cond, body}
        public final List<Stmt> els;
        public IfStmt(Expr cond, List<Stmt> then, List<Object[]> elifs, List<Stmt> els, int line){
            super(line); this.cond = cond; this.then = then; this.elifs = elifs; this.els = els;
        }
    }
    public static final class WhileStmt extends Stmt {
        public final Expr cond; public final List<Stmt> body;
        public WhileStmt(Expr cond, List<Stmt> body, int line){ super(line); this.cond = cond; this.body = body; }
    }
    public static final class ForStmt extends Stmt {
        public final String name; public final Expr iter; public final List<Stmt> body;
        public ForStmt(String name, Expr iter, List<Stmt> body, int line){ super(line); this.name = name; this.iter = iter; this.body = body; }
    }
    public static final class ReturnStmt extends Stmt {
        public final Expr value;
        public ReturnStmt(Expr value, int line){ super(line); this.value = value; }
    }
    public static final class BreakStmt extends Stmt {
        public BreakStmt(int line){ super(line); }
    }
    public static final class ContinueStmt extends Stmt {
        public ContinueStmt(int line){ super(line); }
    }
    public static final class PassStmt extends Stmt {
        public PassStmt(int line){ super(line); }
    }
    public static final class ExprStmt extends Stmt {
        public final Expr expr;
        public ExprStmt(Expr expr, int line){ super(line); this.expr = expr; }
    }

    public static abstract class Expr {
        public final int line;
        Expr(int line){ this.line = line; }
    }
    public static final class NumLit extends Expr {
        public final double v;
        public NumLit(double v, int line){ super(line); this.v = v; }
    }
    public static final class StrLit extends Expr {
        public final String v;
        public StrLit(String v, int line){ super(line); this.v = v; }
    }
    public static final class BoolLit extends Expr {
        public final boolean v;
        public BoolLit(boolean v, int line){ super(line); this.v = v; }
    }
    public static final class NullLit extends Expr {
        public NullLit(int line){ super(line); }
    }
    public static final class Ident extends Expr {
        public final String name;
        public Ident(String name, int line){ super(line); this.name = name; }
    }
    public static final class Bin extends Expr {
        public final String op; public final Expr l, r;
        public Bin(String op, Expr l, Expr r, int line){ super(line); this.op = op; this.l = l; this.r = r; }
    }
    public static final class Un extends Expr {
        public final String op; public final Expr e;
        public Un(String op, Expr e, int line){ super(line); this.op = op; this.e = e; }
    }
    public static final class Call extends Expr {
        public final String name; public final List<Expr> args;
        public Call(String name, List<Expr> args, int line){ super(line); this.name = name; this.args = args; }
    }
    public static final class MethodCall extends Expr {
        public final Expr obj; public final String name; public final List<Expr> args;
        public MethodCall(Expr obj, String name, List<Expr> args, int line){ super(line); this.obj = obj; this.name = name; this.args = args; }
    }
    public static final class Dot extends Expr {
        public final Expr obj; public final String name;
        public Dot(Expr obj, String name, int line){ super(line); this.obj = obj; this.name = name; }
    }
    public static final class Index extends Expr {
        public final Expr obj, idx;
        public Index(Expr obj, Expr idx, int line){ super(line); this.obj = obj; this.idx = idx; }
    }
    public static final class ListLit extends Expr {
        public final List<Expr> items;
        public ListLit(List<Expr> items, int line){ super(line); this.items = items; }
    }
    public static final class MapLit extends Expr {
        public final List<String> keys; public final List<Expr> vals;
        public MapLit(List<String> keys, List<Expr> vals, int line){ super(line); this.keys = keys; this.vals = vals; }
    }

    // ------------------------- parser -------------------------

    private final List<Token> toks;
    private int p = 0;

    public Parser(String src){
        this.toks = Lexer.lex(src);
    }

    public static Program parse(String src){
        return new Parser(src).program();
    }

    private Token peek(int k){ return toks.get(Math.min(p + k, toks.size() - 1)); }
    private Token cur(){ return toks.get(p); }
    private Token next(){ return toks.get(p++); }
    private boolean is(Tok t, String text){
        Token c = cur();
        return c.type == t && (text == null || c.text.equals(text));
    }
    private boolean eat(Tok t, String text){
        if(is(t, text)){ p++; return true; }
        return false;
    }
    private Token expect(Tok t, String text){
        if(!is(t, text)) throw err("expected " + (text == null ? t : "'" + text + "'") + ", got " + cur().text);
        return next();
    }
    private IllegalArgumentException err(String msg){
        return new IllegalArgumentException("HollowScript syntax error, line " + cur().line + ": " + msg);
    }
    private void endLine(){
        if(is(Tok.NEWLINE, null)) next();
        else if(!is(Tok.EOF, null) && !is(Tok.DEDENT, null)) throw err("expected end of line, got " + cur().text);
    }

    public Program program(){
        Program prog = new Program();
        while(!is(Tok.EOF, null)){
            if(eat(Tok.NEWLINE, null) || eat(Tok.DEDENT, null) || eat(Tok.INDENT, null)) continue;
            if(is(Tok.KW, "class")){
                prog.classes.add(classDef());
            } else if(is(Tok.KW, "func")){
                prog.funcs.add(funcDef());
            } else {
                prog.body.add(statement());
            }
        }
        return prog;
    }

    private ClassDef classDef(){
        int line = expect(Tok.KW, "class").line;
        String name = expect(Tok.IDENT, null).text;
        String sup = null;
        if(eat(Tok.KW, "extends")) sup = expect(Tok.IDENT, null).text;
        expect(Tok.OP, ":");
        ClassDef cls = new ClassDef(name, sup, line);
        expect(Tok.NEWLINE, null);
        expect(Tok.INDENT, null);
        while(!is(Tok.DEDENT, null) && !is(Tok.EOF, null)){
            if(eat(Tok.NEWLINE, null)) continue;
            if(is(Tok.KW, "func")){
                FuncDef m = funcDef();
                cls.methods.put(m.name, m);
            } else {
                Stmt s = statement();
                if(!(s instanceof VarStmt)) throw err("class body allows only 'var' and 'func'");
                cls.fields.add((VarStmt)s);
            }
        }
        expect(Tok.DEDENT, null);
        return cls;
    }

    private FuncDef funcDef(){
        int line = expect(Tok.KW, "func").line;
        String name = expect(Tok.IDENT, null).text;
        expect(Tok.OP, "(");
        List<String> params = new ArrayList<>();
        if(!is(Tok.OP, ")")){
            while(true){
                params.add(expect(Tok.IDENT, null).text);
                if(!eat(Tok.OP, ",")) break;
            }
        }
        expect(Tok.OP, ")");
        expect(Tok.OP, ":");
        List<Stmt> body = block();
        return new FuncDef(name, params, body, line);
    }

    private List<Stmt> block(){
        if(eat(Tok.NEWLINE, null)){
            expect(Tok.INDENT, null);
            List<Stmt> out = new ArrayList<>();
            while(!is(Tok.DEDENT, null) && !is(Tok.EOF, null)){
                if(eat(Tok.NEWLINE, null)) continue;
                out.add(statement());
            }
            expect(Tok.DEDENT, null);
            return out;
        }
        List<Stmt> one = new ArrayList<>();
        one.add(statement());                  // single statement on the ':' line
        return one;
    }

    public Stmt statement(){
        int line = cur().line;
        if(is(Tok.KW, "var")){
            next();
            String name = expect(Tok.IDENT, null).text;
            Expr init = eat(Tok.OP, "=") ? expr() : new NullLit(line);
            endLine();
            return new VarStmt(name, init, line);
        }
        if(is(Tok.KW, "if")) return ifStmt();
        if(is(Tok.KW, "while")){
            next();
            Expr c = expr();
            expect(Tok.OP, ":");
            return new WhileStmt(c, block(), line);
        }
        if(is(Tok.KW, "for")){
            next();
            String name = expect(Tok.IDENT, null).text;
            expect(Tok.KW, "in");
            Expr it = expr();
            expect(Tok.OP, ":");
            return new ForStmt(name, it, block(), line);
        }
        if(is(Tok.KW, "return")){
            next();
            Expr v = (is(Tok.NEWLINE, null) || is(Tok.EOF, null) || is(Tok.DEDENT, null))
                ? null : expr();
            endLine();
            return new ReturnStmt(v, line);
        }
        if(is(Tok.KW, "break")){
            next(); endLine();
            return new BreakStmt(line);
        }
        if(is(Tok.KW, "continue")){
            next(); endLine();
            return new ContinueStmt(line);
        }
        if(is(Tok.KW, "pass")){
            next(); endLine();
            return new PassStmt(line);
        }
        // assignment or expression statement
        Expr e = expr();
        if(is(Tok.OP, "=") || is(Tok.OP, "+=") || is(Tok.OP, "-=") || is(Tok.OP, "*=") || is(Tok.OP, "/=")){
            String op = next().text;
            Expr v = expr();
            endLine();
            if(!(e instanceof Ident) && !(e instanceof Dot) && !(e instanceof Index))
                throw err("invalid assignment target");
            return new AssignStmt(e, op, v, line);
        }
        endLine();
        return new ExprStmt(e, line);
    }

    private Stmt ifStmt(){
        int line = expect(Tok.KW, "if").line;
        Expr c = expr();
        expect(Tok.OP, ":");
        List<Stmt> then = block();
        List<Object[]> elifs = new ArrayList<>();
        List<Stmt> els = new ArrayList<>();
        while(is(Tok.KW, "elif")){
            int l2 = next().line;
            Expr c2 = expr();
            expect(Tok.OP, ":");
            elifs.add(new Object[]{ c2, block() });
        }
        if(eat(Tok.KW, "else")){
            expect(Tok.OP, ":");
            els = block();
        }
        return new IfStmt(c, then, elifs, els, line);
    }

    // ---- expressions ----
    public Expr expr(){ return orExpr(); }
    private Expr orExpr(){
        Expr l = andExpr();
        while(is(Tok.KW, "or")){
            int line = next().line;
            l = new Bin("or", l, andExpr(), line);
        }
        return l;
    }
    private Expr andExpr(){
        Expr l = notExpr();
        while(is(Tok.KW, "and")){
            int line = next().line;
            l = new Bin("and", l, notExpr(), line);
        }
        return l;
    }
    private Expr notExpr(){
        if(is(Tok.KW, "not")){
            int line = next().line;
            return new Un("not", notExpr(), line);
        }
        return cmpExpr();
    }
    private Expr cmpExpr(){
        Expr l = addExpr();
        while(is(Tok.OP, "==") || is(Tok.OP, "!=") || is(Tok.OP, "<") || is(Tok.OP, "<=")
              || is(Tok.OP, ">") || is(Tok.OP, ">=")){
            int line = next().line;
            String op = toks.get(p - 1).text;
            l = new Bin(op, l, addExpr(), line);
        }
        return l;
    }
    private Expr addExpr(){
        Expr l = mulExpr();
        while(is(Tok.OP, "+") || is(Tok.OP, "-")){
            int line = next().line;
            String op = toks.get(p - 1).text;
            l = new Bin(op, l, mulExpr(), line);
        }
        return l;
    }
    private Expr mulExpr(){
        Expr l = unary();
        while(is(Tok.OP, "*") || is(Tok.OP, "/") || is(Tok.OP, "%")){
            int line = next().line;
            String op = toks.get(p - 1).text;
            l = new Bin(op, l, unary(), line);
        }
        return l;
    }
    private Expr unary(){
        if(is(Tok.OP, "-")){
            int line = next().line;
            return new Un("-", unary(), line);
        }
        if(is(Tok.OP, "+")){
            next();
            return unary();
        }
        return postfix();
    }
    private Expr postfix(){
        Expr e = primary();
        while(true){
            if(is(Tok.OP, ".")){
                int line = next().line;
                String name = expect(Tok.IDENT, null).text;
                if(is(Tok.OP, "(")){                       // method call: obj.name(args)
                    next();
                    List<Expr> args = new ArrayList<>();
                    if(!is(Tok.OP, ")")){
                        while(true){
                            args.add(expr());
                            if(!eat(Tok.OP, ",")) break;
                        }
                    }
                    expect(Tok.OP, ")");
                    e = new MethodCall(e, name, args, line);
                } else {
                    e = new Dot(e, name, line);
                }
            } else if(is(Tok.OP, "[")){
                int line = next().line;
                Expr idx = expr();
                expect(Tok.OP, "]");
                e = new Index(e, idx, line);
            } else break;
        }
        return e;
    }
    private Expr primary(){
        Token c = cur();
        int line = c.line;
        if(c.type == Tok.NUMBER){ next(); return new NumLit(Double.parseDouble(c.text), line); }
        if(c.type == Tok.STRING){ next(); return new StrLit(c.text, line); }
        if(c.type == Tok.IDENT){
            next();
            if(is(Tok.OP, "(")){
                next();
                List<Expr> args = new ArrayList<>();
                if(!is(Tok.OP, ")")){
                    while(true){
                        args.add(expr());
                        if(!eat(Tok.OP, ",")) break;
                    }
                }
                expect(Tok.OP, ")");
                return new Call(c.text, args, line);
            }
            return new Ident(c.text, line);
        }
        if(is(Tok.KW, "true")){ next(); return new BoolLit(true, line); }
        if(is(Tok.KW, "false")){ next(); return new BoolLit(false, line); }
        if(is(Tok.KW, "null")){ next(); return new NullLit(line); }
        if(is(Tok.KW, "self")){ next(); return new Ident("self", line); }
        if(is(Tok.OP, "(")){
            next();
            Expr e = expr();
            expect(Tok.OP, ")");
            return e;
        }
        if(is(Tok.OP, "[")){
            next();
            List<Expr> items = new ArrayList<>();
            if(!is(Tok.OP, "]")){
                while(true){
                    items.add(expr());
                    if(!eat(Tok.OP, ",")) break;
                }
            }
            expect(Tok.OP, "]");
            return new ListLit(items, line);
        }
        if(is(Tok.OP, "{")){
            next();
            List<String> keys = new ArrayList<>();
            List<Expr> vals = new ArrayList<>();
            if(!is(Tok.OP, "}")){
                while(true){
                    Token k = cur();
                    if(k.type != Tok.STRING && k.type != Tok.IDENT) throw err("map key expected");
                    next();
                    expect(Tok.OP, ":");
                    keys.add(k.text);
                    vals.add(expr());
                    if(!eat(Tok.OP, ",")) break;
                }
            }
            expect(Tok.OP, "}");
            return new MapLit(keys, vals, line);
        }
        throw err("unexpected token '" + c.text + "'");
    }
}
