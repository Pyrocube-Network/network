package net.pyrocube.core.pump.chunk;

import net.pyrocube.core.pump.PumpFormat;

/**
 * A complete chunk frame (§4.1) ready to be stored in a region: the 12 byte frame header followed
 * by the Zstandard data.
 *
 * <p>The backing array is padded with zeros to a whole number of sectors, so a region can write it
 * as is without a second pass for slack bytes (§3.2).
 */
public final class EncodedChunk {
    private final byte[] data;
    private final int length;

    EncodedChunk(byte[] data, int length) {
        if (data.length != PumpFormat.sectorsFor(length) * PumpFormat.SECTOR_SIZE) {
            throw new IllegalArgumentException("Frame buffer must be padded to whole sectors");
        }
        this.data = data;
        this.length = length;
    }

    /** Exact frame length in bytes, the {@code byte_length} of the location table entry. */
    public int length() {
        return length;
    }

    /** Size of the uncompressed {@code PCHK} payload, from the frame header. */
    public int uncompressedLength() {
        return ((data[0] & 0xFF) << 24)
                | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8)
                | (data[3] & 0xFF);
    }

    /** Number of sectors the frame occupies. */
    public int sectors() {
        return data.length / PumpFormat.SECTOR_SIZE;
    }

    /** Length of the sector padded buffer returned by {@link #array()}. */
    public int paddedLength() {
        return data.length;
    }

    /** The sector padded frame. Must not be modified. */
    public byte[] array() {
        return data;
    }
}
