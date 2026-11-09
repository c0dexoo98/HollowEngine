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
        return ByteBuffer.allocateDirect(Math.max(4, floats * 4))
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    public static ShortBuffer shortBuffer(int shorts){
        return ByteBuffer.allocateDirect(Math.max(2, shorts * 2))
            .order(ByteOrder.nativeOrder()).asShortBuffer();
    }

    public static ByteBuffer byteBuffer(int bytes){
        return ByteBuffer.allocateDirect(Math.max(1, bytes)).order(ByteOrder.nativeOrder());
    }
}
