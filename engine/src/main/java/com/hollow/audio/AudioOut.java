package com.hollow.audio;

/** Platform audio sink. Desktop: null. Android: AudioTrack (Agent E). */
public interface AudioOut {
    void open(int sampleRate);
    /** interleaved stereo 16-bit */
    void write(short[] interleavedStereo, int frames);
    void close();
}
