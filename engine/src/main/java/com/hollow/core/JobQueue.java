package com.hollow.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lightweight job queue (CORE): N worker threads for parallelizable engine work
 *  (asset decode, nav baking, audio mixing). Deterministic mode runs everything inline
 *  so simulations stay reproducible on Android. */
public final class JobQueue {
    private final List<Thread> workers = new ArrayList<>();
    private final java.util.concurrent.BlockingQueue<Runnable> queue = new java.util.concurrent.LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final boolean deterministic;
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();

    public JobQueue(int threads, boolean deterministic){
        this.deterministic = deterministic;
        if(!deterministic){
            for(int i=0;i<Math.max(1, threads);i++){
                Thread t = new Thread(this::workerLoop, "hollow-job-" + i);
                t.setDaemon(true);
                t.start();
                workers.add(t);
            }
        }
    }
    public JobQueue(int threads){ this(threads, false); }
    /** shared deterministic queue — runs inline */
    private static final JobQueue INLINE = new JobQueue(1, true);
    public static JobQueue inline(){ return INLINE; }

    public Future<?> submit(Runnable r){
        FutureTask<Void> f = new FutureTask<>(r, null);
        pending.incrementAndGet();
        if(deterministic){ f.run(); pending.decrementAndGet(); }
        else queue.add(f);
        return f;
    }
    public <V> Future<V> submit(Callable<V> c){
        FutureTask<V> f = new FutureTask<>(c);
        pending.incrementAndGet();
        if(deterministic){ f.run(); pending.decrementAndGet(); }
        else queue.add(f);
        return f;
    }

    private void workerLoop(){
        while(running.get()){
            try {
                Runnable r = queue.take();
                try { ((FutureTask<?>) r).run(); }
                catch(Exception e){ Log.e("JobQueue", "job failed", e); }
                finally { pending.decrementAndGet(); }
            } catch(InterruptedException e){ Thread.currentThread().interrupt(); return; }
        }
    }

    /** block until all queued jobs complete (poll-based to avoid deadlocks on cancel) */
    public void waitIdle(long timeoutMs){
        long deadline = System.currentTimeMillis() + timeoutMs;
        while(pending.get() > 0 && System.currentTimeMillis() < deadline){
            try { Thread.sleep(2); } catch(InterruptedException e){ return; }
        }
    }
    public int pending(){ return pending.get(); }
    public int workers(){ return workers.size(); }

    public void shutdown(){
        running.set(false);
        for(Thread t : workers) t.interrupt();
        workers.clear();
    }
}
