package net.pyrocube.core.pump;

import java.util.Arrays;

/**
 * Constants and coordinate helpers of the PUMP region and {@code PCHK} chunk format.
 *
 * <p>Every constant mirrors Appendix A of the specification. Section numbers in the Javadoc refer
 * to the same document.
 */
public final class PumpFormat {
    /** Allocation granularity of a region file in bytes (§3.2). */
    public static final int SECTOR_SIZE = 256;

    /** Number of sectors occupied by the superblock and the location table (§3.3). */
    public static final int HEADER_SECTORS = 33;

    /** Size in bytes of the superblock plus location table. */
    public static final int HEADER_BYTES = HEADER_SECTORS * SECTOR_SIZE;

    /** Number of chunk slots of a region (32 x 32). */
    public static final int CHUNK_COUNT = 1024;

    /** Byte offset of the chunk location table (sector 1). */
    public static final int LOCATION_TABLE_OFFSET = SECTOR_SIZE;

    /** Size of a single {@code PumpChunkEntry}. */
    public static final int CHUNK_ENTRY_BYTES = 8;

    /** Size of the {@code PumpChunkFrameHeader} (§4.1). */
    public static final int FRAME_HEADER_BYTES = 12;

    /** Size of the {@code PumpChunkHeader} (§5.1). */
    public static final int CHUNK_HEADER_BYTES = 31;

    /** {@code PUMP_MAX_DECOMPRESSED_CHUNK_BYTES} (§7.1). */
    public static final int MAX_DECOMPRESSED_CHUNK_BYTES = 64 * 1024 * 1024;

    /** {@code PUMP_MAX_SECTIONS} (§7.1). */
    public static final int MAX_SECTIONS = 256;

    /** {@code PUMP_MAX_TICKS} (§7.1), applies to block and fluid ticks independently. */
    public static final int MAX_TICKS = 65_536;

    /** {@code PUMP_MAX_BLOCK_ENTITIES} (§7.1). */
    public static final int MAX_BLOCK_ENTITIES = 65_536;

    /** Number of blocks in a section. */
    public static final int SECTION_BLOCKS = 4096;

    /** Number of biome cells in a section. */
    public static final int SECTION_BIOMES = 64;

    /** Size in bytes of a nibble light array. */
    public static final int LIGHT_BYTES = 2048;

    /** Number of {@code i64} words of a serialized heightmap (§5.2). */
    public static final int HEIGHTMAP_WORDS = 37;

    /** Size in bytes of a serialized heightmap. */
    public static final int HEIGHTMAP_BYTES = HEIGHTMAP_WORDS * Long.BYTES;

    /** Bits per heightmap element. */
    public static final int HEIGHTMAP_BITS = 9;

    /** Number of columns in a chunk, and thus elements in a heightmap. */
    public static final int HEIGHTMAP_ENTRIES = 256;

    /** Largest value representable in a heightmap element. */
    public static final int HEIGHTMAP_MAX_VALUE = (1 << HEIGHTMAP_BITS) - 1;

    /** Region file signature, {@code "PUMP\0\0\0\1"} (§3.3.1). */
    private static final byte[] SIGNATURE = {'P', 'U', 'M', 'P', 0, 0, 0, 1};

    /** Chunk payload magic, {@code "PCHK"} (§5.1). */
    private static final byte[] CHUNK_MAGIC = {'P', 'C', 'H', 'K'};

    /** Supported chunk schema version. */
    public static final int CHUNK_VERSION = 1;

    /** File extension of a region container. */
    public static final String REGION_EXTENSION = ".pump";

    private PumpFormat() {}

    /** Returns a copy of the 8 byte region signature. */
    public static byte[] signature() {
        return Arrays.copyOf(SIGNATURE, SIGNATURE.length);
    }

    /** Returns a copy of the 4 byte chunk magic. */
    public static byte[] chunkMagic() {
        return Arrays.copyOf(CHUNK_MAGIC, CHUNK_MAGIC.length);
    }

    /** Returns the signature byte at {@code index}. */
    public static byte signatureByte(int index) {
        return SIGNATURE[index];
    }

    /** Returns the chunk magic byte at {@code index}. */
    public static byte chunkMagicByte(int index) {
        return CHUNK_MAGIC[index];
    }

    /** Region coordinate containing the given chunk coordinate: {@code floor(c / 32)}. */
    public static int chunkToRegion(int chunkCoordinate) {
        return chunkCoordinate >> 5;
    }

    /** Index {@code (z & 31) * 32 + (x & 31)} of a chunk inside its region (§2.3.1). */
    public static int chunkIndex(int chunkX, int chunkZ) {
        return ((chunkZ & 31) << 5) | (chunkX & 31);
    }

    /** Chunk X coordinate of the slot {@code index} in region {@code regionX}. */
    public static int chunkXOf(int regionX, int index) {
        return (regionX << 5) + (index & 31);
    }

    /** Chunk Z coordinate of the slot {@code index} in region {@code regionZ}. */
    public static int chunkZOf(int regionZ, int index) {
        return (regionZ << 5) + (index >> 5);
    }

    /** Index of a block inside a section (§2.3.2), coordinates are section relative. */
    public static int blockIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /** Index of a biome cell inside a section (§2.3.3), coordinates are cell relative. */
    public static int biomeIndex(int x, int y, int z) {
        return (y << 4) | (z << 2) | x;
    }

    /** File name of a region, {@code r.<x>.<z>.pump} (§3.1). */
    public static String regionFileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + REGION_EXTENSION;
    }

    /** Number of sectors needed to hold {@code byteLength} bytes (§3.2). */
    public static int sectorsFor(long byteLength) {
        return (int) ((byteLength + SECTOR_SIZE - 1) / SECTOR_SIZE);
    }

    /** Packs two region or chunk coordinates in a single map key. */
    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Inverse of {@link #pack(int, int)}, X component. */
    public static int unpackX(long key) {
        return (int) (key >> 32);
    }

    /** Inverse of {@link #pack(int, int)}, Z component. */
    public static int unpackZ(long key) {
        return (int) key;
    }
}
