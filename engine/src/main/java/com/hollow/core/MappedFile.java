package com.hollow.core;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Memory-mapped file I/O (MEM-017): zero-copy reads of large asset files
 *  (mesh HVM, textures HVT, long audio). Falls back to a plain heap read when
 *  mapping is unavailable (restricted environments). Desktop + Android identical
 *  through java.nio. */
public final class MappedFile {
    private final MappedByteBuffer buffer;   // null in fallback mode
    private final byte[] fallback;
    private final long size;
    private final Path path;

    private MappedFile(Path path, MappedByteBuffer buf, byte[] fallback, long size){
        this.path = path;
        this.buffer = buf;
        this.fallback = fallback;
        this.size = size;
    }

    public static MappedFile open(String filePath) throws IOException{
        Path p = Path.of(filePath);
        long size = Files.size(p);
        if(size > Integer.MAX_VALUE - 8) throw new IOException("file too large to map: " + filePath);
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)){
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
            buf.load(); // hint: pull into memory (page cache backed)
            return new MappedFile(p, buf, null, size);
        } catch(IOException | OutOfMemoryError | SecurityException e){
            Log.w("MappedFile", "map failed (" + e + "), fallback read: " + filePath);
            return new MappedFile(p, null, Files.readAllBytes(p), size);
        }
    }

    public long size(){ return size; }
    public boolean isMapped(){ return buffer != null; }

    /** read the whole file (copy; still avoids the double-buffer of readAllBytes when mapped) */
    public byte[] readAll(){
        if(buffer != null){
            buffer.position(0);
            byte[] out = new byte[(int) size];
            buffer.get(out);
            buffer.position(0);
            return out;
        }
        return fallback.clone();
    }

    /** read a slice [ofs, ofs+len) */
    public byte[] read(int ofs, int len){
        if(ofs < 0 || len < 0 || (long)ofs + len > size) throw new IndexOutOfBoundsException("read " + ofs + "+" + len + " of " + size);
        if(buffer != null){
            byte[] out = new byte[len];
            buffer.position(ofs);
            buffer.get(out);
            return out;
        }
        byte[] out = new byte[len];
        System.arraycopy(fallback, ofs, out, 0, len);
        return out;
    }

    public int readIntLE(int ofs){
        if(ofs < 0 || (long)ofs + 4 > size) throw new IndexOutOfBoundsException("readIntLE " + ofs + " of " + size);
        if(buffer != null){
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
            buffer.position(ofs);
            return buffer.getInt();
        }
        return (fallback[ofs] & 0xFF) | (fallback[ofs+1] & 0xFF) << 8
             | (fallback[ofs+2] & 0xFF) << 16 | (fallback[ofs+3] & 0xFF) << 24;
    }

    /** release OS mapping (portable: drop the reference; the JVM unmaps on GC) */
    public void close(){
        // MappedByteBuffer has no explicit unmap in Java; dropping the reference is the
        // portable contract. Explicit System.gc() is deliberately NOT called.
    }

    @Override public String toString(){ return "MappedFile[" + path.getFileName() + " " + size + "B mapped=" + isMapped() + "]"; }
}
