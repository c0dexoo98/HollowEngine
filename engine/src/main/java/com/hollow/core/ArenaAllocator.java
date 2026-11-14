package com.hollow.core;

/** Bump-pointer arena allocator (MEM). Ultra-cheap allocations for per-frame scratch data;
 *  rewind to a mark or reset the whole arena at frame end. Not a GC substitute for long-lived data. */
public final class ArenaAllocator {
    private final byte[] memory;
    private int offset = 0;
    private int highWater = 0;
    private boolean active = true;

    public ArenaAllocator(int bytes){
        this.memory = new byte[Math.max(64, bytes)];
    }

    public int mark(){ requireActive(); return offset; }
    public void rewind(int mark){
        requireActive();
        if(mark < 0 || mark > offset) throw new IllegalArgumentException("bad mark " + mark);
        offset = mark;
    }

    public int alloc(int bytes, int alignment){
        requireActive();
        if(alignment <= 0 || (alignment & (alignment - 1)) != 0)
            throw new IllegalArgumentException("alignment must be a positive power of two: " + alignment);
        int aligned = (offset + (alignment - 1)) & ~(alignment - 1);
        if(aligned < 0 || aligned > memory.length) throw new OutOfMemoryError("arena exhausted");
        int end = aligned + bytes;
        if(end < 0 || end > memory.length) throw new OutOfMemoryError("arena exhausted: need " + bytes + " have " + (memory.length - aligned));
        offset = end;
        if(offset > highWater) highWater = offset;
        return aligned;
    }
    public int alloc(int bytes){ return alloc(bytes, 8); }

    public void putInt(int addr, int v){
        memory[addr] = (byte) v; memory[addr+1] = (byte)(v >>> 8);
        memory[addr+2] = (byte)(v >>> 16); memory[addr+3] = (byte)(v >>> 24);
    }
    public int getInt(int addr){
        return (memory[addr] & 0xFF) | ((memory[addr+1] & 0xFF) << 8)
             | ((memory[addr+2] & 0xFF) << 16) | ((memory[addr+3] & 0xFF) << 24);
    }
    public void putFloat(int addr, float f){ putInt(addr, Float.floatToRawIntBits(f)); }
    public float getFloat(int addr){ return Float.intBitsToFloat(getInt(addr)); }
    public void putBytes(int addr, byte[] src, int len){ System.arraycopy(src, 0, memory, addr, Math.min(len, memory.length - addr)); }
    public byte[] backing(){ return memory; }

    public void reset(){
        requireActive();
        offset = 0;
    }
    /** deactivates the arena (memory becomes GC-eligible) */
    public void dispose(){ active = false; }

    public int used(){ return offset; }
    public int capacity(){ return memory.length; }
    public int highWaterMark(){ return highWater; }
    public float usage(){ return (float) offset / memory.length; }
    public String stats(){ return "arena[used=" + offset + "/" + memory.length + " high=" + highWater + "]"; }

    private void requireActive(){ if(!active) throw new IllegalStateException("arena disposed"); }
}
