package net.pyrocube.core.pump.anvil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.concurrent.Parallel;
import net.pyrocube.core.pump.region.ChunkPos;
import net.pyrocube.core.pump.region.Durability;
import net.pyrocube.core.pump.region.PumpOptions;
import net.pyrocube.core.pump.region.PumpStorage;
import net.pyrocube.core.pump.region.RegionPos;

/**
 * Bulk, multithreaded conversion of whole region directories between Anvil ({@code .mca}) and PUMP
 * ({@code .pump}).
 *
 * <p>Regions are processed one after the other so memory stays proportional to one region; within a
 * region the expensive stages (decompress and parse, transcode, compress) run in parallel across
 * all chunks. Per-chunk failures are collected in the {@link ConversionReport} instead of aborting
 * the run. PUMP output is written with {@link Durability#DEFERRED} and flushed once at the end: a
 * conversion that is interrupted must simply be restarted.
 *
 * <p>The Anvil {@code entities/} and {@code poi/} region directories that sit next to the {@code
 * region/} directory are converted too. PUMP has no storage for them, so each chunk's entry travels
 * in its custom data (§5.6) under {@link AnvilTranscoder#ENTITIES_KEY} and {@link
 * AnvilTranscoder#POI_KEY}, and {@link #pumpToAnvil} writes the two directories back. Entries
 * without content (no entities, no point of interest) are not kept, vanilla treats them as absent.
 * A sidecar entry whose chunk is missing from the region, or a sidecar region without a terrain
 * region, cannot be attached to anything and is counted as a warning.
 */
public final class WorldConverter {
    private static final Pattern MCA = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int MAX_REPORTED_FAILURES = 50;

    private final AnvilTranscoder transcoder;
    private final PumpOptions options;

    public WorldConverter(AnvilTranscoder transcoder, PumpOptions options) {
        this.transcoder = transcoder;
        this.options = options.withDurability(Durability.DEFERRED);
    }

    /** Converts every {@code r.X.Z.mca} in {@code anvilDirectory} into {@code pumpDirectory}. */
    public ConversionReport anvilToPump(Path anvilDirectory, Path pumpDirectory)
            throws IOException {
        final long start = System.nanoTime();
        final Counters counters = new Counters();
        final List<RegionFile> regions = listAnvilRegions(anvilDirectory);
        final Path entitiesDirectory = anvilDirectory.resolveSibling("entities");
        final Path poiDirectory = anvilDirectory.resolveSibling("poi");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                PumpStorage storage =
                        PumpStorage.open(pumpDirectory, options.withExecutor(executor))) {
            for (RegionFile region : regions) {
                final List<AnvilRegion.RawChunk> raws =
                        AnvilRegion.readAll(region.path(), region.x(), region.z());
                counters.bytesIn.addAndGet(Files.size(region.path()));
                final Map<Integer, AnvilRegion.RawChunk> entities =
                        sidecar(entitiesDirectory, region, counters);
                final Map<Integer, AnvilRegion.RawChunk> poi =
                        sidecar(poiDirectory, region, counters);
                final List<PumpChunk> converted =
                        Parallel.map(
                                raws,
                                raw -> convertToPump(raw, region, entities, poi, counters),
                                executor,
                                options.parallelism());
                // whatever was not taken belongs to a chunk the region does not have
                counters.warnings.addAndGet(entities.size() + poi.size());
                final List<PumpChunk> ok = new ArrayList<>(converted.size());
                for (PumpChunk chunk : converted) if (chunk != null) ok.add(chunk);
                storage.writeAll(ok);
                counters.converted.addAndGet(ok.size());
                counters.regions.incrementAndGet();
            }
            orphanSidecars(entitiesDirectory, regions, counters);
            orphanSidecars(poiDirectory, regions, counters);
            storage.flush();
        }
        long out = 0;
        if (Files.isDirectory(pumpDirectory)) {
            try (DirectoryStream<Path> stream =
                    Files.newDirectoryStream(pumpDirectory, "r.*.*.pump")) {
                for (Path p : stream) out += Files.size(p);
            }
        }
        return counters.report(out, Duration.ofNanos(System.nanoTime() - start));
    }

    private PumpChunk convertToPump(
            AnvilRegion.RawChunk raw,
            RegionFile region,
            Map<Integer, AnvilRegion.RawChunk> entities,
            Map<Integer, AnvilRegion.RawChunk> poi,
            Counters counters) {
        final int expectedX = PumpFormat.chunkXOf(region.x(), raw.index());
        final int expectedZ = PumpFormat.chunkZOf(region.z(), raw.index());
        try {
            final Transcoded<PumpChunk> result = transcoder.toPump(raw.parse());
            final PumpChunk chunk = result.value();
            if (chunk.x() != expectedX || chunk.z() != expectedZ) {
                throw new IOException(
                        "chunk claims position "
                                + chunk.x()
                                + ", "
                                + chunk.z()
                                + " but is stored at "
                                + expectedX
                                + ", "
                                + expectedZ);
            }
            counters.warnings.addAndGet(result.warnings().size());
            return withSidecars(
                    chunk,
                    take(
                            entities,
                            raw.index(),
                            "Entities",
                            AnvilTranscoder.ENTITIES_KEY,
                            region,
                            counters),
                    take(poi, raw.index(), "Sections", AnvilTranscoder.POI_KEY, region, counters));
        } catch (IOException | RuntimeException e) {
            counters.fail(
                    "chunk "
                            + expectedX
                            + ", "
                            + expectedZ
                            + " in "
                            + region.path().getFileName()
                            + ": "
                            + e.getMessage());
            return null;
        }
    }

    /** The raw entries of a sidecar region by slot, empty if the region has no such file. */
    private static Map<Integer, AnvilRegion.RawChunk> sidecar(
            Path directory, RegionFile region, Counters counters) throws IOException {
        final Map<Integer, AnvilRegion.RawChunk> bySlot = new ConcurrentHashMap<>();
        final Path file = directory.resolve(AnvilRegion.fileName(region.x(), region.z()));
        if (!Files.isRegularFile(file)) return bySlot;
        counters.bytesIn.addAndGet(Files.size(file));
        for (AnvilRegion.RawChunk raw : AnvilRegion.readAll(file, region.x(), region.z())) {
            bySlot.put(raw.index(), raw);
        }
        return bySlot;
    }

    /**
     * Removes and parses a chunk's sidecar entry. Returns {@code null} when there is none, when it
     * has no content ({@code contentKey} missing or empty) or when it cannot be parsed, the latter
     * is reported as a failure without dropping the chunk itself.
     */
    private static CompoundBinaryTag take(
            Map<Integer, AnvilRegion.RawChunk> sidecar,
            int slot,
            String contentKey,
            String what,
            RegionFile region,
            Counters counters) {
        final AnvilRegion.RawChunk raw = sidecar.remove(slot);
        if (raw == null) return null;
        try {
            final CompoundBinaryTag entry = raw.parse();
            final boolean empty =
                    entry.get(contentKey) instanceof net.kyori.adventure.nbt.ListBinaryTag list
                            ? list.size() == 0
                            : entry.getCompound(contentKey).isEmpty();
            return empty ? null : entry;
        } catch (IOException | RuntimeException e) {
            counters.fail(
                    what
                            + " of slot "
                            + slot
                            + " in "
                            + region.path().getFileName()
                            + " kept out of the result: "
                            + e.getMessage());
            return null;
        }
    }

    private static PumpChunk withSidecars(
            PumpChunk chunk, CompoundBinaryTag entities, CompoundBinaryTag poi) {
        if (entities == null && poi == null) return chunk;
        final CompoundBinaryTag.Builder custom = CompoundBinaryTag.builder();
        if (chunk.customData() != null) custom.put(chunk.customData());
        if (entities != null) custom.put(AnvilTranscoder.ENTITIES_KEY, entities);
        if (poi != null) custom.put(AnvilTranscoder.POI_KEY, poi);
        return chunk.toBuilder().customData(custom.build()).build();
    }

    /**
     * Sidecar regions with no terrain region of the same coordinates hold data nothing can carry.
     */
    private static void orphanSidecars(Path directory, List<RegionFile> terrain, Counters counters)
            throws IOException {
        for (RegionFile file : listAnvilRegions(directory)) {
            if (terrain.stream().anyMatch(r -> r.x() == file.x() && r.z() == file.z())) continue;
            counters.bytesIn.addAndGet(Files.size(file.path()));
            counters.warnings.addAndGet(
                    AnvilRegion.readAll(file.path(), file.x(), file.z()).size());
        }
    }

    /** Converts every PUMP region in {@code pumpDirectory} into {@code r.X.Z.mca} files. */
    public ConversionReport pumpToAnvil(Path pumpDirectory, Path anvilDirectory)
            throws IOException {
        final long start = System.nanoTime();
        final Counters counters = new Counters();
        Files.createDirectories(anvilDirectory);
        final Path entitiesDirectory = anvilDirectory.resolveSibling("entities");
        final Path poiDirectory = anvilDirectory.resolveSibling("poi");
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                PumpStorage storage =
                        PumpStorage.open(
                                pumpDirectory, options.withExecutor(executor).withReadOnly(true))) {
            for (RegionPos region : storage.regions()) {
                final Path pumpFile =
                        pumpDirectory.resolve(PumpFormat.regionFileName(region.x(), region.z()));
                counters.bytesIn.addAndGet(Files.size(pumpFile));
                final List<ChunkPos> positions = storage.chunksIn(region);
                final List<Packed> packed =
                        Parallel.map(
                                positions,
                                pos -> convertToAnvil(storage, pos, counters),
                                executor,
                                options.parallelism());
                final byte[][] bySlot = new byte[1024][];
                final byte[][] entitiesBySlot = new byte[1024][];
                final byte[][] poiBySlot = new byte[1024][];
                int converted = 0;
                for (int i = 0; i < positions.size(); i++) {
                    final Packed p = packed.get(i);
                    if (p == null) continue;
                    final int slot = positions.get(i).index();
                    bySlot[slot] = p.chunk();
                    entitiesBySlot[slot] = p.entities();
                    poiBySlot[slot] = p.poi();
                    converted++;
                }
                final String name = AnvilRegion.fileName(region.x(), region.z());
                final Path target = anvilDirectory.resolve(name);
                AnvilRegion.write(target, bySlot, 2);
                counters.bytesOut.addAndGet(Files.size(target));
                writeSidecar(entitiesDirectory.resolve(name), entitiesBySlot, counters);
                writeSidecar(poiDirectory.resolve(name), poiBySlot, counters);
                counters.converted.addAndGet(converted);
                counters.regions.incrementAndGet();
            }
        }
        return counters.report(
                counters.bytesOut.get(), Duration.ofNanos(System.nanoTime() - start));
    }

    /** A chunk and its sidecar entries, each already zlib compressed, {@code null} when absent. */
    private record Packed(byte[] chunk, byte[] entities, byte[] poi) {}

    private Packed convertToAnvil(PumpStorage storage, ChunkPos pos, Counters counters) {
        try {
            final PumpChunk chunk = storage.read(pos.x(), pos.z());
            if (chunk == null) return null;
            final Transcoded<CompoundBinaryTag> result = transcoder.toAnvil(chunk);
            counters.warnings.addAndGet(result.warnings().size());
            final CompoundBinaryTag custom = chunk.customData();
            return new Packed(
                    zlib(result.value()),
                    custom != null
                                    && custom.get(AnvilTranscoder.ENTITIES_KEY)
                                            instanceof CompoundBinaryTag e
                            ? zlib(e)
                            : null,
                    custom != null
                                    && custom.get(AnvilTranscoder.POI_KEY)
                                            instanceof CompoundBinaryTag p
                            ? zlib(p)
                            : null);
        } catch (IOException | RuntimeException e) {
            counters.fail("chunk " + pos.x() + ", " + pos.z() + ": " + e.getMessage());
            return null;
        }
    }

    private static byte[] zlib(CompoundBinaryTag tag) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
        BinaryTagIO.writer().write(tag, bytes, BinaryTagIO.Compression.ZLIB);
        return bytes.toByteArray();
    }

    /** Writes a sidecar region if any slot has an entry, creating its directory on first use. */
    private static void writeSidecar(Path file, byte[][] bySlot, Counters counters)
            throws IOException {
        if (java.util.Arrays.stream(bySlot).allMatch(java.util.Objects::isNull)) return;
        Files.createDirectories(file.getParent());
        AnvilRegion.write(file, bySlot, 2);
        counters.bytesOut.addAndGet(Files.size(file));
    }

    private record RegionFile(Path path, int x, int z) {}

    private static List<RegionFile> listAnvilRegions(Path directory) throws IOException {
        final List<RegionFile> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "r.*.*.mca")) {
            for (Path p : stream) {
                final Matcher m = MCA.matcher(p.getFileName().toString());
                if (m.matches()) {
                    out.add(
                            new RegionFile(
                                    p, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                }
            }
        }
        out.sort(Comparator.comparingInt(RegionFile::x).thenComparingInt(RegionFile::z));
        return out;
    }

    private static final class Counters {
        final AtomicInteger regions = new AtomicInteger();
        final AtomicInteger converted = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger warnings = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong bytesIn =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong bytesOut =
                new java.util.concurrent.atomic.AtomicLong();
        final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        void fail(String message) {
            if (failed.incrementAndGet() <= MAX_REPORTED_FAILURES) failures.add(message);
        }

        ConversionReport report(long bytesOut, Duration elapsed) {
            return new ConversionReport(
                    regions.get(),
                    converted.get(),
                    failed.get(),
                    warnings.get(),
                    List.copyOf(failures),
                    bytesIn.get(),
                    bytesOut,
                    elapsed);
        }
    }
}
