package net.pyrocube.core.pump.minestom;

/** How {@link PumpChunkLoader} reacts to a stored chunk that violates the format. */
public enum CorruptChunkPolicy {
    /**
     * Report the error to Minestom's exception manager and treat the chunk as absent, so the
     * generator produces a new one. Matches {@code AnvilLoader}; the damaged bytes are overwritten
     * on the next save.
     */
    REGENERATE,

    /** Fail the load with an exception and keep the damaged bytes for manual recovery. */
    FAIL
}
