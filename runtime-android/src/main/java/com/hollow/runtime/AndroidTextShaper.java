package com.hollow.runtime;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.hollow.assets.ImageData;
import com.hollow.core.UiDrawList;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Android text shaper — full Arabic/Persian/RTL correctness for free, because Android's
 * text stack (HarfBuzz + ICU) does the shaping internally. Whole strings are rendered to
 * cached bitmap textures (correct joining/ligatures) and emitted as quads.
 */
final class AndroidTextShaper extends com.hollow.ui.TextShaper {
    private static final int CACHE_MAX = 96;
    private final Map<String, ImageData> cache = new LinkedHashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);

    AndroidTextShaper(){
        paint.setTypeface(Typeface.DEFAULT);
        measure.setTypeface(Typeface.DEFAULT);
    }

    private ImageData rasterize(String text, float fontSize, int rgba){
        String key = text + "|" + ((int)fontSize) + "|" + rgba;
        ImageData cached = cache.get(key);
        if(cached != null) return cached;
        synchronized(cache){
            cached = cache.get(key);
            if(cached != null) return cached;
            paint.setTextSize(fontSize);
            paint.setColor(rgba);
            measure.setTextSize(fontSize);
            float w = Math.max(1f, measure.measureText(text));
            float pad = (float)Math.ceil(fontSize * 0.25f);
            int bw = Math.max(1, (int)Math.ceil(w + pad*2));
            int bh = Math.max(1, (int)Math.ceil(fontSize * 1.5f + pad*2));
            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            paint.setTextSize(fontSize);
            paint.setColor(rgba);
            float baseline = bh - pad - fontSize * 0.25f;
            c.drawText(text, pad, baseline, paint);
            int[] px = new int[bw*bh];
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh);
            bmp.recycle();
            ImageData img = new ImageData(bw, bh, px);
            img.name = key;
            if(cache.size() >= CACHE_MAX){
                String oldest = cache.keySet().iterator().next();
                cache.remove(oldest);
                com.hollow.assets.AssetDB db = assets();
                if(db != null) { /* cache eviction ok — renderer re-uploads lazily */ }
            }
            cache.put(key, img);
            com.hollow.assets.AssetDB db = assets();
            if(db != null) db.registerImage("@text:" + Integer.toHexString(key.hashCode()), img);
            return img;
        }
    }
    private com.hollow.assets.AssetDB assets(){
        com.hollow.game.GameApp app = com.hollow.game.GameApp.instance;
        return app != null && app.services() != null ? app.services().assets : null;
    }

    @Override public float measure(String text, float fontSize){
        if(text == null) return 0;
        measure.setTextSize(fontSize);
        return measure.measureText(text);
    }

    @Override public void draw(UiDrawList list, String text, float x, float y, float fontSize,
                               int rgba, int align, float maxWidth){
        if(list == null || text == null || text.isEmpty()) return;
        float lineHeight = fontSize * 1.5f;
        // simple wrap
        if(maxWidth > 0){
            String[] words = text.split("(?<=\\s)");
            StringBuilder line = new StringBuilder();
            float cursor = 0;
            int lineIdx = 0;
            for(String word : words){
                float ww = measure(word.trim(), fontSize);
                if(cursor + ww > maxWidth && line.length() > 0){
                    drawLine(list, line.toString(), x, y + lineIdx*lineHeight, fontSize, rgba, align, maxWidth);
                    lineIdx++;
                    line.setLength(0);
                    cursor = 0;
                }
                line.append(word);
                cursor += ww;
            }
            if(line.length() > 0) drawLine(list, line.toString(), x, y + lineIdx*lineHeight, fontSize, rgba, align, maxWidth);
        } else {
            drawLine(list, text, x, y, fontSize, rgba, align, 0);
        }
    }

    private void drawLine(UiDrawList list, String line, float x, float y, float fontSize,
                          int rgba, int align, float maxWidth){
        if(line.isEmpty()) return;
        ImageData img = rasterize(line, fontSize, rgba);
        if(img == null) return;
        String key = "@text:" + Integer.toHexString((line + "|" + ((int)fontSize) + "|" + rgba).hashCode());
        float w = img.width * (fontSize / (fontSize)) / 1f; // natural size
        float h = img.height;
        float dx = x, dy = y;
        if(align == ALIGN_CENTER && maxWidth > 0) dx = x + (maxWidth - img.width) * 0.5f;
        else if(align == ALIGN_RIGHT && maxWidth > 0) dx = x + maxWidth - img.width;
        list.addQuad(key, dx, dy, img.width, img.height, 0, 0, 1, 1, rgba);
    }
}
