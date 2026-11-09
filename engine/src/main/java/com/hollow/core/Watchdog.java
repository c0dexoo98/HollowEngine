package com.hollow.core;

/** Frame watchdog (CORE): a daemon thread that monitors heartbeats from the main loop.
 *  If no heartbeat arrives within the timeout, it logs a stall warning with a thread dump
 *  so hangs are diagnosable in the field. */
public final class Watchdog implements AutoCloseable {
    private volatile long lastBeat = System.nanoTime();
    private volatile long timeoutNanos;
    private volatile boolean enabled = true;
    private final Thread monitor;
    private int stalls = 0;
    private long lastStallMs = 0;

    public Watchdog(float timeoutSeconds){
        this.timeoutNanos = (long)(timeoutSeconds * 1e9f);
        monitor = new Thread(this::loop, "hollow-watchdog");
        monitor.setDaemon(true);
        monitor.start();
    }
    public Watchdog(){ this(5f); }

    /** call once per frame from the main loop */
    public void heartbeat(){ lastBeat = System.nanoTime(); }

    public void setTimeout(float seconds){ this.timeoutNanos = (long)(seconds * 1e9f); }
    public void setEnabled(boolean e){ enabled = e; }
    public int stalls(){ return stalls; }
    public float secondsSinceBeat(){ return (System.nanoTime() - lastBeat) / 1e9f; }

    private void loop(){
        while(true){
            try { Thread.sleep(500); } catch(InterruptedException e){ return; }
            if(!enabled) continue;
            float since = secondsSinceBeat();
            if(since * 1e9f > timeoutNanos){
                long now = System.currentTimeMillis();
                if(now - lastStallMs > 5000){ // warn at most every 5s, not every poll
                    stalls++;
                    lastStallMs = now;
                    Log.w("Watchdog", String.format("main loop stalled %.1fs (timeout %.1fs) — thread dump:%n%s",
                            since, timeoutNanos / 1e9f, dumpThreads()));
                }
            }
        }
    }

    static String dumpThreads(){
        StringBuilder b = new StringBuilder(1024);
        for(String ti : threadInfos()){
            b.append("  ").append(ti).append('\n');
        }
        return b.toString();
    }
    private static java.util.List<String> threadInfos(){
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            java.lang.management.ThreadMXBean mx = java.lang.management.ManagementFactory.getThreadMXBean();
            for(long id : mx.getAllThreadIds()){
                java.lang.management.ThreadInfo info = mx.getThreadInfo(id, 0);
                if(info != null) out.add(info.getThreadName() + " state=" + info.getThreadState());
            }
        } catch(Throwable ignored){ }
        return out;
    }

    @Override public void close(){ if(monitor != null) monitor.interrupt(); }
}
