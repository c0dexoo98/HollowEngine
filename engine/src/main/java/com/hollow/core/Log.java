package com.hollow.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;

/** Engine-wide logging with pluggable sink. Never use System.out directly. */
public final class Log {
    public enum Level { DEBUG(0), INFO(1), WARN(2), ERROR(3);
        public final int v; Level(int v){this.v=v;} }

    public interface Sink { void write(Level lvl, String tag, String msg); }

    private static volatile Sink sink = (lvl, tag, msg) -> {
        String line = String.format("%02d:%02d:%02d", LocalTime.now().getHour(), LocalTime.now().getMinute(), LocalTime.now().getSecond()) + " [" + lvl + "][" + tag + "] " + msg;
        (lvl == Level.ERROR ? System.err : System.out).println(line);
    };
    private static volatile Level minLevel = Level.INFO;
    /** collect messages for headless test reporting */
    public static final java.util.List<String> CAPTURE = new java.util.ArrayList<>();
    private static volatile boolean capturing = false;

    private Log() {}

    public static void setSink(Sink s){ sink = s; }
    public static void setLevel(Level l){ minLevel = l; }
    public static void setCapturing(boolean c){ capturing = c; }
    public static void clearCapture(){ CAPTURE.clear(); }

    public static void d(String tag, String msg){ if(minLevel.v<=Level.DEBUG.v) out(Level.DEBUG,tag,msg); }
    public static void i(String tag, String msg){ out(Level.INFO,tag,msg); }
    public static void w(String tag, String msg){ out(Level.WARN,tag,msg); }
    public static void e(String tag, String msg){ out(Level.ERROR,tag,msg); }
    public static void e(String tag, String msg, Throwable t){
        StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw));
        out(Level.ERROR, tag, msg + "\n" + sw);
    }
    private static void out(Level lvl, String tag, String msg){
        if(capturing) synchronized(CAPTURE){ CAPTURE.add(lvl+"|"+tag+"|"+msg); }
        Sink s = sink; if(s!=null) s.write(lvl,tag,msg);
    }
}
