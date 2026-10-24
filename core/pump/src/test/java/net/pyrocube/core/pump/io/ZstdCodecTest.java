package net.pyrocube.core.pump.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.pyrocube.core.pump.PumpCorruptException;
import org.junit.jupiter.api.Test;

class ZstdCodecTest {
    private static byte[] compress(ZstdCodec codec, byte[] data) {
        final byte[] out = new byte[ZstdCodec.compressBound(data.length)];
        final int n = codec.compress(data, 0, data.length, out, 0);
        return java.util.Arrays.copyOf(out, n);
    }

    @Test
    void roundTripAllLevels() throws Exception {
        final byte[] data = new byte[50_000];
        new Random(1).nextBytes(data);
        for (int i = 0; i < data.length; i += 3) data[i] = 7;
        for (int level : new int[] {1, 3, 9, 19}) {
            try (ZstdCodec codec = new ZstdCodec(level)) {
                final byte[] packed = compress(codec, data);
                final byte[] back = new byte[data.length];
                codec.decompress(packed, 0, packed.length, back);
                assertArrayEquals(data, back, "level " + level);
            }
        }
    }

    @Test
    void contextsAreSharedSafelyAcrossThreads() throws Exception {
        try (ZstdCodec codec = new ZstdCodec(3, 4);
                ExecutorService pool = Executors.newFixedThreadPool(12)) {
            final List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 12; t++) {
                final int seed = t;
                futures.add(
                        pool.submit(
                                () -> {
                                    final Random r = new Random(seed);
                                    for (int i = 0; i < 300; i++) {
                                        final byte[] data = new byte[1 + r.nextInt(20_000)];
                                        r.nextBytes(data);
                                        for (int k = 0; k < data.length; k += 2) data[k] = (byte) seed;
                                        final byte[] packed = compress(codec, data);
                                        final byte[] back = new byte[data.length];
                                        codec.decompress(packed, 0, packed.length, back);
                                        assertArrayEquals(data, back);
                                    }
                                    return null;
                                }));
            }
            for (Future<?> f : futures) f.get();
        }
    }

    @Test
    void failedDecompressionDoesNotPoisonTheCodec() throws Exception {
        try (ZstdCodec codec = new ZstdCodec(3, 1)) {
            final byte[] data = new byte[1000];
            new Random(2).nextBytes(data);
            final byte[] packed = compress(codec, data);
            final byte[] broken = packed.clone();
            for (int i = 8; i < broken.length; i++) broken[i] = (byte) 0xA5;
            assertThrows(
                    PumpCorruptException.class,
                    () -> codec.decompress(broken, 0, broken.length, new byte[1000]));
            final byte[] back = new byte[1000];
            codec.decompress(packed, 0, packed.length, back);
            assertArrayEquals(data, back);
        }
    }

    @Test
    void rejectsWrongDeclaredSize() throws Exception {
        try (ZstdCodec codec = new ZstdCodec(3)) {
            final byte[] data = new byte[1000];
            final byte[] packed = compress(codec, data);
            assertThrows(
                    PumpCorruptException.class,
                    () -> codec.decompress(packed, 0, packed.length, new byte[999]));
            assertThrows(
                    PumpCorruptException.class,
                    () -> codec.decompress(packed, 0, packed.length, new byte[1001]));
        }
    }

    @Test
    void rejectsInvalidLevel() {
        assertThrows(IllegalArgumentException.class, () -> new ZstdCodec(1000));
    }
}
