package com.hollow.core;

import java.util.NoSuchElementException;

/** Fixed-capacity circular buffer (MEM). O(1) offer/poll, overwrites oldest in ring mode.
 *  Used for: input history, audio staging, debug streams, command history. */
public final class RingBuffer<T> {
    private final Object[] data;
    private int head = 0; // next write
    private int tail = 0; // next read
    private int size = 0;
    private final boolean overwrite;
    private long totalWritten = 0, totalDropped = 0;

    public RingBuffer(int capacity, boolean overwriteOldest){
        if(capacity <= 0) throw new IllegalArgumentException("capacity");
        this.data = new Object[capacity];
        this.overwrite = overwriteOldest;
    }
    public RingBuffer(int capacity){ this(capacity, false); }

    /** @return false only when full and overwrite==false */
    public boolean offer(T t){
        if(size == data.length){
            if(!overwrite){ totalDropped++; return false; }
            tail = (tail + 1) % data.length; // drop oldest
            size--;
            totalDropped++;
        }
        data[head] = t;
        head = (head + 1) % data.length;
        size++;
        totalWritten++;
        return true;
    }
    @SuppressWarnings("unchecked")
    public T poll(){
        if(size == 0) return null;
        T t = (T) data[tail];
        data[tail] = null;
        tail = (tail + 1) % data.length;
        size--;
        return t;
    }
    @SuppressWarnings("unchecked")
    public T peek(){
        if(size == 0) return null;
        return (T) data[tail];
    }
    /** newest element without consuming */
    @SuppressWarnings("unchecked")
    public T peekLast(){
        if(size == 0) return null;
        return (T) data[(head - 1 + data.length) % data.length];
    }
    @SuppressWarnings("unchecked")
    public T get(int index){
        if(index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return (T) data[(tail + index) % data.length];
    }
    public void clear(){
        java.util.Arrays.fill(data, null);
        head = tail = size = 0;
    }
    public int size(){ return size; }
    public int capacity(){ return data.length; }
    public boolean isEmpty(){ return size == 0; }
    public boolean isFull(){ return size == data.length; }
    public long totalWritten(){ return totalWritten; }
    public long totalDropped(){ return totalDropped; }
    public String stats(){ return "ring[cap=" + data.length + " size=" + size + " written=" + totalWritten + " dropped=" + totalDropped + "]"; }
}
