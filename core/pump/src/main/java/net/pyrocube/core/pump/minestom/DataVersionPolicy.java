package net.pyrocube.core.pump.minestom;

/**
 * What to do with a chunk written for a different Minecraft data version than the one the server
 * runs. Block state and biome ids are only meaningful for the data version they were written with.
 */
public enum DataVersionPolicy {
    /** Fail the load with an exception and leave the stored chunk untouched. Safe default. */
    REJECT,

    /**
     * Load the chunk anyway. Only correct if the registries did not change between versions,
     * otherwise blocks and biomes silently turn into other ones.
     */
    ACCEPT
}
