package com.hollow.render;

import com.hollow.core.ArenaAllocator;
import com.hollow.core.NioUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Mesh staging (MEM-019): packs vertex/index data into an ArenaAllocator block,
 *  then exposes it as direct NIO buffers for a single one-shot GPU upload
 *  (glBufferData with one allocation, no per-stream garbage). Arena is rewound
 *  after upload so the same block serves the next mesh — streaming imports and
 *  mesh combine ops run allocation-free. */
public final class MeshStager {
    public static final int ALIGN = 4;

    private final ArenaAllocator arena;
    private FloatBuffer floatView;
    private ByteBuffer byteView;

    public MeshStager(int capacityBytes){
        this.arena = new ArenaAllocator(capacityBytes);
        com.hollow.core.MemoryStats.track("meshStager", arena::stats);
    }

    // ---------------- staging API ----------------

    public static final class Staged {
        public final int addr;          // arena offset
        public final int vertexCount;
        public final int indexCount;
        Staged(int addr, int vertexCount, int indexCount){
            this.addr = addr; this.vertexCount = vertexCount; this.indexCount = indexCount;
        }
    }

    /** stage interleaved vertex data (stride in floats); returns handle for buffer views */
    public int stageVertices(float[] data, int floatCount){
        int addr = arena.alloc(floatCount * 4, ALIGN);
        int base = addr;
        byte[] mem = arena.backing();
        for(int i=0;i<floatCount;i++){
            int bits = Float.floatToRawIntBits(data[i]);
            mem[base++] = (byte) bits;
            mem[base++] = (byte)(bits >>> 8);
            mem[base++] = (byte)(bits >>> 16);
            mem[base++] = (byte)(bits >>> 24);
        }
        return addr;
    }

    /** stage 16-bit indices */
    public int stageIndices(short[] data, int count){
        int addr = arena.alloc(count * 2, ALIGN);
        byte[] mem = arena.backing();
        for(int i=0;i<count;i++){
            mem[addr++] = (byte)(data[i] & 0xFF);
            mem[addr++] = (byte)((data[i] >>> 8) & 0xFF);
        }
        return addr;
    }

    /** stage raw bytes (weights, bones, custom attributes) */
    public int stageBytes(byte[] data, int len){
        int addr = arena.alloc(len, ALIGN);
        arena.putBytes(addr, data, len);
        return addr;
    }

    // ---------------- upload views ----------------

    /** direct FloatBuffer over a staged vertex block (position 0, limit = count) */
    public FloatBuffer vertexBuffer(int addr, int floatCount){
        if(floatView == null || floatView.capacity() < arena.capacity() / 4){
            floatView = com.hollow.core.NioUtil.floatBuffer(arena.capacity() / 4);
        }
        // copy arena block into the reusable direct buffer (one copy, zero alloc after warm)
        floatView.clear();
        byte[] mem = arena.backing();
        int p = addr;
        for(int i=0;i<floatCount;i++){
            int bits = (mem[p] & 0xFF) | (mem[p+1] & 0xFF) << 8 | (mem[p+2] & 0xFF) << 16 | (mem[p+3] & 0xFF) << 24;
            floatView.put(i, Float.intBitsToFloat(bits));
            p += 4;
        }
        floatView.position(0).limit(floatCount);
        return floatView;
    }

    /** direct ByteBuffer over a staged block */
    public ByteBuffer byteBuffer(int addr, int len){
        if(byteView == null || byteView.capacity() < arena.capacity()){
            byteView = com.hollow.core.NioUtil.byteBuffer(arena.capacity());
        }
        byteView.clear();
        byteView.put(arena.backing(), addr, len);
        byteView.position(0).limit(len);
        return byteView;
    }

    /** upload helper contract: caller does glBufferData(GL_*, limit(), usage) with the
     *  returned view, then MUST call {@link #endFrame()} to rewind the arena. */
    public void rewind(){ arena.reset(); }
    public void rewindTo(int mark){ arena.rewind(mark); }
    public int mark(){ return arena.mark(); }

    public int used(){ return arena.used(); }
    public int capacity(){ return arena.capacity(); }
    public int highWater(){ return arena.highWaterMark(); }
    public String stats(){ return arena.stats(); }
}
