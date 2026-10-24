package net.pyrocube.core.pump.region;

/**
 * Counters of a {@link PumpStorage}, a consistent-enough snapshot for monitoring.
 *
 * @param chunksRead chunks decoded
 * @param chunksWritten chunks encoded and committed
 * @param payloadBytes uncompressed {@code PCHK} bytes processed (both directions)
 * @param storedBytes compressed frame bytes processed (both directions)
 * @param commits region commits, each paying two flush barriers in strict mode
 * @param regionsOpened region files opened
 * @param regionsEvicted region files closed to honor the open file limit
 */
public record PumpStats(
        long chunksRead,
        long chunksWritten,
        long payloadBytes,
        long storedBytes,
        long commits,
        long regionsOpened,
        long regionsEvicted) {

    /** Uncompressed to stored size ratio. */
    public double compressionRatio() {
        return storedBytes == 0 ? 0 : (double) payloadBytes / storedBytes;
    }
}
