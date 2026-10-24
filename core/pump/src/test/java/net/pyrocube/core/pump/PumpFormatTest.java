package net.pyrocube.core.pump;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PumpFormatTest {
    @Test
    void constantsMatchAppendixA() {
        assertEquals(256, PumpFormat.SECTOR_SIZE);
        assertEquals(33, PumpFormat.HEADER_SECTORS);
        assertEquals(8448, PumpFormat.HEADER_BYTES);
        assertEquals(1024, PumpFormat.CHUNK_COUNT);
        assertEquals(64 * 1024 * 1024, PumpFormat.MAX_DECOMPRESSED_CHUNK_BYTES);
        assertEquals(256, PumpFormat.MAX_SECTIONS);
        assertEquals(65_536, PumpFormat.MAX_TICKS);
        assertEquals(65_536, PumpFormat.MAX_BLOCK_ENTITIES);
        assertEquals(296, PumpFormat.HEIGHTMAP_BYTES);
        assertEquals(12, PumpFormat.FRAME_HEADER_BYTES);
        assertEquals(31, PumpFormat.CHUNK_HEADER_BYTES);
    }

    @Test
    void chunkIndexUsesEuclideanModulo() {
        assertEquals(0, PumpFormat.chunkIndex(0, 0));
        assertEquals(1, PumpFormat.chunkIndex(1, 0));
        assertEquals(32, PumpFormat.chunkIndex(0, 1));
        assertEquals(1023, PumpFormat.chunkIndex(31, 31));
        assertEquals(1023, PumpFormat.chunkIndex(-1, -1));
        assertEquals(0, PumpFormat.chunkIndex(-32, -32));
        assertEquals(31, PumpFormat.chunkIndex(-33, 0));
        assertEquals(1023, PumpFormat.chunkIndex(Integer.MIN_VALUE + 31, -1));
    }

    @Test
    void regionIsFlooredNotTruncated() {
        assertEquals(0, PumpFormat.chunkToRegion(31));
        assertEquals(1, PumpFormat.chunkToRegion(32));
        assertEquals(-1, PumpFormat.chunkToRegion(-1));
        assertEquals(-1, PumpFormat.chunkToRegion(-32));
        assertEquals(-2, PumpFormat.chunkToRegion(-33));
    }

    @Test
    void chunkCoordinatesFromRegionAndIndex() {
        for (int x : new int[] {-100, -33, -32, -1, 0, 1, 31, 32, 99}) {
            for (int z : new int[] {-64, -1, 0, 5, 63}) {
                final int index = PumpFormat.chunkIndex(x, z);
                assertEquals(x, PumpFormat.chunkXOf(PumpFormat.chunkToRegion(x), index));
                assertEquals(z, PumpFormat.chunkZOf(PumpFormat.chunkToRegion(z), index));
            }
        }
    }

    @Test
    void blockAndBiomeIndices() {
        assertEquals(0, PumpFormat.blockIndex(0, 0, 0));
        assertEquals(15, PumpFormat.blockIndex(15, 0, 0));
        assertEquals(16, PumpFormat.blockIndex(0, 0, 1));
        assertEquals(256, PumpFormat.blockIndex(0, 1, 0));
        assertEquals(4095, PumpFormat.blockIndex(15, 15, 15));
        assertEquals(3, PumpFormat.biomeIndex(3, 0, 0));
        assertEquals(4, PumpFormat.biomeIndex(0, 0, 1));
        assertEquals(16, PumpFormat.biomeIndex(0, 1, 0));
        assertEquals(63, PumpFormat.biomeIndex(3, 3, 3));
    }

    @Test
    void sectorsAndNames() {
        assertEquals(1, PumpFormat.sectorsFor(1));
        assertEquals(1, PumpFormat.sectorsFor(256));
        assertEquals(2, PumpFormat.sectorsFor(257));
        assertEquals(262_144, PumpFormat.sectorsFor(67_108_864L));
        assertEquals("r.-3.12.pump", PumpFormat.regionFileName(-3, 12));
    }

    @Test
    void packedKeysRoundTrip() {
        for (int x : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            for (int z : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
                final long key = PumpFormat.pack(x, z);
                assertEquals(x, PumpFormat.unpackX(key));
                assertEquals(z, PumpFormat.unpackZ(key));
            }
        }
    }
}
