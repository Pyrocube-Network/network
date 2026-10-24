package net.pyrocube.core.pump.chunk;

import net.pyrocube.core.pump.PumpCorruptException;

/** Generation lifecycle state of a chunk, the {@code status} field of the header (§5.1). */
public enum ChunkStatus {
    EMPTY(0),
    STRUCTURE_STARTS(1),
    STRUCTURE_REFERENCES(2),
    BIOMES(3),
    TERRAIN(4),
    FEATURES(5),
    INITIALIZE_LIGHT(6),
    LIGHT(7),
    SPAWN(8),
    FULL(9);

    private static final ChunkStatus[] BY_ID = values();

    private final int id;

    ChunkStatus(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** Whether the chunk is completely generated. */
    public boolean isFull() {
        return this == FULL;
    }

    public static ChunkStatus fromId(int id) throws PumpCorruptException {
        if (id < 0 || id >= BY_ID.length) {
            throw new PumpCorruptException("Invalid chunk status " + id + ", expected 0..9");
        }
        return BY_ID[id];
    }
}
