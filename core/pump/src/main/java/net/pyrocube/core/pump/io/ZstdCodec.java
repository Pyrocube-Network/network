package net.pyrocube.core.pump.io;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.pyrocube.core.pump.PumpCorruptException;

/**
 * Thread-safe Zstandard (RFC 8878) compressor and decompressor.
 *
 * <p>Native Zstd contexts are expensive to create and not thread-safe, so they are pooled. The pool
 * is shared by all threads (including virtual threads, where a {@link ThreadLocal} would create one
 * context per task) and capped, contexts released above the cap are freed immediately.
 */
public final class ZstdCodec implements AutoCloseable {
    /** Default compression level, a good ratio/speed trade-off for chunk sized inputs. */
    public static final int DEFAULT_LEVEL = 3;

    private final int level;
    private final Pool<ZstdCompressCtx> compressors;
    private final Pool<ZstdDecompressCtx> decompressors;

    public ZstdCodec(int level) {
        this(level, Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
    }

    public ZstdCodec(int level, int maxPooledContexts) {
        if (level < Zstd.minCompressionLevel() || level > Zstd.maxCompressionLevel()) {
            throw new IllegalArgumentException("Invalid zstd level " + level);
        }
        this.level = level;
        this.compressors =
                new Pool<>(
                        () -> new ZstdCompressCtx().setChecksum(false).setContentSize(true),
                        ZstdCompressCtx::close,
                        maxPooledContexts);
        this.decompressors =
                new Pool<>(ZstdDecompressCtx::new, ZstdDecompressCtx::close, maxPooledContexts);
    }

    public int level() {
        return level;
    }

    /** Upper bound of the compressed size of {@code length} input bytes. */
    public static int compressBound(int length) {
        return (int) Zstd.compressBound(length);
    }

    /**
     * Compresses {@code src[off, off + len)} as a single Zstd frame into {@code dst} at {@code
     * dstOff}.
     *
     * @return the number of bytes written
     */
    public int compress(byte[] src, int off, int len, byte[] dst, int dstOff) {
        final ZstdCompressCtx ctx = compressors.acquire();
        try {
            ctx.setLevel(level);
            return ctx.compressByteArray(dst, dstOff, dst.length - dstOff, src, off, len);
        } finally {
            compressors.release(ctx);
        }
    }

    /**
     * Decompresses a Zstd frame into {@code dst}, which must be exactly the expected size.
     *
     * @throws PumpCorruptException if the data is not a valid frame or does not decompress to
     *     exactly {@code dst.length} bytes
     */
    public void decompress(byte[] src, int off, int len, byte[] dst) throws PumpCorruptException {
        final long declared = Zstd.getFrameContentSize(src, off, len);
        if (declared == -2L) {
            throw new PumpCorruptException("Invalid Zstandard frame header");
        }
        if (declared >= 0 && declared != dst.length) {
            throw new PumpCorruptException(
                    "Zstandard frame declares "
                            + declared
                            + " bytes but the chunk frame header declares "
                            + dst.length);
        }
        final ZstdDecompressCtx ctx = decompressors.acquire();
        boolean healthy = false;
        try {
            final int written = ctx.decompressByteArray(dst, 0, dst.length, src, off, len);
            healthy = true;
            if (written != dst.length) {
                throw new PumpCorruptException(
                        "Decompressed size mismatch: expected " + dst.length + ", got " + written);
            }
        } catch (ZstdException e) {
            throw new PumpCorruptException("Zstandard decompression failed: " + e.getMessage(), e);
        } finally {
            if (healthy) {
                decompressors.release(ctx);
            } else {
                // A context that raised an error is discarded rather than recycled.
                ctx.close();
            }
        }
    }

    @Override
    public void close() {
        compressors.close();
        decompressors.close();
    }

    private static final class Pool<T> {
        private final ConcurrentLinkedQueue<T> idle = new ConcurrentLinkedQueue<>();
        private final AtomicInteger idleCount = new AtomicInteger();
        private final Supplier<T> factory;
        private final Consumer<T> closer;
        private final int max;
        private volatile boolean closed;

        Pool(Supplier<T> factory, Consumer<T> closer, int max) {
            this.factory = factory;
            this.closer = closer;
            this.max = max;
        }

        T acquire() {
            final T pooled = idle.poll();
            if (pooled != null) {
                idleCount.decrementAndGet();
                return pooled;
            }
            return factory.get();
        }

        void release(T ctx) {
            if (!closed && idleCount.incrementAndGet() <= max) {
                idle.add(ctx);
                if (closed) drain();
            } else {
                idleCount.decrementAndGet();
                closer.accept(ctx);
            }
        }

        void close() {
            closed = true;
            drain();
        }

        private void drain() {
            T ctx;
            while ((ctx = idle.poll()) != null) {
                idleCount.decrementAndGet();
                closer.accept(ctx);
            }
        }
    }
}
