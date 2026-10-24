package net.pyrocube.core.pump.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.world.DimensionType;
import net.pyrocube.core.pump.anvil.AnvilRegion;
import net.pyrocube.core.pump.anvil.AnvilTranscoder;
import net.pyrocube.core.pump.anvil.BiomeIds;
import net.pyrocube.core.pump.anvil.UnknownBlockPolicy;
import net.pyrocube.core.pump.chunk.ChunkFrame;
import net.pyrocube.core.pump.chunk.EncodedChunk;
import net.pyrocube.core.pump.chunk.PchkCodec;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.io.XXH64;
import net.pyrocube.core.pump.io.ZstdCodec;
import net.pyrocube.core.pump.minestom.MinestomChunkMapper;
import net.pyrocube.core.pump.minestom.PumpChunkLoader;
import net.pyrocube.core.pump.region.ChunkPos;
import net.pyrocube.core.pump.region.Durability;
import net.pyrocube.core.pump.region.PumpOptions;
import net.pyrocube.core.pump.region.PumpStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Throughput benchmarks of the PUMP implementation. Run with {@code ./gradlew :core:pump:perfTest}.
 *
 * <p>The suite prints a markdown report and saves it to {@code core/pump/build/perf/report.md}.
 * Assertions are limited to correctness and to very loose sanity floors, wall-clock numbers depend
 * on the machine and are meant to be read, not enforced.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PumpPerformanceTest {
    private static final int REGION_CHUNKS = 1024;
    private static final int CORES = Runtime.getRuntime().availableProcessors();

    @TempDir Path dir;

    private final Bench bench = new Bench();
    private List<PumpChunk> chunks;
    private long payloadBytes;

    @BeforeAll
    void setUp() throws Exception {
        final long start = System.nanoTime();
        // Two adjacent regions: x 0..63, z 0..15.
        chunks = new ArrayList<>();
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 16; z++) chunks.add(SyntheticTerrain.chunk(x, z));
        }
        for (PumpChunk c : chunks) payloadBytes += PchkCodec.encode(c).length;
        bench.heading("Environment");
        bench.note(
                String.format(
                        "- %d logical cores, JVM %s, %s",
                        CORES,
                        Runtime.version(),
                        System.getProperty("os.name") + " " + System.getProperty("os.arch")));
        bench.note(
                String.format(
                        "- dataset: %d synthetic overworld chunks (24 sections), %.1f KiB of PCHK payload each on average, generated in %.1f s",
                        chunks.size(),
                        payloadBytes / 1024.0 / chunks.size(),
                        (System.nanoTime() - start) / 1e9));
    }

    // ---------------------------------------------------------------------------------------

    @Test
    void codec() throws Exception {
        bench.heading("Single-thread codec");
        final List<PumpChunk> sample = chunks.subList(0, 256);
        final List<byte[]> payloads = new ArrayList<>();
        for (PumpChunk c : sample) payloads.add(PchkCodec.encode(c));
        long bytes = 0;
        for (byte[] p : payloads) bytes += p.length;
        final double mb = bytes / 1e6;
        final int n = sample.size();

        bench.table("Stage", "chunks/s", "MB/s (uncompressed)");
        double t = Bench.median(2, 5, () -> { for (PumpChunk c : sample) PchkCodec.encode(c); });
        bench.row("PCHK serialize", n / t, mb / t);
        t = Bench.median(2, 5, () -> { for (byte[] p : payloads) PchkCodec.decode(p); });
        bench.row("PCHK parse + validate", n / t, mb / t);
        t = Bench.median(2, 5, () -> { for (byte[] p : payloads) XXH64.hash(p); });
        bench.row("XXH64", n / t, mb / t);

        try (ZstdCodec codec = new ZstdCodec(3)) {
            t = Bench.median(2, 5, () -> { for (byte[] p : payloads) ChunkFrame.frame(p, 0, p.length, codec); });
            bench.row("frame (zstd-3 + XXH64)", n / t, mb / t);
            final List<EncodedChunk> frames = new ArrayList<>();
            for (byte[] p : payloads) frames.add(ChunkFrame.frame(p, 0, p.length, codec));
            t =
                    Bench.median(
                            2,
                            5,
                            () -> {
                                for (EncodedChunk f : frames) ChunkFrame.decode(f.array(), 0, f.length(), codec);
                            });
            bench.row("unframe (verify + zstd)", n / t, mb / t);
            t =
                    Bench.median(
                            2,
                            5,
                            () -> {
                                for (EncodedChunk f : frames) {
                                    PchkCodec.decode(ChunkFrame.decode(f.array(), 0, f.length(), codec));
                                }
                            });
            bench.row("full chunk read from frame", n / t, mb / t);
        }

        bench.heading("Zstandard level trade-off");
        bench.table("Level", "stored KiB/chunk", "ratio", "compress MB/s");
        for (int level : new int[] {1, 3, 6, 9, 12}) {
            try (ZstdCodec codec = new ZstdCodec(level)) {
                long stored = 0;
                for (byte[] p : payloads) stored += ChunkFrame.frame(p, 0, p.length, codec).length();
                final double ct = Bench.median(1, 3, () -> { for (byte[] p : payloads) ChunkFrame.frame(p, 0, p.length, codec); });
                bench.row("zstd-" + level, stored / 1024.0 / n, (double) bytes / stored, mb / ct);
            }
        }
        assertTrue(mb / t > 5, "decoding below 5 MB/s indicates something is badly wrong");
    }

    @Test
    void parallelScaling() throws Exception {
        bench.heading("Multithreaded encode scaling (serialize + compress + checksum)");
        bench.table("Threads", "chunks/s", "speedup");
        final List<PumpChunk> work = chunks.subList(0, 512);
        double base = 0;
        for (int threads : threadCounts()) {
            try (ZstdCodec codec = new ZstdCodec(3)) {
                final ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    final double t = Bench.median(1, 4, () -> runAll(pool, work, c -> ChunkFrame.encode(c, codec)));
                    final double rate = work.size() / t;
                    if (threads == 1) base = rate;
                    bench.row(threads, rate, String.format("%.2fx", rate / base));
                } finally {
                    pool.shutdown();
                }
            }
        }
    }

    @Test
    void storage() throws Exception {
        bench.heading("Region storage");
        final List<ChunkPos> positions = new ArrayList<>();
        for (PumpChunk c : chunks) positions.add(new ChunkPos(c.x(), c.z()));

        bench.table("Operation", "chunks/s", "notes");

        // bulk write, strict durability: two flush barriers per region
        final Path strictDir = dir.resolve("strict");
        double t =
                Bench.median(
                        0,
                        3,
                        () -> {
                            deleteTree(strictDir);
                            try (PumpStorage s = PumpStorage.open(strictDir)) {
                                s.writeAll(chunks);
                            }
                        });
        bench.row("writeAll, STRICT", chunks.size() / t, "2 regions, 2 commits, parallel compression");

        final Path deferredDir = dir.resolve("deferred");
        t =
                Bench.median(
                        0,
                        3,
                        () -> {
                            deleteTree(deferredDir);
                            try (PumpStorage s =
                                    PumpStorage.open(deferredDir, PumpOptions.defaults().withDurability(Durability.DEFERRED))) {
                                s.writeAll(chunks);
                                s.flush();
                            }
                        });
        bench.row("writeAll, DEFERRED", chunks.size() / t, "same, barriers only at the end");

        // one commit per chunk (what a naive saveChunk loop does)
        final Path singleDir = dir.resolve("single");
        final List<PumpChunk> few = chunks.subList(0, 128);
        t =
                Bench.median(
                        0,
                        2,
                        () -> {
                            deleteTree(singleDir);
                            try (PumpStorage s = PumpStorage.open(singleDir)) {
                                for (PumpChunk c : few) s.write(c);
                            }
                        });
        bench.row("write() one by one, STRICT", few.size() / t, "128 chunks, 2 fsyncs each");

        // reads
        try (PumpStorage s = PumpStorage.open(strictDir)) {
            t = Bench.median(1, 5, () -> { for (ChunkPos p : positions) assertNotNull(s.read(p.x(), p.z())); });
            bench.row("read(), 1 thread", positions.size() / t, "decode + verify");
            t = Bench.median(1, 5, () -> assertEquals(positions.size(), s.readAll(positions).size()));
            bench.row("readAll(), " + CORES + " tasks", positions.size() / t, "parallel decode");
        }

        // cold reads: fresh process-level state is not possible, but fresh storage re-opens files
        t =
                Bench.median(
                        1,
                        5,
                        () -> {
                            try (PumpStorage s = PumpStorage.open(strictDir)) {
                                s.readAll(positions);
                            }
                        });
        bench.row("open + readAll()", positions.size() / t, "includes region open + validation");

        bench.heading("Concurrent readers on one storage");
        bench.note(
                "Each thread reads its own stripe of the 1024 chunks, 8 passes (all verified by checksum), after a warm-up.");
        bench.note("");
        bench.table("Threads", "chunks/s", "speedup");
        try (PumpStorage s = PumpStorage.open(strictDir)) {
            double base = 0;
            for (int threads : threadCounts()) {
                final ExecutorService pool = Executors.newFixedThreadPool(threads);
                try {
                    final int passes = 8;
                    final double time =
                            Bench.median(
                                    1,
                                    4,
                                    () -> {
                                        final List<Future<?>> futures = new ArrayList<>();
                                        for (int worker = 0; worker < threads; worker++) {
                                            final int stripe = worker;
                                            futures.add(
                                                    pool.submit(
                                                            () -> {
                                                                for (int pass = 0; pass < passes; pass++) {
                                                                    for (int i = stripe; i < positions.size(); i += threads) {
                                                                        final ChunkPos p = positions.get(i);
                                                                        s.read(p.x(), p.z());
                                                                    }
                                                                }
                                                                return null;
                                                            }));
                                        }
                                        for (Future<?> f : futures) f.get();
                                    });
                    final double rate = (double) positions.size() * passes / time;
                    if (threads == 1) base = rate;
                    bench.row(threads, rate, String.format("%.2fx", rate / base));
                } finally {
                    pool.shutdown();
                }
            }
        }

        bench.heading("Mixed workload: readers while a writer commits");
        try (PumpStorage s = PumpStorage.open(strictDir)) {
            final ExecutorService pool = Executors.newFixedThreadPool(CORES);
            final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            final java.util.concurrent.atomic.LongAdder reads = new java.util.concurrent.atomic.LongAdder();
            final List<Future<?>> readers = new ArrayList<>();
            for (int i = 0; i < CORES - 1; i++) {
                readers.add(
                        pool.submit(
                                () -> {
                                    int k = 0;
                                    while (!stop.get()) {
                                        final ChunkPos p = positions.get((k++ * 7919) % positions.size());
                                        try {
                                            assertNotNull(s.read(p.x(), p.z()));
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        }
                                        reads.increment();
                                    }
                                }));
            }
            final long start = System.nanoTime();
            int commits = 0;
            while (System.nanoTime() - start < 3_000_000_000L) {
                s.writeAll(chunks.subList((commits * 32) % 960, (commits * 32) % 960 + 32));
                commits++;
            }
            stop.set(true);
            for (Future<?> f : readers) f.get();
            pool.shutdown();
            final double seconds = (System.nanoTime() - start) / 1e9;
            bench.note(
                    String.format(
                            "- %d reader threads sustained %,.0f reads/s while a writer committed %,.0f chunks/s (%d batches of 32, strict durability); every read verified",
                            CORES - 1, reads.sum() / seconds, commits * 32 / seconds, commits));
        }
    }

    @Test
    void versusAnvil() throws Exception {
        bench.heading("PUMP vs Anvil (same 256 chunks, one region)");
        final AnvilTranscoder transcoder = new AnvilTranscoder(
                BiomeIds.ofList(List.of("minecraft:the_void", "minecraft:plains", "minecraft:desert", "minecraft:forest", "minecraft:taiga"), "minecraft:plains"),
                UnknownBlockPolicy.FAIL);
        final List<PumpChunk> sample = new ArrayList<>(chunks.subList(0, 0));
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) sample.add(SyntheticTerrain.chunk(x, z));

        final Path pumpDir = dir.resolve("vs-pump");
        final Path mcaFile = dir.resolve("vs-anvil/r.0.0.mca");
        Files.createDirectories(mcaFile.getParent());

        final PumpOptions deferred = PumpOptions.defaults().withDurability(Durability.DEFERRED);
        final double pumpWrite =
                Bench.median(
                        1,
                        3,
                        () -> {
                            deleteTree(pumpDir);
                            try (PumpStorage s = PumpStorage.open(pumpDir, deferred.withParallelism(1))) {
                                s.writeAll(sample);
                                s.flush();
                            }
                        });
        final double pumpWriteParallel =
                Bench.median(
                        1,
                        3,
                        () -> {
                            deleteTree(pumpDir);
                            try (PumpStorage s = PumpStorage.open(pumpDir, deferred)) {
                                s.writeAll(sample);
                                s.flush();
                            }
                        });

        final CompoundBinaryTag[] anvil = new CompoundBinaryTag[1024];
        final double anvilTranscode =
                Bench.median(
                        1,
                        3,
                        () -> {
                            for (PumpChunk c : sample) {
                                anvil[(c.z() & 31) * 32 + (c.x() & 31)] = transcoder.toAnvil(c).value();
                            }
                        });
        final double anvilWrite = Bench.median(1, 3, () -> AnvilRegion.write(mcaFile, anvil));

        final long pumpSize = Files.size(pumpDir.resolve("r.0.0.pump"));
        final long anvilSize = Files.size(mcaFile);
        // Bytes of compressed chunk data without the sector padding each format adds.
        long pumpData = 0;
        try (PumpStorage s = PumpStorage.open(pumpDir)) {
            for (PumpChunk c : sample) {
                pumpData += s.storedLength(c.x(), c.z());
            }
        }
        long anvilData = 0;
        for (var raw : AnvilRegion.readAll(mcaFile, 0, 0)) anvilData += raw.data().length;

        final double pumpRead;
        try (PumpStorage s = PumpStorage.open(pumpDir)) {
            final List<ChunkPos> positions = sample.stream().map(c -> new ChunkPos(c.x(), c.z())).toList();
            pumpRead = Bench.median(1, 5, () -> {
                for (ChunkPos p : positions) s.read(p.x(), p.z());
            });
        }
        final double anvilRead =
                Bench.median(
                        1,
                        5,
                        () -> {
                            for (var raw : AnvilRegion.readAll(mcaFile, 0, 0)) {
                                transcoder.toPump(raw.parse());
                            }
                        });

        bench.table("", "PUMP", "Anvil (zlib)");
        bench.row("file size (KiB), sector padded", pumpSize / 1024.0, anvilSize / 1024.0);
        bench.row("compressed chunk bytes (KiB), no padding", pumpData / 1024.0, anvilData / 1024.0);
        bench.row("write 256 chunks, 1 thread (ms)", pumpWrite * 1000, (anvilTranscode + anvilWrite) * 1000);
        bench.row("write 256 chunks, " + CORES + " threads (ms)", pumpWriteParallel * 1000, "n/a (single threaded here)");
        bench.row("read + decode to chunk model (ms)", pumpRead * 1000, anvilRead * 1000);
        bench.note(
                String.format(
                        "- Sector padding is 256 B for PUMP and 4 KiB for Anvil, so the file sizes also measure how small the chunks are; the unpadded row compares the formats themselves (PUMP data is %.0f%% of Anvil's, and %.1f KiB per chunk). The Anvil write column includes building the NBT tree (%.0f ms) which a real server also pays. Both single-thread rows run the whole pipeline on one thread. The Anvil read column includes block-state name lookups and bit unpacking, as a loader must do.",
                        100.0 * pumpData / anvilData, pumpData / 1024.0 / sample.size(), anvilTranscode * 1000));
    }

    @Test
    void minestomBridge() throws Exception {
        MinecraftServer.init();
        bench.heading("Minestom bridge");
        final Path world = dir.resolve("bridge");
        try (PumpChunkLoader loader = new PumpChunkLoader(world, DimensionType.OVERWORLD.key())) {
            final InstanceContainer instance = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD, loader);
            instance.setGenerator(
                    unit -> {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                final int h = SyntheticTerrain.surface(unit.absoluteStart().blockX() + x, unit.absoluteStart().blockZ() + z);
                                unit.modifier().fillHeight(-64, h - 3, net.minestom.server.instance.block.Block.STONE);
                                unit.modifier().fillHeight(h - 3, h, net.minestom.server.instance.block.Block.DIRT);
                                unit.modifier().setBlock(unit.absoluteStart().blockX() + x, h, unit.absoluteStart().blockZ() + z, net.minestom.server.instance.block.Block.GRASS_BLOCK);
                            }
                        }
                    });
            final List<Chunk> loaded = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) loaded.add(instance.loadChunk(x, z).join());
            }
            final int n = loaded.size();

            bench.table("Stage", "chunks/s", "notes");
            double t = Bench.median(1, 5, () -> { for (Chunk c : loaded) MinestomChunkMapper.toPump(c); });
            bench.row("Chunk -> PumpChunk (snapshot)", n / t, "holds each chunk's write lock only for this step");
            t = Bench.median(0, 3, () -> loader.saveChunks(loaded));
            bench.row("saveChunks (snapshot + compress + commit)", n / t, "256 chunks, strict durability, one region");

            final List<PumpChunk> stored = new ArrayList<>();
            for (Chunk c : loaded) stored.add(loader.storage().read(c.getChunkX(), c.getChunkZ()));
            t = Bench.median(1, 5, () -> { for (PumpChunk p : stored) MinestomChunkMapper.toChunk(instance, p); });
            bench.row("PumpChunk -> Chunk (populate)", n / t, "palette fill, light, heightmaps, block entities");
            final List<net.pyrocube.core.pump.region.ChunkPos> positions = loaded.stream().map(c -> new net.pyrocube.core.pump.region.ChunkPos(c.getChunkX(), c.getChunkZ())).toList();
            t = Bench.median(1, 5, () -> { for (var p : positions) loader.loadChunk(instance, p.x(), p.z()); });
            bench.row("loadChunk end to end", n / t, "read + decode + populate, 1 thread");
        }
    }

    // ---------------------------------------------------------------------------------------

    @org.junit.jupiter.api.AfterAll
    void report() throws Exception {
        bench.save(Path.of("build", "perf", "report.md"));
    }

    private static int[] threadCounts() {
        final List<Integer> counts = new ArrayList<>();
        for (int t = 1; t < CORES; t *= 2) counts.add(t);
        counts.add(CORES);
        return counts.stream().mapToInt(Integer::intValue).toArray();
    }

    private interface Work<T, R> {
        R apply(T value) throws Exception;
    }

    private static <T, R> void runAll(ExecutorService pool, List<T> items, Work<T, R> work) throws Exception {
        final List<Future<R>> futures = new ArrayList<>(items.size());
        for (T item : items) futures.add(pool.submit(() -> work.apply(item)));
        for (Future<R> f : futures) f.get();
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
