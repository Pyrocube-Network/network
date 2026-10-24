package net.pyrocube.core.pump.minestom;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpException;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.concurrent.Parallel;
import net.pyrocube.core.pump.region.PumpStats;
import net.pyrocube.core.pump.region.PumpStorage;

/**
 * A Minestom {@link ChunkLoader} that stores chunks in PUMP regions.
 *
 * <pre>{@code
 * instance.setChunkLoader(new PumpChunkLoader(Path.of("world"), DimensionType.OVERWORLD.key()));
 * }</pre>
 *
 * <p>Regions live in {@code <world>/dimensions/<namespace>/<value>/region}, the layout Minestom's
 * own {@code AnvilLoader} uses, with {@code r.X.Z.pump} instead of {@code r.X.Z.mca}.
 *
 * <p>The loader supports parallel loading and saving: chunk loads run concurrently on Minestom's
 * virtual threads and {@link #saveChunks(Collection)} snapshots, compresses and commits a whole
 * batch in parallel with one commit per region. Call {@link #close()} on shutdown to release the
 * region files; committed data is already durable at that point with the default strict durability.
 */
public final class PumpChunkLoader implements ChunkLoader, AutoCloseable {
    private final PumpStorage storage;
    private final PumpLoaderOptions options;
    private final ExecutorService snapshotExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** Creates a loader for one dimension of a world directory. */
    public PumpChunkLoader(Path world, Key dimension) {
        this(world, dimension, PumpLoaderOptions.defaults());
    }

    public PumpChunkLoader(Path world, Key dimension, PumpLoaderOptions options) {
        this(
                world.resolve("dimensions")
                        .resolve(dimension.namespace())
                        .resolve(dimension.value())
                        .resolve("region"),
                options);
    }

    private PumpChunkLoader(Path regionDirectory, PumpLoaderOptions options) {
        this.options = options;
        this.storage = PumpStorage.open(regionDirectory, options.storage());
    }

    /** Creates a loader that reads and writes {@code r.X.Z.pump} files directly in a directory. */
    public static PumpChunkLoader forRegionDirectory(Path regionDirectory) {
        return forRegionDirectory(regionDirectory, PumpLoaderOptions.defaults());
    }

    public static PumpChunkLoader forRegionDirectory(
            Path regionDirectory, PumpLoaderOptions options) {
        return new PumpChunkLoader(regionDirectory, options);
    }

    /** The underlying storage, for direct chunk access and statistics. */
    public PumpStorage storage() {
        return storage;
    }

    public PumpStats stats() {
        return storage.stats();
    }

    // ---------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------

    @Override
    public Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
        final PumpChunk data;
        try {
            data = storage.read(chunkX, chunkZ);
        } catch (PumpCorruptException e) {
            return corrupt(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read chunk " + chunkX + ", " + chunkZ, e);
        }
        if (data == null) return null;

        if (data.status().id() < options.minimumStatus().id()) return null;
        if (data.dataVersion() != MinecraftServer.DATA_VERSION
                && options.dataVersionPolicy() == DataVersionPolicy.REJECT) {
            throw new IllegalStateException(
                    "Chunk "
                            + chunkX
                            + ", "
                            + chunkZ
                            + " was written for data version "
                            + data.dataVersion()
                            + " but this server runs "
                            + MinecraftServer.DATA_VERSION
                            + "; convert the world or use DataVersionPolicy.ACCEPT");
        }

        final Chunk chunk;
        try {
            chunk = MinestomChunkMapper.toChunk(instance, data);
        } catch (PumpCorruptException e) {
            return corrupt(e);
        }
        options.hooks().afterLoad(chunk, data);
        return chunk;
    }

    private Chunk corrupt(PumpCorruptException e) {
        if (options.corruptChunkPolicy() == CorruptChunkPolicy.FAIL) {
            throw new UncheckedIOException(e);
        }
        MinecraftServer.getExceptionManager().handleException(e);
        return null;
    }

    @Override
    public boolean supportsParallelLoading() {
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    @Override
    public void saveChunk(Chunk chunk) {
        try {
            storage.write(snapshot(chunk));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to save chunk " + chunk.getChunkX() + ", " + chunk.getChunkZ(), e);
        }
    }

    @Override
    public void saveChunks(Collection<Chunk> chunks) {
        if (chunks.isEmpty()) return;
        try {
            final List<Chunk> list = new ArrayList<>(chunks);
            final List<PumpChunk> snapshots =
                    Parallel.map(
                            list,
                            chunk -> snapshot(chunk),
                            snapshotExecutor,
                            options.storage().parallelism());
            storage.writeAll(snapshots);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save " + chunks.size() + " chunks", e);
        }
    }

    private PumpChunk snapshot(Chunk chunk) {
        return options.hooks().beforeSave(chunk, MinestomChunkMapper.toPump(chunk));
    }

    @Override
    public boolean supportsParallelSaving() {
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    /** Closes the region files. The loader must not be used afterwards. */
    @Override
    public void close() throws PumpException {
        snapshotExecutor.shutdown();
        try {
            storage.close();
        } catch (PumpException e) {
            throw e;
        } catch (IOException e) {
            throw new PumpException("Failed to close PUMP storage", e);
        }
    }
}
