package com.hollow.runtime;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Android AudioTrack output for the engine mixer. */
final class AndroidAudioOut implements com.hollow.audio.AudioOut {
    private AudioTrack track;
    private int sampleRate = 44100;

    @Override public void open(int sampleRate){
        this.sampleRate = sampleRate;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minBuf, sampleRate * 4))
                .build();
        track.play();
        com.hollow.core.Log.i("AndroidAudio", "AudioTrack open @" + sampleRate);
    }

    @Override public void write(short[] interleavedStereo, int frames){
        if(track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING){
            track.write(interleavedStereo, 0, frames * 2);
        }
    }

    @Override public void close(){
        if(track != null){
            try { track.stop(); track.release(); } catch(Exception ignored){}
            track = null;
        }
    }
}
