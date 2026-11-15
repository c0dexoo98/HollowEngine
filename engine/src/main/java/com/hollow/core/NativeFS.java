package com.hollow.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Desktop/CLI file system rooted at a base directory. */
public final class NativeFS implements FileSystem {
    private final Path root;
    public NativeFS(String baseDir){ this.root = Paths.get(baseDir).toAbsolutePath().normalize(); }
    public Path resolve(String path){ return root.resolve(path).normalize(); }
    public String rootDir(){ return root.toString(); }

    private Path safe(String path){
        Path p = resolve(path);
        if(!p.startsWith(root)) throw new SecurityException("path escapes project root: " + path);
        return p;
    }
    @Override public boolean exists(String path){ return Files.exists(safe(path)); }
    @Override public boolean isDirectory(String path){ return Files.isDirectory(safe(path)); }
    @Override public byte[] read(String path) throws IOException { return Files.readAllBytes(safe(path)); }
    @Override public List<String> list(String dir) throws IOException {
        List<String> out = new ArrayList<>();
        Path d = safe(dir);
        if(!Files.isDirectory(d)) return out;
        try(Stream<Path> s = Files.list(d)){
            s.forEach(p -> out.add(p.getFileName().toString()));
        }
        out.sort(String::compareTo);
        return out;
    }
    @Override public void write(String path, byte[] data) throws IOException {
        Path p = safe(path);
        Files.createDirectories(p.getParent());
        Files.write(p, data);
    }
    @Override public void delete(String path) throws IOException { Files.deleteIfExists(safe(path)); }
    @Override public boolean move(String from, String to){
        try {
            Files.move(safe(from), safe(to),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);   // N8
            return true;
        } catch(AtomicMoveNotSupportedException e){
            try {
                Files.move(safe(from), safe(to), StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch(Exception ex){ return false; }
        } catch(Exception e){ return false; }
    }

    /** recursive walk relative paths under dir */
    public List<String> walk(String dir) throws IOException {
        List<String> out = new ArrayList<>();
        Path d = safe(dir);
        if(!Files.isDirectory(d)) return out;
        try(Stream<Path> s = Files.walk(d)){
            s.filter(Files::isRegularFile).forEach(p -> out.add(root.relativize(p).toString().replace('\\','/')));
        }
        out.sort(String::compareTo);
        return out;
    }
}
