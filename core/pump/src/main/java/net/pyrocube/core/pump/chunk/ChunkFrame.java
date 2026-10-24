package net.pyrocube.core.pump.chunk;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.PumpLimitException;
import net.pyrocube.core.pump.io.XXH64;
import net.pyrocube.core.pump.io.ZstdCodec;

/**
 * The compression envelope of a chunk (chapter 4): {@code u32 uncompressed_len}, {@code u64
 * checksum} (XXH64 of the decompressed payload, seed 0) and a Zstandard frame.
 */
public final class ChunkFrame {
    private static final VarHandle INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private ChunkFrame() {}

    /** Serializes, checksums and compresses a chunk. */
    public static EncodedChunk encode(PumpChunk chunk, ZstdCodec codec) throws IOException {
        final byte[] payload = PchkCodec.encode(chunk);
        return frame(payload, 0, payload.length, codec);
    }

    /** Wraps an already serialized {@code PCHK} payload in a frame. */
    public static EncodedChunk frame(byte[] payload, int off, int len, ZstdCodec codec)
            throws PumpLimitException {
        if (len > PumpFormat.MAX_DECOMPRESSED_CHUNK_BYTES) {
            throw new PumpLimitException(
                    "PUMP_MAX_DECOMPRESSED_CHUNK_BYTES",
                    len,
                    PumpFormat.MAX_DECOMPRESSED_CHUNK_BYTES);
        }
        final int bound = ZstdCodec.compressBound(len);
        final byte[] work = new byte[PumpFormat.FRAME_HEADER_BYTES + bound];
        final int compressed =
                codec.compress(payload, off, len, work, PumpFormat.FRAME_HEADER_BYTES);
        INT.set(work, 0, len);
        LONG.set(work, 4, XXH64.hash(payload, off, len));

        final int frameLength = PumpFormat.FRAME_HEADER_BYTES + compressed;
        final int paddedLength = PumpFormat.sectorsFor(frameLength) * PumpFormat.SECTOR_SIZE;
        final byte[] padded = Arrays.copyOf(work, paddedLength);
        // The compressor may scribble past the size it reports, so the slack is cleared
        // explicitly: the specification requires zero filled slack bytes (§3.2).
        Arrays.fill(padded, frameLength, paddedLength, (byte) 0);
        return new EncodedChunk(padded, frameLength);
    }

    /**
     * Validates and decompresses the frame stored in {@code frame[off, off + length)}, following
     * steps 6 to 8 of §6.1.
     *
     * @return the verified {@code PCHK} payload
     * @throws PumpCorruptException on a short frame, an oversized declaration, a decompression
     *     failure, a size mismatch or a checksum mismatch
     */
    public static byte[] decode(byte[] frame, int off, int length, ZstdCodec codec)
            throws PumpCorruptException {
        if (length <= PumpFormat.FRAME_HEADER_BYTES) {
            throw new PumpCorruptException(
                    "Chunk frame of " + length + " bytes is not larger than its 12 byte header");
        }
        final long declared = ((int) INT.get(frame, off)) & 0xFFFFFFFFL;
        if (declared > PumpFormat.MAX_DECOMPRESSED_CHUNK_BYTES) {
            // Rejected before anything is allocated or decompressed (§7.1).
            throw new PumpLimitException(
                    "PUMP_MAX_DECOMPRESSED_CHUNK_BYTES",
                    declared,
                    PumpFormat.MAX_DECOMPRESSED_CHUNK_BYTES);
        }
        final long checksum = (long) LONG.get(frame, off + 4);
        final byte[] payload = new byte[(int) declared];
        codec.decompress(
                frame,
                off + PumpFormat.FRAME_HEADER_BYTES,
                length - PumpFormat.FRAME_HEADER_BYTES,
                payload);
        final long actual = XXH64.hash(payload);
        if (actual != checksum) {
            throw new PumpCorruptException(
                    "Chunk checksum mismatch: stored 0x"
                            + Long.toHexString(checksum)
                            + ", computed 0x"
                            + Long.toHexString(actual));
        }
        return payload;
    }
}
