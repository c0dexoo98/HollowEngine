package com.hollow.script;

import java.util.ArrayList;
import java.util.List;

/** HollowScript — lexer for HollowEngine 2's GDScript-style language.
 *  Indentation blocks (like GDScript/Python), `#` comments, doubles,
 *  strings with escapes, keyword set, NEWLINE/INDENT/DEDENT tokens.
 */
public final class Lexer {

    public enum Tok { IDENT, NUMBER, STRING, KW, OP, NEWLINE, INDENT, DEDENT, EOF }

    public static final class Token {
        public final Tok type;
        public final String text;
        public final int line;
        public Token(Tok type, String text, int line){
            this.type = type; this.text = text; this.line = line;
        }
        @Override public String toString(){ return type + "(" + text + ")@" + line; }
    }

    static final String[] KEYWORDS = {
        "var", "func", "if", "elif", "else", "while", "for", "in", "return",
        "break", "continue", "true", "false", "null", "and", "or", "not", "pass",
        "class", "extends", "self"
    };

    public static List<Token> lex(String src){
        List<Token> out = new ArrayList<>();
        java.util.ArrayDeque<Integer> indents = new java.util.ArrayDeque<>();
        indents.push(0);
        int i = 0, line = 1, n = src.length();
        boolean atLineStart = true;
        boolean freshLine = true;               // a NEWLINE is pending for the current line

        while(i < n){
            if(atLineStart){
                // measure indentation; skip blank / comment-only lines
                int width = 0;
                int j = i;
                while(j < n){
                    char c = src.charAt(j);
                    if(c == ' ') { width++; j++; }
                    else if(c == '\t'){ width += 4 - (width % 4); j++; }
                    else break;
                }
                if(j >= n) break;
                char c = src.charAt(j);
                if(c == '\n'){ line++; i = j + 1; continue; }
                if(c == '\r'){ j++; continue; }
                if(c == '#'){ while(j < n && src.charAt(j) != '\n') j++; i = j; continue; }
                int top = indents.peek();
                if(width > top){
                    indents.push(width);
                    out.add(new Token(Tok.INDENT, "", line));
                } else if(width < top){
                    while(indents.peek() > width){
                        indents.pop();
                        out.add(new Token(Tok.DEDENT, "", line));
                    }
                    if(indents.peek() != width)
                        throw err("inconsistent indentation", line);
                }
                atLineStart = false;
                i = j;
                continue;
            }

            char c = src.charAt(i);
            if(c == '\r'){ i++; continue; }
            if(c == '\n'){
                if(freshLine){
                    out.add(new Token(Tok.NEWLINE, "", line));
                    freshLine = false;
                }
                line++;
                i++;
                atLineStart = true;
                continue;
            }
            if(c == ' ' || c == '\t'){ i++; continue; }
            if(c == '#'){ while(i < n && src.charAt(i) != '\n') i++; continue; }

            // two-char operators first
            if(i + 1 < n){
                char d = src.charAt(i + 1);
                String two = "" + c + d;
                if(two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
                   || two.equals("+=") || two.equals("-=") || two.equals("*=") || two.equals("/=")){
                    out.add(new Token(Tok.OP, two, line));
                    i += 2;
                    freshLine = true;
                    continue;
                }
            }

            if(c == '"'){
                int st = line;
                i++;
                StringBuilder b = new StringBuilder();
                boolean closed = false;
                while(i < n){
                    char e = src.charAt(i++);
                    if(e == '"'){ closed = true; break; }
                    if(e == '\\'){
                        if(i >= n) break;
                        char x = src.charAt(i++);
                        if(x == 'n') b.append('\n');
                        else if(x == 't') b.append('\t');
                        else if(x == '"') b.append('"');
                        else if(x == '\\') b.append('\\');
                        else throw err("bad escape \\" + x, line);
                    } else {
                        if(e == '\n') line++;
                        b.append(e);
                    }
                }
                if(!closed) throw err("unterminated string", st);
                out.add(new Token(Tok.STRING, b.toString(), st));
                freshLine = true;
                continue;
            }

            if(Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))){
                int st = i;
                while(i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                if(i < n && (src.charAt(i) == 'e' || src.charAt(i) == 'E')){
                    i++;
                    if(i < n && (src.charAt(i) == '-' || src.charAt(i) == '+')) i++;
                    while(i < n && Character.isDigit(src.charAt(i))) i++;
                }
                out.add(new Token(Tok.NUMBER, src.substring(st, i), line));
                freshLine = true;
                continue;
            }

            if(Character.isLetter(c) || c == '_'){
                int st = i;
                while(i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                String word = src.substring(st, i);
                boolean kw = false;
                for(String k : KEYWORDS) if(k.equals(word)){ kw = true; break; }
                out.add(new Token(kw ? Tok.KW : Tok.IDENT, word, line));
                freshLine = true;
                continue;
            }

            String one = String.valueOf(c);
            if("+-*/%<>=.,()[]{}:".indexOf(c) >= 0){
                out.add(new Token(Tok.OP, one, line));
                i++;
                freshLine = true;
                continue;
            }
            throw err("unexpected character '" + c + "'", line);
        }

        // close the last line + dedents
        if(freshLine && !out.isEmpty() && out.get(out.size() - 1).type != Tok.NEWLINE
           && out.get(out.size() - 1).type != Tok.DEDENT)
            out.add(new Token(Tok.NEWLINE, "", line));
        while(indents.peek() > 0){
            indents.pop();
            out.add(new Token(Tok.DEDENT, "", line));
        }
        out.add(new Token(Tok.EOF, "", line + 1));
        return out;
    }

    private static IllegalArgumentException err(String msg, int line){
        return new IllegalArgumentException("HollowScript lex error, line " + line + ": " + msg);
    }
}
