import com.hollow.core.MathX;
import com.hollow.scene.Node;
import com.hollow.scene.Scene;
import com.hollow.script.Interp;
import com.hollow.script.Lexer;
import com.hollow.script.Lexer.Token;
import com.hollow.script.Lexer.Tok;
import com.hollow.script.Parser;

import java.util.ArrayList;
import java.util.List;

/** HollowEngine 2 — HollowScript regression: lexer, parser, interpreter. */
public class VerifyH2Script {
    static int fails = 0, total = 0;
    static void check(String name, boolean ok, String detail){
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  <-- " + detail));
        total++; if(!ok) fails++;
    }
    static Interp run(String src){
        return run(src, null);
    }
    static Interp run(String src, List<String> out){
        Interp i = Interp.fromSource(src);
        if(out != null) i.setOut(out::add);
        i.runBody();
        return i;
    }

    public static void main(String[] args){
        lexer();
        parser();
        interp();
        System.out.println(fails == 0 ? "\nALL " + total + " SCRIPT TESTS PASSED"
                                      : "\n" + fails + " OF " + total + " SCRIPT TEST(S) FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void lexer(){
        List<Token> t = Lexer.lex("var x = 1 # trailing comment\nx = 2\n");
        boolean nl = false;
        for(Token k : t){ if(k.type == Tok.NEWLINE) nl = true; }
        check("lx: comments skipped, newlines kept", nl
              && t.stream().noneMatch(k -> k.text.contains("#")), "");
        List<Token> ind = Lexer.lex("if a:\n    b = 1\n    c = 2\nd = 3\n");
        long indents = ind.stream().filter(k -> k.type == Tok.INDENT).count();
        long dedents = ind.stream().filter(k -> k.type == Tok.DEDENT).count();
        check("lx: indent/dedent pairs", indents == 1 && dedents == 1, indents + "/" + dedents);
        List<Token> nest = Lexer.lex("if a:\n    if b:\n        x = 1\n    y = 2\n");
        check("lx: nested blocks", nest.stream().filter(k -> k.type == Tok.INDENT).count() == 2
              && nest.stream().filter(k -> k.type == Tok.DEDENT).count() == 2, "");
        List<Token> str = Lexer.lex("\"tab\\tnl\\nq\\\"\"");
        check("lx: string escapes", str.get(0).type == Tok.STRING && str.get(0).text.equals("tab\tnl\nq\""), "");
        List<Token> ops = Lexer.lex("a += 1 == 2 != 3 <= 4 >= 5");
        check("lx: two-char operators", ops.stream().filter(k -> k.type == Tok.OP && (k.text.equals("==") || k.text.equals("+=") || k.text.equals("!="))).count() == 3, "");
        try {
            Lexer.lex("\"unterminated");
            check("lx: unterminated string throws", false, "");
        } catch(IllegalArgumentException e){
            check("lx: unterminated string throws", e.getMessage().contains("unterminated"), "");
        }
        try {
            Lexer.lex("if a:\n  b = 1\n c = 2\n");
            check("lx: inconsistent indent throws", false, "");
        } catch(IllegalArgumentException e){
            check("lx: inconsistent indent throws", e.getMessage().contains("indent"), "");
        }
        List<Token> num = Lexer.lex("x = 1.5e-2");
        check("lx: scientific numbers", num.get(2).text.equals("1.5e-2"), num.get(2).text);
    }

    static void parser(){
        try {
            Parser.parse("var = 5\n");
            check("pr: missing name throws", false, "");
        } catch(IllegalArgumentException e){
            check("pr: missing name throws", true, "");
        }
        try {
            Parser.parse("if x:\nprint(1)\n");
            check("pr: empty block throws", false, "");
        } catch(IllegalArgumentException e){
            check("pr: empty block throws", true, "");
        }
        try {
            Parser.parse("a = 1 +\n");
            check("pr: dangling operator throws", false, "");
        } catch(IllegalArgumentException e){
            check("pr: dangling operator throws", true, "");
        }
        Parser.Program p = Parser.parse("func f(a, b):\n    return a\n\nfunc g():\n    pass\n");
        check("pr: two funcs parsed", p.funcs.size() == 2 && p.funcs.get(0).params.size() == 2, "");
        Parser.Program inline = Parser.parse("if x: y = 1\nz = 2\n");
        check("pr: single-line block", inline.body.size() == 2
              && ((Parser.IfStmt)inline.body.get(0)).then.size() == 1, "");
        Parser.Program elif = Parser.parse("if a:\n    x = 1\nelif b:\n    x = 2\nelif c:\n    x = 3\nelse:\n    x = 4\n");
        Parser.IfStmt ifs = (Parser.IfStmt)elif.body.get(0);
        check("pr: elif chain + else", ifs.elifs.size() == 2 && ifs.els.size() == 1, "");
    }

    static void interp(){
        List<String> out = new ArrayList<>();
        Interp i = run("print(\"a\", 1, true, null)\n", out);
        check("in: print joins args", out.size() == 1 && out.get(0).equals("a 1 true null"), out.toString());

        i = run("var x = 7\nx += 3\nx *= 2\nx -= 1\nx /= 3\n");
        check("in: compound assigns", Math.abs((double)i.global("x") - 19.0 / 3.0) < 1e-9, "" + i.global("x"));

        i = run("var a = 2 + 3 * 4\nvar b = (2 + 3) * 4\nvar c = -2 * 3\nvar d = 10 % 4\n");
        check("in: precedence + unary", (double)i.global("a") == 14 && (double)i.global("b") == 20
              && (double)i.global("c") == -6 && (double)i.global("d") == 2, "");

        i = run("func outer(n):\n    func_unused = 0\n    return inner(n) + 1\nfunc inner(n):\n    return n * 2\n");
        check("in: cross-function calls", (double)i.callFunc("outer", 5.0) == 11.0, "");

        i = run(String.join("\n",
            "func classify(n):",
            "    if n < 0:",
            "        return \"neg\"",
            "    elif n == 0:",
            "        return \"zero\"",
            "    else:",
            "        return \"pos\""));
        check("in: elif chain", i.callFunc("classify", -5.0).equals("neg")
              && i.callFunc("classify", 0.0).equals("zero")
              && i.callFunc("classify", 9.0).equals("pos"), "");

        i = run(String.join("\n",
            "var total = 0",
            "var hits = 0",
            "for i in range(1, 4):",
            "    for j in range(1, 3):",
            "        total = total + i * j",
            "        if j == 2:",
            "            continue",
            "        hits = hits + 1"));
        check("in: nested for + continue", (double)i.global("total") == 18.0 && (double)i.global("hits") == 3.0, "");

        i = run(String.join("\n",
            "var i = 0",
            "while i < 100:",
            "    i = i + 1",
            "    if i == 7:",
            "        break"));
        check("in: while + break", (double)i.global("i") == 7.0, "");

        i = run("var s = \"Hello\"\nvar l = s.lower()\nvar u = \"x\".upper()\nvar has = s.contains(\"ell\")\nvar parts = \"a,b,c\".split(\",\")\n");
        check("in: string methods", i.global("l").equals("hello") && i.global("u").equals("X")
              && (boolean)i.global("has") && ((List<?>)i.global("parts")).size() == 3, "");

        i = run("var m = {a: 1, b: 2}\nvar k = keys(m)\nvar found = has(m, \"b\")\nm[\"c\"] = 3\nm.a = 10\n");
        check("in: map read/write", ((List<?>)i.global("k")).size() == 2 && (boolean)i.global("found")
              && (double)((java.util.Map<?,?>)i.global("m")).get("c") == 3
              && (double)((java.util.Map<?,?>)i.global("m")).get("a") == 10, "");

        i = run("var l = [1, 2, 3]\nl.append(4)\nvar last = l.pop()\nvar second = l[1]\nvar n = len(l)\n");
        check("in: list ops + index", (double)i.global("last") == 4 && (double)i.global("second") == 2
              && (double)i.global("n") == 3, "");

        i = run("var w = \"ab\" * 3\nvar joined = \"x\" + 1 + true\n");
        check("in: repeat + auto-str", i.global("w").equals("ababab") && i.global("joined").equals("x1true"), "");

        Interp a = run("srand(42)\nvar x = rand()\nvar y = rand(10)\n");
        Interp b = run("srand(42)\nvar x = rand()\nvar y = rand(10)\n");
        check("in: srand deterministic", a.global("x").equals(b.global("x")) && a.global("y").equals(b.global("y")), "");

        i = run("var t = typeof(1) + \",\" + typeof(\"s\") + \",\" + typeof([1]) + \",\" + typeof(null)\n");
        check("in: typeof", i.global("t").equals("number,string,list,null"), "" + i.global("t"));

        Node n = new Node("probe");
        n.position.y = 4;
        i = run("func touch(node):\n    node.name = \"renamed\"\n    node.position_y = node.position_y + 1\n    return node.position_y\n");
        check("in: native read/write", (double)i.callFunc("touch", n) == 5.0 && n.name.equals("renamed"), "");

        i = run("func find(node, scene):\n    return scene.find(\"hero\") != null\n");
        Scene s = new Scene();
        s.add(s.createNode("mesh", "hero"));
        check("in: scene native find", (boolean)i.callFunc("find", n, s), "");

        try {
            run("var x = 1 / 0\n");
            check("in: division by zero errors", false, "");
        } catch(Interp.ScriptError e){
            check("in: division by zero errors", e.getMessage().contains("zero"), "");
        }
        i = run("undeclared = 42\n");
        check("in: bare assign declares global", (double)i.global("undeclared") == 42.0, "");
        try {
            Interp bad = Interp.fromSource("func f():\n    return 1\nf(2)\n");
            bad.runBody();
            check("in: arity mismatch errors", false, "");
        } catch(Interp.ScriptError e){
            check("in: arity mismatch errors", e.getMessage().contains("expects"), "");
        }
        try {
            Interp bad = Interp.fromSource("y =\n");
            bad.runBody();
            check("in: syntax error message has line", false, "");
        } catch(IllegalArgumentException e){
            check("in: syntax error message has line", e.getMessage().contains("line"), "");
        }
        i = run("var z = int(3.9)\nvar fl = floor(-1.2)\nvar ce = ceil(1.2)\nvar sq = sqrt(16)\nvar pw = pow(2, 10)\nvar ab = abs(-4)\nvar mn = min(3, 1, 2)\nvar mx = max(3, 1, 2)\n");
        check("in: math builtins", (double)i.global("z") == 3 && (double)i.global("fl") == -2
              && (double)i.global("ce") == 2 && (double)i.global("sq") == 4
              && (double)i.global("pw") == 1024 && (double)i.global("ab") == 4
              && (double)i.global("mn") == 1 && (double)i.global("mx") == 3, "");
    }
}
