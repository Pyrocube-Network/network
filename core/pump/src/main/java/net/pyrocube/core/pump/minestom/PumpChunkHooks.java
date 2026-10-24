package net.pyrocube.core.pump.minestom;

import net.minestom.server.instance.Chunk;
import net.pyrocube.core.pump.chunk.PumpChunk;

/**
 * Extension points for data that Minestom has no model for, such as scheduled ticks, the
 * motion-blocking-no-leaves heightmap or a custom status. Hooks run on the thread doing the load or
 * save, outside of the chunk locks, and must be thread-safe.
 */
public interface PumpChunkHooks {
    PumpChunkHooks NONE = new PumpChunkHooks() {};

    /**
     * Called with the stored data after a chunk was populated. {@code data} carries everything that
     * was in the file, including the parts that were not applied to the chunk.
     */
    default void afterLoad(Chunk chunk, PumpChunk data) {}

    /**
     * Called with the data derived from {@code chunk} just before it is stored. May return a
     * modified copy (see {@link PumpChunk#toBuilder()}), for instance with scheduled ticks added.
     */
    default PumpChunk beforeSave(Chunk chunk, PumpChunk data) {
        return data;
    }
}
