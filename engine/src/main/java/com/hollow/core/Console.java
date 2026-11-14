package com.hollow.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Console command registry + line interpreter (REPL core).
 *  Backs the interactive `hollow shell`, in-game dev console and .hvefile batch scripts.
 *  Commands are registered with a name + help + handler and invoked as `name arg1 arg2`. */
public final class Console {

    public interface Handler { /** @return output text (may be multi-line, null = silent) */
        String run(String[] args) throws Exception; }

    public static final class Command {
        public final String name, help;
        public final Handler handler;
        Command(String n, String h, Handler r){ name = n; help = h; handler = r; }
    }

    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final List<String> history = new ArrayList<>();
    private final int historyLimit;
    /** last error, for scripting branches */
    public String lastError = null;

    public Console(){ this(200); }
    public Console(int historyLimit){ this.historyLimit = historyLimit; }

    // ---------------- registration ----------------
    public synchronized Console register(String name, String help, Handler h){
        commands.put(name, new Command(name, help, h));
        return this;
    }
    public synchronized Console alias(String alias, String target){
        aliases.put(alias, target);
        return this;
    }
    public synchronized boolean has(String name){
        return commands.containsKey(name) || aliases.containsKey(name);
    }
    public synchronized List<Command> allCommands(){
        return new ArrayList<>(commands.values());
    }
    /** tab-completion candidates for a partial token */
    public synchronized List<String> complete(String prefix){
        List<String> out = new ArrayList<>();
        for(String n : commands.keySet()) if(n.startsWith(prefix)) out.add(n);
        for(String n : aliases.keySet()) if(n.startsWith(prefix)) out.add(n);
        return out;
    }

    // ---------------- history ----------------
    public List<String> history(){ return new ArrayList<>(history); }
    public void clearHistory(){ history.clear(); }

    // ---------------- execution ----------------
    /** Execute one line: parse, resolve alias, dispatch. Returns output (null ok). */
    public synchronized String execute(String line){
        String trimmed = line == null ? "" : line.trim();
        if(trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) return null;
        history.add(trimmed);
        if(history.size() > historyLimit) history.remove(0);

        String[] tokens = tokenize(trimmed);
        if(tokens.length == 0) return null;
        String name = tokens[0];

        // cvar shorthand: "cvar_name value" or bare "cvar_name" prints it
        CVar v = CVar.exists(name) ? CVar.get(name) : null;
        if(v != null){
            if(tokens.length == 1) return v.name + " = " + v.getString();
            v.set(tokens[1]);
            return v.name + " = " + v.getString();
        }

        String target = aliases.getOrDefault(name, name);
        Command cmd = commands.get(target);
        if(cmd == null){
            lastError = "unknown command: " + name + " (try 'help')";
            return lastError;
        }
        String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);
        try {
            String out = cmd.handler.run(args);
            lastError = null;
            return out;
        } catch(Exception e){
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            Log.e("Console", "command '" + target + "' failed", e);
            return "ERROR: " + lastError;
        }
    }

    /** Execute a .hvefile batch script (one command per line, # comments, `wait N` supported by caller). */
    public int executeScript(String text){
        int executed = 0;
        for(String line : text.split("\n")){
            String t = line.trim();
            if(t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue;
            execute(t);
            executed++;
        }
        return executed;
    }

    // ---------------- parsing ----------------
    /** whitespace split with double/single-quote grouping */
    public static String[] tokenize(String line){
        List<String> out = new ArrayList<>(8);
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean hadQuote = false;
        for(int i=0;i<line.length();i++){
            char c = line.charAt(i);
            if(quote != 0){
                if(c == quote){
                    quote = 0;
                    if(cur.length() == 0 && hadQuote){ out.add(""); }   // explicit empty arg
                    continue;
                }
                cur.append(c);
            } else if(c == '"' || c == '\''){ quote = c; hadQuote = true; }
            else if(c == ' ' || c == '\t'){
                if(cur.length() > 0){ out.add(cur.toString()); cur.setLength(0); }
            } else cur.append(c);
        }
        if(cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
