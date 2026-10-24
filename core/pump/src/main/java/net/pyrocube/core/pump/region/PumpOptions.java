package net.pyrocube.core.pump.region;

import java.util.concurrent.Executor;
import net.pyrocube.core.pump.io.ZstdCodec;

/**
 * Tuning knobs of a {@link PumpStorage}. Instances are immutable, use the {@code with} methods to
 * derive variants of {@link #defaults()}.
 *
 * @param compressionLevel Zstandard level, 3 is the default, higher trades CPU for size
 * @param maxOpenRegions region files kept open at once, least recently used ones are closed
 * @param durability flush barrier policy, see {@link Durability}
 * @param executor runs the parallel stages, {@code null} for an internal virtual-thread executor
 * @param parallelism maximum concurrently running encode, decode and commit tasks
 * @param readOnly never write, never create files and do not lock them
 */
public record PumpOptions(
        int compressionLevel,
        int maxOpenRegions,
        Durability durability,
        Executor executor,
        int parallelism,
        boolean readOnly) {

    public PumpOptions {
        if (maxOpenRegions < 1) throw new IllegalArgumentException("maxOpenRegions must be >= 1");
        if (parallelism < 1) throw new IllegalArgumentException("parallelism must be >= 1");
        if (durability == null) throw new IllegalArgumentException("durability");
    }

    public static PumpOptions defaults() {
        return new PumpOptions(
                ZstdCodec.DEFAULT_LEVEL,
                64,
                Durability.STRICT,
                null,
                Runtime.getRuntime().availableProcessors(),
                false);
    }

    public PumpOptions withCompressionLevel(int level) {
        return new PumpOptions(level, maxOpenRegions, durability, executor, parallelism, readOnly);
    }

    public PumpOptions withMaxOpenRegions(int max) {
        return new PumpOptions(compressionLevel, max, durability, executor, parallelism, readOnly);
    }

    public PumpOptions withDurability(Durability value) {
        return new PumpOptions(
                compressionLevel, maxOpenRegions, value, executor, parallelism, readOnly);
    }

    public PumpOptions withExecutor(Executor value) {
        return new PumpOptions(
                compressionLevel, maxOpenRegions, durability, value, parallelism, readOnly);
    }

    public PumpOptions withParallelism(int value) {
        return new PumpOptions(
                compressionLevel, maxOpenRegions, durability, executor, value, readOnly);
    }

    public PumpOptions withReadOnly(boolean value) {
        return new PumpOptions(
                compressionLevel, maxOpenRegions, durability, executor, parallelism, value);
    }
}
