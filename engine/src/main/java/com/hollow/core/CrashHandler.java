package com.hollow.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Crash handler (CORE): traps fatal uncaught exceptions, writes a timestamped crash report
 *  (stack + version + CVars + memory) to the log directory, then restores the previous handler
 *  so the platform can still show its own crash UI. */
public final class CrashHandler {

    public interface Listener { void onCrash(String summary, String report); }

    private static final java.util.List<Listener> listeners = new java.util.ArrayList<>();
    private static volatile boolean installed = false;
    private static String lastCrashFile = null;
    public static String logDir = "logs";

    public static synchronized void addListener(Listener l){ listeners.add(l); }

    public static synchronized void install(){
        if(installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try { writeReport(thread, throwable); } catch(Throwable ignored){ }
            if(previous != null) previous.uncaughtException(thread, throwable);
        });
        Log.i("Crash", "crash handler installed (reports -> " + logDir + ")");
    }

    static void writeReport(Thread thread, Throwable t){
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String file = logDir + "/crash-" + ts + ".log";
        StringBuilder b = new StringBuilder(4096);
        b.append("HollowEngine crash report\n");
        b.append("time:    ").append(ts).append('\n');
        b.append("thread:  ").append(thread.getName()).append('\n');
        b.append("version: ").append(EngineVersion.FULL).append('\n');
        b.append("\n--- exception ---\n");
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        b.append(sw).append('\n');
        b.append("\n--- memory ---\n");
        try { b.append(MemoryStats.report()); } catch(Throwable ignored){ }
        b.append("\n--- cvars ---\n");
        try { b.append(CVar.table(null)); } catch(Throwable ignored){ }

        lastCrashFile = file;
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(file);
            if(p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, b.toString());
            Log.e("Crash", "crash report written: " + file);
        } catch(Exception e){ Log.e("Crash", "could not write crash report", e); }

        String summary = t.getClass().getSimpleName() + ": " + t.getMessage();
        for(Listener l : listeners){ try { l.onCrash(summary, b.toString()); } catch(Throwable ignored){ } }
    }

    /** manual report for caught fatal conditions */
    public static String reportNow(String title, Throwable t){
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String file = logDir + "/error-" + ts + ".log";
        StringBuilder b = new StringBuilder(2048);
        b.append("HollowEngine error report — ").append(title).append('\n');
        b.append("version: ").append(EngineVersion.FULL).append('\n');
        if(t != null){
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            b.append(sw).append('\n');
        }
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(file);
            if(p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, b.toString());
        } catch(Exception ignored){ }
        return file;
    }

    public static String lastCrashFile(){ return lastCrashFile; }
}
