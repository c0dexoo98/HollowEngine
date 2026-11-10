package com.hollow.audio;

import com.hollow.assets.AudioClipData;
import com.hollow.core.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** v2.3 audio streaming & resource management (Batch 3):
 *  <pre>
 *  AUD-027 AudioClipCache   LRU decode cache with byte budget (re-decode avoidance)
 *  AUD-021 StreamPlayer     large-file streaming: WAV 16-bit PCM read in chunks,
 *                           deterministic synchronous refill (no threads on CLI)
 *  AUD-026 AudioFocusBridge focus/session abstraction; Android runtime installs a
 *                           MediaSession-backed impl, desktop uses AutoBridge
 *  </pre>
 */
public final class AudioStreams {
    private AudioStreams() {}

    // ==================================================================
    // AUD-027 — LRU decode cache
    // ==================================================================
    public static final class AudioClipCache {
        private final long maxBytes;
        private long usedBytes = 0;
        private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>(64, 0.75f, true);
        public long hits = 0, misses = 0, evictions = 0;

        private static final class Entry {
            final AudioClipData clip;
            final long bytes;
            Entry(AudioClipData c, long b){ clip = c; bytes = b; }
        }

        public AudioClipCache(long maxBytes){ this.maxBytes = Math.max(1, maxBytes); }

        public AudioClipData get(String path){
            Entry e = map.get(path);
            if(e != null){ hits++; return e.clip; }
            misses++;
            return null;
        }

        public void put(String path, AudioClipData clip){
            if(clip == null || clip.pcm == null) return;
            long bytes = (long) clip.pcm.length * 2L;
            if(bytes > maxBytes) return;                       // single clip too big to cache
            while(usedBytes + bytes > maxBytes && !map.isEmpty()){
                Map.Entry<String, Entry> old = map.entrySet().iterator().next();
                usedBytes -= old.getValue().bytes;
                map.remove(old.getKey());
                evictions++;
            }
            map.put(path, new Entry(clip, bytes));
            usedBytes += bytes;
        }

        public void clear(){ map.clear(); usedBytes = 0; }
        public int size(){ return map.size(); }
        public long usedBytes(){ return usedBytes; }
        public String stats(){ return String.format("cache %d clips %d/%d KB (hit %d miss %d evict %d)",
            map.size(), usedBytes / 1024, maxBytes / 1024, hits, misses, evictions); }
    }

    // ==================================================================
    // AUD-021 — WAV streaming (16-bit PCM, chunked read)
    // ==================================================================
    public static final class StreamPlayer {
        public static final int CHUNK_FRAMES = 16384;

        /** one streamed source; pull-model: caller (fixed step) refills and consumes */
        public static final class StreamVoice {
            public Path file;
            public int sampleRate = 44100;
            public int channels = 1;
            public long dataOffset;                 // PCM start in file
            public long dataLength;                 // PCM byte length
            public long readPos;                    // bytes consumed so far
            public float[] buffer = new float[CHUNK_FRAMES * 2];
            public int bufFrames = 0;               // valid frames in buffer
            public int bufCursor = 0;
            public boolean loop = false;
            public float volume = 1f;
            public boolean finished = false;
            private byte[] raw = new byte[CHUNK_FRAMES * 2 * 2];

            public double totalSeconds(){ return (dataLength / (double)(2 * channels)) / sampleRate; }
            public double playedSeconds(){
                return ((readPos - bufFrames * 2L * channels) / (double)(2 * channels)) / sampleRate;
            }
        }

        /** open a 16-bit PCM WAV for streaming (header validated, no full read) */
        public static StreamVoice open(String path, boolean loop, float volume) throws IOException {
            return openWith(Path.of(path), loop, volume);
        }

        private static StreamVoice openWith(Path p, boolean loop, float volume) throws IOException {
            byte[] head;
            try (var is = Files.newInputStream(p)) {
                head = is.readNBytes(4096);
            }
            if(head.length < 44 || head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F')
                throw new IOException("not a RIFF file: " + p);
            StreamVoice v = new StreamVoice();
            // walk chunks
            int pos = 12;
            boolean haveFmt = false, haveData = false;
            while(pos + 8 <= head.length){
                String id = new String(head, pos, 4, StandardCharsets.US_ASCII);
                long sz = (head[pos+4] & 0xFFL) | ((head[pos+5] & 0xFFL) << 8) | ((head[pos+6] & 0xFFL) << 16) | ((head[pos+7] & 0xFFL) << 24);
                if(id.equals("fmt ")){
                    int audioFmt = (head[pos+8] & 0xFF) | ((head[pos+9] & 0xFF) << 8);
                    if(audioFmt != 1) throw new IOException("streaming requires PCM (fmt=" + audioFmt + ")");
                    v.channels = (head[pos+10] & 0xFF) | ((head[pos+11] & 0xFF) << 8);
                    v.sampleRate = (head[pos+12] & 0xFF) | ((head[pos+13] & 0xFF) << 8) | ((head[pos+14] & 0xFF) << 16) | ((head[pos+15] & 0xFF) << 24);
                    int bits = (head[pos+22] & 0xFF) | ((head[pos+23] & 0xFF) << 8);
                    if(bits != 16) throw new IOException("streaming requires 16-bit (bits=" + bits + ")");
                    haveFmt = true;
                } else if(id.equals("data")){
                    v.dataOffset = pos + 8;
                    v.dataLength = Math.max(0, sz);
                    haveData = true;
                    break;
                }
                pos += 8 + (int)((sz + 1) & ~1L);
            }
            if(!haveFmt || !haveData) throw new IOException("missing fmt/data chunk: " + p);
            v.file = p;
            v.loop = loop;
            v.volume = volume;
            return v;
        }

        /** pull the next chunk from disk into the voice buffer (deterministic, sync) */
        public static void refill(StreamVoice v) throws IOException {
            try (var ch = Files.newByteChannel(v.file)) {
                long want = Math.min(CHUNK_FRAMES * 2L * v.channels, v.dataLength - v.readPos);
                if(want <= 0){
                    if(v.loop && v.dataLength > 0){
                        v.readPos = 0;
                        want = Math.min(CHUNK_FRAMES * 2L * v.channels, v.dataLength);
                    } else {
                        v.finished = true;
                        return;
                    }
                }
                ch.position(v.dataOffset + v.readPos);
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate((int) want);
                while(bb.hasRemaining()){
                    if(ch.read(bb) <= 0) break;
                }
                byte[] bytes = bb.array();
                int n = bb.position();
                int frames = n / (2 * v.channels);
                v.bufFrames = frames;
                v.bufCursor = 0;
                for(int f = 0; f < frames; f++){
                    int b = f * 2 * v.channels;
                    short l = (short)((bytes[b] & 0xFF) | ((bytes[b+1] & 0xFF) << 8));
                    float fl = l / 32768f;
                    if(v.channels == 2){
                        short r = (short)((bytes[b+2] & 0xFF) | ((bytes[b+3] & 0xFF) << 8));
                        v.buffer[f * 2] = fl;
                        v.buffer[f * 2 + 1] = r / 32768f;
                    } else {
                        v.buffer[f * 2] = fl;
                        v.buffer[f * 2 + 1] = fl;
                    }
                }
                v.readPos += n;
            }
        }
    }

    // ==================================================================
    // AUD-026 — audio focus bridge (media session abstraction)
    // ==================================================================
    public interface AudioFocusBridge {
        /** request focus; false = another app owns audio (engine should duck/stop) */
        boolean requestFocus();
        void abandonFocus();
        /** register a callback invoked when focus is lost (incoming call, notification sound) */
        void onFocusLost(Runnable r);
        /** current state 0=none 1=granted 2=lost/transient */
        int state();
    }

    /** desktop/CLI default: focus is always granted (no OS media session) */
    public static final class AutoBridge implements AudioFocusBridge {
        private int state = 0;
        private Runnable lost;
        @Override public boolean requestFocus(){ state = 1; return true; }
        @Override public void abandonFocus(){ state = 0; }
        @Override public void onFocusLost(Runnable r){ lost = r; }
        @Override public int state(){ return state; }
        /** desktop simulation hook: tests can fire focus loss (incoming call scenario) */
        public void simulateLoss(){ state = 2; if(lost != null) lost.run(); }
    }

    /** AudioEngine hook: what happens when focus is lost */
    public static final class FocusPolicy {
        public float musicDuck = 0f;        // music bus volume while unfocused
        public boolean pauseVoices = true;  // stop sfx/ambient/voice buses
        public boolean resumeOnFocus = true;
    }
}
