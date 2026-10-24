package net.pyrocube.core.pump.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParallelTest {
    private static void nap(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    @Test
    void preservesOrder() throws Exception {
        final List<Integer> input = new ArrayList<>();
        for (int i = 0; i < 500; i++) input.add(i);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Integer> out =
                    Parallel.map(
                            input,
                            i -> {
                                if (i % 7 == 0) nap(1);
                                return i * 2;
                            },
                            pool,
                            8);
            for (int i = 0; i < 500; i++) assertEquals(i * 2, out.get(i));
        }
    }

    @Test
    void neverExceedsTheRequestedConcurrency() throws Exception {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Parallel.map(
                    new ArrayList<>(java.util.Collections.nCopies(200, 0)),
                    x -> {
                        final int now = running.incrementAndGet();
                        peak.accumulateAndGet(now, Math::max);
                        nap(2);
                        running.decrementAndGet();
                        return x;
                    },
                    pool,
                    3);
        }
        assertTrue(peak.get() <= 3, "peak " + peak.get());
        assertTrue(peak.get() >= 2, "should actually run in parallel, peak " + peak.get());
    }

    @Test
    void failureWaitsForRunningTasksThenRethrowsTheOriginal() throws Exception {
        final AtomicInteger finished = new AtomicInteger();
        final IOException boom = new IOException("boom");
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            final IOException thrown =
                    assertThrows(
                            IOException.class,
                            () ->
                                    Parallel.map(
                                            List.of(1, 2, 3, 4, 5, 6),
                                            i -> {
                                                nap(5);
                                                finished.incrementAndGet();
                                                if (i == 3) throw boom;
                                                return i;
                                            },
                                            pool,
                                            2));
            assertSame(boom, thrown);
            assertEquals(6, finished.get(), "no task is left running behind a failed call");
        }
    }

    @Test
    void emptyAndSingleInputsRunInline() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            assertEquals(List.of(), Parallel.map(List.<Integer>of(), i -> i, pool, 4));
            final Thread caller = Thread.currentThread();
            final List<Thread> ran =
                    Parallel.map(List.of(1), i -> Thread.currentThread(), pool, 4);
            assertSame(caller, ran.get(0));
        }
    }

    @Test
    void nullResultsAreAllowed() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<String> out =
                    Parallel.map(List.of(1, 2, 3), i -> i == 2 ? null : "x" + i, pool, 2);
            assertEquals("x1", out.get(0));
            assertEquals(null, out.get(1));
        }
    }
}
