package net.pyrocube.core.pump.io;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * XXH64 as specified by Yann Collet, one shot over a byte array. The format mandates seed zero
 * (§4.3) but any seed is accepted so the implementation can be verified against published vectors.
 */
public final class XXH64 {
    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private static final VarHandle LONG_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private XXH64() {}

    public static long hash(byte[] data) {
        return hash(data, 0, data.length, 0L);
    }

    public static long hash(byte[] data, int off, int len) {
        return hash(data, off, len, 0L);
    }

    public static long hash(byte[] data, int off, int len, long seed) {
        if (off < 0 || len < 0 || off > data.length - len) {
            throw new IndexOutOfBoundsException();
        }
        int p = off;
        final int end = off + len;
        long h;
        if (len >= 32) {
            long v1 = seed + P1 + P2;
            long v2 = seed + P2;
            long v3 = seed;
            long v4 = seed - P1;
            final int limit = end - 32;
            do {
                v1 = round(v1, (long) LONG_LE.get(data, p));
                v2 = round(v2, (long) LONG_LE.get(data, p + 8));
                v3 = round(v3, (long) LONG_LE.get(data, p + 16));
                v4 = round(v4, (long) LONG_LE.get(data, p + 24));
                p += 32;
            } while (p <= limit);
            h =
                    Long.rotateLeft(v1, 1)
                            + Long.rotateLeft(v2, 7)
                            + Long.rotateLeft(v3, 12)
                            + Long.rotateLeft(v4, 18);
            h = mergeRound(h, v1);
            h = mergeRound(h, v2);
            h = mergeRound(h, v3);
            h = mergeRound(h, v4);
        } else {
            h = seed + P5;
        }
        h += len;

        while (p <= end - 8) {
            h ^= round(0, (long) LONG_LE.get(data, p));
            h = Long.rotateLeft(h, 27) * P1 + P4;
            p += 8;
        }
        if (p <= end - 4) {
            h ^= ((int) INT_LE.get(data, p) & 0xFFFFFFFFL) * P1;
            h = Long.rotateLeft(h, 23) * P2 + P3;
            p += 4;
        }
        while (p < end) {
            h ^= (data[p] & 0xFFL) * P5;
            h = Long.rotateLeft(h, 11) * P1;
            p++;
        }

        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        h ^= h >>> 32;
        return h;
    }

    private static long round(long acc, long input) {
        acc += input * P2;
        acc = Long.rotateLeft(acc, 31);
        return acc * P1;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0, val);
        acc ^= val;
        return acc * P1 + P4;
    }
}
