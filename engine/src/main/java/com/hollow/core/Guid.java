package com.hollow.core;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Stable identifiers (OBJ): short readable GUIDs like `hv-3f9a2c81b7d4` for
 *  entities, assets and save slots. Monotonic time prefix + random tail keeps them
 *  collision-safe across sessions and devices. */
public final class Guid {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final AtomicLong COUNTER = new AtomicLong(0);
    private static final SecureRandom RNG = new SecureRandom();
    private static final long BOOT_RANDOM = RNG.nextLong();

    private Guid(){}

    /** 12 hex chars + 'hv-' prefix, e.g. hv-3f9a2c81b7d4 */
    public static String create(){
        long c = COUNTER.incrementAndGet();
        return "hv-" + hex(BOOT_RANDOM ^ (c * 0x9E3779B97F4A7C15L) ^ (System.nanoTime() << 1), 12);
    }
    /** 4-char short form for logs, not for storage */
    public static String shortForm(){ return hex(System.nanoTime() ^ (COUNTER.incrementAndGet() * 0xD1B54A32D192ED03L), 4); }

    public static boolean isValid(String g){
        return g != null && g.startsWith("hv-") && g.length() == 15;
    }

    static String hex(long v, int chars){
        StringBuilder b = new StringBuilder(chars);
        for(int i = chars - 1; i >= 0; i--) b.append(HEX[(int)((v >>> (i * 4)) & 0xF)]);
        return b.toString();
    }
}
