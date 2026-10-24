package net.pyrocube.core.pump.chunk;

/** Bit masks of the {@code flags} header field (§5.1). */
public final class ChunkFlags {
    public static final int LIGHT_CORRECT = 0x01;
    public static final int HAS_WORLD_SURFACE = 0x02;
    public static final int HAS_MOTION_BLOCKING = 0x04;
    public static final int HAS_MOTION_BLOCKING_NO_LEAVES = 0x08;
    public static final int HAS_CUSTOM_DATA = 0x10;

    /** Bits 5..7, which must be zero. */
    public static final int RESERVED = 0xE0;

    private ChunkFlags() {}
}
