package com.hollow.assets;

import com.hollow.core.FileSystem;

/** Decodes PNG/JPEG bytes to RGBA pixels. Platform implementations register here. */
public abstract class ImageDecoder {
    private static ImageDecoder instance;
    public static ImageDecoder get(){ return instance; }
    public static void set(ImageDecoder d){ instance = d; }

    public abstract ImageData decode(byte[] bytes, String name);

    public ImageData decodeFile(String path, FileSystem fs){
        try { return decode(fs.read(path), path); }
        catch(Exception e){
            com.hollow.core.Log.e("ImageDecoder", "failed to decode " + path + " — using 1x1 magenta fallback");
            return new ImageData(1, 1, new int[]{0xFFFF00FF});
        }
    }
}
