package net.pyrocube.core.pump.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpException;
import net.pyrocube.core.pump.TestChunks;
import net.pyrocube.core.pump.chunk.PumpChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PumpStorageTest {
    @TempDir Path dir;

    private static PumpChunk chunk(int x, int z) {
        return TestChunks.terrain(x, z, 5, 24);
    }

    @Test
    void writeThenRead() throws Exception {
        try (PumpStorage s = PumpStorage.open(dir.resolve("region"))) {
            assertNull(s.read(1, 2));
            assertFalse(s.exists(1, 2));
            s.write(chunk(1, 2));
            assertTrue(s.exists(1, 2));
            assertEquals(chunk(1, 2), s.read(1, 2));
            s.delete(1, 2);
            assertNull(s.read(1, 2));
            s.delete(100, 100); // absent region is a no-op
        }
    }

    @Test
    void persistsAcrossInstances() throws Exception {
        try (PumpStorage s = PumpStorage.open(dir)) {
            s.write(TestChunks.everything(-1, -1, 3));
        }
        try (PumpStorage s = PumpStorage.open(dir)) {
            assertEquals(TestChunks.everything(-1, -1, 3), s.read(-1, -1));
        }
        assertTrue(Files.exists(dir.resolve("r.-1.-1.pump")));
    }

    @Test
    void chunksLandInTheRightRegionFiles() throws Exception {
        try (PumpStorage s = PumpStorage.open(dir)) {
            s.write(chunk(0, 0));
            s.write(chunk(31, 31));
            s.write(chunk(32, 0));
            s.write(chunk(-1, -32));
            s.write(chunk(-33, 5));
            assertEquals(
                    List.of(
                            new RegionPos(-2, 0),
                            new RegionPos(-1, -1),
                            new RegionPos(0, 0),
                            new RegionPos(1, 0)),
                    s.regions());
            assertEquals(
                    List.of(new ChunkPos(0, 0), new ChunkPos(31, 31)),
                    s.chunksIn(new RegionPos(0, 0)));
            assertEquals(List.of(new ChunkPos(-1, -32)), s.chunksIn(new RegionPos(-1, -1)));
            assertEquals(List.of(), s.chunksIn(new RegionPos(5, 5)));
        }
    }

    @Test
    void bulkWriteAndReadAcrossManyRegions() throws Exception {
        final List<PumpChunk> chunks = new ArrayList<>();
        final List<ChunkPos> positions = new ArrayList<>();
        for (int x = -40; x < 40; x += 3) {
            for (int z = -40; z < 40; z += 3) {
                chunks.add(chunk(x, z));
                positions.add(new ChunkPos(x, z));
            }
        }
        try (PumpStorage s = PumpStorage.open(dir)) {
            s.writeAll(chunks);
            positions.add(new ChunkPos(1000, 1000));
            final List<PumpChunk> back = s.readAll(positions);
            assertEquals(positions.size(), back.size());
            for (int i = 0; i < chunks.size(); i++) assertEquals(chunks.get(i), back.get(i));
            assertNull(back.get(back.size() - 1));
            final PumpStats stats = s.stats();
            assertEquals(chunks.size(), stats.chunksWritten());
            assertEquals(chunks.size(), stats.chunksRead());
            assertTrue(stats.compressionRatio() > 1.5, "ratio " + stats.compressionRatio());
            // one commit per region instead of one per chunk
            assertTrue(stats.commits() <= 16, "commits " + stats.commits());
        }
    }

    @Test
    void duplicateChunksInABatchKeepTheLast() throws Exception {
        final PumpChunk first = chunk(3, 3);
        final PumpChunk second = TestChunks.terrain(3, 3, 99, 10);
        try (PumpStorage s = PumpStorage.open(dir)) {
            s.writeAll(List.of(first, second));
            assertEquals(second, s.read(3, 3));
        }
    }

    @Test
    void openFileLimitIsHonored() throws Exception {
        final PumpOptions options = PumpOptions.defaults().withMaxOpenRegions(2);
        try (PumpStorage s = PumpStorage.open(dir, options)) {
            for (int r = 0; r < 6; r++) s.write(chunk(r * 32, 0));
            assertTrue(s.openRegions() <= 2, "open " + s.openRegions());
            for (int r = 0; r < 6; r++) assertEquals(chunk(r * 32, 0), s.read(r * 32, 0));
            assertTrue(s.openRegions() <= 2);
            assertTrue(s.stats().regionsEvicted() >= 4);
        }
    }

    @Test
    void asyncApi() throws Exception {
        try (PumpStorage s = PumpStorage.open(dir)) {
            s.writeAsync(chunk(4, 4)).get();
            assertEquals(chunk(4, 4), s.readAsync(4, 4).get());
            assertNull(s.readAsync(5, 5).get());
            s.writeAllAsync(List.of(chunk(6, 6), chunk(7, 7))).get();
            assertEquals(chunk(7, 7), s.readAsync(7, 7).get());
        }
    }

    @Test
    void asyncFailuresCarryTheOriginalException() throws Exception {
        try (PumpStorage s = PumpStorage.open(dir)) {
            s.write(chunk(0, 0));
        }
        final Path file = dir.resolve("r.0.0.pump");
        final byte[] bytes = Files.readAllBytes(file);
        bytes[0x2100 + 20] ^= 0x55;
        Files.write(file, bytes);
        try (PumpStorage s = PumpStorage.open(dir)) {
            final ExecutionException e =
                    assertThrows(ExecutionException.class, () -> s.readAsync(0, 0).get());
            assertTrue(e.getCause() instanceof PumpCorruptException, "" + e.getCause());
        }
    }

    @Test
    void corruptionNamesTheChunk() throws Exception {
        try (PumpStorage s = PumpStorage.open(dir)) {
            s.write(chunk(2, 3));
            s.write(chunk(4, 5));
        }
        final Path file = dir.resolve("r.0.0.pump");
        final byte[] bytes = Files.readAllBytes(file);
        bytes[0x2100 + 40] ^= (byte) 0xFF;
        Files.write(file, bytes);
        try (PumpStorage s = PumpStorage.open(dir)) {
            final PumpCorruptException e =
                    assertThrows(PumpCorruptException.class, () -> s.read(2, 3));
            assertTrue(e.getMessage().contains("(2, 3)"), e.getMessage());
            assertTrue(e.getMessage().contains("r.0.0.pump"), e.getMessage());
            assertEquals(chunk(4, 5), s.read(4, 5), "other chunks are unaffected");
        }
    }

    @Test
    void positionMismatchIsCorruption() throws Exception {
        // A chunk stored in a slot that does not match its header coordinates
        try (PumpStorage s = PumpStorage.open(dir)) {
            s.write(chunk(1, 0));
        }
        final Path file = dir.resolve("r.0.0.pump");
        final byte[] bytes = Files.readAllBytes(file);
        final java.nio.ByteBuffer t = java.nio.ByteBuffer.wrap(bytes);
        // move the entry of slot 1 to slot 2
        final long entry = t.getLong(0x100 + 8);
        t.putLong(0x100 + 8, 0);
        t.putLong(0x100 + 16, entry);
        Files.write(file, bytes);
        try (PumpStorage s = PumpStorage.open(dir)) {
            final PumpCorruptException e =
                    assertThrows(PumpCorruptException.class, () -> s.read(2, 0));
            assertTrue(e.getMessage().contains("does not match"), e.getMessage());
        }
    }

    @Test
    void readOnlyStorage() throws Exception {
        try (PumpStorage s = PumpStorage.open(dir)) {
            s.write(chunk(0, 0));
        }
        try (PumpStorage s = PumpStorage.open(dir, PumpOptions.defaults().withReadOnly(true))) {
            assertEquals(chunk(0, 0), s.read(0, 0));
            assertNull(s.read(100, 100));
            assertThrows(PumpException.class, () -> s.write(chunk(1, 1)));
            assertThrows(PumpException.class, () -> s.writeAll(List.of(chunk(1, 1))));
            assertThrows(PumpException.class, () -> s.delete(0, 0));
        }
        assertEquals(1, Files.list(dir).count(), "read only access never creates files");
    }

    @Test
    void useAfterCloseFails() throws Exception {
        final PumpStorage s = PumpStorage.open(dir);
        s.write(chunk(0, 0));
        s.close();
        s.close();
        assertThrows(PumpException.class, () -> s.read(0, 0));
    }

    @Test
    void customExecutorIsUsedAndNotShutDown() throws Exception {
        final var pool = Executors.newFixedThreadPool(3);
        try (PumpStorage s = PumpStorage.open(dir, PumpOptions.defaults().withExecutor(pool))) {
            final List<PumpChunk> chunks = new ArrayList<>();
            for (int i = 0; i < 40; i++) chunks.add(chunk(i, 0));
            s.writeAll(chunks);
            assertEquals(chunks.get(39), s.read(39, 0));
        }
        assertFalse(pool.isShutdown());
        pool.shutdown();
    }

    @Test
    void manyThreadsHammerOneStorage() throws Exception {
        final int threads = 8;
        final int perThread = 40;
        try (PumpStorage s = PumpStorage.open(dir, PumpOptions.defaults().withMaxOpenRegions(3))) {
            final List<Thread> all = new ArrayList<>();
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                all.add(
                        Thread.ofVirtual()
                                .start(
                                        () -> {
                                            try {
                                                for (int i = 0; i < perThread; i++) {
                                                    // threads spread over 6 regions, shared files
                                                    final int x = (id * 7 + i * 5) % 192 - 96;
                                                    final int z = id;
                                                    final PumpChunk c = chunk(x, z);
                                                    s.write(c);
                                                    assertEquals(c, s.read(x, z));
                                                }
                                            } catch (Throwable e) {
                                                failure.compareAndSet(null, e);
                                            }
                                        }));
            }
            for (Thread t : all) t.join();
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
}
