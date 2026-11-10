package com.hollow.core;

/** IEEE-754 half precision (MATH): conversion between float and 16-bit half,
 *  used by compact vertex/color formats. Standard bit manipulation, no F16 hardware needed. */
public final class HalfFloat {
    private HalfFloat(){}

    /** float -> half bits (round-to-nearest). ±0, subnormals, inf, NaN all handled. */
    public static short toHalf(float f){
        int bits = Float.floatToRawIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int mant = bits & 0x007FFFFF;
        int exp  = (bits >>> 23) & 0xFF;

        if(exp == 0xFF)                                             // inf / NaN
            return (short)(sign | 0x7C00 | (mant != 0 ? 0x0200 : 0));

        int e = exp - 127 + 15;                                     // re-bias to half exponent
        if(e >= 0x1F) return (short)(sign | 0x7C00);                // overflow -> infinity
        if(e <= 0){                                                 // zero / subnormal
            if(e < -10) return (short) sign;                        // underflow -> signed zero
            mant |= 0x00800000;                                     // implicit leading 1
            int shift = 14 - e;
            int half = mant >> shift;
            half += (mant >> (shift - 1)) & 1;                      // round to nearest
            return (short)(sign | half);
        }
        int half = (e << 10) | (mant >>> 13);
        half += (mant >>> 12) & 1;                                  // round to nearest
        return (short)(sign | half);
    }

    /** half bits -> float */
    public static float toFloat(short h){
        int sign = (h & 0x8000) << 16;
        int exp  = (h >>> 10) & 0x1F;
        int mant = h & 0x03FF;
        int bits;
        if(exp == 0){
            if(mant == 0){
                bits = sign;                                        // ±0
            } else {                                                // subnormal -> normal
                int e = -1;
                do { e++; mant <<= 1; } while((mant & 0x400) == 0);
                mant &= 0x3FF;
                bits = sign | ((127 - 15 - e) << 23) | (mant << 13);
            }
        } else if(exp == 31){
            bits = sign | 0x7F800000 | (mant << 13);                // inf / NaN
        } else {
            bits = sign | ((exp - 15 + 127) << 23) | (mant << 13);
        }
        return Float.intBitsToFloat(bits);
    }

    /** round-trip helpers for vertex streams */
    public static void pack(short[] out, int ofs, float f){ out[ofs] = toHalf(f); }
    public static float unpack(short h){ return toFloat(h); }
}
