package com.hollow.assets;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/** Desktop image decoder (CLI / headless). Android registers its own BitmapFactory-based decoder. */
public final class DesktopImageDecoder extends ImageDecoder {
    public static void install(){ ImageDecoder.set(new DesktopImageDecoder()); }

    @Override public ImageData decode(byte[] bytes, String name){
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if(img == null) throw new IllegalArgumentException("unreadable image: " + name);
            int w = img.getWidth(), h = img.getHeight();
            int[] px = new int[w*h];
            if(img.getType() == BufferedImage.TYPE_INT_ARGB || img.getType() == BufferedImage.TYPE_INT_RGB){
                img.getRGB(0, 0, w, h, px, 0, w);
                if(img.getType() == BufferedImage.TYPE_INT_RGB){
                    for(int i=0;i<px.length;i++) px[i] |= 0xFF000000;
                }
            } else {
                // general path via raster
                for(int y=0;y<h;y++){
                    for(int x=0;x<w;x++){
                        px[y*w+x] = img.getRGB(x, y);
                    }
                }
            }
            ImageData d = new ImageData(w, h, px);
            d.name = name;
            return d;
        } catch(Exception e){
            com.hollow.core.Log.e("ImageDecoder", "decode failed: " + name, e);
            return new ImageData(1, 1, new int[]{0xFFFF00FF});
        }
    }
}
