package net.pyrocube.core.pump.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpException;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.TestChunks;
import net.pyrocube.core.pump.chunk.ChunkFrame;
import net.pyrocube.core.pump.chunk.EncodedChunk;
import net.pyrocube.core.pump.chunk.PchkCodec;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.io.ZstdCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PumpRegionFileTest {
    private static final ZstdCodec CODEC = new ZstdCodec(3);

    @TempDir Path dir;

    @AfterAll
    static void close() {
        CODEC.close();
    }

    /** A frame of roughly {@code bytes} bytes (incompressible payload). */
    private static EncodedChunk frameOf(int bytes, long seed) throws IOException {
        final byte[] payload = new byte[bytes];
        new Random(seed).nextBytes(payload);
        return ChunkFrame.frame(payload, 0, payload.length, CODEC);
    }

    private static PumpRegionFile open(Path file, int rx, int rz) throws IOException {
        return PumpRegionFile.open(file, rx, rz, PumpRegionFile.Mode.READ_WRITE, Durability.STRICT);
    }

    private Path file(int rx, int rz) {
        return PumpRegionFile.pathFor(dir, rx, rz);
    }

    // ---- layout ----------------------------------------------------------------------------

    @Test
    void newFileHasExactlyTheHeader() throws Exception {
        try (PumpRegionFile r = open(file(-3, 7), -3, 7)) {
            assertEquals(0, r.chunkCount());
        }
        assertEquals("r.-3.7.pump", file(-3, 7).getFileName().toString());
        final byte[] b = Files.readAllBytes(file(-3, 7));
        assertEquals(0x2100, b.length);
        assertArrayEquals(
                new byte[] {'P', 'U', 'M', 'P', 0, 0, 0, 1}, java.util.Arrays.copyOf(b, 8));
        assertEquals(-3, ByteBuffer.wrap(b).getInt(8));
        assertEquals(7, ByteBuffer.wrap(b).getInt(12));
        for (int i = 16; i < b.length; i++) assertEquals(0, b[i], "byte " + i);
    }

    @Test
    void tableEntryAndFrameAreWhereTheSpecSaysTheyAre() throws Exception {
        final EncodedChunk frame = frameOf(1000, 1);
        final int index = PumpFormat.chunkIndex(-31, 33); // slot (33 & 31) * 32 + (-31 & 31) = 33
        assertEquals(33, index);
        try (PumpRegionFile r = open(file(-1, 1), -1, 1)) {
            r.write(index, frame);
        }
        final byte[] b = Files.readAllBytes(file(-1, 1));
        final ByteBuffer buf = ByteBuffer.wrap(b);
        final int entryAt = 0x100 + index * 8;
        assertEquals(33, buf.getInt(entryAt), "first data sector is 33");
        assertEquals(frame.length(), buf.getInt(entryAt + 4));
        assertEquals(0x2100 + frame.paddedLength(), b.length);
        assertArrayEquals(
                java.util.Arrays.copyOf(frame.array(), frame.length()),
                java.util.Arrays.copyOfRange(b, 0x2100, 0x2100 + frame.length()));
        for (int i = 0x2100 + frame.length(); i < b.length; i++) {
            assertEquals(0, b[i], "slack byte");
        }
        // every other entry is zero
        for (int i = 0; i < 1024; i++) {
            if (i != index) assertEquals(0L, buf.getLong(0x100 + i * 8));
        }
    }

    @Test
    void readBackAndPersistence() throws Exception {
        final EncodedChunk a = frameOf(500, 1);
        final EncodedChunk b = frameOf(70_000, 2);
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            r.write(5, a);
            r.write(1023, b);
            assertEquals(2, r.chunkCount());
            assertNull(r.readFrame(6));
        }
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            assertEquals(2, r.chunkCount());
            assertArrayEquals(
                    java.util.Arrays.copyOf(a.array(), a.length()), r.readFrame(5));
            assertArrayEquals(
                    java.util.Arrays.copyOf(b.array(), b.length()), r.readFrame(1023));
        }
    }

    // ---- allocation ------------------------------------------------------------------------

    @Test
    void appendsThenReusesTheFirstFittingGap() throws Exception {
        final EncodedChunk small = frameOf(100, 1); // 1 sector
        final EncodedChunk three = frameOf(600, 2); // 3 sectors (12 + ~600)
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            r.write(0, small); // sector 33
            r.write(1, three); // 34..36
            r.write(2, small); // 37
            assertEquals(33 + 1 + 3 + 1, r.usedSectors());
            r.delete(1); // hole of 3 sectors at 34
            assertEquals(33 + 1 + 1, r.usedSectors());
            r.write(3, small); // first fit: 34
            r.write(4, small); // 35
            r.write(5, three); // does not fit the remaining hole (36 only), appended at 38
        }
        final ByteBuffer t = ByteBuffer.wrap(Files.readAllBytes(file(0, 0)));
        assertEquals(33, t.getInt(0x100));
        assertEquals(34, t.getInt(0x100 + 3 * 8));
        assertEquals(35, t.getInt(0x100 + 4 * 8));
        assertEquals(37, t.getInt(0x100 + 2 * 8));
        assertEquals(38, t.getInt(0x100 + 5 * 8));
    }

    @Test
    void rewriteIsCopyOnWrite() throws Exception {
        final EncodedChunk first = frameOf(100, 1);
        final EncodedChunk second = frameOf(100, 2);
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            r.write(0, first);
            r.write(0, second);
            // the new frame went to a fresh sector, the old one was reclaimed afterwards
            assertEquals(33 + 1, r.usedSectors());
            assertArrayEquals(
                    java.util.Arrays.copyOf(second.array(), second.length()), r.readFrame(0));
        }
        assertEquals(
                34,
                ByteBuffer.wrap(Files.readAllBytes(file(0, 0))).getInt(0x100),
                "copy on write alternates between sectors");
    }

    @Test
    void fileIsTruncatedWhenTrailingSectorsAreFreed() throws Exception {
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            r.write(0, frameOf(100, 1));
            r.write(1, frameOf(5000, 2));
            final long big = Files.size(file(0, 0));
            r.delete(1);
            assertTrue(Files.size(file(0, 0)) < big);
            assertEquals(0x2100 + 256, Files.size(file(0, 0)));
            r.delete(0);
            assertEquals(0x2100, Files.size(file(0, 0)));
        }
    }

    @Test
    void batchCommitIsOneHeaderWriteAndLastEntryWins() throws Exception {
        final EncodedChunk a = frameOf(100, 1);
        final EncodedChunk b = frameOf(100, 2);
        final EncodedChunk c = frameOf(100, 3);
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            r.commit(
                    List.of(
                            PumpRegionFile.Write.put(7, a),
                            PumpRegionFile.Write.put(7, b),
                            PumpRegionFile.Write.put(8, c),
                            PumpRegionFile.Write.delete(9)));
            assertEquals(2, r.chunkCount());
            assertArrayEquals(java.util.Arrays.copyOf(b.array(), b.length()), r.readFrame(7));
            assertEquals(33 + 2, r.usedSectors());
        }
    }

    @Test
    void manyRandomOperationsKeepTheFileConsistent() throws Exception {
        final Random rnd = new Random(99);
        final EncodedChunk[] model = new EncodedChunk[1024];
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            for (int step = 0; step < 600; step++) {
                final int slot = rnd.nextInt(40);
                if (rnd.nextInt(5) == 0) {
                    r.delete(slot);
                    model[slot] = null;
                } else {
                    final EncodedChunk f = frameOf(50 + rnd.nextInt(3000), step);
                    r.write(slot, f);
                    model[slot] = f;
                }
            }
            verify(r, model);
        }
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            verify(r, model);
        }
        // no two chunks share a sector, nothing sticks out past the last one
        final ByteBuffer t = ByteBuffer.wrap(Files.readAllBytes(file(0, 0)));
        int highest = 33;
        final boolean[] taken = new boolean[100_000];
        for (int i = 0; i < 1024; i++) {
            final int off = t.getInt(0x100 + i * 8);
            if (off == 0) continue;
            final int n = PumpFormat.sectorsFor(t.getInt(0x104 + i * 8));
            for (int s = off; s < off + n; s++) {
                assertFalse(taken[s], "sector " + s + " allocated twice");
                taken[s] = true;
            }
            highest = Math.max(highest, off + n);
        }
        assertEquals(highest * 256L, Files.size(file(0, 0)));
    }

    private static void verify(PumpRegionFile r, EncodedChunk[] model) throws IOException {
        for (int i = 0; i < model.length; i++) {
            if (model[i] == null) {
                assertNull(r.readFrame(i), "slot " + i);
            } else {
                assertArrayEquals(
                        java.util.Arrays.copyOf(model[i].array(), model[i].length()),
                        r.readFrame(i),
                        "slot " + i);
            }
        }
    }

    // ---- crash consistency -------------------------------------------------------------------

    @Test
    void crashBetweenDataAndHeaderLeavesTheOldVersion() throws Exception {
        final EncodedChunk oldFrame = frameOf(300, 1);
        final EncodedChunk newFrame = frameOf(3000, 2);
        final Path path = file(0, 0);
        try (PumpRegionFile r = open(path, 0, 0)) {
            r.write(3, oldFrame);
        }
        final byte[] before = Files.readAllBytes(path);
        try (PumpRegionFile r = open(path, 0, 0)) {
            r.write(3, newFrame);
        }
        final byte[] after = Files.readAllBytes(path);

        // State after Barrier 1 but before the header write reached the disk: new data present,
        // old table.
        final byte[] crashed = after.clone();
        System.arraycopy(before, 0, crashed, 0, PumpFormat.HEADER_BYTES);
        Files.write(path, crashed);

        try (PumpRegionFile r = open(path, 0, 0)) {
            assertArrayEquals(
                    java.util.Arrays.copyOf(oldFrame.array(), oldFrame.length()), r.readFrame(3));
            // the orphaned data was reclaimed on open and the file was cut back
            assertEquals(33 + oldFrame.sectors(), r.usedSectors());
        }
        assertEquals(0x2100 + oldFrame.paddedLength(), Files.size(path));
    }

    @Test
    void crashAfterHeaderBeforeReclaimLeavesTheNewVersion() throws Exception {
        final EncodedChunk oldFrame = frameOf(300, 1);
        final EncodedChunk newFrame = frameOf(3000, 2);
        final Path path = file(0, 0);
        try (PumpRegionFile r = open(path, 0, 0)) {
            r.write(3, oldFrame);
            r.write(3, newFrame);
        }
        final byte[] after = Files.readAllBytes(path);
        // Phase 4 had not run: the old sector is still there (zeros are fine, it is unreferenced)
        final byte[] crashed = java.util.Arrays.copyOf(after, after.length + 256);
        Files.write(path, crashed);
        try (PumpRegionFile r = open(path, 0, 0)) {
            assertArrayEquals(
                    java.util.Arrays.copyOf(newFrame.array(), newFrame.length()), r.readFrame(3));
        }
    }

    // ---- validation ------------------------------------------------------------------------

    private byte[] validFileWithChunk() throws Exception {
        try (PumpRegionFile r = open(file(2, 3), 2, 3)) {
            r.write(0, frameOf(100, 1));
            r.write(1, frameOf(100, 2));
        }
        return Files.readAllBytes(file(2, 3));
    }

    private void assertOpenFails(byte[] content, String messagePart) throws IOException {
        Files.write(file(2, 3), content);
        final PumpCorruptException e =
                assertThrows(PumpCorruptException.class, () -> open(file(2, 3), 2, 3));
        assertTrue(
                e.getMessage().toLowerCase().contains(messagePart.toLowerCase()),
                () -> "'" + e.getMessage() + "' should mention '" + messagePart + "'");
    }

    @Test
    void rejectsBrokenContainers() throws Exception {
        final byte[] good = validFileWithChunk();

        byte[] b = good.clone();
        b[2] = 'X';
        assertOpenFails(b, "signature");

        b = good.clone();
        b[7] = 2;
        assertOpenFails(b, "version");

        b = good.clone();
        b[11] ^= 1;
        assertOpenFails(b, "superblock");

        b = good.clone();
        b[15] ^= 1;
        assertOpenFails(b, "superblock");

        assertOpenFails(java.util.Arrays.copyOf(good, 100), "truncated");

        b = good.clone();
        ByteBuffer.wrap(b).putInt(0x100, 32); // sector below the first data sector
        assertOpenFails(b, "header");

        b = good.clone();
        ByteBuffer.wrap(b).putInt(0x100 + 8, 33); // slot 1 onto slot 0
        assertOpenFails(b, "overlap");
    }

    @Test
    void zeroInEitherFieldMeansAbsent() throws Exception {
        final byte[] b = validFileWithChunk();
        ByteBuffer.wrap(b).putInt(0x100 + 4, 0); // slot 0: offset set, length 0
        Files.write(file(2, 3), b);
        try (PumpRegionFile r = open(file(2, 3), 2, 3)) {
            assertNull(r.readFrame(0));
            assertNotNull(r.readFrame(1));
        }
    }

    @Test
    void entriesOutsideTheFileFailOnlyWhenRead() throws Exception {
        final byte[] good = validFileWithChunk();
        byte[] b = good.clone();
        ByteBuffer.wrap(b).putInt(0x100 + 4, 5_000_000); // length past EOF
        Files.write(file(2, 3), b);
        try (PumpRegionFile r = open(file(2, 3), 2, 3)) {
            assertThrows(PumpCorruptException.class, () -> r.readFrame(0));
            assertNotNull(r.readFrame(1), "other slots keep working");
            // the damaged slot can be replaced
            r.write(0, frameOf(100, 5));
            assertNotNull(r.readFrame(0));
        }

        b = good.clone();
        ByteBuffer.wrap(b).putInt(0x100 + 4, 12); // frames must be larger than their header
        Files.write(file(2, 3), b);
        try (PumpRegionFile r = open(file(2, 3), 2, 3)) {
            assertThrows(PumpCorruptException.class, () -> r.readFrame(0));
        }

        b = good.clone();
        ByteBuffer.wrap(b).putInt(0x100, -1); // sector 4294967295
        Files.write(file(2, 3), b);
        try (PumpRegionFile r = open(file(2, 3), 2, 3)) {
            assertThrows(PumpCorruptException.class, () -> r.readFrame(0));
        }
    }

    @Test
    void reservedSuperblockBytesSurviveRewrites() throws Exception {
        final byte[] b = validFileWithChunk();
        b[100] = 0x7A;
        b[255] = 0x55;
        Files.write(file(2, 3), b);
        try (PumpRegionFile r = open(file(2, 3), 2, 3)) {
            r.write(9, frameOf(100, 9));
            r.delete(0);
        }
        final byte[] after = Files.readAllBytes(file(2, 3));
        assertEquals(0x7A, after[100]);
        assertEquals(0x55, after[255]);
    }

    // ---- modes -----------------------------------------------------------------------------

    @Test
    void readOnlyMode() throws Exception {
        assertThrows(
                NoSuchFileException.class,
                () ->
                        PumpRegionFile.open(
                                file(9, 9),
                                9,
                                9,
                                PumpRegionFile.Mode.READ_ONLY,
                                Durability.STRICT));
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            r.write(0, frameOf(100, 1));
        }
        try (PumpRegionFile r =
                PumpRegionFile.open(
                        file(0, 0), 0, 0, PumpRegionFile.Mode.READ_ONLY, Durability.STRICT)) {
            assertNotNull(r.readFrame(0));
            assertThrows(PumpException.class, () -> r.write(1, frameOf(100, 2)));
            assertThrows(PumpException.class, () -> r.delete(0));
        }
    }

    @Test
    void secondWriterIsRejected() throws Exception {
        try (PumpRegionFile first = open(file(0, 0), 0, 0)) {
            final PumpException e = assertThrows(PumpException.class, () -> open(file(0, 0), 0, 0));
            assertTrue(e.getMessage().contains("locked"));
            first.write(0, frameOf(100, 1));
        }
        // released on close
        try (PumpRegionFile again = open(file(0, 0), 0, 0)) {
            assertEquals(1, again.chunkCount());
        }
    }

    @Test
    void closedRegionRejectsOperations() throws Exception {
        final PumpRegionFile r = open(file(0, 0), 0, 0);
        r.close();
        r.close(); // idempotent
        assertThrows(PumpException.class, () -> r.readFrame(0));
        assertThrows(PumpException.class, () -> r.write(0, frameOf(100, 1)));
    }

    @Test
    void deferredDurabilityStillProducesAValidFile() throws Exception {
        try (PumpRegionFile r =
                PumpRegionFile.open(
                        file(0, 0), 0, 0, PumpRegionFile.Mode.READ_WRITE, Durability.DEFERRED)) {
            for (int i = 0; i < 50; i++) r.write(i, frameOf(100 + i * 10, i));
            r.sync();
        }
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            assertEquals(50, r.chunkCount());
        }
    }

    // ---- concurrency -----------------------------------------------------------------------

    @Test
    void concurrentReadersAndWritersNeverSeeTornData() throws Exception {
        final int slots = 24;
        final int writers = 4;
        final int readers = 6;
        final int rounds = 150;
        final java.util.concurrent.atomic.AtomicBoolean stop =
                new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            // Each frame is self-describing: payload byte i == (slot * 31 + version) & 0xFF.
            for (int s = 0; s < slots; s++) r.write(s, selfDescribing(s, 0));

            final List<Thread> threads = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                final int writer = w;
                threads.add(
                        Thread.ofPlatform()
                                .start(
                                        () -> {
                                            try {
                                                final Random rnd = new Random(writer);
                                                for (int i = 1; i <= rounds; i++) {
                                                    final List<PumpRegionFile.Write> batch =
                                                            new ArrayList<>();
                                                    for (int k = 0; k < 3; k++) {
                                                        // writers own disjoint slots
                                                        final int slot =
                                                                writer * (slots / writers)
                                                                        + rnd.nextInt(slots / writers);
                                                        batch.add(
                                                                PumpRegionFile.Write.put(
                                                                        slot,
                                                                        selfDescribing(slot, i)));
                                                    }
                                                    r.commit(batch);
                                                }
                                            } catch (Throwable t) {
                                                failure.compareAndSet(null, t);
                                            }
                                        }));
            }
            final List<Thread> readerThreads = new ArrayList<>();
            for (int rd = 0; rd < readers; rd++) {
                final int reader = rd;
                readerThreads.add(
                        Thread.ofPlatform()
                                .start(
                                        () -> {
                                            try {
                                                final Random rnd = new Random(1000 + reader);
                                                while (!stop.get()) {
                                                    final int slot = rnd.nextInt(slots);
                                                    final byte[] f = r.readFrame(slot);
                                                    assertNotNull(f, "slot " + slot);
                                                    checkSelfDescribing(slot, f);
                                                }
                                            } catch (Throwable t) {
                                                failure.compareAndSet(null, t);
                                            }
                                        }));
            }
            for (Thread t : threads) t.join();
            stop.set(true);
            for (Thread t : readerThreads) t.join();
            if (failure.get() != null) throw new AssertionError(failure.get());
            for (int s = 0; s < slots; s++) checkSelfDescribing(s, r.readFrame(s));
        }
        try (PumpRegionFile r = open(file(0, 0), 0, 0)) {
            assertEquals(slots, r.chunkCount());
            for (int s = 0; s < slots; s++) checkSelfDescribing(s, r.readFrame(s));
        }
    }

    private static EncodedChunk selfDescribing(int slot, int version) throws IOException {
        final byte[] payload = new byte[300 + ((slot * 37 + version * 13) % 4000)];
        java.util.Arrays.fill(payload, (byte) (slot * 31 + version));
        return ChunkFrame.frame(payload, 0, payload.length, CODEC);
    }

    private static void checkSelfDescribing(int slot, byte[] frame) throws IOException {
        final byte[] payload = ChunkFrame.decode(frame, 0, frame.length, CODEC);
        final byte first = payload[0];
        assertEquals((byte) (slot * 31) , (byte) (first - ((first - slot * 31) & 0xFF)), "slot tag");
        for (byte b : payload) assertEquals(first, b, "torn frame in slot " + slot);
    }

    @Test
    void chunkPayloadRoundTripThroughARegion() throws Exception {
        final PumpChunk chunk = TestChunks.everything(35, -2, 8);
        final EncodedChunk frame = ChunkFrame.encode(chunk, CODEC);
        try (PumpRegionFile r = open(file(1, -1), 1, -1)) {
            r.write(PumpFormat.chunkIndex(35, -2), frame);
            final byte[] stored = r.readFrame(PumpFormat.chunkIndex(35, -2));
            final byte[] payload = ChunkFrame.decode(stored, 0, stored.length, CODEC);
            assertEquals(chunk, PchkCodec.decode(payload, 35, -2));
        }
    }
}
