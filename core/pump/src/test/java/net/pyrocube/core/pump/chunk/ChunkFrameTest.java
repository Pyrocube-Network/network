package net.pyrocube.core.pump.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.PumpLimitException;
import net.pyrocube.core.pump.TestChunks;
import net.pyrocube.core.pump.io.XXH64;
import net.pyrocube.core.pump.io.ZstdCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class ChunkFrameTest {
    private static final ZstdCodec CODEC = new ZstdCodec(3);

    @AfterAll
    static void close() {
        CODEC.close();
    }

    @Test
    void roundTrip() throws Exception {
        final PumpChunk chunk = TestChunks.everything(3, 4, 11);
        final EncodedChunk frame = ChunkFrame.encode(chunk, CODEC);
        final byte[] payload = ChunkFrame.decode(frame.array(), 0, frame.length(), CODEC);
        assertEquals(chunk, PchkCodec.decode(payload, 3, 4));
    }

    @Test
    void headerAndPadding() throws Exception {
        final PumpChunk chunk = TestChunks.terrain(0, 0, 1, 24);
        final byte[] payload = PchkCodec.encode(chunk);
        final EncodedChunk frame = ChunkFrame.frame(payload, 0, payload.length, CODEC);
        final byte[] f = frame.array();

        assertEquals(payload.length, readInt(f, 0));
        assertEquals(XXH64.hash(payload), readLong(f, 4));
        assertEquals(0, f.length % PumpFormat.SECTOR_SIZE);
        assertEquals(PumpFormat.sectorsFor(frame.length()) * 256, f.length);
        assertTrue(frame.length() > 12);
        for (int i = frame.length(); i < f.length; i++) {
            assertEquals(0, f[i], "slack byte " + i + " must be zero");
        }
        assertTrue(frame.length() < payload.length, "chunk data should compress");
    }

    @Test
    void decodeIgnoresTrailingSlack() throws Exception {
        final byte[] payload = PchkCodec.encode(TestChunks.terrain(0, 0, 1, 4));
        final EncodedChunk frame = ChunkFrame.frame(payload, 0, payload.length, CODEC);
        assertArrayEquals(payload, ChunkFrame.decode(frame.array(), 0, frame.length(), CODEC));
    }

    @Test
    void rejectsTinyFrames() {
        final byte[] f = new byte[256];
        assertThrows(PumpCorruptException.class, () -> ChunkFrame.decode(f, 0, 12, CODEC));
        assertThrows(PumpCorruptException.class, () -> ChunkFrame.decode(f, 0, 3, CODEC));
    }

    @Test
    void rejectsOversizedDeclarationBeforeDecompressing() throws Exception {
        final byte[] payload = PchkCodec.encode(TestChunks.terrain(0, 0, 1, 4));
        final byte[] f = ChunkFrame.frame(payload, 0, payload.length, CODEC).array().clone();
        writeInt(f, 0, PumpFormat.MAX_DECOMPRESSED_CHUNK_BYTES + 1);
        assertThrows(PumpLimitException.class, () -> ChunkFrame.decode(f, 0, f.length, CODEC));
        writeInt(f, 0, -1); // 4 GiB - 1 as unsigned
        assertThrows(PumpLimitException.class, () -> ChunkFrame.decode(f, 0, f.length, CODEC));
    }

    @Test
    void rejectsSizeMismatchBothWays() throws Exception {
        final byte[] payload = PchkCodec.encode(TestChunks.terrain(0, 0, 1, 4));
        final EncodedChunk frame = ChunkFrame.frame(payload, 0, payload.length, CODEC);
        for (int delta : new int[] {-1, 1, 1000}) {
            final byte[] f = frame.array().clone();
            writeInt(f, 0, payload.length + delta);
            assertThrows(
                    PumpCorruptException.class,
                    () -> ChunkFrame.decode(f, 0, frame.length(), CODEC),
                    "delta " + delta);
        }
    }

    @Test
    void rejectsChecksumMismatch() throws Exception {
        final byte[] payload = PchkCodec.encode(TestChunks.terrain(0, 0, 1, 4));
        final EncodedChunk frame = ChunkFrame.frame(payload, 0, payload.length, CODEC);
        final byte[] f = frame.array().clone();
        f[11] ^= 0x01;
        final PumpCorruptException e =
                assertThrows(
                        PumpCorruptException.class,
                        () -> ChunkFrame.decode(f, 0, frame.length(), CODEC));
        assertTrue(e.getMessage().contains("checksum"));
    }

    @Test
    void rejectsFlippedBitsInCompressedData() throws Exception {
        final byte[] payload = PchkCodec.encode(TestChunks.everything(0, 0, 5));
        final EncodedChunk frame = ChunkFrame.frame(payload, 0, payload.length, CODEC);
        // A bit flip must either be reported or (when it only touches bitstream padding) leave the
        // decoded payload bit-identical. Silently returning different data is never acceptable.
        int detected = 0;
        int positions = 0;
        for (int pos = PumpFormat.FRAME_HEADER_BYTES; pos < frame.length(); pos += 7) {
            final byte[] f = frame.array().clone();
            f[pos] ^= 0x10;
            positions++;
            try {
                final byte[] out = ChunkFrame.decode(f, 0, frame.length(), CODEC);
                assertArrayEquals(payload, out, "silent corruption at " + pos);
            } catch (PumpCorruptException expected) {
                detected++;
            }
        }
        assertTrue(detected >= positions * 0.98, detected + " of " + positions + " detected");
    }

    @Test
    void rejectsGarbageCompressedData() {
        final byte[] f = new byte[256];
        writeInt(f, 0, 100);
        Arrays.fill(f, 12, 100, (byte) 0x5A);
        assertThrows(PumpCorruptException.class, () -> ChunkFrame.decode(f, 0, 100, CODEC));
    }

    @Test
    void refusesToEncodeBeyondTheLimit() {
        final byte[] big = new byte[PumpFormat.MAX_DECOMPRESSED_CHUNK_BYTES + 1];
        assertThrows(PumpLimitException.class, () -> ChunkFrame.frame(big, 0, big.length, CODEC));
    }

    @Test
    void largestAllowedPayloadRoundTrips() throws Exception {
        final byte[] big = new byte[PumpFormat.MAX_DECOMPRESSED_CHUNK_BYTES];
        big[12345] = 7;
        final EncodedChunk frame = ChunkFrame.frame(big, 0, big.length, CODEC);
        assertArrayEquals(big, ChunkFrame.decode(frame.array(), 0, frame.length(), CODEC));
    }

    private static int readInt(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24)
                | ((b[p + 1] & 0xFF) << 16)
                | ((b[p + 2] & 0xFF) << 8)
                | (b[p + 3] & 0xFF);
    }

    private static long readLong(byte[] b, int p) {
        return ((long) readInt(b, p) << 32) | (readInt(b, p + 4) & 0xFFFFFFFFL);
    }

    private static void writeInt(byte[] b, int p, int v) {
        b[p] = (byte) (v >>> 24);
        b[p + 1] = (byte) (v >>> 16);
        b[p + 2] = (byte) (v >>> 8);
        b[p + 3] = (byte) v;
    }
}
