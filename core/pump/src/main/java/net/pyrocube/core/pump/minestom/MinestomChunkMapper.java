package net.pyrocube.core.pump.minestom;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockEntityType;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.heightmap.Heightmap;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.utils.MathUtils;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.chunk.Biomes;
import net.pyrocube.core.pump.chunk.BlockStates;
import net.pyrocube.core.pump.chunk.ChunkStatus;
import net.pyrocube.core.pump.chunk.Heightmaps;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.chunk.PumpSection;

/**
 * Converts between a live Minestom {@link Chunk} and the server-independent {@link PumpChunk}.
 *
 * <p>Mapping decisions where Minestom and the format differ:
 *
 * <ul>
 *   <li>Block states are Minestom state ids and biomes are the ids of the instance's biome
 *       registry, both are tied to {@link MinecraftServer#DATA_VERSION}.
 *   <li>Minestom has no concept of generation status, scheduled ticks or the no-leaves heightmap.
 *       Saved chunks are {@link ChunkStatus#FULL} and carry none of them, use {@link
 *       PumpChunkHooks} to supply or consume them.
 *   <li>The chunk's tag data is the custom data block (§5.6). The {@code InhabitedTime} tag, the
 *       name vanilla uses, is lifted into the header field and restored on load.
 *   <li>Heightmaps are stored with the 9 bit element size of the format, so they are omitted for
 *       dimensions taller than 511 blocks and recomputed by Minestom on demand.
 * </ul>
 */
public final class MinestomChunkMapper {
    /** Name of the chunk tag mirrored by the {@code inhabited_time} header field. */
    public static final String INHABITED_TIME_TAG = "InhabitedTime";

    private static final int LIGHT_BYTES = PumpFormat.LIGHT_BYTES;
    private static final String WORLD_SURFACE = Heightmap.Type.WORLD_SURFACE.name();
    private static final String MOTION_BLOCKING = Heightmap.Type.MOTION_BLOCKING.name();

    static {
        if (Block.statesCount() > 0x10000) {
            throw new ExceptionInInitializerError(
                    "This Minestom version has "
                            + Block.statesCount()
                            + " block states, more than the u16 ids of the PUMP format can hold");
        }
    }

    private MinestomChunkMapper() {}

    // ---------------------------------------------------------------------------------------
    // Chunk -> PumpChunk
    // ---------------------------------------------------------------------------------------

    /**
     * Snapshots a chunk. The chunk is write-locked for the duration of the copy (Minestom's lazy
     * light and heightmap evaluation mutate internal state), which takes microseconds to a few
     * milliseconds; compression and I/O happen after the lock is released.
     */
    public static PumpChunk toPump(Chunk chunk) {
        final Instance instance = chunk.getInstance();
        final DimensionType dimension = instance.getCachedDimensionType();
        final int minSection = chunk.getMinSection();
        final int maxSection = chunk.getMaxSection();

        final PumpChunk.Builder out =
                PumpChunk.builder(chunk.getChunkX(), chunk.getChunkZ())
                        .dataVersion(MinecraftServer.DATA_VERSION)
                        .minSectionY(minSection)
                        .status(ChunkStatus.FULL);

        boolean lightComplete = true;
        chunk.lockWriteLock();
        try {
            for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
                final Section section = chunk.getSection(sectionY);
                final byte[] blockLight = lightOf(section.blockLight().array());
                final byte[] skyLight = lightOf(section.skyLight().array());
                lightComplete &=
                        blockLight != null
                                && skyLight != null
                                && !section.blockLight().requiresUpdate()
                                && !section.skyLight().requiresUpdate();
                out.section(
                        new PumpSection(
                                blocksOf(section.blockPalette()),
                                biomesOf(section.biomePalette()),
                                blockLight,
                                skyLight));
                collectBlockEntities(chunk, sectionY, out);
            }
            if (dimension.height() <= PumpFormat.HEIGHTMAP_MAX_VALUE) {
                out.worldSurface(heightmapOf(chunk.worldSurfaceHeightmap(), dimension));
                out.motionBlocking(heightmapOf(chunk.motionBlockingHeightmap(), dimension));
            }
        } finally {
            chunk.unlockWriteLock();
        }
        out.lightCorrect(lightComplete);

        CompoundBinaryTag tags = chunk.tagHandler().asCompound();
        final long inhabited = tags.getLong(INHABITED_TIME_TAG, 0L);
        out.inhabitedTime(inhabited);
        if (tags.get(INHABITED_TIME_TAG) != null) {
            tags = CompoundBinaryTag.builder().put(tags).remove(INHABITED_TIME_TAG).build();
        }
        if (!tags.isEmpty()) out.customData(tags);
        return out.build();
    }

    private static byte[] lightOf(byte[] array) {
        return array != null && array.length == LIGHT_BYTES ? array.clone() : null;
    }

    private static BlockStates blocksOf(Palette palette) {
        final int single = palette.singleValue();
        if (single != -1) return BlockStates.uniform(single);
        final char[] states = new char[PumpFormat.SECTION_BLOCKS];
        palette.getAll((x, y, z, value) -> states[PumpFormat.blockIndex(x, y, z)] = (char) value);
        return BlockStates.of(states);
    }

    private static Biomes biomesOf(Palette palette) {
        final int single = palette.singleValue();
        if (single != -1) return Biomes.uniform(single);
        final byte[] cells = new byte[PumpFormat.SECTION_BIOMES];
        palette.getAll(
                (x, y, z, value) -> {
                    if (value < 0 || value > 0xFF) {
                        throw new IllegalStateException(
                                "Biome id " + value + " does not fit the u8 of the PUMP format");
                    }
                    cells[PumpFormat.biomeIndex(x, y, z)] = (byte) value;
                });
        return Biomes.of(cells);
    }

    private static short[] heightmapOf(Heightmap heightmap, DimensionType dimension) {
        // Reading a height evaluates the lazily computed map, getNBT only exports what is there.
        heightmap.getHeight(0, 0);
        final int bits = MathUtils.bitsToRepresent(dimension.height());
        return Heightmaps.unpackVanilla(heightmap.getNBT(), bits);
    }

    private static void collectBlockEntities(Chunk chunk, int sectionY, PumpChunk.Builder out) {
        final int baseY = sectionY * Chunk.CHUNK_SECTION_SIZE;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final Block block =
                            chunk.getBlock(x, baseY + y, z, Block.Getter.Condition.CACHED);
                    if (block == null) continue;
                    final BlockHandler handler = block.handler();
                    final CompoundBinaryTag nbt = block.nbt();
                    if (handler == null && nbt == null) continue;

                    final CompoundBinaryTag.Builder tag = CompoundBinaryTag.builder();
                    if (nbt != null) tag.put(nbt);
                    tag.putString("id", blockEntityId(block, handler));
                    tag.putInt("x", chunk.getChunkX() * Chunk.CHUNK_SIZE_X + x);
                    tag.putInt("y", baseY + y);
                    tag.putInt("z", chunk.getChunkZ() * Chunk.CHUNK_SIZE_Z + z);
                    out.blockEntity(tag.build());
                }
            }
        }
    }

    private static String blockEntityId(Block block, BlockHandler handler) {
        if (handler != null) return handler.getKey().asString();
        final BlockEntityType type = block.blockEntityType();
        if (type != null) return type.key().asString();
        return block.key().asString();
    }

    // ---------------------------------------------------------------------------------------
    // PumpChunk -> Chunk
    // ---------------------------------------------------------------------------------------

    /**
     * Creates a chunk with the instance's chunk supplier and fills it with {@code data}.
     *
     * @throws PumpCorruptException if the data references block states Minestom does not know
     */
    public static Chunk toChunk(Instance instance, PumpChunk data) throws PumpCorruptException {
        final Chunk chunk = instance.getChunkSupplier().createChunk(instance, data.x(), data.z());
        chunk.lockWriteLock();
        try {
            populate(chunk, data);
        } finally {
            chunk.unlockWriteLock();
        }
        return chunk;
    }

    /** Applies {@code data} to {@code chunk}, which the caller has write-locked. */
    static void populate(Chunk chunk, PumpChunk data) throws PumpCorruptException {
        final Instance instance = chunk.getInstance();
        final DimensionType dimension = instance.getCachedDimensionType();
        final DynamicRegistry<Biome> biomeRegistry = instance.registries().biome();
        final int plains = biomeRegistry.getId(Biome.PLAINS);
        final int stateLimit = Block.statesCount();

        for (int i = 0; i < data.sections().size(); i++) {
            final int sectionY = data.minSectionY() + i;
            if (sectionY < chunk.getMinSection() || sectionY >= chunk.getMaxSection()) continue;
            final Section section = chunk.getSection(sectionY);
            final PumpSection stored = data.sections().get(i);

            final BlockStates blocks = stored.blocks();
            if (blocks.isUniform()) {
                checkState(blocks.uniformState(), stateLimit);
                section.blockPalette().fill(blocks.uniformState());
            } else {
                for (int k = 0; k < PumpFormat.SECTION_BLOCKS; k++) {
                    checkState(blocks.get(k), stateLimit);
                }
                section.blockPalette()
                        .setAll((x, y, z) -> blocks.get(PumpFormat.blockIndex(x, y, z)));
            }

            final Biomes biomes = stored.biomes();
            final int[] known = new int[256]; // 0 = unchecked, otherwise id + 1 of the usable biome
            if (biomes.isUniform()) {
                section.biomePalette()
                        .fill(usableBiome(biomeRegistry, known, biomes.uniformBiome(), plains));
            } else {
                section.biomePalette()
                        .setAll(
                                (x, y, z) ->
                                        usableBiome(
                                                biomeRegistry,
                                                known,
                                                biomes.get(PumpFormat.biomeIndex(x, y, z)),
                                                plains));
            }

            if (stored.blockLight() != null) section.setBlockLight(stored.blockLight().clone());
            if (stored.skyLight() != null) section.setSkyLight(stored.skyLight().clone());
        }

        applyBlockEntities(chunk, data);

        if (dimension.height() <= PumpFormat.HEIGHTMAP_MAX_VALUE) {
            final int bits = MathUtils.bitsToRepresent(dimension.height());
            final CompoundBinaryTag.Builder maps = CompoundBinaryTag.builder();
            if (data.worldSurface() != null) {
                maps.put(
                        WORLD_SURFACE,
                        LongArrayBinaryTag.longArrayBinaryTag(
                                Heightmaps.packVanilla(data.worldSurface(), bits)));
            }
            if (data.motionBlocking() != null) {
                maps.put(
                        MOTION_BLOCKING,
                        LongArrayBinaryTag.longArrayBinaryTag(
                                Heightmaps.packVanilla(data.motionBlocking(), bits)));
            }
            chunk.loadHeightmapsFromNBT(maps.build());
        }

        final CompoundBinaryTag.Builder tags = CompoundBinaryTag.builder();
        if (data.customData() != null) tags.put(data.customData());
        if (data.inhabitedTime() != 0) tags.putLong(INHABITED_TIME_TAG, data.inhabitedTime());
        final CompoundBinaryTag content = tags.build();
        if (!content.isEmpty()) chunk.tagHandler().updateContent(content);
    }

    private static void checkState(int stateId, int limit) throws PumpCorruptException {
        if (stateId >= limit) {
            throw new PumpCorruptException(
                    "Block state id "
                            + stateId
                            + " is unknown to this server ("
                            + limit
                            + " states), the chunk was written for another data version");
        }
    }

    private static int usableBiome(
            DynamicRegistry<Biome> registry, int[] cache, int biomeId, int fallback) {
        int cached = cache[biomeId];
        if (cached == 0) {
            cached = (registry.getKey(biomeId) != null ? biomeId : fallback) + 1;
            cache[biomeId] = cached;
        }
        return cached - 1;
    }

    private static void applyBlockEntities(Chunk chunk, PumpChunk data) {
        final List<CompoundBinaryTag> entries = new ArrayList<>(data.blockEntities());
        for (CompoundBinaryTag entry : entries) {
            final int x = entry.getInt("x");
            final int y = entry.getInt("y");
            final int z = entry.getInt("z");
            if ((x >> 4) != chunk.getChunkX()
                    || (z >> 4) != chunk.getChunkZ()
                    || (y >> 4) < chunk.getMinSection()
                    || (y >> 4) >= chunk.getMaxSection()) {
                continue; // outside the chunk: cannot belong to a block of this chunk
            }
            final Section section = chunk.getSectionAt(y);
            final int stateId = section.blockPalette().get(x & 15, y & 15, z & 15);
            Block block = Block.fromStateId(stateId);
            if (block == null) continue;
            if (entry.get("id") instanceof StringBinaryTag id) {
                block =
                        block.withHandler(
                                MinecraftServer.getBlockManager().getHandlerOrDummy(id.value()));
            }
            final CompoundBinaryTag trimmed =
                    CompoundBinaryTag.builder()
                            .put(entry)
                            .remove("id")
                            .remove("keepPacked")
                            .remove("x")
                            .remove("y")
                            .remove("z")
                            .build();
            chunk.setBlock(x, y, z, trimmed.isEmpty() ? block : block.withNbt(trimmed));
        }
    }
}
