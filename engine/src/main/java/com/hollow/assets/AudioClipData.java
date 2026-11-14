package com.hollow.assets;

/** Decoded PCM audio. */
public final class AudioClipData {
    public String name = "audio";
    public int sampleRate = 44100;
    public int channels = 1;              // 1 = mono, 2 = stereo
    public float[] pcm;                   // interleaved, [-1, 1]
    public boolean loop = false;

    public float durationSeconds(){ return pcm.length / (float)(sampleRate * channels); }

    // ---- WAV decode (PCM16/PCM8/PCM32F, RIFF) — works everywhere ----
    public static AudioClipData fromWav(byte[] bytes, String name){
        java.io.DataInputStream d = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
        try {
            byte[] riff = new byte[4]; d.readFully(riff);
            if(!(riff[0]=='R'&&riff[1]=='I'&&riff[2]=='F'&&riff[3]=='F')) throw new IllegalArgumentException("not RIFF: " + name);
            d.readInt();
            byte[] wave = new byte[4]; d.readFully(wave);
            int sampleRate=44100, channels=1, bits=16; float[] pcm=null; boolean floatPcm=false; int dataLen=0;
            boolean loop = false;
            while(true){
                byte[] id = new byte[4];
                if(d.read(id) < 4) break;
                int len = Integer.reverseBytes(d.readInt());
                if(new String(id, "ASCII").equals("fmt ")){
                    d.readShort(); // audio format
                    channels = Short.reverseBytes(d.readShort());
                    sampleRate = Integer.reverseBytes(d.readInt());
                    d.readInt(); // byte rate
                    d.readShort(); // block align
                    bits = Short.reverseBytes(d.readShort());
                    int skip = len - 16;
                    if(bits == 32) floatPcm = true;
                    for(int i=0;i<skip;i++) d.readByte();
                } else if(new String(id, "ASCII").equals("data")){
                    dataLen = len;
                    break;
                } else {
                    for(int i=0;i<len;i++) d.readByte();
                }
            }
            if(floatPcm){
                int n = dataLen/4;
                pcm = new float[n];
                for(int i=0;i<n;i++) pcm[i] = Float.intBitsToFloat(Integer.reverseBytes(d.readInt()));
            } else if(bits == 16){
                int n = dataLen/2;
                pcm = new float[n];
                for(int i=0;i<n;i++) pcm[i] = (short)((d.readByte() & 0xFF) | ((d.readByte() & 0xFF) << 8)) / 32767f; // WAV: little-endian
            } else if(bits == 8){
                pcm = new float[dataLen];
                for(int i=0;i<dataLen;i++) pcm[i] = (d.readByte() & 0xFF)/255f*2f - 1f;
            } else throw new IllegalArgumentException("unsupported WAV bit depth " + bits);
            AudioClipData a = new AudioClipData();
            a.name = name; a.sampleRate = sampleRate; a.channels = channels; a.pcm = pcm; a.loop = loop;
            return a;
        } catch(java.io.IOException e){
            throw new IllegalArgumentException("bad wav: " + name, e);
        }
    }
}
