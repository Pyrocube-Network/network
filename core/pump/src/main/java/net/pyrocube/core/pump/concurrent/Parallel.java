package net.pyrocube.core.pump.concurrent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/** Runs a function over a list on an executor with bounded concurrency, preserving order. */
public final class Parallel {
    /** A function that may fail with an {@link IOException}. */
    @FunctionalInterface
    public interface IoFunction<T, R> {
        R apply(T value) throws IOException;
    }

    private Parallel() {}

    /**
     * Applies {@code fn} to every element, at most {@code parallelism} at a time, and returns the
     * results in input order. All tasks are allowed to finish before the first failure is rethrown,
     * so no work is left running behind a failed call.
     */
    public static <T, R> List<R> map(
            List<T> items, IoFunction<T, R> fn, Executor executor, int parallelism)
            throws IOException {
        final int n = items.size();
        if (n == 0) return List.of();
        if (n == 1 || parallelism == 1) {
            final Object[] out = new Object[n];
            for (int i = 0; i < n; i++) out[i] = fn.apply(items.get(i));
            return cast(out);
        }

        final Semaphore permits = new Semaphore(parallelism);
        final Object[] out = new Object[n];
        @SuppressWarnings("unchecked")
        final CompletableFuture<Void>[] futures = new CompletableFuture[n];
        for (int i = 0; i < n; i++) {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                permits.release(parallelism);
                throw new IOException("Interrupted while scheduling parallel work", e);
            }
            final int index = i;
            futures[i] =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    out[index] = fn.apply(items.get(index));
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                } finally {
                                    permits.release();
                                }
                            },
                            executor);
        }

        Throwable failure = null;
        for (CompletableFuture<Void> future : futures) {
            try {
                future.join();
            } catch (CompletionException e) {
                if (failure == null) failure = e.getCause();
                else failure.addSuppressed(e.getCause());
            }
        }
        if (failure != null) {
            if (failure instanceof UncheckedIOException u) throw u.getCause();
            if (failure instanceof RuntimeException r) throw r;
            if (failure instanceof Error err) throw err;
            throw new IOException(failure);
        }
        return cast(out);
    }

    @SuppressWarnings("unchecked")
    private static <R> List<R> cast(Object[] out) {
        return (List<R>) java.util.Arrays.asList(out);
    }
}
