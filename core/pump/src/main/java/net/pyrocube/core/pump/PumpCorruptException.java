package net.pyrocube.core.pump;

/**
 * Raised when a region container or chunk payload violates a Valid Usage clause of the
 * specification: bad signature, checksum mismatch, out of range indices and similar faults.
 */
public class PumpCorruptException extends PumpException {
    public PumpCorruptException(String message) {
        super(message);
    }

    public PumpCorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
