package com.hollow.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Direct NIO buffer helpers (HollowEngine 2.0) — one place for the native-order
 *  allocation pattern used by every GL upload path. */
public final class NioUtil {
    private NioUtil() {}

    public static FloatBuffer floatBuffer(int floats){
        long bytes = (long) floats * 4;
        if(bytes > Integer.MAX_VALUE) throw new IllegalArgumentException("floatBuffer too large: " + floats);
        return ByteBuffer.allocateDirect((int) Math.max(4, bytes))
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public static ShortBuffer shortBuffer(int shorts){
        long bytes = (long) shorts * 2;
        if(bytes > Integer.MAX_VALUE) throw new IllegalArgumentException("shortBuffer too large: " + shorts);
        return ByteBuffer.allocateDirect((int) Math.max(2, bytes))
            .order(ByteOrder.nativeOrder()).asShortBuffer();
    }

    public static ByteBuffer byteBuffer(int bytes){
        return ByteBuffer.allocateDirect(Math.max(1, bytes)).order(ByteOrder.nativeOrder());
    }
}
