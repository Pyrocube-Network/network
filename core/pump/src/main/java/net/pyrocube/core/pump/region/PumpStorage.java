package net.pyrocube.core.pump.region;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpException;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.PumpLimitException;
import net.pyrocube.core.pump.chunk.ChunkFrame;
import net.pyrocube.core.pump.chunk.EncodedChunk;
import net.pyrocube.core.pump.chunk.PchkCodec;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.concurrent.Parallel;
import net.pyrocube.core.pump.io.ZstdCodec;

/**
 * Chunk storage over a directory of {@code r.X.Z.pump} region files: the thread-safe entry point
 * for reading and writing {@link PumpChunk}s.
 *
 * <h2>Threading model</h2>
 *
 * <ul>
 *   <li>Every method may be called from any number of threads at once.
 *   <li>Reads of any chunks run in parallel. Decompression, checksum verification and parsing
 *       happen on the calling thread outside of every lock, only the positional read of the
 *       compressed bytes touches the region's read lock.
 *   <li>Bulk operations ({@link #writeAll}, {@link #readAll}) fan the CPU heavy stages (encode,
 *       compress, decode) out over the configured executor, with at most {@link
 *       PumpOptions#parallelism()} tasks in flight, and commit each region once.
 *   <li>The number of open file descriptors is bounded: least recently used regions without a
 *       running operation are closed.
 * </ul>
 */
public final class PumpStorage implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(PumpStorage.class.getName());
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.pump");

    /**
     * An open region with a use count. The count is {@code -1} once the region is being closed, a
     * thread can only use a region it managed to {@linkplain #retain() retain}.
     */
    private static final class Slot {
        private static final long TOUCH_INTERVAL_NANOS = 1_000_000L;

        final long key;
        final PumpRegionFile file;
        final AtomicInteger refs = new AtomicInteger();
        volatile long lastUse = System.nanoTime();

        Slot(long key, PumpRegionFile file) {
            this.key = key;
            this.file = file;
        }

        boolean retain() {
            while (true) {
                final int current = refs.get();
                if (current < 0) return false;
                if (refs.compareAndSet(current, current + 1)) return true;
            }
        }

        void touch() {
            // Rarely written, so hot regions do not bounce this cache line between cores.
            final long now = System.nanoTime();
            if (now - lastUse > TOUCH_INTERVAL_NANOS) lastUse = now;
        }

        /** Marks the slot as closing; only succeeds if nobody is using it. */
        boolean tryRetire() {
            return refs.compareAndSet(0, -1);
        }
    }

    private final Path directory;
    private final PumpOptions options;
    private final ZstdCodec codec;
    private final Executor executor;
    private final ExecutorService ownedExecutor;

    /**
     * Open regions. Lookups of an already open region are lock free ({@code get} plus an atomic
     * increment), the lock only serializes opening and evicting.
     */
    private final ConcurrentHashMap<Long, Slot> open = new ConcurrentHashMap<>();

    private final ReentrantLock openLock = new ReentrantLock();
    private volatile boolean closed;

    private final LongAdder chunksRead = new LongAdder();
    private final LongAdder chunksWritten = new LongAdder();
    private final LongAdder payloadBytes = new LongAdder();
    private final LongAdder storedBytes = new LongAdder();
    private final LongAdder commits = new LongAdder();
    private final LongAdder regionsOpened = new LongAdder();
    private final LongAdder regionsEvicted = new LongAdder();

    private PumpStorage(Path directory, PumpOptions options) {
        this.directory = directory;
        this.options = options;
        this.codec = new ZstdCodec(options.compressionLevel());
        if (options.executor() != null) {
            this.executor = options.executor();
            this.ownedExecutor = null;
        } else {
            this.ownedExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.executor = ownedExecutor;
        }
    }

    /**
     * Opens a storage rooted at {@code regionDirectory}, which is created lazily on first write.
     */
    public static PumpStorage open(Path regionDirectory, PumpOptions options) {
        return new PumpStorage(regionDirectory, options);
    }

    public static PumpStorage open(Path regionDirectory) {
        return open(regionDirectory, PumpOptions.defaults());
    }

    public Path directory() {
        return directory;
    }

    public PumpOptions options() {
        return options;
    }

    // ---------------------------------------------------------------------------------------
    // Single chunk operations
    // ---------------------------------------------------------------------------------------

    /**
     * Reads and fully validates a chunk.
     *
     * @return the chunk, or {@code null} if it is not stored
     * @throws PumpCorruptException if the stored data violates the format
     */
    public PumpChunk read(int chunkX, int chunkZ) throws IOException {
        final Slot slot = acquire(chunkX >> 5, chunkZ >> 5, false);
        if (slot == null) return null;
        try {
            final byte[] frame = slot.file.readFrame(PumpFormat.chunkIndex(chunkX, chunkZ));
            if (frame == null) return null;
            try {
                final byte[] payload = ChunkFrame.decode(frame, 0, frame.length, codec);
                final PumpChunk chunk = PchkCodec.decode(payload, chunkX, chunkZ);
                chunksRead.increment();
                payloadBytes.add(payload.length);
                storedBytes.add(frame.length);
                return chunk;
            } catch (PumpLimitException e) {
                throw e;
            } catch (PumpCorruptException e) {
                throw new PumpCorruptException(
                        "Corrupt chunk ("
                                + chunkX
                                + ", "
                                + chunkZ
                                + ") in "
                                + slot.file.path().getFileName()
                                + ": "
                                + e.getMessage(),
                        e);
            }
        } finally {
            release(slot);
        }
    }

    /**
     * The length in bytes of the stored frame of a chunk, 0 if it is not stored. Useful to compare
     * the compressed size of chunks without decoding them.
     */
    public int storedLength(int chunkX, int chunkZ) throws IOException {
        final Slot slot = acquire(chunkX >> 5, chunkZ >> 5, false);
        if (slot == null) return 0;
        try {
            return slot.file.frameLength(PumpFormat.chunkIndex(chunkX, chunkZ));
        } finally {
            release(slot);
        }
    }

    /** Whether a chunk is stored, without decoding it. */
    public boolean exists(int chunkX, int chunkZ) throws IOException {
        final Slot slot = acquire(chunkX >> 5, chunkZ >> 5, false);
        if (slot == null) return false;
        try {
            return slot.file.hasChunk(PumpFormat.chunkIndex(chunkX, chunkZ));
        } finally {
            release(slot);
        }
    }

    /** Encodes, compresses and stores one chunk, paying both flush barriers. */
    public void write(PumpChunk chunk) throws IOException {
        requireWritable();
        final EncodedChunk frame = encode(chunk);
        final Slot slot = acquire(chunk.x() >> 5, chunk.z() >> 5, true);
        try {
            slot.file.write(PumpFormat.chunkIndex(chunk.x(), chunk.z()), frame);
            chunksWritten.increment();
            commits.increment();
        } finally {
            release(slot);
        }
    }

    /** Removes a chunk if present. */
    public void delete(int chunkX, int chunkZ) throws IOException {
        requireWritable();
        final Slot slot = acquire(chunkX >> 5, chunkZ >> 5, false);
        if (slot == null) return;
        try {
            slot.file.delete(PumpFormat.chunkIndex(chunkX, chunkZ));
            commits.increment();
        } finally {
            release(slot);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Bulk operations
    // ---------------------------------------------------------------------------------------

    /**
     * Stores many chunks: all of them are serialized and compressed in parallel, then every touched
     * region is committed once (two flush barriers per region instead of per chunk) with regions
     * committing in parallel. Memory use is proportional to the compressed size of the batch.
     */
    public void writeAll(Collection<PumpChunk> chunks) throws IOException {
        requireWritable();
        if (chunks.isEmpty()) return;
        final List<PumpChunk> list = List.copyOf(chunks);
        final List<EncodedChunk> frames =
                Parallel.map(list, this::encode, executor, options.parallelism());

        final Map<Long, List<PumpRegionFile.Write>> byRegion = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            final PumpChunk c = list.get(i);
            byRegion.computeIfAbsent(
                            PumpFormat.pack(c.x() >> 5, c.z() >> 5), k -> new ArrayList<>())
                    .add(
                            PumpRegionFile.Write.put(
                                    PumpFormat.chunkIndex(c.x(), c.z()), frames.get(i)));
        }
        final List<Map.Entry<Long, List<PumpRegionFile.Write>>> jobs =
                new ArrayList<>(byRegion.entrySet());
        Parallel.map(
                jobs,
                job -> {
                    final Slot slot =
                            acquire(
                                    PumpFormat.unpackX(job.getKey()),
                                    PumpFormat.unpackZ(job.getKey()),
                                    true);
                    try {
                        slot.file.commit(job.getValue());
                        commits.increment();
                        chunksWritten.add(job.getValue().size());
                        return null;
                    } finally {
                        release(slot);
                    }
                },
                executor,
                options.parallelism());
    }

    /**
     * Reads many chunks in parallel.
     *
     * @return one entry per requested position, in order, {@code null} where nothing is stored
     */
    public List<PumpChunk> readAll(List<ChunkPos> positions) throws IOException {
        return Parallel.map(positions, p -> read(p.x(), p.z()), executor, options.parallelism());
    }

    // ---------------------------------------------------------------------------------------
    // Asynchronous variants
    // ---------------------------------------------------------------------------------------

    public CompletableFuture<PumpChunk> readAsync(int chunkX, int chunkZ) {
        return async(() -> read(chunkX, chunkZ));
    }

    public CompletableFuture<Void> writeAsync(PumpChunk chunk) {
        return async(
                () -> {
                    write(chunk);
                    return null;
                });
    }

    public CompletableFuture<Void> writeAllAsync(Collection<PumpChunk> chunks) {
        return async(
                () -> {
                    writeAll(chunks);
                    return null;
                });
    }

    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    /**
     * Runs a blocking operation on its own virtual thread, never on the stage executor: operations
     * wait for stage tasks, so running them there could starve a bounded pool.
     */
    private static <T> CompletableFuture<T> async(IoSupplier<T> op) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                future.complete(op.get());
                            } catch (Throwable t) {
                                future.completeExceptionally(t);
                            }
                        });
        return future;
    }

    // ---------------------------------------------------------------------------------------
    // Enumeration, lifecycle, statistics
    // ---------------------------------------------------------------------------------------

    /** The regions present on disk. */
    public List<RegionPos> regions() throws IOException {
        final List<RegionPos> out = new ArrayList<>();
        if (!Files.isDirectory(directory)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "r.*.*.pump")) {
            for (Path p : stream) {
                final Matcher m = REGION_FILE.matcher(p.getFileName().toString());
                if (!m.matches()) continue;
                try {
                    out.add(
                            new RegionPos(
                                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                } catch (NumberFormatException ignored) {
                    // coordinates outside the int range are not valid regions
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt(RegionPos::x).thenComparingInt(RegionPos::z));
        return out;
    }

    /** The chunks stored in a region, in slot order. */
    public List<ChunkPos> chunksIn(RegionPos region) throws IOException {
        final Slot slot = acquire(region.x(), region.z(), false);
        if (slot == null) return List.of();
        try {
            final BitSet stored = slot.file.storedChunks();
            final List<ChunkPos> out = new ArrayList<>(stored.cardinality());
            for (int i = stored.nextSetBit(0); i >= 0; i = stored.nextSetBit(i + 1)) {
                out.add(
                        new ChunkPos(
                                PumpFormat.chunkXOf(region.x(), i),
                                PumpFormat.chunkZOf(region.z(), i)));
            }
            return out;
        } finally {
            release(slot);
        }
    }

    /**
     * Issues a flush barrier on every open region. Only needed with {@link Durability#DEFERRED}.
     */
    public void flush() throws IOException {
        for (Slot slot : List.copyOf(open.values())) {
            if (!slot.retain()) continue;
            try {
                slot.file.sync();
            } finally {
                release(slot);
            }
        }
    }

    public PumpStats stats() {
        return new PumpStats(
                chunksRead.sum(),
                chunksWritten.sum(),
                payloadBytes.sum(),
                storedBytes.sum(),
                commits.sum(),
                regionsOpened.sum(),
                regionsEvicted.sum());
    }

    /** Number of region files currently open. */
    public int openRegions() {
        return open.size();
    }

    /**
     * Closes every region and releases the codec. Operations still running when this is called fail
     * with an exception, callers are expected to drain them first.
     */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        openLock.lock();
        try {
            if (closed) return;
            closed = true;
            for (Slot slot : open.values()) {
                try {
                    slot.file.close();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            open.clear();
        } finally {
            openLock.unlock();
        }
        if (ownedExecutor != null) ownedExecutor.shutdown();
        codec.close();
        if (failure != null) throw failure;
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private EncodedChunk encode(PumpChunk chunk) throws IOException {
        final EncodedChunk frame = ChunkFrame.encode(chunk, codec);
        payloadBytes.add(frame.uncompressedLength());
        storedBytes.add(frame.length());
        return frame;
    }

    private void requireWritable() throws PumpException {
        if (options.readOnly()) throw new PumpException("Storage is read-only: " + directory);
    }

    /** Returns the region with its use count raised, or {@code null} if absent and not created. */
    private Slot acquire(int regionX, int regionZ, boolean create) throws IOException {
        final long key = PumpFormat.pack(regionX, regionZ);
        final Slot fast = open.get(key);
        if (fast != null && fast.retain()) {
            fast.touch();
            return fast;
        }
        return acquireSlow(key, regionX, regionZ, create);
    }

    private Slot acquireSlow(long key, int regionX, int regionZ, boolean create)
            throws IOException {
        openLock.lock();
        try {
            if (closed) throw new PumpException("Storage is closed: " + directory);
            Slot slot = open.get(key);
            if (slot == null) {
                final Path path = PumpRegionFile.pathFor(directory, regionX, regionZ);
                if (!create && !Files.exists(path)) return null;
                if (create && options.readOnly()) return null;
                final PumpRegionFile file =
                        PumpRegionFile.open(
                                path,
                                regionX,
                                regionZ,
                                options.readOnly()
                                        ? PumpRegionFile.Mode.READ_ONLY
                                        : PumpRegionFile.Mode.READ_WRITE,
                                options.durability());
                slot = new Slot(key, file);
                open.put(key, slot);
                regionsOpened.increment();
            }
            // Retiring needs this lock, so a region found in the map can always be retained.
            if (!slot.retain()) throw new IllegalStateException("Region retired while mapped");
            slot.touch();
            return slot;
        } finally {
            openLock.unlock();
        }
    }

    private void release(Slot slot) {
        slot.refs.decrementAndGet();
        if (open.size() > options.maxOpenRegions()) evictIdle();
    }

    /** Closes least recently used regions that nobody is using until the limit is met. */
    private void evictIdle() {
        openLock.lock();
        try {
            if (closed) return;
            final List<Slot> candidates = new ArrayList<>(open.values());
            candidates.sort(java.util.Comparator.comparingLong(slot -> slot.lastUse));
            for (Slot candidate : candidates) {
                if (open.size() <= options.maxOpenRegions()) break;
                if (!candidate.tryRetire()) continue; // in use right now, try the next one
                open.remove(candidate.key);
                regionsEvicted.increment();
                try {
                    candidate.file.close();
                } catch (IOException e) {
                    LOGGER.log(
                            System.Logger.Level.ERROR,
                            "Failed to close region " + candidate.file.path(),
                            e);
                }
            }
        } finally {
            openLock.unlock();
        }
    }
}
