package com.hollow.core;

/** UI draw command buffer — filled by ui.UIManager, consumed by renderer. Platform-neutral. */
public final class UiDrawList {
    /** interleaved quads: x,y,w,h, u0,v0,u1,v1, r,g,b,a  (12 floats) */
    public static final int STRIDE = 12;
    public float[] quads = new float[STRIDE * 256];
    public int quadCount = 0;
    /** texture key per quad (asset path or "@white") */
    public String[] textures = new String[256];

    public void reset(){ quadCount = 0; }

    private void grow(){
        if(quadCount == quads.length/STRIDE){
            quads = java.util.Arrays.copyOf(quads, quads.length*2);
            textures = java.util.Arrays.copyOf(textures, textures.length*2);
        }
    }
    public void addQuad(String texture, float x, float y, float w, float h,
                        float u0, float v0, float u1, float v1, float r, float g, float b, float a){
        grow();
        int o = quadCount*STRIDE;
        quads[o]=x; quads[o+1]=y; quads[o+2]=w; quads[o+3]=h;
        quads[o+4]=u0; quads[o+5]=v0; quads[o+6]=u1; quads[o+7]=v1;
        quads[o+8]=r; quads[o+9]=g; quads[o+10]=b; quads[o+11]=a;
        textures[quadCount] = texture;
        quadCount++;
    }
    public void addQuad(String texture, float x, float y, float w, float h,
                        float u0, float v0, float u1, float v1, int rgba8888){
        grow();
        int o = quadCount*STRIDE;
        quads[o]=x; quads[o+1]=y; quads[o+2]=w; quads[o+3]=h;
        quads[o+4]=u0; quads[o+5]=v0; quads[o+6]=u1; quads[o+7]=v1;
        quads[o+8]=((rgba8888>>16)&255)/255f; quads[o+9]=((rgba8888>>8)&255)/255f;
        quads[o+10]=(rgba8888&255)/255f; quads[o+11]=((rgba8888>>>24)&255)/255f;
        textures[quadCount] = texture;
        quadCount++;
    }
}
