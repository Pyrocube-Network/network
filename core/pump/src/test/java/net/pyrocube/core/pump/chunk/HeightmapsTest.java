package net.pyrocube.core.pump.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import net.pyrocube.core.pump.TestChunks;
import org.junit.jupiter.api.Test;

class HeightmapsTest {
    @Test
    void roundTrip() {
        final Random r = new Random(1);
        for (int i = 0; i < 200; i++) {
            final short[] v = new short[256];
            for (int j = 0; j < 256; j++) v[j] = (short) r.nextInt(512);
            assertArrayEquals(v, Heightmaps.unpack(Heightmaps.pack(v)));
        }
    }

    @Test
    void layoutIsContinuousLittleEndianBitStream() {
        final short[] v = new short[256];
        v[0] = 0x1FF;
        v[7] = 0x155; // bits 63..71, straddles words 0 and 1
        final long[] w = Heightmaps.pack(v);
        assertEquals(37, w.length);
        assertEquals(0x1FFL | (0x155L << 63), w[0]);
        assertEquals(0x155L >>> 1, w[1]);
        assertEquals(0L, w[36], "the 37th word is zero padding");
    }

    @Test
    void lastElementEndsInWord35() {
        final short[] v = new short[256];
        v[255] = 0x1FF;
        final long[] w = Heightmaps.pack(v);
        // 255 * 9 = 2295 -> word 35, bit 55: the 256 values end exactly at bit 2304.
        assertEquals(0x1FFL << 55, w[35]);
        assertEquals(0L, w[36]);
    }

    @Test
    void rejectsOutOfRange() {
        final short[] v = new short[256];
        v[3] = 512;
        assertThrows(IllegalArgumentException.class, () -> Heightmaps.pack(v));
    }

    @Test
    void vanillaLayoutRoundTrip() {
        final short[] v = TestChunks.heights(new Random(3), 200);
        final long[] packed = Heightmaps.packVanilla(v, 9);
        assertEquals(37, packed.length);
        assertArrayEquals(v, Heightmaps.unpackVanilla(packed, 9));
        // seven values per long, bit 63 stays clear
        for (int i = 0; i < 36; i++) assertEquals(0L, packed[i] >>> 63);
    }
}
