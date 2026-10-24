package net.pyrocube.core.pump.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.luben.zstd.ZstdCompressCtx;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class XXH64Test {
    @Test
    void publishedVectors() {
        assertEquals(0xEF46DB3751D8E999L, XXH64.hash(new byte[0], 0, 0, 0));
        assertEquals(0xD24EC4F1A98C6E5BL, XXH64.hash("a".getBytes(StandardCharsets.UTF_8)));
        assertEquals(0x44BC2CF5AD770999L, XXH64.hash("abc".getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Zstandard's optional frame checksum is the low 32 bits of XXH64(content, 0), which gives an
     * independent oracle for every input length class (tail, 4, 8 and 32 byte paths).
     */
    @Test
    void agreesWithZstdFrameChecksum() {
        final Random r = new Random(7);
        try (ZstdCompressCtx ctx = new ZstdCompressCtx().setChecksum(true)) {
            final int[] lengths = {0, 1, 3, 4, 5, 7, 8, 9, 15, 16, 31, 32, 33, 63, 64, 65, 255, 4096, 100_003};
            for (int len : lengths) {
                final byte[] data = new byte[len];
                r.nextBytes(data);
                final byte[] frame = new byte[(int) com.github.luben.zstd.Zstd.compressBound(len) + 16];
                final int n = ctx.compressByteArray(frame, 0, frame.length, data, 0, len);
                final long tail =
                        (frame[n - 4] & 0xFFL)
                                | (frame[n - 3] & 0xFFL) << 8
                                | (frame[n - 2] & 0xFFL) << 16
                                | (frame[n - 1] & 0xFFL) << 24;
                assertEquals(XXH64.hash(data) & 0xFFFFFFFFL, tail, "length " + len);
            }
        }
    }

    @Test
    void offsetAndSeed() {
        final byte[] data = "xxhello world".getBytes(StandardCharsets.UTF_8);
        final byte[] slice = "hello world".getBytes(StandardCharsets.UTF_8);
        assertEquals(XXH64.hash(slice, 0, slice.length, 0), XXH64.hash(data, 2, slice.length, 0));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                XXH64.hash(slice, 0, slice.length, 0), XXH64.hash(slice, 0, slice.length, 1));
    }
}
