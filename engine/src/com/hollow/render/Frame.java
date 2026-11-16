package com.hollow.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

/** HollowEngine 2 — frame buffer: ARGB pixels + NDC-z depth buffer,
 *  written as PNG. fillRatio() is the blank-frame detector used by
 *  tests and the editor. */
public final class Frame {

    public final int w, h;
    public final int[] pixels;          // ARGB
    public final float[] depth;         // NDC z, smaller = closer
    public int background;

    public Frame(int w, int h){
        this.w = w; this.h = h;
        pixels = new int[w * h];
        depth = new float[w * h];
    }

    public void clear(int argb){
        background = argb;
        java.util.Arrays.fill(pixels, argb);
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
    }

    /** fraction of pixels that differ from the background — blank-frame detector */
    public double fillRatio(){
        int n = 0;
        for(int p : pixels) if(p != background) n++;
        return (double)n / (w * h);
    }

    public int distinctColors(){
        java.util.Set<Integer> s = new java.util.HashSet<>();
        for(int p : pixels) s.add(p & 0xFFFFFF);
        return s.size();
    }

    public boolean writePNG(Path out){
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, w, h, pixels, 0, w);
            File f = out.toFile();
            File parent = f.getParentFile();
            if(parent != null) parent.mkdirs();
            return ImageIO.write(img, "png", f);
        } catch(Exception e){
            throw new RuntimeException("writePNG failed: " + e.getMessage(), e);
        }
    }
}
