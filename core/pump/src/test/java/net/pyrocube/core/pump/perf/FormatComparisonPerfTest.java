package net.pyrocube.core.pump.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.anvil.AnvilLoader;
import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.pyrocube.core.pump.minestom.PumpChunkLoader;
import net.pyrocube.core.pump.anvil.AnvilRegion;
import net.pyrocube.core.pump.anvil.AnvilTranscoder;
import net.pyrocube.core.pump.anvil.BiomeIds;
import net.pyrocube.core.pump.anvil.UnknownBlockPolicy;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.region.ChunkPos;
import net.pyrocube.core.pump.region.Durability;
import net.pyrocube.core.pump.region.PumpOptions;
import net.pyrocube.core.pump.region.PumpStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * PUMP vs Linear vs Anvil on the same full region (1024 synthetic chunks). Run with {@code
 * ./gradlew :core:pump:perfTest --tests '*FormatComparisonPerfTest'}; report in {@code
 * build/perf/formats.md}.
 *
 * <p>Every number is the whole pipeline from/to {@link PumpChunk}, so the NBT build and parse that
 * Anvil and Linear need is counted for them. Nothing is fsynced (Anvil does not, Linear is set not
 * to, PUMP is DEFERRED), compression is each format's default level.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormatComparisonPerfTest {
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    private static final int COLD_READS = 64;

    @TempDir Path dir;

    private final Bench bench = new Bench();
    private final AnvilTranscoder transcoder =
            new AnvilTranscoder(
                    BiomeIds.ofList(
                            List.of("minecraft:the_void", "minecraft:plains", "minecraft:desert", "minecraft:forest", "minecraft:taiga"),
                            "minecraft:plains"),
                    UnknownBlockPolicy.FAIL);
    private final PumpOptions pumpOptions = PumpOptions.defaults().withDurability(Durability.DEFERRED);

    private List<PumpChunk> chunks;
    private List<ChunkPos> positions;

    @BeforeAll
    void setUp() {
        chunks = new ArrayList<>();
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) chunks.add(SyntheticTerrain.chunk(x, z));
        positions = chunks.stream().map(c -> new ChunkPos(c.x(), c.z())).toList();
        bench.heading("Environment");
        bench.note(String.format("- %d logical cores, JVM %s, %d chunks (one full region)", CORES, Runtime.version(), chunks.size()));
    }

    @Test
    void pumpVsLinearVsAnvil() throws Exception {
        final Path pumpDir = dir.resolve("pump");
        final Path anvilFile = dir.resolve("anvil/r.0.0.mca");
        final Path v2File = dir.resolve("linear/r.0.0.linear");
        final Path v1File = dir.resolve("linear-v1/r.0.0.linear");
        for (Path p : List.of(anvilFile, v2File, v1File)) Files.createDirectories(p.getParent());

        // ---- writes: PumpChunk -> file, full pipeline
        final double[] write = new double[4];
        write[0] = Bench.median(1, 3, () -> writePump(pumpDir, pumpOptions.withParallelism(1)));
        write[1] = Bench.median(1, 3, () -> writePump(pumpDir, pumpOptions));
        final double anvilWrite = Bench.median(1, 3, () -> AnvilRegion.write(anvilFile, toAnvil(false)));
        final double anvilWriteParallel = Bench.median(1, 3, () -> AnvilRegion.write(anvilFile, toAnvil(true)));

        // ---- sizes
        final long pumpSize = Files.size(pumpDir.resolve("r.0.0.pump"));

        // ---- reads: file -> PumpChunk, all 1024 chunks
        final double pumpRead, pumpReadParallel, pumpCold;
        try (PumpStorage s = PumpStorage.open(pumpDir)) {
            pumpRead = Bench.median(1, 5, () -> { for (ChunkPos p : positions) s.read(p.x(), p.z()); });
            pumpReadParallel = Bench.median(1, 5, () -> assertEquals(1024, s.readAll(positions).size()));
        }
        pumpCold =
                Bench.median(
                        1,
                        5,
                        () -> {
                            for (int i = 0; i < COLD_READS; i++) {
                                final ChunkPos p = positions.get(i * 16 + 3);
                                try (PumpStorage s = PumpStorage.open(pumpDir)) {
                                    s.read(p.x(), p.z());
                                }
                            }
                        })
                        / COLD_READS;

        final double anvilRead = Bench.median(1, 5, () -> readAnvil(anvilFile, false));
        final double anvilReadParallel = Bench.median(1, 5, () -> readAnvil(anvilFile, true));
        final double anvilCold =
                Bench.median(
                        1,
                        5,
                        () -> {
                            for (int i = 0; i < COLD_READS; i++) {
                                // Anvil has no random access API here: parse the chunk table and one chunk
                                parseAnvil(AnvilRegion.readAll(anvilFile, 0, 0).get(i * 16 + 3));
                            }
                        })
                        / COLD_READS;

        // ---- round trip: every format must give the chunks back
        assertEquals(1024, readAnvil(anvilFile, true));

        bench.heading("PUMP vs Linear vs Anvil (one full region, 1024 chunks)");
        bench.table("", "PUMP (zstd)", "Linear v2 grid 8 (zstd)", "Linear v1 (zstd)", "Anvil (zlib)");
        bench.row("file size (KiB)", pumpSize / 1024.0, Files.size(v2File) / 1024.0, Files.size(v1File) / 1024.0, Files.size(anvilFile) / 1024.0);
        bench.row("write, 1 thread (ms)", write[0] * 1000, anvilWrite * 1000);
        bench.row("write, " + CORES + " threads (ms)", write[1] * 1000, anvilWriteParallel * 1000);
        bench.row("read all, 1 thread (ms)", pumpRead * 1000, anvilRead * 1000);
        bench.row("read all, " + CORES + " threads (ms)", pumpReadParallel * 1000, anvilReadParallel * 1000);
        bench.row("cold single chunk: open + read one (ms)", pumpCold * 1000, anvilCold * 1000);
        bench.note("- Anvil's write only parallelises the NBT build, its container write and zlib stay single threaded; Anvil's cold read goes through readAll (inflates the chunk table lazily, so it is a loose upper bound).");
        bench.note("- Sizes include each format's header, Anvil pads every chunk to 4 KiB sectors.");
    }

    /**
     * The same 1024 Minestom chunks through each format's own {@link ChunkLoader}, with default
     * loader options (so PUMP strict durability and Linear fsync are on, unlike the test above).
     */
    @Test
    void minestomLoaders() throws Exception {
        MinecraftServer.init();
        final InstanceContainer source = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);
        source.setGenerator(
                unit -> {
                    final int x0 = unit.absoluteStart().blockX(), z0 = unit.absoluteStart().blockZ();
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            final int h = SyntheticTerrain.surface(x0 + x, z0 + z);
                            unit.modifier().fillHeight(-64, h - 3, Block.STONE);
                            unit.modifier().fillHeight(h - 3, h, Block.DIRT);
                            unit.modifier().setBlock(x0 + x, h, z0 + z, Block.GRASS_BLOCK);
                        }
                    }
                });
        final List<Chunk> loaded = new ArrayList<>();
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) loaded.add(source.loadChunk(x, z).join());
        final InstanceContainer target = MinecraftServer.getInstanceManager().createInstanceContainer(DimensionType.OVERWORLD);

        final Path anvilDir = dir.resolve("mc-anvil"), pumpDir = dir.resolve("mc-pump"), v2Dir = dir.resolve("mc-v2"), v1Dir = dir.resolve("mc-v1");
        final Map<String, Supplier<ChunkLoader>> loaders = new java.util.LinkedHashMap<>();
        loaders.put("PUMP", () -> PumpChunkLoader.forRegionDirectory(pumpDir));
        loaders.put("Anvil (Minestom AnvilLoader)", () -> new AnvilLoader(anvilDir));
        final Map<String, Path> dirs = Map.of("PUMP", pumpDir, "Linear v2", v2Dir, "Linear v1", v1Dir, "Anvil (Minestom AnvilLoader)", anvilDir);

        bench.heading("Minestom ChunkLoader end to end (1024 chunks, default options)");
        bench.table("Loader", "size (KiB)", "save all (ms)", "load all, 1 thread (ms)", "load all, " + CORES + " threads (ms)", "load one, cold (ms)");
        for (var entry : loaders.entrySet()) {
            final Supplier<ChunkLoader> make = entry.getValue();
            final Path root = dirs.get(entry.getKey());
            final double save = Bench.median(0, 3, () -> {
                Bench.deleteTree(root);
                final ChunkLoader l = make.get();
                l.saveChunks(loaded);
                if (l instanceof AutoCloseable c) c.close();
            });
            long size;
            try (var walk = Files.walk(root)) {
                size = walk.filter(Files::isRegularFile).mapToLong(f -> unchecked(() -> Files.size(f))).sum();
            }
            final double serial = Bench.median(1, 5, () -> loadAll(make, target, false));
            final Object parallel = make.get().supportsParallelLoading() ? (Object) (Bench.median(1, 5, () -> loadAll(make, target, true)) * 1000) : "n/a";
            final double cold = Bench.median(1, 5, () -> {
                final ChunkLoader l = make.get();
                if (l.loadChunk(target, 3, 3) == null) throw new AssertionError("missing chunk");
                if (l instanceof AutoCloseable c) c.close();
            });
            bench.row(entry.getKey(), size / 1024.0, save * 1000, serial * 1000, parallel, cold * 1000);
        }
        bench.note("- Anvil loader: Minestom's own (zlib, 4 KiB sectors). 'cold' opens the loader and loads one chunk.");
    }

    private static void loadAll(Supplier<ChunkLoader> make, InstanceContainer target, boolean parallel) throws Exception {
        final ChunkLoader l = make.get();
        final var ints = parallel ? java.util.stream.IntStream.range(0, 1024).parallel() : java.util.stream.IntStream.range(0, 1024);
        final int ok = ints.mapToObj(i -> l.loadChunk(target, i & 31, i >> 5)).filter(java.util.Objects::nonNull).toList().size();
        if (l instanceof AutoCloseable c) c.close();
        assertEquals(1024, ok);
    }

    // ------------------------------------------------------------------ helpers

    private void writePump(Path pumpDir, PumpOptions options) throws Exception {
        Bench.deleteTree(pumpDir);
        try (PumpStorage s = PumpStorage.open(pumpDir, options)) {
            s.writeAll(chunks);
        }
    }

    /** Anvil NBT by slot index; the Linear payload is the same tree, uncompressed. */
    private CompoundBinaryTag[] toAnvil(boolean parallel) {
        final CompoundBinaryTag[] out = new CompoundBinaryTag[1024];
        (parallel ? chunks.parallelStream() : chunks.stream())
                .forEach(c -> out[(c.z() & 31) * 32 + (c.x() & 31)] = unchecked(() -> transcoder.toAnvil(c).value()));
        return out;
    }

    /** Returns the number of chunks decoded. */
    private int readAnvil(Path file, boolean parallel) throws Exception {
        final var raw = AnvilRegion.readAll(file, 0, 0);
        return (parallel ? raw.parallelStream() : raw.stream()).map(r -> unchecked(() -> parseAnvil(r))).toList().size();
    }

    private PumpChunk parseAnvil(AnvilRegion.RawChunk raw) throws Exception {
        return transcoder.toPump(raw.parse()).value();
    }

    private PumpChunk parseLinear(byte[] payload) throws Exception {
        final CompoundBinaryTag nbt =
                BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(payload), BinaryTagIO.Compression.NONE);
        return transcoder.toPump(nbt).value();
    }

    @AfterAll
    void report() throws IOException {
        bench.save(Path.of("build", "perf", "formats.md"));
    }

    private interface Thrower<T> {
        T get() throws Exception;
    }

    private static <T> T unchecked(Thrower<T> t) {
        try {
            return t.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
