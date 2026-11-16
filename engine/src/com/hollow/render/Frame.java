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

    // ------------------------- HUD text + post FX -------------------------

    /** width of a string in pixels at the given scale (6px per char) */
    public int stringWidth(String s, int scale){
        return s.length() * 6 * scale;
    }

    /** draw text with the 5x7 bitmap font; opaque pixels only.
     *  Glyph rows store bit4 = leftmost pixel. */
    public void drawString(String s, int x, int y, int color, int scale){
        for(int ci = 0; ci < s.length(); ci++){
            int[] rows = Font5x7.glyph(s.charAt(ci));
            int gx = x + ci * 6 * scale;
            for(int ry = 0; ry < 7; ry++){
                for(int rx = 0; rx < 5; rx++){
                    if((rows[ry] & (1 << (4 - rx))) == 0) continue;
                    for(int sy = 0; sy < scale; sy++){
                        for(int sx = 0; sx < scale; sx++){
                            int px = gx + rx * scale + sx;
                            int py = y + ry * scale + sy;
                            if(px < 0 || py < 0 || px >= w || py >= h) continue;
                            pixels[py * w + px] = color;
                        }
                    }
                }
            }
        }
    }

    public void drawString(String s, int x, int y, int color){
        drawString(s, x, y, color, 1);
    }

    /** darken toward the edges (horror post-FX); strength 0..1 */
    public void vignette(float strength){
        float cx = w / 2f, cy = h / 2f;
        float maxD = (float)Math.sqrt(cx * cx + cy * cy);
        for(int y = 0; y < h; y++){
            for(int x = 0; x < w; x++){
                float dx = (x - cx) / maxD, dy = (y - cy) / maxD;
                float d = (float)Math.sqrt(dx * dx + dy * dy);
                float f = strength * Math.max(0, (d - 0.55f) / 0.45f);
                if(f <= 0) continue;
                f = Math.min(1, f * f);
                int p = pixels[y * w + x];
                int r = (int)(((p >> 16) & 0xFF) * (1 - f));
                int g = (int)(((p >> 8) & 0xFF) * (1 - f));
                int b = (int)((p & 0xFF) * (1 - f));
                pixels[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
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
