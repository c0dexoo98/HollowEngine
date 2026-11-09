package com.hollow.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.hollow.assets.ImageData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Platform implementations for Android: FS over assets (+files dir writes), image decode. */
final class AndroidPlatform {

    private AndroidPlatform() {}

    /** read-only asset FS with writable overlay in filesDir (for saves) */
    static final class AssetFS implements com.hollow.core.FileSystem {
        private final Context ctx;
        private final String base;       // "project"
        private final OverlayFS overlay;

        AssetFS(Context ctx, String base){
            this.ctx = ctx;
            this.base = base;
            this.overlay = new OverlayFS(new java.io.File(ctx.getFilesDir(), "write").getPath());
        }
        private String p(String path){ return base + "/" + path; }

        @Override public boolean exists(String path){
            try { ctx.getAssets().open(p(path)).close(); return true; }
            catch(IOException e){ return overlay.exists(path); }
        }
        @Override public byte[] read(String path) throws IOException {
            try (InputStream in = ctx.getAssets().open(p(path))){
                ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
                byte[] buf = new byte[8192]; int n;
                while((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } catch(IOException e){
                return overlay.read(path);
            }
        }
        @Override public List<String> list(String dir){
            List<String> out = new ArrayList<>();
            try {
                String[] names = ctx.getAssets().list(p(dir));
                if(names != null) for(String n : names) out.add(n);
            } catch(IOException ignored){}
            for(String n : overlay.list(dir)) if(!out.contains(n)) out.add(n);
            out.sort(String::compareTo);
            return out;
        }
        @Override public boolean isDirectory(String path){
            try {
                String[] names = ctx.getAssets().list(p(path));
                return names != null && names.length > 0;
            } catch(IOException e){ return overlay.isDirectory(path); }
        }
        @Override public void write(String path, byte[] data) throws IOException { overlay.write(path, data); }
        @Override public void delete(String path) throws IOException { overlay.delete(path); }
    }

    /** simple files-dir backed overlay (saves etc.) */
    static final class OverlayFS implements com.hollow.core.FileSystem {
        private final java.io.File root;
        OverlayFS(String rootPath){ this.root = new java.io.File(rootPath); }
        private java.io.File f(String path){ return new java.io.File(root, path); }
        @Override public boolean exists(String path){ return f(path).exists(); }
        @Override public byte[] read(String path) throws IOException {
            return java.nio.file.Files.readAllBytes(f(path).toPath());
        }
        @Override public List<String> list(String dir){
            List<String> out = new ArrayList<>();
            java.io.File d = f(dir);
            String[] names = d.list();
            if(names != null) for(String n : names) out.add(n);
            return out;
        }
        @Override public boolean isDirectory(String path){ return f(path).isDirectory(); }
        @Override public void write(String path, byte[] data) throws IOException {
            java.io.File t = f(path);
            t.getParentFile().mkdirs();
            java.nio.file.Files.write(t.toPath(), data);
        }
        @Override public void delete(String path) throws IOException { f(path).delete(); }
    }

    static final class AndroidImageDecoder extends com.hollow.assets.ImageDecoder {
        static void install(){
            com.hollow.assets.ImageDecoder.set(new AndroidImageDecoder());
        }
        @Override public ImageData decode(byte[] bytes, String name){
            try {
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if(bmp == null) throw new IllegalArgumentException("decode failed: " + name);
                int w = bmp.getWidth(), h = bmp.getHeight();
                int[] px = new int[w*h];
                bmp.getPixels(px, 0, w, 0, 0, w, h);
                // Bitmap ARGB -> our (a<<24)|(r<<16)|(g<<8)|b is identical layout
                bmp.recycle();
                ImageData d = new ImageData(w, h, px);
                d.name = name;
                return d;
            } catch(Exception e){
                com.hollow.core.Log.e("AndroidImageDecoder", "failed " + name, e);
                return new ImageData(1, 1, new int[]{0xFFFF00FF});
            }
        }
    }
}
