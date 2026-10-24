package net.pyrocube.core.pump.chunk;

import java.util.Arrays;
import net.pyrocube.core.pump.PumpFormat;

/**
 * The 4096 block states of a section, as global block state ids in {@code Y, Z, X} order.
 *
 * <p>The on-disk representation (homogeneous, indexed or dense, §5.3.2) is an encoding concern:
 * this class keeps either a single id or the expanded array, and the codec chooses the most compact
 * mode when writing.
 */
public final class BlockStates {
    private static final int COUNT = PumpFormat.SECTION_BLOCKS;

    private final int uniform;
    private final char[] states;

    private BlockStates(int uniform, char[] states) {
        this.uniform = uniform;
        this.states = states;
    }

    /** A section where every block has the same state. */
    public static BlockStates uniform(int stateId) {
        checkId(stateId);
        return new BlockStates(stateId, null);
    }

    /**
     * Wraps an array of 4096 state ids. The array is owned by the returned instance and must not be
     * modified afterwards. Arrays holding a single value collapse to {@link #uniform(int)}.
     */
    public static BlockStates of(char[] states) {
        if (states.length != COUNT) {
            throw new IllegalArgumentException("Expected 4096 states, got " + states.length);
        }
        final char first = states[0];
        for (int i = 1; i < COUNT; i++) {
            if (states[i] != first) return new BlockStates(-1, states);
        }
        return new BlockStates(first, null);
    }

    private static void checkId(int id) {
        if (id < 0 || id > 0xFFFF) {
            throw new IllegalArgumentException("Block state id out of u16 range: " + id);
        }
    }

    public boolean isUniform() {
        return states == null;
    }

    /** The single state id; only valid when {@link #isUniform()}. */
    public int uniformState() {
        if (states != null) throw new IllegalStateException("Not uniform");
        return uniform;
    }

    /** State id at {@code index} (see {@link PumpFormat#blockIndex(int, int, int)}). */
    public int get(int index) {
        return states == null ? uniform : states[index];
    }

    /** Copies all states into {@code dst}. */
    public void copyTo(char[] dst) {
        if (states == null) {
            Arrays.fill(dst, 0, COUNT, (char) uniform);
        } else {
            System.arraycopy(states, 0, dst, 0, COUNT);
        }
    }

    /** Backing array for read-only codec access, {@code null} when uniform. */
    char[] raw() {
        return states;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockStates other)) return false;
        if (states == null || other.states == null) {
            return states == other.states && uniform == other.uniform;
        }
        return Arrays.equals(states, other.states);
    }

    @Override
    public int hashCode() {
        return states == null ? uniform : Arrays.hashCode(states);
    }

    @Override
    public String toString() {
        return states == null ? "BlockStates[uniform=" + uniform + "]" : "BlockStates[mixed]";
    }
}
