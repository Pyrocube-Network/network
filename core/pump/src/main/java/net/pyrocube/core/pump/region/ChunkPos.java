package net.pyrocube.core.pump.region;

import net.pyrocube.core.pump.PumpFormat;

/** A chunk coordinate pair. */
public record ChunkPos(int x, int z) {
    public int regionX() {
        return PumpFormat.chunkToRegion(x);
    }

    public int regionZ() {
        return PumpFormat.chunkToRegion(z);
    }

    /** Slot of this chunk inside its region. */
    public int index() {
        return PumpFormat.chunkIndex(x, z);
    }
}
