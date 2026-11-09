package com.hollow.runtime;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.hollow.assets.AudioClipData;

import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/** OGG Vorbis decode via Android MediaCodec (registered into OggDecoder hook at boot). */
final class AndroidOggDecoder {
    private AndroidOggDecoder() {}

    static AudioClipData decode(byte[] raw, String name){
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(new android.media.MediaDataSource() {
                @Override public int readAt(long position, byte[] buffer, int offset, int size){
                    if(position >= raw.length) return -1;
                    int n = (int)Math.min(size, raw.length - position);
                    System.arraycopy(raw, (int)position, buffer, offset, n);
                    return n;
                }
                @Override public long getSize(){ return raw.length; }
                @Override public void close(){}
            });
            int trackIndex = -1;
            MediaFormat format = null;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f = extractor.getTrackFormat(i);
                if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){
                    trackIndex = i; format = f; break;
                }
            }
            if(trackIndex < 0) throw new IllegalArgumentException("no audio track: " + name);
            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            final int[] feed = {0};
            codec.configure(format, null, null, 0);
            codec.start();

            java.io.ByteArrayOutputStream pcmOut = new java.io.ByteArrayOutputStream(1 << 16);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            long timeoutUs = 10_000;
            int guard = 0;
            while(!outputDone && guard++ < 2000){
                if(!inputDone){
                    int inIdx = codec.dequeueInputBuffer(timeoutUs);
                    if(inIdx >= 0){
                        java.nio.ByteBuffer ib = codec.getInputBuffer(inIdx);
                        ib.clear();
                        int chunk = Math.min(ib.capacity(), raw.length - feed[0]);
                        if(chunk <= 0){
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            ib.put(raw, feed[0], chunk);
                            codec.queueInputBuffer(inIdx, 0, chunk, feed[0], 0);
                            feed[0] += chunk;
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
                if(outIdx >= 0){
                    java.nio.ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if(ob != null && info.size > 0){
                        byte[] chunkBytes = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(chunkBytes);
                        pcmOut.write(chunkBytes, 0, info.size);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if(outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat nf = codec.getOutputFormat();
                    sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            codec.stop();
            codec.release();

            byte[] bytes = pcmOut.toByteArray();
            ShortBuffer sb = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            int frames = sb.remaining() / channels;
            float[] pcm = new float[frames * channels];
            for(int i=0;i<pcm.length;i++) pcm[i] = sb.get(i) / 32767f;

            AudioClipData clip = new AudioClipData();
            clip.name = name;
            clip.sampleRate = sampleRate;
            clip.channels = channels;
            clip.pcm = pcm;
            return clip;
        } catch(Exception e){
            com.hollow.core.Log.e("AndroidOgg", "decode failed: " + name, e);
            return new AudioClipData();
        } finally {
            try { extractor.release(); } catch(Exception ignored){}
        }
    }
}
