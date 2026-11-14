package com.hollow.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * v2.6.0 NET-ROLLBACK: deterministic rollback netcode core (GGPO-style, engine-sized).
 *
 * The transport layer is deliberately abstract: the engine ships an in-memory
 * loopback (tests, local co-op) and a thin UDP-style shim can wrap TcpChannel.
 * Reliability of INPUT messages is handled by resending every input packet
 * for the last INPUT_REDUNDANCY ticks (a la GGPO) — the protocol never needs
 * reliable delivery for correctness, only for latency.
 *
 * This file: InputBuffer — per-player ring of input frames with redundancy
 * encoding/decoding. A frame is (tick, buttons, ax, ay). Kept tiny (long + 2 floats)
 * so a 3-tick redundant payload stays under 32 bytes.
 */
public final class InputBuffer {

    /** packed input frame: 32 button bits + 32-bit sequence of small analog axes */
    public static final class Frame {
        public int tick;
        public long buttons;      // bitfield, meaning defined by the game
        public float ax, ay;      // analog axes, clamped -1..1

        public Frame(){ this(0, 0L, 0f, 0f); }
        public Frame(int tick, long buttons, float ax, float ay){
            this.tick = tick;
            this.buttons = buttons;
            this.ax = Math.max(-1f, Math.min(1f, ax));
            this.ay = Math.max(-1f, Math.min(1f, ay));
        }
        public boolean isDefault(){ return buttons == 0 && ax == 0f && ay == 0f; }
        @Override public boolean equals(Object o){
            if(!(o instanceof Frame)) return false;
            Frame f = (Frame)o;
            return tick == f.tick && buttons == f.buttons
                && Float.floatToIntBits(ax) == Float.floatToIntBits(f.ax)
                && Float.floatToIntBits(ay) == Float.floatToIntBits(f.ay);
        }
        @Override public int hashCode(){ return tick * 31 + Long.hashCode(buttons); }
    }

    public static final int CAPACITY = 256;      // ~4s of history at 60Hz — enough for any sane RTT
    public static final int INPUT_REDUNDANCY = 3;

    private final Frame[] ring = new Frame[CAPACITY];
    private int newestTick = -1;

    public InputBuffer(){
        for(int i = 0; i < CAPACITY; i++) ring[i] = new Frame();
    }

    /** record (or overwrite, when a late input arrives) the frame for `tick` */
    public void set(int tick, long buttons, float ax, float ay){
        if(tick < 0) return;
        Frame f = ring[tick % CAPACITY];
        f.tick = tick; f.buttons = buttons; f.ax = ax; f.ay = ay;
        if(tick > newestTick) newestTick = tick;
    }

    /** frame for `tick`, or null when missing (caller predicts with last known input) */
    public Frame get(int tick){
        if(tick < 0 || tick > newestTick) return null;
        Frame f = ring[tick % CAPACITY];
        return f.tick == tick ? f : null;
    }

    /** latest recorded frame (may be null on empty buffer) */
    public Frame latest(){
        return newestTick < 0 ? null : get(newestTick);
    }

    public int newestTick(){ return newestTick; }

    /** encode the last INPUT_REDUNDANCY frames ending at `tick` for one UDP datagram */
    public byte[] encodeRedundant(int tick){
        List<Frame> frames = new ArrayList<>(INPUT_REDUNDANCY);
        for(int t = tick - INPUT_REDUNDANCY + 1; t <= tick; t++){
            Frame f = get(t);
            if(f != null) frames.add(f);
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(4 + frames.size() * 16);
        bos.write(frames.size());
        for(Frame f : frames){
            writeInt(bos, f.tick);
            writeLong(bos, f.buttons);
            writeInt(bos, Float.floatToIntBits(f.ax));
            writeInt(bos, Float.floatToIntBits(f.ay));
        }
        return bos.toByteArray();
    }

    /** decode a redundant payload; every decodable frame is merged (older kept) */
    public static InputBuffer decodeRedundant(byte[] data){
        InputBuffer out = new InputBuffer();
        if(data == null || data.length < 1) return out;
        int n = data[0] & 0xFF;
        int p = 1;
        for(int i = 0; i < n && p + 16 <= data.length; i++){
            int tick = readInt(data, p); p += 4;
            long buttons = readLong(data, p); p += 8;
            float ax = Float.intBitsToFloat(readInt(data, p)); p += 4;
            float ay = Float.intBitsToFloat(readInt(data, p)); p += 4;
            out.set(tick, buttons, ax, ay);
        }
        return out;
    }

    private static void writeInt(java.io.ByteArrayOutputStream bos, int v){
        bos.write(v); bos.write(v >> 8); bos.write(v >> 16); bos.write(v >> 24);
    }
    private static void writeLong(java.io.ByteArrayOutputStream bos, long v){
        writeInt(bos, (int)v); writeInt(bos, (int)(v >> 32));
    }
    private static int readInt(byte[] d, int p){
        return (d[p] & 0xFF) | ((d[p+1] & 0xFF) << 8) | ((d[p+2] & 0xFF) << 16) | ((d[p+3] & 0xFF) << 24);
    }
    private static long readLong(byte[] d, int p){
        return (readInt(d, p) & 0xFFFFFFFFL) | ((long)readInt(d, p + 4) << 32);
    }

    /** structural clone for rollback copies (cheap: 256 frames of POD) */
    public InputBuffer copy(){
        InputBuffer c = new InputBuffer();
        c.newestTick = newestTick;
        for(int i = 0; i < CAPACITY; i++){
            Frame s = ring[i], d = c.ring[i];
            d.tick = s.tick; d.buttons = s.buttons; d.ax = s.ax; d.ay = s.ay;
        }
        return c;
    }
}
