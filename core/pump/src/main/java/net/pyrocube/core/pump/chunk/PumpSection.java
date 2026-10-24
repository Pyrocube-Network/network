package net.pyrocube.core.pump.chunk;

import java.util.Arrays;
import java.util.Objects;
import net.pyrocube.core.pump.PumpFormat;

/**
 * A 16x16x16 section: blocks, biomes and optional light.
 *
 * <p>Light arrays are 2048 bytes of 4-bit nibbles, low nibble first (§5.3.4). A {@code null} array
 * is implicitly all zero. Arrays are owned by the section and must not be modified after
 * construction.
 */
public record PumpSection(BlockStates blocks, Biomes biomes, byte[] blockLight, byte[] skyLight) {
    public PumpSection {
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(biomes, "biomes");
        checkLight(blockLight, "blockLight");
        checkLight(skyLight, "skyLight");
    }

    public PumpSection(BlockStates blocks, Biomes biomes) {
        this(blocks, biomes, null, null);
    }

    /** An all-air section using {@code biomeId}. */
    public static PumpSection empty(int biomeId) {
        return new PumpSection(BlockStates.uniform(0), Biomes.uniform(biomeId));
    }

    private static void checkLight(byte[] light, String name) {
        if (light != null && light.length != PumpFormat.LIGHT_BYTES) {
            throw new IllegalArgumentException(
                    name + " must be " + PumpFormat.LIGHT_BYTES + " bytes, got " + light.length);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PumpSection s
                && blocks.equals(s.blocks)
                && biomes.equals(s.biomes)
                && Arrays.equals(blockLight, s.blockLight)
                && Arrays.equals(skyLight, s.skyLight);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blocks, biomes, Arrays.hashCode(blockLight), Arrays.hashCode(skyLight));
    }
}
