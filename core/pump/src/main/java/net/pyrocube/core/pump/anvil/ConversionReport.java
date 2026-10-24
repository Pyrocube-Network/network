package net.pyrocube.core.pump.anvil;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of a bulk world conversion.
 *
 * @param regions region files processed
 * @param converted chunks converted successfully
 * @param failed chunks that could not be converted and were skipped
 * @param warnings non-fatal problems across all converted chunks
 * @param failures descriptions of the first failures, capped
 * @param bytesIn size of the source region data read
 * @param bytesOut size of the region files produced
 * @param elapsed wall clock time
 */
public record ConversionReport(
        int regions,
        int converted,
        int failed,
        int warnings,
        List<String> failures,
        long bytesIn,
        long bytesOut,
        Duration elapsed) {

    /** Source size divided by result size. */
    public double sizeRatio() {
        return bytesOut == 0 ? 0 : (double) bytesIn / bytesOut;
    }

    public double chunksPerSecond() {
        final double seconds = elapsed.toNanos() / 1e9;
        return seconds == 0 ? 0 : converted / seconds;
    }

    @Override
    public String toString() {
        return String.format(
                "ConversionReport[regions=%d, converted=%d, failed=%d, warnings=%d, %.1f chunks/s, in=%d B, out=%d B]",
                regions, converted, failed, warnings, chunksPerSecond(), bytesIn, bytesOut);
    }
}
