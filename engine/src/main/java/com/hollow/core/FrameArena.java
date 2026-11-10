package com.hollow.core;

/** Per-frame scratch arena (MEM-012): a bump allocator that auto-resets at frame end.
 *  Engine loop allocates transient data (visiblity lists, staging buffers, AI scratch)
 *  through {@link #get()} and {@link GameApp}.frame() calls {@link #endFrame()} after
 *  the frame — one memory.reset() per frame, zero GC. */
public final class FrameArena {
    private static final ThreadLocal<ArenaAllocator> TL = ThreadLocal.withInitial(() -> new ArenaAllocator(1 << 20)); // 1 MB

    private static long totalResets = 0;
    private static long totalBytesServed = 0;

    private FrameArena(){}

    /** thread-local per-frame arena */
    public static ArenaAllocator get(){ return TL.get(); }

    /** call exactly once per rendered frame, AFTER all consumers are done (GameApp.frame tail) */
    public static void endFrame(){
        ArenaAllocator a = TL.get();
        totalBytesServed += a.used();
        a.reset();
        totalResets++;
    }

    public static long resets(){ return totalResets; }
    public static long bytesServed(){ return totalBytesServed; }

    public static String stats(){
        ArenaAllocator a = TL.get();
        return "frameArena[cap=" + (a.capacity() / 1024) + "KB used=" + a.used() + " high=" + a.highWaterMark()
             + " resets=" + totalResets + " served=" + (totalBytesServed / 1024) + "KB]";
    }
}
