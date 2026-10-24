package net.pyrocube.core.pump.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tiny benchmarking helper: warmup, repeated timed runs, median, and a markdown report. */
final class Bench {
    interface Task {
        void run() throws Exception;
    }

    private final List<String> lines = new ArrayList<>();

    /** Median wall time in seconds of {@code runs} executions after {@code warmup} ones. */
    static double median(int warmup, int runs, Task task) throws Exception {
        for (int i = 0; i < warmup; i++) task.run();
        final double[] times = new double[runs];
        for (int i = 0; i < runs; i++) {
            final long start = System.nanoTime();
            task.run();
            times[i] = (System.nanoTime() - start) / 1e9;
        }
        Arrays.sort(times);
        return times[runs / 2];
    }

    void heading(String text) {
        add("");
        add("### " + text);
        add("");
    }

    void table(String... header) {
        add("| " + String.join(" | ", header) + " |");
        final String[] sep = new String[header.length];
        Arrays.fill(sep, "---");
        add("| " + String.join(" | ", sep) + " |");
    }

    void row(Object... cells) {
        final String[] out = new String[cells.length];
        for (int i = 0; i < cells.length; i++) {
            out[i] = cells[i] instanceof Double d ? String.format("%,.1f", d) : String.valueOf(cells[i]);
        }
        add("| " + String.join(" | ", out) + " |");
    }

    void note(String text) {
        add(text);
    }

    private void add(String line) {
        lines.add(line);
        System.out.println(line);
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, lines);
    }
}
