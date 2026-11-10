package com.hollow.core;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Weak/soft object cache (MEM-014): decoded assets (textures, audio, meshes) live
 *  in a soft-referenced cache — the JVM may reclaim them under memory pressure and
 *  they are transparently re-decoded on the next access. Keys stay strong; values
 *  do not. Complements AssetDB (strong cache + refcounting) for cold data. */
public final class WeakPool<K, V> {
    private final Map<K, SoftEntry> map = new ConcurrentHashMap<>();
    private final ReferenceQueue<V> queue = new ReferenceQueue<>();
    private final Loader<K, V> loader;
    private int hits = 0, misses = 0, evicted = 0;

    public interface Loader<K, V> { V load(K key); }

    public WeakPool(Loader<K, V> loader){ this.loader = loader; }

    private final class SoftEntry extends SoftReference<V> {
        final K key;
        SoftEntry(K key, V value){ super(value, queue); this.key = key; }
    }

    /** get-or-decode */
    public V get(K key){
        drainQueue();
        SoftEntry e = map.get(key);
        if(e != null){
            V v = e.get();
            if(v != null){ hits++; return v; }
            map.remove(key);
        }
        misses++;
        V v = loader.load(key);
        if(v != null) map.put(key, new SoftEntry(key, v));
        return v;
    }

    /** peek without loading */
    public V peek(K key){
        SoftEntry e = map.get(key);
        return e != null ? e.get() : null;
    }

    public void invalidate(K key){ map.remove(key); drainQueue(); }
    public void clear(){ map.clear(); drainQueue(); }

    /** number of entries currently holding live values */
    public int liveCount(){
        drainQueue();
        int n = 0;
        for(SoftEntry e : map.values()) if(e.get() != null) n++;
        return n;
    }
    public int size(){ drainQueue(); return map.size(); }
    public int hits(){ return hits; }
    public int misses(){ return misses; }
    public int evicted(){ evicted = countDead(); return evicted; }

    private int countDead(){
        int n = 0;
        for(SoftEntry e : map.values()) if(e.get() == null) n++;
        return n;
    }

    /** drop entries whose value was reclaimed */
    @SuppressWarnings("unchecked")
    private void drainQueue(){
        SoftEntry e;
        while((e = (SoftEntry) queue.poll()) != null){
            map.remove(e.key, e);
        }
    }

    public String stats(){
        return "weakPool[size=" + size() + " live=" + liveCount() + " hits=" + hits + " misses=" + misses + "]";
    }
}
