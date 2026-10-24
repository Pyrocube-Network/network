package net.pyrocube.core.pump.minestom;

import java.util.Objects;
import net.pyrocube.core.pump.chunk.ChunkStatus;
import net.pyrocube.core.pump.region.PumpOptions;

/**
 * Behavior of a {@link PumpChunkLoader}.
 *
 * @param storage options of the underlying {@link net.pyrocube.core.pump.region.PumpStorage}
 * @param dataVersionPolicy handling of chunks from another data version
 * @param corruptChunkPolicy handling of chunks that fail validation
 * @param minimumStatus chunks below this generation status are treated as absent so the generator
 *     completes them, {@link ChunkStatus#FULL} by default
 * @param hooks extension points
 */
public record PumpLoaderOptions(
        PumpOptions storage,
        DataVersionPolicy dataVersionPolicy,
        CorruptChunkPolicy corruptChunkPolicy,
        ChunkStatus minimumStatus,
        PumpChunkHooks hooks) {

    public PumpLoaderOptions {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(dataVersionPolicy, "dataVersionPolicy");
        Objects.requireNonNull(corruptChunkPolicy, "corruptChunkPolicy");
        Objects.requireNonNull(minimumStatus, "minimumStatus");
        Objects.requireNonNull(hooks, "hooks");
    }

    public static PumpLoaderOptions defaults() {
        return new PumpLoaderOptions(
                PumpOptions.defaults(),
                DataVersionPolicy.REJECT,
                CorruptChunkPolicy.REGENERATE,
                ChunkStatus.FULL,
                PumpChunkHooks.NONE);
    }

    public PumpLoaderOptions withStorage(PumpOptions value) {
        return new PumpLoaderOptions(
                value, dataVersionPolicy, corruptChunkPolicy, minimumStatus, hooks);
    }

    public PumpLoaderOptions withDataVersionPolicy(DataVersionPolicy value) {
        return new PumpLoaderOptions(storage, value, corruptChunkPolicy, minimumStatus, hooks);
    }

    public PumpLoaderOptions withCorruptChunkPolicy(CorruptChunkPolicy value) {
        return new PumpLoaderOptions(storage, dataVersionPolicy, value, minimumStatus, hooks);
    }

    public PumpLoaderOptions withMinimumStatus(ChunkStatus value) {
        return new PumpLoaderOptions(storage, dataVersionPolicy, corruptChunkPolicy, value, hooks);
    }

    public PumpLoaderOptions withHooks(PumpChunkHooks value) {
        return new PumpLoaderOptions(
                storage, dataVersionPolicy, corruptChunkPolicy, minimumStatus, value);
    }
}
