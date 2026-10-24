package net.pyrocube.core.pump.region;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.TestChunks;
import net.pyrocube.core.pump.chunk.ChunkFrame;
import net.pyrocube.core.pump.chunk.EncodedChunk;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.io.ZstdCodec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash recovery of the region commit protocol (§6.2), STRICT durability.
 *
 * <p>Two complementary checks:
 *
 * <ul>
 *   <li>The sweep tests build the on-disk state a power loss can leave. Everything written since the
 *       last flush barrier may or may not have reached the disk, in any combination, so for each
 *       barrier interval every subset of the written 512 byte units is applied to the last durable
 *       image: the data phase (new frames, old table) and the header phase (new table, data already
 *       durable). Each image must open, read back only complete old or new chunks, and accept further
 *       commits.
 *   <li>The kill test SIGKILLs a real JVM that is committing in a tight loop and checks what is
 *       left. It covers a process crash only: the page cache survives a SIGKILL, so it cannot
 *       exercise the flush barriers, the sweep does that.
 * </ul>
 *
 * <p>A table write torn between units can leave some slots of one batch old and others new. Only the
 * per slot guarantee (old or new, never torn) is asserted, not batch atomicity.
 */
class CrashRecoveryTest {
    private static final ZstdCodec CODEC = new ZstdCodec(3);
    private static final int HEADER = PumpFormat.HEADER_BYTES;
    private static final int UNIT = 512;

    /** Slots rewritten by the batch under test, one per table unit: [3] is deleted, [9] is new. */
    private static final int[] BATCH = {7, 107, 207, 307, 407, 507, 607, 707, 807, 907};

    private static final int NEW_SLOT = 9;
    private static final int DELETED_SLOT = 3;

    @TempDir Path dir;

    @AfterAll
    static void close() {
        CODEC.close();
    }

    // ---- sweep -----------------------------------------------------------------------------

    /** Version 0 means absent. */
    private static int oldVersion(int slot) {
        return slot == BATCH[NEW_SLOT] ? 0 : 1;
    }

    private static int newVersion(int slot) {
        return slot == BATCH[DELETED_SLOT] ? 0 : 2;
    }

    private static boolean inBatch(int slot) {
        return Arrays.stream(BATCH).anyMatch(s -> s == slot);
    }

    /** Slots present before the batch: the batch ones plus a spread of untouched ones. */
    private static List<Integer> populatedSlots() {
        final List<Integer> slots = new ArrayList<>();
        for (int s : BATCH) slots.add(s);
        for (int i = 0; i < 41; i++) slots.add(i * 25 + 3);
        return slots;
    }

    /** Deterministic incompressible payload, 8 byte tag + 200..7200 bytes, so frames differ in size. */
    private static byte[] payload(int slot, int version) {
        final Random random = new Random(slot * 131L + version);
        final byte[] bytes = new byte[208 + random.nextInt(7000)];
        random.nextBytes(bytes);
        ByteBuffer.wrap(bytes).putInt(slot).putInt(version);
        return bytes;
    }

    private static EncodedChunk frame(int slot, int version) throws IOException {
        final byte[] payload = payload(slot, version);
        return ChunkFrame.frame(payload, 0, payload.length, CODEC);
    }

    private static PumpRegionFile open(Path file) throws IOException {
        return PumpRegionFile.open(file, 0, 0, PumpRegionFile.Mode.READ_WRITE, Durability.STRICT);
    }

    /** The version stored in a slot (0 if empty), after checking the frame checksum and content. */
    private static int versionOf(PumpRegionFile region, int slot) throws IOException {
        final byte[] stored = region.readFrame(slot);
        if (stored == null) return 0;
        final byte[] decoded = ChunkFrame.decode(stored, 0, stored.length, CODEC);
        final int version = ByteBuffer.wrap(decoded).getInt(4);
        assertArrayEquals(payload(slot, version), decoded, "slot " + slot + " holds torn data");
        return version;
    }

    private record Images(byte[] before, byte[] after) {}

    /** The durable image before the batch, and the image after it ran to completion. */
    private Images commitBatch() throws Exception {
        final Path file = dir.resolve("r.0.0.pump");
        try (PumpRegionFile r = open(file)) {
            for (int slot : populatedSlots()) {
                if (oldVersion(slot) != 0) r.write(slot, frame(slot, 1));
            }
        }
        final byte[] before = Files.readAllBytes(file);
        try (PumpRegionFile r = open(file)) {
            final List<PumpRegionFile.Write> batch = new ArrayList<>();
            for (int slot : BATCH) {
                batch.add(
                        newVersion(slot) == 0
                                ? PumpRegionFile.Write.delete(slot)
                                : PumpRegionFile.Write.put(slot, frame(slot, 2)));
            }
            r.commit(batch);
        }
        return new Images(before, Files.readAllBytes(file));
    }

    /**
     * Opens a crashed image, checks every slot, then proves the file is still usable by committing
     * the whole batch again. {@code allowNew} says whether batch slots may already be at the new
     * version. Returns how many batch slots were new.
     */
    private int recover(byte[] image, boolean allowNew) throws Exception {
        final Path file = dir.resolve("crashed/r.0.0.pump");
        Files.createDirectories(file.getParent());
        Files.write(file, image);
        int newCount = 0;
        try (PumpRegionFile r = open(file)) {
            for (int slot : populatedSlots()) {
                final int found = versionOf(r, slot);
                if (found == oldVersion(slot)) continue;
                assertTrue(
                        allowNew && inBatch(slot) && found == newVersion(slot),
                        "slot " + slot + " has version " + found);
                newCount++;
            }
            // Recovery must leave a consistent allocator: rewrite everything, nothing may collide.
            final List<PumpRegionFile.Write> again = new ArrayList<>();
            for (int slot : BATCH) again.add(PumpRegionFile.Write.put(slot, frame(slot, 3)));
            r.commit(again);
        }
        try (PumpRegionFile r = open(file)) {
            for (int slot : populatedSlots()) {
                assertEquals(inBatch(slot) ? 3 : 1, versionOf(r, slot), "slot " + slot + " after reuse");
            }
        }
        return newCount;
    }

    private static boolean differ(byte[] a, byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            final byte x = i < a.length ? a[i] : 0;
            final byte y = i < b.length ? b[i] : 0;
            if (x != y) return true;
        }
        return false;
    }

    private static void copyUnit(byte[] from, byte[] to, int start, int end) {
        for (int i = start; i < Math.min(end, from.length); i++) to[i] = from[i];
    }

    /** Phase 3: data is durable, the table write persists any subset of its changed units. */
    @Test
    void tableWriteTornAtAnyUnitSubset() throws Exception {
        final Images images = commitBatch();
        final List<Integer> changed = new ArrayList<>();
        for (int u = 0; u * UNIT < HEADER; u++) {
            if (differ(images.before, images.after, u * UNIT, Math.min((u + 1) * UNIT, HEADER))) changed.add(u);
        }
        assertTrue(changed.size() >= BATCH.length, "batch should touch " + BATCH.length + " table units: " + changed);

        // Durable body: the new frames sit in free space, the old ones are still there (phase 4 pending).
        final byte[] body = Arrays.copyOf(images.before, Math.max(images.before.length, images.after.length));
        System.arraycopy(images.after, HEADER, body, HEADER, images.after.length - HEADER);

        int mixed = 0;
        for (int mask = 0; mask < 1 << changed.size(); mask++) {
            final byte[] image = body.clone();
            for (int bit = 0; bit < changed.size(); bit++) {
                if ((mask & 1 << bit) == 0) continue;
                final int unit = changed.get(bit);
                copyUnit(images.after, image, unit * UNIT, Math.min((unit + 1) * UNIT, HEADER));
            }
            final int newCount = recover(image, true);
            if (newCount > 0 && newCount < BATCH.length) mixed++;
        }
        System.out.printf(
                "table sweep: %d images over %d changed units, %d left the batch partly applied%n",
                1 << changed.size(), changed.size(), mixed);
    }

    /** Phase 2: old table, any subset of the new frames' units reached the disk. */
    @Test
    void unsyncedFramesNeverDisturbTheOldVersion() throws Exception {
        final Images images = commitBatch();
        final List<Integer> dirty = new ArrayList<>();
        for (int u = 0; HEADER + u * UNIT < images.after.length; u++) {
            final int from = HEADER + u * UNIT;
            if (differ(images.before, images.after, from, Math.min(from + UNIT, images.after.length))) dirty.add(u);
        }
        assertTrue(dirty.size() > BATCH.length, "new frames should span several units");

        final Random random = new Random(42);
        final List<boolean[]> subsets = new ArrayList<>();
        subsets.add(new boolean[dirty.size()]); // nothing persisted
        for (int prefix = 1; prefix <= dirty.size(); prefix++) { // every prefix, the last one is "all"
            final boolean[] keep = new boolean[dirty.size()];
            Arrays.fill(keep, 0, prefix, true);
            subsets.add(keep);
        }
        for (int i = 0; i < 300; i++) { // arbitrary reordering of the writes
            final boolean[] keep = new boolean[dirty.size()];
            for (int j = 0; j < keep.length; j++) keep[j] = random.nextBoolean();
            subsets.add(keep);
        }

        for (boolean[] keep : subsets) {
            int length = images.before.length;
            for (int j = 0; j < keep.length; j++) {
                if (keep[j]) length = Math.max(length, Math.min(HEADER + (dirty.get(j) + 1) * UNIT, images.after.length));
            }
            final byte[] image = Arrays.copyOf(images.before, length);
            for (int j = 0; j < keep.length; j++) {
                if (!keep[j]) continue;
                final int from = HEADER + dirty.get(j) * UNIT;
                copyUnit(images.after, image, from, from + UNIT);
            }
            assertEquals(0, recover(image, false));
        }
    }

    /** Phase 4 interrupted: new table durable, the old frames still occupy the file. */
    @Test
    void crashBeforeReclaimKeepsTheNewVersion() throws Exception {
        final Images images = commitBatch();
        final byte[] image = Arrays.copyOf(images.before, Math.max(images.before.length, images.after.length));
        System.arraycopy(images.after, 0, image, 0, images.after.length);
        assertEquals(BATCH.length, recover(image, true));
    }

    // ---- real process kill -----------------------------------------------------------------

    private static final int KILL_ROUNDS = 12;
    private static final int CHUNKS = 48; // x 0..47: two regions, the second one partly filled

    /** Chunk {@code i} at generation {@code g}; generation 0 is the seed, then every third chunk per generation. */
    private static PumpChunk chunk(int i, int g) {
        return TestChunks.terrain(i, 0, g, 4 + g % 6);
    }

    private static boolean writtenIn(int i, int g) {
        return g == 0 || g % 3 == i % 3;
    }

    /** Runs in the child JVM: seed generation 0, announce each generation, commit it, forever. */
    public static final class Writer {
        public static void main(String[] args) throws Exception {
            try (PumpStorage storage = PumpStorage.open(Path.of(args[0]))) {
                final List<PumpChunk> seed = new ArrayList<>();
                for (int i = 0; i < CHUNKS; i++) seed.add(chunk(i, 0));
                storage.writeAll(seed);
                System.out.println("ready");
                System.out.flush();
                for (int g = 1; ; g++) {
                    System.out.println(g); // the commit of g is in flight from here on
                    System.out.flush();
                    final List<PumpChunk> batch = new ArrayList<>();
                    for (int i = 0; i < CHUNKS; i++) if (writtenIn(i, g)) batch.add(chunk(i, g));
                    storage.writeAll(batch);
                }
            }
        }
    }

    @Test
    void killedWriterLeavesOnlyCompleteChunks() throws Exception {
        final Random random = new Random(7);
        int generations = 0;
        for (int round = 0; round < KILL_ROUNDS; round++) {
            final Path world = dir.resolve("kill-" + round);
            final int announced = runAndKill(world, 40 + random.nextInt(300));
            generations += announced;

            try (PumpStorage storage = PumpStorage.open(world)) {
                for (int i = 0; i < CHUNKS; i++) {
                    // Last generation that certainly committed, and the one that was in flight.
                    int committed = 0;
                    for (int g = announced - 1; g >= 1; g--) {
                        if (writtenIn(i, g)) {
                            committed = g;
                            break;
                        }
                    }
                    final PumpChunk found = storage.read(i, 0);
                    assertNotNull(found, "chunk " + i + " lost, round " + round);
                    final boolean inFlight = announced >= 1 && writtenIn(i, announced);
                    assertTrue(
                            found.equals(chunk(i, committed)) || inFlight && found.equals(chunk(i, announced)),
                            "chunk " + i + " is neither generation " + committed + " nor " + announced + ", round " + round);
                }
                // The recovered world must take writes again.
                final List<PumpChunk> next = new ArrayList<>();
                for (int i = 0; i < CHUNKS; i++) next.add(chunk(i, 1000));
                storage.writeAll(next);
            }
            try (PumpStorage storage = PumpStorage.open(world)) {
                for (int i = 0; i < CHUNKS; i++) assertEquals(chunk(i, 1000), storage.read(i, 0));
            }
        }
        System.out.printf("kill test: %d rounds, %d generations announced in total%n", KILL_ROUNDS, generations);
        assertTrue(generations > 0, "the writer never got going, the test proved nothing");
    }

    /** Starts the writer, waits for the seed, lets it run {@code millis}, SIGKILLs it, returns the last announced generation. */
    private static int runAndKill(Path world, int millis) throws Exception {
        final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final Process process =
                new ProcessBuilder(
                                java,
                                "--enable-native-access=ALL-UNNAMED",
                                "-Xmx256m",
                                "-cp",
                                System.getProperty("java.class.path"),
                                Writer.class.getName(),
                                world.toString())
                        .redirectErrorStream(true)
                        .start();
        final AtomicInteger announced = new AtomicInteger();
        final CountDownLatch ready = new CountDownLatch(1);
        final StringBuilder output = new StringBuilder();
        final Thread reader =
                new Thread(
                        () -> {
                            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                                for (String line; (line = in.readLine()) != null; ) {
                                    if (line.equals("ready")) ready.countDown();
                                    else if (line.matches("\\d+")) announced.set(Integer.parseInt(line));
                                    else output.append(line).append('\n');
                                }
                            } catch (IOException ignored) {
                                // stream closed by the kill
                            }
                        });
        reader.start();
        try {
            assertTrue(ready.await(60, TimeUnit.SECONDS), "writer did not start:\n" + output);
            Thread.sleep(millis);
        } finally {
            process.destroyForcibly(); // SIGKILL
            process.waitFor();
            reader.join();
        }
        return announced.get();
    }
}
