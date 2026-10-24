package net.pyrocube.core.pump.chunk;

import static net.pyrocube.core.pump.PumpFormat.HEIGHTMAP_BITS;
import static net.pyrocube.core.pump.PumpFormat.HEIGHTMAP_ENTRIES;
import static net.pyrocube.core.pump.PumpFormat.HEIGHTMAP_MAX_VALUE;
import static net.pyrocube.core.pump.PumpFormat.HEIGHTMAP_WORDS;

/**
 * Bit packing of heightmaps.
 *
 * <p>A heightmap holds 256 columns, element {@code x + z * 16}, each a 9 bit value (§5.2). The
 * value is the height of the highest matching block relative to the bottom of the world plus one,
 * the same convention as Minecraft's {@code Heightmaps} NBT.
 *
 * <p>The PUMP layout ({@link #pack}) follows the normative text of §5.2: the 256 values are packed
 * <em>continuously</em>, element {@code i} occupying bits {@code [9i, 9i + 9)} of the little-endian
 * bit stream formed by the words, so they take 2304 bits, 36 words, and the 37th word is zero
 * padding. This differs from Minecraft's own layout (seven 9 bit values per long, last bit of every
 * word unused), which {@link #packVanilla} and {@link #unpackVanilla} convert from and to.
 */
public final class Heightmaps {
    private Heightmaps() {}

    /** Packs 256 values into the 37 word PUMP layout. */
    public static long[] pack(short[] values) {
        if (values.length != HEIGHTMAP_ENTRIES) {
            throw new IllegalArgumentException("Expected 256 heightmap values");
        }
        final long[] words = new long[HEIGHTMAP_WORDS];
        for (int i = 0; i < HEIGHTMAP_ENTRIES; i++) {
            final long v = values[i];
            if (v < 0 || v > HEIGHTMAP_MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Heightmap value out of range 0.." + HEIGHTMAP_MAX_VALUE + ": " + v);
            }
            final int bit = i * HEIGHTMAP_BITS;
            final int word = bit >>> 6;
            final int offset = bit & 63;
            words[word] |= v << offset;
            if (offset > 64 - HEIGHTMAP_BITS) {
                words[word + 1] |= v >>> (64 - offset);
            }
        }
        return words;
    }

    /** Unpacks the 37 word PUMP layout. The padding word is ignored. */
    public static short[] unpack(long[] words) {
        if (words.length != HEIGHTMAP_WORDS) {
            throw new IllegalArgumentException("Expected 37 heightmap words");
        }
        final short[] values = new short[HEIGHTMAP_ENTRIES];
        for (int i = 0; i < HEIGHTMAP_ENTRIES; i++) {
            final int bit = i * HEIGHTMAP_BITS;
            final int word = bit >>> 6;
            final int offset = bit & 63;
            long v = words[word] >>> offset;
            if (offset > 64 - HEIGHTMAP_BITS) {
                v |= words[word + 1] << (64 - offset);
            }
            values[i] = (short) (v & HEIGHTMAP_MAX_VALUE);
        }
        return values;
    }

    /**
     * Packs values the way Minecraft stores heightmaps: {@code 64 / bits} values per long, never
     * straddling two longs.
     */
    public static long[] packVanilla(short[] values, int bitsPerEntry) {
        final int perLong = 64 / bitsPerEntry;
        final long[] data = new long[(values.length + perLong - 1) / perLong];
        final long mask = (1L << bitsPerEntry) - 1;
        for (int i = 0; i < values.length; i++) {
            data[i / perLong] |= (values[i] & mask) << ((i % perLong) * bitsPerEntry);
        }
        return data;
    }

    /** Inverse of {@link #packVanilla}. */
    public static short[] unpackVanilla(long[] data, int bitsPerEntry) {
        final int perLong = 64 / bitsPerEntry;
        final long mask = (1L << bitsPerEntry) - 1;
        final short[] values = new short[HEIGHTMAP_ENTRIES];
        for (int i = 0; i < values.length; i++) {
            final int word = i / perLong;
            if (word >= data.length) break;
            values[i] = (short) ((data[word] >>> ((i % perLong) * bitsPerEntry)) & mask);
        }
        return values;
    }
}
