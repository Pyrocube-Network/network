package net.pyrocube.core.pump.chunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.pyrocube.core.pump.PumpFormat;

/**
 * An in-memory {@code PCHK} chunk payload (§5): everything the format stores about one chunk,
 * independent of the game server it is loaded into.
 *
 * <p>Instances are treated as immutable, the arrays they hold must not be modified. Use {@link
 * #builder(int, int)} to create one.
 *
 * @param dataVersion Minecraft data version the block state and biome ids belong to
 * @param x chunk X coordinate
 * @param z chunk Z coordinate
 * @param minSectionY vertical index of the lowest section
 * @param status generation status
 * @param inhabitedTime cumulative inhabited ticks
 * @param lightCorrect whether the stored light is solved and valid
 * @param worldSurface optional heightmap, 256 values of 0..511, element {@code x + z * 16}
 * @param motionBlocking optional heightmap
 * @param motionBlockingNoLeaves optional heightmap
 * @param sections sections in ascending Y, section {@code i} has Y {@code minSectionY + i}
 * @param blockTicks scheduled block ticks
 * @param fluidTicks scheduled fluid ticks
 * @param blockEntities block entity compounds, each with {@code x}, {@code y}, {@code z}, {@code
 *     id}
 * @param customData optional auxiliary compound that must survive rewrites (§5.6)
 */
public record PumpChunk(
        int dataVersion,
        int x,
        int z,
        int minSectionY,
        ChunkStatus status,
        long inhabitedTime,
        boolean lightCorrect,
        short[] worldSurface,
        short[] motionBlocking,
        short[] motionBlockingNoLeaves,
        List<PumpSection> sections,
        List<ScheduledTick> blockTicks,
        List<ScheduledTick> fluidTicks,
        List<CompoundBinaryTag> blockEntities,
        CompoundBinaryTag customData) {

    public PumpChunk {
        Objects.requireNonNull(status, "status");
        checkHeightmap(worldSurface, "worldSurface");
        checkHeightmap(motionBlocking, "motionBlocking");
        checkHeightmap(motionBlockingNoLeaves, "motionBlockingNoLeaves");
        sections = List.copyOf(sections);
        blockTicks = List.copyOf(blockTicks);
        fluidTicks = List.copyOf(fluidTicks);
        blockEntities = List.copyOf(blockEntities);
        if (sections.size() > PumpFormat.MAX_SECTIONS) {
            throw new IllegalArgumentException("Too many sections: " + sections.size());
        }
        if (blockTicks.size() > PumpFormat.MAX_TICKS) {
            throw new IllegalArgumentException("Too many block ticks: " + blockTicks.size());
        }
        if (fluidTicks.size() > PumpFormat.MAX_TICKS) {
            throw new IllegalArgumentException("Too many fluid ticks: " + fluidTicks.size());
        }
        if (blockEntities.size() > PumpFormat.MAX_BLOCK_ENTITIES) {
            throw new IllegalArgumentException("Too many block entities: " + blockEntities.size());
        }
        for (CompoundBinaryTag be : blockEntities) {
            final String problem = BlockEntities.problem(be);
            if (problem != null) throw new IllegalArgumentException(problem);
        }
    }

    private static void checkHeightmap(short[] map, String name) {
        if (map == null) return;
        if (map.length != PumpFormat.HEIGHTMAP_ENTRIES) {
            throw new IllegalArgumentException(name + " must hold 256 values");
        }
        for (short v : map) {
            if (v < 0 || v > PumpFormat.HEIGHTMAP_MAX_VALUE) {
                throw new IllegalArgumentException(name + " value out of range 0..511: " + v);
            }
        }
    }

    /** The {@code flags} header byte derived from the content of this chunk. */
    public int flags() {
        int flags = 0;
        if (lightCorrect) flags |= ChunkFlags.LIGHT_CORRECT;
        if (worldSurface != null) flags |= ChunkFlags.HAS_WORLD_SURFACE;
        if (motionBlocking != null) flags |= ChunkFlags.HAS_MOTION_BLOCKING;
        if (motionBlockingNoLeaves != null) flags |= ChunkFlags.HAS_MOTION_BLOCKING_NO_LEAVES;
        if (customData != null) flags |= ChunkFlags.HAS_CUSTOM_DATA;
        return flags;
    }

    /** Vertical index of the section after the last one. */
    public int maxSectionY() {
        return minSectionY + sections.size();
    }

    public static Builder builder(int x, int z) {
        return new Builder(x, z);
    }

    /** A builder pre-filled with the content of this chunk. */
    public Builder toBuilder() {
        final Builder b = new Builder(x, z);
        b.dataVersion = dataVersion;
        b.minSectionY = minSectionY;
        b.status = status;
        b.inhabitedTime = inhabitedTime;
        b.lightCorrect = lightCorrect;
        b.worldSurface = worldSurface;
        b.motionBlocking = motionBlocking;
        b.motionBlockingNoLeaves = motionBlockingNoLeaves;
        b.sections.addAll(sections);
        b.blockTicks.addAll(blockTicks);
        b.fluidTicks.addAll(fluidTicks);
        b.blockEntities.addAll(blockEntities);
        b.customData = customData;
        return b;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PumpChunk c
                && dataVersion == c.dataVersion
                && x == c.x
                && z == c.z
                && minSectionY == c.minSectionY
                && status == c.status
                && inhabitedTime == c.inhabitedTime
                && lightCorrect == c.lightCorrect
                && Arrays.equals(worldSurface, c.worldSurface)
                && Arrays.equals(motionBlocking, c.motionBlocking)
                && Arrays.equals(motionBlockingNoLeaves, c.motionBlockingNoLeaves)
                && sections.equals(c.sections)
                && blockTicks.equals(c.blockTicks)
                && fluidTicks.equals(c.fluidTicks)
                && blockEntities.equals(c.blockEntities)
                && Objects.equals(customData, c.customData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                dataVersion,
                x,
                z,
                minSectionY,
                status,
                inhabitedTime,
                lightCorrect,
                Arrays.hashCode(worldSurface),
                Arrays.hashCode(motionBlocking),
                Arrays.hashCode(motionBlockingNoLeaves),
                sections,
                blockTicks,
                fluidTicks,
                blockEntities,
                customData);
    }

    @Override
    public String toString() {
        return "PumpChunk[x="
                + x
                + ", z="
                + z
                + ", dataVersion="
                + dataVersion
                + ", status="
                + status
                + ", sections="
                + sections.size()
                + ", blockEntities="
                + blockEntities.size()
                + "]";
    }

    /** Mutable builder for {@link PumpChunk}. */
    public static final class Builder {
        private final int x;
        private final int z;
        private int dataVersion;
        private int minSectionY;
        private ChunkStatus status = ChunkStatus.FULL;
        private long inhabitedTime;
        private boolean lightCorrect;
        private short[] worldSurface;
        private short[] motionBlocking;
        private short[] motionBlockingNoLeaves;
        private final List<PumpSection> sections = new ArrayList<>();
        private final List<ScheduledTick> blockTicks = new ArrayList<>();
        private final List<ScheduledTick> fluidTicks = new ArrayList<>();
        private final List<CompoundBinaryTag> blockEntities = new ArrayList<>();
        private CompoundBinaryTag customData;

        private Builder(int x, int z) {
            this.x = x;
            this.z = z;
        }

        public Builder dataVersion(int v) {
            this.dataVersion = v;
            return this;
        }

        public Builder minSectionY(int v) {
            this.minSectionY = v;
            return this;
        }

        public Builder status(ChunkStatus v) {
            this.status = v;
            return this;
        }

        public Builder inhabitedTime(long v) {
            this.inhabitedTime = v;
            return this;
        }

        public Builder lightCorrect(boolean v) {
            this.lightCorrect = v;
            return this;
        }

        public Builder worldSurface(short[] v) {
            this.worldSurface = v;
            return this;
        }

        public Builder motionBlocking(short[] v) {
            this.motionBlocking = v;
            return this;
        }

        public Builder motionBlockingNoLeaves(short[] v) {
            this.motionBlockingNoLeaves = v;
            return this;
        }

        public Builder section(PumpSection v) {
            this.sections.add(v);
            return this;
        }

        public Builder sections(List<PumpSection> v) {
            this.sections.clear();
            this.sections.addAll(v);
            return this;
        }

        public Builder blockTick(ScheduledTick v) {
            this.blockTicks.add(v);
            return this;
        }

        public Builder fluidTick(ScheduledTick v) {
            this.fluidTicks.add(v);
            return this;
        }

        public Builder blockEntity(CompoundBinaryTag v) {
            this.blockEntities.add(v);
            return this;
        }

        public Builder customData(CompoundBinaryTag v) {
            this.customData = v;
            return this;
        }

        public PumpChunk build() {
            return new PumpChunk(
                    dataVersion,
                    x,
                    z,
                    minSectionY,
                    status,
                    inhabitedTime,
                    lightCorrect,
                    worldSurface,
                    motionBlocking,
                    motionBlockingNoLeaves,
                    sections,
                    blockTicks,
                    fluidTicks,
                    blockEntities,
                    customData);
        }
    }
}
