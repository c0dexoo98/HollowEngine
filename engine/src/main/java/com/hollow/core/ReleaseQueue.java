package com.hollow.core;

import java.util.ArrayList;
import java.util.List;

/** Deferred release queue (MEM): engine objects that own native/GPU handles (textures,
 *  buffers, audio clips) cannot always be freed mid-frame; park them here and drain
 *  at a safe point (end of frame / world switch). */
public final class ReleaseQueue {

    public interface Releasable { void release(); }

    private static final List<Releasable> QUEUE = new ArrayList<>(64);
    private static long released = 0;

    public static synchronized void enqueue(Releasable r){
        if(r != null) QUEUE.add(r);
    }
    /** drain everything queued so far; safe to call anytime */
    public static synchronized int drain(){
        if(QUEUE.isEmpty()) return 0;
        List<Releasable> batch = new ArrayList<>(QUEUE);
        QUEUE.clear();
        int n = 0;
        for(Releasable r : batch){
            try { r.release(); n++; }
            catch(Throwable t){ Log.w("ReleaseQueue", "release failed: " + t); }
        }
        released += n;
        return n;
    }
    public static synchronized int pending(){ return QUEUE.size(); }
    public static synchronized long totalReleased(){ return released; }
}
