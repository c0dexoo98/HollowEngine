package com.hollow.core;

import com.hollow.core.JsonW;

/** Thin typed wrapper over JSON writer (keeps component code clean). */
public final class JsonW {
    private final StringBuilder b = new StringBuilder();
    private int depth = 0;
    private boolean needComma = false;

    public JsonW obj(){ open('{'); return this; }
    public JsonW endObj(){ close('}'); return this; }
    public JsonW arr(){ open('['); return this; }
    public JsonW endArr(){ close(']'); return this; }

    private void open(char c){ comma(); b.append(c); depth++; needComma=false; }
    private void close(char c){ depth--; b.append(c); needComma=true; }
    private void comma(){ if(needComma) b.append(','); needComma=false; }

    public void key(String k){ comma(); Json.keyRaw(k, b); needComma=false; }

    public JsonW kv(String k, String v){ key(k); Json.valRaw(v, b); needComma=true; return this; }
    public JsonW kv(String k, double v){ key(k); b.append(Json.numRaw(v)); needComma=true; return this; }
    public JsonW kv(String k, float v){ return kv(k, (double)v); }
    public JsonW kv(String k, int v){ key(k); b.append(v); needComma=true; return this; }
    public JsonW kv(String k, boolean v){ key(k); b.append(v); needComma=true; return this; }
    public JsonW kvv(String k, float x, float y, float z){
        key(k); b.append('[').append(Json.numRaw(x)).append(',').append(Json.numRaw(y)).append(',').append(Json.numRaw(z)).append(']');
        needComma=true; return this;
    }
    public JsonW kvv(String k, float x, float y){
        key(k); b.append('[').append(Json.numRaw(x)).append(',').append(Json.numRaw(y)).append(']');
        needComma=true; return this;
    }

    /** start object for key */
    public JsonW obj(String k){ key(k); open('{'); return this; }
    public JsonW arr(String k){ key(k); open('['); return this; }

    public JsonW valueRaw(String raw){ comma(); b.append(raw); needComma=true; return this; }

    /** quoted string value for arrays */
    public JsonW val(String v){ comma(); Json.valRaw(v, b); needComma=true; return this; }

    @Override public String toString(){ return b.toString(); }
}
