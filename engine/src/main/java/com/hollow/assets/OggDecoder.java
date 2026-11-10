package com.hollow.assets;

/** OGG Vorbis decoder. Desktop: unavailable by default. Android runtime registers a
 *  MediaCodec-backed implementation at boot (via setImplementation).
 *  AGENT-E: implement AndroidOggDecoder in runtime-android. */
public final class OggDecoder {
    public interface Impl { AudioClipData decode(byte[] raw, String name); }
    private static volatile Impl impl;

    private OggDecoder() {}
    public static void setImplementation(Impl i){ impl = i; }
    public static boolean available(){ return impl != null; }
    public static AudioClipData decode(byte[] raw, String name){
        Impl i = impl;
        return i != null ? i.decode(raw, name) : new AudioClipData();
    }
}
