package com.hollow.assets;

import java.util.ArrayList;
import java.util.List;

/** Shelf-based sprite atlas packer with 2px gutters, power-of-two canvas. */
public final class AtlasPacker {
    public static final int GUTTER = 2;

    public static final class Item {
        public final String name;
        public final ImageData image;
        public int x, y;
        public Item(String n, ImageData i){ name=n; image=i; }
    }
    public static final class Result {
        public ImageData canvas;
        public List<Item> placed = new ArrayList<>();
        public int width, height;
        public int dropped = 0;
    }

    /** pack by descending height (shelf); canvas grows to next power of two on overflow */
    public static Result pack(List<Item> input, int maxSize){
        List<Item> items = new ArrayList<>(input);
        items.sort((a,b) -> Integer.compare(b.image.height, a.image.height));
        int cap = 256;
        while(cap <= maxSize){
            Result r = tryPack(items, cap, cap);
            if(r != null) return r;
            cap *= 2;
        }
        Result r = tryPack(items, maxSize, maxSize);
        if(r != null) return r;
        // drop overflow items (report)
        Result partial = new Result();
        List<Item> allowed = new ArrayList<>();
        int budget = maxSize*maxSize;
        for(Item it : items){
            int area = (it.image.width+GUTTER)*(it.image.height+GUTTER);
            if(area <= budget){ allowed.add(it); budget -= area; }
            else partial.dropped++;
        }
        Result pr = tryPack(allowed, maxSize, maxSize);
        if(pr == null) pr = new Result();
        pr.dropped = partial.dropped;
        return pr;
    }

    private static Result tryPack(List<Item> items, int W, int H){
        Result r = new Result();
        int[] shelfY = new int[W+1];       // skyline heights
        int x = 0, y = 0, shelfH = 0;
        for(Item it : items){
            int w = it.image.width + GUTTER*2, h = it.image.height + GUTTER*2;
            if(w > W || h > H) return null;
            if(x + w > W){
                // next shelf
                y += shelfH;
                x = 0;
                shelfH = 0;
            }
            if(y + h > H) return null;
            it.x = x + GUTTER; it.y = y + GUTTER;
            x += w;
            shelfH = Math.max(shelfH, h);
            r.placed.add(it);
        }
        // build canvas
        int size = 1;
        while(size < Math.max(W, H)) size <<= 1;
        // choose tight power-of-two square
        int need = 1;
        for(Item it : r.placed) need = Math.max(need, Math.max(it.x + it.image.width, it.y + it.image.height));
        while(need > size) size <<= 1;
        ImageData canvas = new ImageData(size, size, new int[size*size]);
        canvas.name = "atlas";
        for(Item it : r.placed){
            for(int py=0; py<it.image.height; py++){
                System.arraycopy(it.image.pixels, py*it.image.width, canvas.pixels, (it.y+py)*size + it.x, it.image.width);
            }
        }
        r.canvas = canvas;
        r.width = size; r.height = size;
        return r;
    }
}
