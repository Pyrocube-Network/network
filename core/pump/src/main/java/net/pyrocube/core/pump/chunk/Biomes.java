package net.pyrocube.core.pump.chunk;

import java.util.Arrays;
import net.pyrocube.core.pump.PumpFormat;

/** The 64 biome cells (4x4x4) of a section as biome ids in {@code Y, Z, X} order (§5.3.3). */
public final class Biomes {
    private static final int COUNT = PumpFormat.SECTION_BIOMES;

    private final int uniform;
    private final byte[] cells;

    private Biomes(int uniform, byte[] cells) {
        this.uniform = uniform;
        this.cells = cells;
    }

    public static Biomes uniform(int biomeId) {
        if (biomeId < 0 || biomeId > 0xFF) {
            throw new IllegalArgumentException("Biome id out of u8 range: " + biomeId);
        }
        return new Biomes(biomeId, null);
    }

    /**
     * Wraps 64 biome ids, owned by the returned instance afterwards. Arrays holding a single value
     * collapse to {@link #uniform(int)}.
     */
    public static Biomes of(byte[] cells) {
        if (cells.length != COUNT) {
            throw new IllegalArgumentException("Expected 64 biome cells, got " + cells.length);
        }
        final byte first = cells[0];
        for (int i = 1; i < COUNT; i++) {
            if (cells[i] != first) return new Biomes(-1, cells);
        }
        return new Biomes(first & 0xFF, null);
    }

    public boolean isUniform() {
        return cells == null;
    }

    public int uniformBiome() {
        if (cells != null) throw new IllegalStateException("Not uniform");
        return uniform;
    }

    /** Biome id at {@code index} (see {@link PumpFormat#biomeIndex(int, int, int)}). */
    public int get(int index) {
        return cells == null ? uniform : cells[index] & 0xFF;
    }

    public void copyTo(byte[] dst) {
        if (cells == null) {
            Arrays.fill(dst, 0, COUNT, (byte) uniform);
        } else {
            System.arraycopy(cells, 0, dst, 0, COUNT);
        }
    }

    byte[] raw() {
        return cells;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Biomes other)) return false;
        if (cells == null || other.cells == null) {
            return cells == other.cells && uniform == other.uniform;
        }
        return Arrays.equals(cells, other.cells);
    }

    @Override
    public int hashCode() {
        return cells == null ? uniform : Arrays.hashCode(cells);
    }

    @Override
    public String toString() {
        return cells == null ? "Biomes[uniform=" + uniform + "]" : "Biomes[mixed]";
    }
}
