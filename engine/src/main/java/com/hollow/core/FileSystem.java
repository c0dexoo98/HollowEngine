package com.hollow.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Abstract file system: desktop uses NativeFS, Android uses asset manager.
 *  All engine asset paths are '/'-separated and relative to the project root. */
public interface FileSystem {
    boolean exists(String path);
    byte[] read(String path) throws IOException;
    List<String> list(String dir) throws IOException;      // names, not recursive
    boolean isDirectory(String path);
    void write(String path, byte[] data) throws IOException;
    void delete(String path) throws IOException;
    /** N8: atomic rename where the FS supports it; safe copy+delete fallback otherwise. */
    default boolean move(String from, String to){
        try { write(to, read(from)); delete(from); return true; }
        catch(Exception e){ return false; }
    }

    default String readText(String path) throws IOException { return new String(read(path), java.nio.charset.StandardCharsets.UTF_8); }
    default InputStream open(String path) throws IOException { return new java.io.ByteArrayInputStream(read(path)); }
}
