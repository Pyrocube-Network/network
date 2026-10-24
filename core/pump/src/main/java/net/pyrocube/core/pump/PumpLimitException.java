package net.pyrocube.core.pump;

/**
 * Raised when an input exceeds one of the resource bounds of chapter 7.1 (decompressed size,
 * section count, tick count, block entity count). Such inputs are rejected before the memory is
 * committed.
 */
public final class PumpLimitException extends PumpCorruptException {
    public PumpLimitException(String limit, long actual, long maximum) {
        super(limit + " exceeded: " + actual + " > " + maximum);
    }
}
