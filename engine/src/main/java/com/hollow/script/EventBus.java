package com.hollow.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deferred event bus: fire() queues, flush() delivers once per frame in order (deterministic). */
public final class EventBus {
    public interface Listener { void onEvent(String name, Map<String,Object> data); }

    private static final class Queued { final String name; final Map<String,Object> data;
        Queued(String n, Map<String,Object> d){ name=n; data=d; } }

    private final List<Queued> queue = new ArrayList<>();
    private final Map<String, List<Listener>> listeners = new LinkedHashMap<>();

    public void listen(String name, Listener l){
        listeners.computeIfAbsent(name, k -> new ArrayList<>()).add(l);
    }
    /** P8: remove a listener — components that subscribe on onAttach must unsubscribe on
     *  onDetach or every spawned/destroyed entity leaks its lambda into the bus forever. */
    public void unlisten(String name, Listener l){
        List<Listener> ls = listeners.get(name);
        if(ls != null) ls.remove(l);
    }
    public void fire(String name){ fire(name, null); }
    public void fire(String name, Map<String,Object> data){
        queue.add(new Queued(name, data));
        if(queue.size() > 4096) queue.remove(0); // safety
    }
    /** deliver all queued events */
    public void flush(){
        // events fired during flush go to next frame (swap buffers)
        List<Queued> current = new ArrayList<>(queue);
        queue.clear();
        for(Queued q : current){
            List<Listener> ls = listeners.get(q.name);
            if(ls == null) continue;
            for(int i=0;i<ls.size();i++){
                try { ls.get(i).onEvent(q.name, q.data); }
                catch(Exception e){ com.hollow.core.Log.e("EventBus", "listener error for '" + q.name + "'", e); }
            }
        }
    }
    public int pending(){ return queue.size(); }
    public void clear(){ queue.clear(); }
}
