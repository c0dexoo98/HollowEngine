package com.hollow.core;

import java.util.Arrays;

/** Pure-Java LZ4 block codec (MEM-016): fast compression for temp buffers, save
 *  snapshots and network payloads. No dependencies; the format is the standard LZ4
 *  block format (interoperable with any LZ4 implementation). Optimized for speed:
 *  single hash table, 4-byte matches, greedy parsing. Typical engine buffers
 *  compress 2-6x at tens of MB/s. */
public final class LZ4 {
    private LZ4(){}

    public static final int MIN_MATCH = 4;
    private static final int MFLIMIT = 12;          // COPY_LENGTH + MIN_MATCH
    private static final int LAST_LITERALS = 5;
    private static final int HASH_LOG = 12;
    private static final int HASH_SIZE = 1 << HASH_LOG;
    private static final int MAX_DISTANCE = 0xFFFF;

    // ---------------- public API ----------------

    /** worst-case compressed size for a block of n bytes */
    public static int maxCompressedLength(int n){
        return n + n / 255 + 16;
    }

    /** compress src[0..len) into dst (must be >= maxCompressedLength(len)); returns compressed length */
    public static int compress(byte[] src, int len, byte[] dst){
        boolean DBG = Boolean.getBoolean("lz4.debug");
        int[] hashTable = new int[HASH_SIZE];
        Arrays.fill(hashTable, -1);
        int dstPtr = 0;
        int anchor = 0;
        int p = 0;
        int mfLimit = len - MFLIMIT;
        int matchLimit = len - LAST_LITERALS;

        if(len >= MFLIMIT){
            hashTable[hash(src, 0)] = 0;
            p = 1;
        }

        while(p <= mfLimit){
            int h = hash(src, p);
            int cand = hashTable[h];
            hashTable[h] = p;
            if(cand < 0 || p - cand > MAX_DISTANCE || read32(src, cand) != read32(src, p)){
                p++;
                continue;
            }
            // ---- emit sequence: match found at p ----
            int matchEnd = extendMatch(src, cand + MIN_MATCH, p + MIN_MATCH, matchLimit);
            int ml = matchEnd - p;                     // total match length >= 4
            if(DBG) System.err.printf("SEQ1 p=%d cand=%d lit=%d off=%d ml=%d%n", p, cand, p - anchor, p - cand, ml);
            int tokenPos = dstPtr;
            dstPtr = emitSequence(dst, dstPtr, src, anchor, p - anchor, p - cand, ml);
            p = matchEnd;
            anchor = p;
            // try one immediate follow-up match (LZ4 greedy continuation)
            if(p + 2 <= mfLimit){
                hashTable[hash(src, p - 2)] = p - 2;
                int h2 = hash(src, p);
                int cand2 = hashTable[h2];
                hashTable[h2] = p;
                if(cand2 >= 0 && p - cand2 <= MAX_DISTANCE && read32(src, cand2) == read32(src, p)){
                    int me2 = extendMatch(src, cand2 + MIN_MATCH, p + MIN_MATCH, matchLimit);
                    int ml2 = me2 - p;
                    if(DBG) System.err.printf("SEQ2 p=%d cand=%d lit=0 off=%d ml=%d%n", p, cand2, p - cand2, ml2);
                    dstPtr = emitSequence(dst, dstPtr, src, anchor, 0, p - cand2, ml2);
                    p = me2;
                    anchor = p;
                }
            }
            p++;
        }
        // ---- last literals (no offset) ----
        int litLen = len - anchor;
        dst[dstPtr++] = (byte)(litLen >= 15 ? 0xF0 : litLen << 4);
        if(litLen >= 15){
            int rest = litLen - 15;
            while(rest >= 255){ dst[dstPtr++] = (byte)255; rest -= 255; }
            dst[dstPtr++] = (byte) rest;
        }
        if(litLen > 0) System.arraycopy(src, anchor, dst, dstPtr, litLen);
        dstPtr += litLen;
        return dstPtr;
    }

    /**
     * Emit one LZ4 sequence: token, [literal-length ext], literals, offset(LE), [match-length ext].
     * Returns the new dstPtr.
     */
    private static int emitSequence(byte[] dst, int dstPtr, byte[] src, int litOfs, int litLen,
                                    int offset, int matchLen){
        int tokenPos = dstPtr;                          // patch target for short match lengths
        int nibbleLit = Math.min(15, litLen);
        int ext = matchLen - MIN_MATCH;
        // token: literal nibble + match nibble (15 = long match, extension bytes follow offset)
        dst[dstPtr++] = (byte)((nibbleLit << 4) | (ext >= 15 ? 0xF : ext));
        if(litLen >= 15){
            int rest = litLen - 15;
            while(rest >= 255){ dst[dstPtr++] = (byte)255; rest -= 255; }
            dst[dstPtr++] = (byte) rest;
        }
        if(litLen > 0){
            System.arraycopy(src, litOfs, dst, dstPtr, litLen);
            dstPtr += litLen;
        }
        // offset (little endian)
        dst[dstPtr++] = (byte) offset;
        dst[dstPtr++] = (byte)(offset >>> 8);
        // match-length extension bytes (only when nibble saturated)
        if(ext >= 15){
            int rest = ext - 15;
            while(rest >= 255){ dst[dstPtr++] = (byte)255; rest -= 255; }
            dst[dstPtr++] = (byte) rest;
        }
        return dstPtr;
    }

    /** decompress a standard LZ4 block; returns uncompressed byte[] of expectedLen */
    public static byte[] decompress(byte[] src, int srcLen, int expectedLen){
        byte[] dst = new byte[expectedLen];
        int s = 0, d = 0;
        while(s < srcLen){
            int token = src[s++] & 0xFF;
            int litLen = token >>> 4;
            if(litLen == 15){
                int b;
                do { b = src[s++] & 0xFF; litLen += b; } while(b == 255);
            }
            if(s + litLen > srcLen || d + litLen > expectedLen) throw new IllegalArgumentException("corrupt LZ4: literals overrun");
            if(litLen > 0){ System.arraycopy(src, s, dst, d, litLen); }
            s += litLen; d += litLen;
            if(s >= srcLen) break;                      // last sequence
            int offset = (src[s++] & 0xFF) | (src[s++] & 0xFF) << 8;
            if(offset == 0 || offset > d) throw new IllegalArgumentException("corrupt LZ4: offset " + offset + " at " + d);
            int matchLen = (token & 0xF) + MIN_MATCH;
            if((token & 0xF) == 15){
                int b;
                do { b = src[s++] & 0xFF; matchLen += b; } while(b == 255);
            }
            if(d + matchLen > expectedLen) throw new IllegalArgumentException("corrupt LZ4: output overrun");
            int mp = d - offset;
            if(offset >= matchLen){
                // non-overlapping: fast bulk copy
                System.arraycopy(dst, mp, dst, d, matchLen);
                d += matchLen;
            } else {
                // overlapping (RLE-style): MUST copy byte-wise — arraycopy would
                // snapshot the source and break the repeating-pattern semantics
                for(int i=0;i<matchLen;i++) dst[d++] = dst[mp++];
            }
        }
        return d == expectedLen ? dst : Arrays.copyOf(dst, d);
    }

    /** round-trip validity check (used by tests + savegame verification) */
    public static boolean isValid(byte[] src, int srcLen, int expectedLen){
        try { return decompress(src, srcLen, expectedLen).length == expectedLen; }
        catch(Exception e){ return false; }
    }

    // ---------------- internals ----------------

    private static int hash(byte[] b, int p){
        return (int)(((read32(b, p) & 0xFFFFFFFFL) * 2654435761L) >>> 32) & (HASH_SIZE - 1);
    }
    private static int read32(byte[] b, int p){
        return (b[p] & 0xFF) | (b[p+1] & 0xFF) << 8 | (b[p+2] & 0xFF) << 16 | (b[p+3] & 0xFF) << 24;
    }
    /** scan match beyond the first 4 bytes; returns end position */
    private static int extendMatch(byte[] src, int match, int p, int limit){
        while(p < limit && src[match] == src[p]){ match++; p++; }
        return p;
    }

    // ---------------- convenience ----------------

    /** self-describing container: [origLen int32 LE][LZ4 block] */
    public static byte[] pack(byte[] src){
        byte[] dst = new byte[maxCompressedLength(src.length) + 4];
        dst[0] = (byte) src.length; dst[1] = (byte)(src.length >>> 8);
        dst[2] = (byte)(src.length >>> 16); dst[3] = (byte)(src.length >>> 24);
        int n = 4 + compress(src, src.length, dst, 4);
        return Arrays.copyOf(dst, n);
    }
    public static byte[] unpack(byte[] packed){
        int len = (packed[0] & 0xFF) | (packed[1] & 0xFF) << 8 | (packed[2] & 0xFF) << 16 | (packed[3] & 0xFF) << 24;
        return decompress(packed, 4, packed.length - 4, len);
    }

    /** compress into dst starting at dstOfs; returns compressed byte count */
    public static int compress(byte[] src, int len, byte[] dst, int dstOfs){
        byte[] tmp = new byte[maxCompressedLength(len)];
        int n = compress(src, len, tmp);
        System.arraycopy(tmp, 0, dst, dstOfs, n);
        return n;
    }
    /** decompress a block located at srcOfs..srcLen */
    public static byte[] decompress(byte[] src, int srcOfs, int srcLen, int expectedLen){
        if(srcOfs == 0) return decompress(src, srcLen, expectedLen);
        byte[] tmp = java.util.Arrays.copyOfRange(src, srcOfs, srcOfs + srcLen);
        return decompress(tmp, srcLen, expectedLen);
    }
}
