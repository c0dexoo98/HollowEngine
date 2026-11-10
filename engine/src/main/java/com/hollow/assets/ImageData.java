package com.hollow.assets;

/** Decoded RGBA8 image. Decoding is platform-specific (ImageIO on desktop, Bitmap on Android). */
public final class ImageData {
    public final int width, height;
    public final int[] pixels;   // RGBA8888, row-major
    public String name = "image";

    public ImageData(int w, int h, int[] px){ width=w; height=h; pixels=px; }

    /** average color (for fallback material tint) */
    public float[] avgColor(){
        long r=0,g=0,b=0; int n=pixels.length;
        for(int p : pixels){ r+=(p>>16)&255; g+=(p>>8)&255; b+=p&255; }
        return new float[]{ r/(255f*n), g/(255f*n), b/(255f*n) };
    }

    // ---- binary .hvt format ----
    public static final int HVT_MAGIC = 0x48565431; // 'HVT1'

    public void save(String path, com.hollow.core.FileSystem fs) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(1 << 16);
        java.io.DataOutputStream d = new java.io.DataOutputStream(buf);
        d.writeInt(HVT_MAGIC);
        d.writeInt(width);
        d.writeInt(height);
        for(int p : pixels) d.writeInt(p);
        d.close();
        fs.write(path, buf.toByteArray());
    }
    public static ImageData load(String path, com.hollow.core.FileSystem fs) throws java.io.IOException {
        byte[] raw = fs.read(path);
        java.io.DataInputStream d = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw));
        if(d.readInt() != HVT_MAGIC) throw new java.io.IOException("bad HVT magic: " + path);
        int w = d.readInt(), h = d.readInt();
        int[] px = new int[w*h];
        for(int i=0;i<w*h;i++) px[i] = d.readInt();
        d.close();
        return new ImageData(w, h, px);
    }

    /** generate mip chain (box filter) — returns mip 0..N as ImageData[] */
    public ImageData[] mipChain(){
        java.util.List<ImageData> mips = new java.util.ArrayList<>();
        ImageData cur = this;
        mips.add(cur);
        while(cur.width > 1 || cur.height > 1){
            int nw = Math.max(1, cur.width/2), nh = Math.max(1, cur.height/2);
            int[] out = new int[nw*nh];
            for(int y=0;y<nh;y++){
                for(int x=0;x<nw;x++){
                    int x0=Math.min(cur.width-1,x*2), x1=Math.min(cur.width-1,x*2+1);
                    int y0=Math.min(cur.height-1,y*2), y1=Math.min(cur.height-1,y*2+1);
                    int a=cur.pixels[y0*cur.width+x0], b=cur.pixels[y0*cur.width+x1];
                    int c=cur.pixels[y1*cur.width+x0], e=cur.pixels[y1*cur.width+x1];
                    int r=(((a>>16)&255)+((b>>16)&255)+((c>>16)&255)+((e>>16)&255))/4;
                    int g=(((a>>8)&255)+((b>>8)&255)+((c>>8)&255)+((e>>8)&255))/4;
                    int bl=((a&255)+(b&255)+(c&255)+(e&255))/4;
                    int al=(((a>>>24)&255)+((b>>>24)&255)+((c>>>24)&255)+((e>>>24)&255))/4;
                    out[y*nw+x] = (al<<24)|(r<<16)|(g<<8)|bl;
                }
            }
            cur = new ImageData(nw, nh, out);
            mips.add(cur);
        }
        return mips.toArray(new ImageData[0]);
    }
}
