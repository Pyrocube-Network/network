package net.pyrocube.core.pump.region;

/** When a region file issues the storage flush barriers of the write protocol (§6.2). */
public enum Durability {
    /**
     * Both barriers are issued on every commit: chunk data is on stable storage before the header
     * that references it, and the header is on stable storage before the old sectors are reclaimed.
     * This is the only conforming mode for a live world.
     */
    STRICT,

    /**
     * Barriers are skipped and issued once by {@link PumpRegionFile#sync()} or {@link
     * PumpRegionFile#close()}. Write ordering inside the file is unchanged but a crash before the
     * final sync can lose recent commits or leave the region unreadable. Meant for bulk imports
     * that can simply be restarted, such as world conversion.
     */
    DEFERRED
}
