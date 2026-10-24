package net.pyrocube.core.pump.anvil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.ByteBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.instance.block.Block;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpException;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.chunk.Biomes;
import net.pyrocube.core.pump.chunk.BlockEntities;
import net.pyrocube.core.pump.chunk.BlockStates;
import net.pyrocube.core.pump.chunk.ChunkStatus;
import net.pyrocube.core.pump.chunk.Heightmaps;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.chunk.PumpSection;
import net.pyrocube.core.pump.chunk.ScheduledTick;

/**
 * Conversion between Anvil chunk NBT and {@link PumpChunk} following Appendix B of the
 * specification.
 *
 * <ul>
 *   <li><b>Block states.</b> Anvil's {@code Name} plus {@code Properties} compounds map to the
 *       numeric state id of the running server's registry. The result is only valid for that
 *       server's data version, which is recorded in the chunk.
 *   <li><b>Biomes.</b> Biome names map to ids through {@link BiomeIds}.
 *   <li><b>Light.</b> {@code BlockLight} and {@code SkyLight} are the same 2048 byte nibble arrays.
 *   <li><b>Everything else</b> that PCHK has no field for (structures, {@code LastUpdate}, mod and
 *       plugin tags, heightmaps other than the three PCHK knows) is kept in the custom data
 *       compound and merged back on the way out, so a round trip does not strip anything.
 * </ul>
 *
 * <p>Tick targets use the numeric registry ids: {@link Block#id()} for block ticks and, for fluid
 * ticks, 0 empty, 1 flowing water, 2 water, 3 flowing lava, 4 lava.
 *
 * <p>Instances are thread-safe, they only hold caches.
 */
public final class AnvilTranscoder {
    private static final String[] FLUIDS = {
        "minecraft:empty",
        "minecraft:flowing_water",
        "minecraft:water",
        "minecraft:flowing_lava",
        "minecraft:lava"
    };

    private static final List<String> HEIGHTMAP_KEYS =
            List.of("WORLD_SURFACE", "MOTION_BLOCKING", "MOTION_BLOCKING_NO_LEAVES");

    /**
     * Custom data keys that carry the chunk's entry of the Anvil {@code entities/} and {@code poi/}
     * regions. {@link WorldConverter} fills and drains them, they never appear in a chunk root.
     */
    static final String ENTITIES_KEY = "anvil:entities";

    static final String POI_KEY = "anvil:poi";

    /** Root keys that map to a PCHK field and so are not part of the custom data. */
    private static final List<String> HANDLED_ROOT_KEYS =
            List.of(
                    "DataVersion",
                    "xPos",
                    "yPos",
                    "zPos",
                    "status",
                    "Status",
                    "sections",
                    "Sections",
                    "block_entities",
                    "Heightmaps",
                    "block_ticks",
                    "fluid_ticks",
                    "InhabitedTime",
                    "isLightOn");

    private final BiomeIds biomes;
    private final UnknownBlockPolicy unknownBlocks;
    private final Map<String, Integer> stateIds = new ConcurrentHashMap<>();
    private final Map<Integer, CompoundBinaryTag> stateTags = new ConcurrentHashMap<>();

    public AnvilTranscoder(BiomeIds biomes, UnknownBlockPolicy unknownBlocks) {
        this.biomes = biomes;
        this.unknownBlocks = unknownBlocks;
    }

    // ---------------------------------------------------------------------------------------
    // Anvil -> PUMP
    // ---------------------------------------------------------------------------------------

    /** Converts the root compound of an Anvil chunk. */
    public Transcoded<PumpChunk> toPump(CompoundBinaryTag root) throws PumpException {
        final List<String> warnings = new ArrayList<>();
        if (!(root.get("xPos") instanceof IntBinaryTag)
                || !(root.get("zPos") instanceof IntBinaryTag)) {
            throw new PumpCorruptException("Anvil chunk has no xPos/zPos");
        }
        final int x = root.getInt("xPos");
        final int z = root.getInt("zPos");

        // Collect sections by Y; vanilla also stores light-only sections below and above the world.
        final Map<Integer, CompoundBinaryTag> byY = new TreeMap<>();
        int maxBlockY = Integer.MIN_VALUE;
        int lowestY = Integer.MAX_VALUE;
        final ListBinaryTag sectionList =
                root.getList(
                        root.get("sections") != null ? "sections" : "Sections",
                        BinaryTagTypes.COMPOUND);
        for (BinaryTag tag : sectionList) {
            if (!(tag instanceof CompoundBinaryTag section)) continue;
            final int y = section.getInt("Y", Integer.MIN_VALUE);
            if (y == Integer.MIN_VALUE) throw new PumpCorruptException("Anvil section without Y");
            byY.put(y, section);
            lowestY = Math.min(lowestY, y);
            if (section.get("block_states") != null) maxBlockY = Math.max(maxBlockY, y);
        }
        final int minSectionY =
                root.get("yPos") instanceof IntBinaryTag
                        ? root.getInt("yPos")
                        : (lowestY == Integer.MAX_VALUE ? 0 : lowestY);

        final PumpChunk.Builder out = PumpChunk.builder(x, z);
        out.dataVersion(root.getInt("DataVersion"))
                .minSectionY(minSectionY)
                .status(statusOf(root))
                .inhabitedTime(root.getLong("InhabitedTime"))
                .lightCorrect(root.getByte("isLightOn") != 0);

        if (maxBlockY >= minSectionY) {
            final int count = maxBlockY - minSectionY + 1;
            if (count > PumpFormat.MAX_SECTIONS) {
                throw new PumpCorruptException("Anvil chunk spans " + count + " sections");
            }
            for (int y = minSectionY; y <= maxBlockY; y++) {
                final CompoundBinaryTag section = byY.get(y);
                out.section(
                        section == null
                                ? PumpSection.empty(biomes.fallbackId())
                                : sectionToPump(section, y, warnings));
            }
        }

        final CompoundBinaryTag heightmaps = root.getCompound("Heightmaps");
        out.worldSurface(heightmapOf(heightmaps, "WORLD_SURFACE"));
        out.motionBlocking(heightmapOf(heightmaps, "MOTION_BLOCKING"));
        out.motionBlockingNoLeaves(heightmapOf(heightmaps, "MOTION_BLOCKING_NO_LEAVES"));

        for (BinaryTag tag : root.getList("block_entities", BinaryTagTypes.COMPOUND)) {
            final CompoundBinaryTag be = (CompoundBinaryTag) tag;
            final String problem = BlockEntities.problem(be);
            if (problem == null) out.blockEntity(be);
            else warnings.add("dropped block entity: " + problem);
        }

        ticksToPump(root.getList("block_ticks", BinaryTagTypes.COMPOUND), false, out, warnings);
        ticksToPump(root.getList("fluid_ticks", BinaryTagTypes.COMPOUND), true, out, warnings);

        final CompoundBinaryTag.Builder custom = CompoundBinaryTag.builder();
        for (Map.Entry<String, ? extends BinaryTag> entry : root) {
            if (!HANDLED_ROOT_KEYS.contains(entry.getKey()))
                custom.put(entry.getKey(), entry.getValue());
        }
        final CompoundBinaryTag.Builder leftoverMaps = CompoundBinaryTag.builder();
        for (Map.Entry<String, ? extends BinaryTag> entry : heightmaps) {
            if (!HEIGHTMAP_KEYS.contains(entry.getKey()) || !isUsableHeightmap(entry.getValue())) {
                leftoverMaps.put(entry.getKey(), entry.getValue());
            }
        }
        final CompoundBinaryTag leftover = leftoverMaps.build();
        if (!leftover.isEmpty()) custom.put("Heightmaps", leftover);
        final CompoundBinaryTag customData = custom.build();
        if (!customData.isEmpty()) out.customData(customData);

        return new Transcoded<>(out.build(), warnings);
    }

    private PumpSection sectionToPump(CompoundBinaryTag section, int y, List<String> warnings)
            throws PumpCorruptException {
        final CompoundBinaryTag blockStates = section.getCompound("block_states");
        final ListBinaryTag blockPalette = blockStates.getList("palette", BinaryTagTypes.COMPOUND);
        final BlockStates blocks;
        if (blockPalette.size() == 0) {
            blocks = BlockStates.uniform(0);
        } else {
            final int[] ids = new int[blockPalette.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = stateId(blockPalette.getCompound(i), warnings);
            }
            if (ids.length == 1) {
                blocks = BlockStates.uniform(ids[0]);
            } else {
                final int[] indices = new int[PumpFormat.SECTION_BLOCKS];
                unpack(
                        blockStates.getLongArray("data"),
                        Math.max(4, bitsFor(ids.length - 1)),
                        indices,
                        ids.length,
                        "block");
                final char[] states = new char[PumpFormat.SECTION_BLOCKS];
                for (int i = 0; i < states.length; i++) states[i] = (char) ids[indices[i]];
                blocks = BlockStates.of(states);
            }
        }

        final CompoundBinaryTag biomeTag = section.getCompound("biomes");
        final ListBinaryTag biomePalette = biomeTag.getList("palette", BinaryTagTypes.STRING);
        final Biomes biomeData;
        if (biomePalette.size() == 0) {
            biomeData = Biomes.uniform(biomes.fallbackId());
        } else {
            final int[] ids = new int[biomePalette.size()];
            for (int i = 0; i < ids.length; i++) {
                final String name = biomePalette.getString(i);
                final int id = biomes.idOf(name);
                if (id < 0 || id > 0xFF) {
                    warnings.add("unknown biome " + name + " replaced by the fallback");
                    ids[i] = biomes.fallbackId();
                } else {
                    ids[i] = id;
                }
            }
            if (ids.length == 1) {
                biomeData = Biomes.uniform(ids[0]);
            } else {
                final int[] indices = new int[PumpFormat.SECTION_BIOMES];
                unpack(
                        biomeTag.getLongArray("data"),
                        Math.max(1, bitsFor(ids.length - 1)),
                        indices,
                        ids.length,
                        "biome");
                final byte[] cells = new byte[PumpFormat.SECTION_BIOMES];
                for (int i = 0; i < cells.length; i++) cells[i] = (byte) ids[indices[i]];
                biomeData = Biomes.of(cells);
            }
        }

        return new PumpSection(
                blocks,
                biomeData,
                lightOf(section.get("BlockLight")),
                lightOf(section.get("SkyLight")));
    }

    private static byte[] lightOf(BinaryTag tag) {
        return tag instanceof ByteArrayBinaryTag array && array.size() == PumpFormat.LIGHT_BYTES
                ? array.value()
                : null;
    }

    private int stateId(CompoundBinaryTag entry, List<String> warnings)
            throws PumpCorruptException {
        final String name = entry.getString("Name");
        final CompoundBinaryTag props = entry.getCompound("Properties");
        final StringBuilder key = new StringBuilder(name);
        if (!props.isEmpty()) {
            final Map<String, String> sorted = new TreeMap<>();
            for (Map.Entry<String, ? extends BinaryTag> p : props) {
                if (p.getValue() instanceof StringBinaryTag s) sorted.put(p.getKey(), s.value());
            }
            key.append(sorted);
        }
        final Integer cached = stateIds.get(key.toString());
        if (cached != null) {
            if (cached < 0) return unknownBlock(key.toString(), warnings);
            return cached;
        }
        int id = -1;
        try {
            Block block = Block.fromKey(name);
            if (block != null) {
                if (!props.isEmpty()) {
                    final Map<String, String> map = new HashMap<>();
                    for (Map.Entry<String, ? extends BinaryTag> p : props) {
                        if (p.getValue() instanceof StringBinaryTag s)
                            map.put(p.getKey(), s.value());
                    }
                    block = block.withProperties(map);
                }
                id = block.stateId();
            }
        } catch (RuntimeException e) {
            id = -1;
        }
        stateIds.put(key.toString(), id);
        return id < 0 ? unknownBlock(key.toString(), warnings) : id;
    }

    private int unknownBlock(String description, List<String> warnings)
            throws PumpCorruptException {
        if (unknownBlocks == UnknownBlockPolicy.FAIL) {
            throw new PumpCorruptException("Unknown block state " + description);
        }
        warnings.add("unknown block state " + description + " replaced by air");
        return 0;
    }

    private static ChunkStatus statusOf(CompoundBinaryTag root) {
        String status = root.getString("status");
        if (status.isEmpty()) status = root.getString("Status");
        if (status.isEmpty()) return ChunkStatus.FULL; // very old chunks carry no status
        if (status.startsWith("minecraft:")) status = status.substring(10);
        return switch (status) {
            case "empty" -> ChunkStatus.EMPTY;
            case "structure_starts" -> ChunkStatus.STRUCTURE_STARTS;
            case "structure_references" -> ChunkStatus.STRUCTURE_REFERENCES;
            case "biomes" -> ChunkStatus.BIOMES;
            case "noise", "surface", "carvers", "liquid_carvers" -> ChunkStatus.TERRAIN;
            case "features" -> ChunkStatus.FEATURES;
            case "initialize_light" -> ChunkStatus.INITIALIZE_LIGHT;
            case "light" -> ChunkStatus.LIGHT;
            case "spawn" -> ChunkStatus.SPAWN;
            case "full", "heightmaps" -> ChunkStatus.FULL;
            default -> ChunkStatus.EMPTY;
        };
    }

    private static boolean isUsableHeightmap(BinaryTag tag) {
        return tag instanceof LongArrayBinaryTag array
                && array.size() == PumpFormat.HEIGHTMAP_WORDS;
    }

    private static short[] heightmapOf(CompoundBinaryTag heightmaps, String key) {
        final BinaryTag tag = heightmaps.get(key);
        if (!isUsableHeightmap(tag)) return null;
        return Heightmaps.unpackVanilla(
                ((LongArrayBinaryTag) tag).value(), PumpFormat.HEIGHTMAP_BITS);
    }

    private void ticksToPump(
            ListBinaryTag ticks, boolean fluid, PumpChunk.Builder out, List<String> warnings) {
        for (BinaryTag tag : ticks) {
            final CompoundBinaryTag tick = (CompoundBinaryTag) tag;
            final String target = tick.getString("i");
            final int id = fluid ? fluidId(target) : blockId(target);
            if (id < 0) {
                warnings.add("dropped " + (fluid ? "fluid" : "block") + " tick for " + target);
                continue;
            }
            final int delay = tick.getInt("t");
            final int priority = tick.getInt("p");
            if (delay < 0 || delay > 255 || priority < -3 || priority > 3) {
                warnings.add(
                        "clamped tick values of "
                                + target
                                + " (delay "
                                + delay
                                + ", priority "
                                + priority
                                + ")");
            }
            final ScheduledTick converted =
                    new ScheduledTick(
                            tick.getInt("x"),
                            tick.getInt("y"),
                            tick.getInt("z"),
                            Math.max(0, Math.min(255, delay)),
                            Math.max(-3, Math.min(3, priority)),
                            id);
            if (fluid) out.fluidTick(converted);
            else out.blockTick(converted);
        }
    }

    private static int blockId(String name) {
        final Block block = name.isEmpty() ? null : Block.fromKey(name);
        return block == null ? -1 : block.id();
    }

    private static int fluidId(String name) {
        for (int i = 0; i < FLUIDS.length; i++) if (FLUIDS[i].equals(name)) return i;
        return -1;
    }

    // ---------------------------------------------------------------------------------------
    // PUMP -> Anvil
    // ---------------------------------------------------------------------------------------

    /** Converts a chunk to the root compound of an Anvil chunk. */
    public Transcoded<CompoundBinaryTag> toAnvil(PumpChunk chunk) throws PumpException {
        final List<String> warnings = new ArrayList<>();
        final CompoundBinaryTag.Builder root = CompoundBinaryTag.builder();
        CompoundBinaryTag leftoverMaps = CompoundBinaryTag.empty();
        if (chunk.customData() != null) {
            for (Map.Entry<String, ? extends BinaryTag> entry : chunk.customData()) {
                if ("Heightmaps".equals(entry.getKey())
                        && entry.getValue() instanceof CompoundBinaryTag maps) {
                    leftoverMaps = maps;
                } else if (!ENTITIES_KEY.equals(entry.getKey())
                        && !POI_KEY.equals(entry.getKey())) {
                    root.put(entry.getKey(), entry.getValue());
                }
            }
        }

        root.putInt("DataVersion", chunk.dataVersion());
        root.putInt("xPos", chunk.x());
        root.putInt("yPos", chunk.minSectionY());
        root.putInt("zPos", chunk.z());
        root.putString("status", "minecraft:" + statusName(chunk.status()));
        root.putLong("InhabitedTime", chunk.inhabitedTime());
        root.putByte("isLightOn", (byte) (chunk.lightCorrect() ? 1 : 0));

        final ListBinaryTag.Builder<CompoundBinaryTag> sections =
                ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        final int[] indices = new int[PumpFormat.SECTION_BLOCKS];
        final int[] tableKeys = new int[8192];
        final int[] tableVals = new int[8192];
        for (int i = 0; i < chunk.sections().size(); i++) {
            sections.add(
                    sectionToAnvil(
                            chunk.sections().get(i),
                            chunk.minSectionY() + i,
                            indices,
                            tableKeys,
                            tableVals));
        }
        root.put("sections", sections.build());

        final ListBinaryTag.Builder<CompoundBinaryTag> blockEntities =
                ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        chunk.blockEntities().forEach(blockEntities::add);
        root.put("block_entities", blockEntities.build());

        final CompoundBinaryTag.Builder maps = CompoundBinaryTag.builder().put(leftoverMaps);
        putHeightmap(maps, "WORLD_SURFACE", chunk.worldSurface());
        putHeightmap(maps, "MOTION_BLOCKING", chunk.motionBlocking());
        putHeightmap(maps, "MOTION_BLOCKING_NO_LEAVES", chunk.motionBlockingNoLeaves());
        root.put("Heightmaps", maps.build());

        root.put("block_ticks", ticksToAnvil(chunk.blockTicks(), false, warnings));
        root.put("fluid_ticks", ticksToAnvil(chunk.fluidTicks(), true, warnings));
        return new Transcoded<>(root.build(), warnings);
    }

    private CompoundBinaryTag sectionToAnvil(
            PumpSection section, int y, int[] indices, int[] tableKeys, int[] tableVals) {
        final CompoundBinaryTag.Builder out = CompoundBinaryTag.builder();
        out.putByte("Y", (byte) y);

        final CompoundBinaryTag.Builder blockStates = CompoundBinaryTag.builder();
        final BlockStates blocks = section.blocks();
        if (blocks.isUniform()) {
            blockStates.put(
                    "palette",
                    ListBinaryTag.listBinaryTag(
                            BinaryTagTypes.COMPOUND, List.of(stateTag(blocks.uniformState()))));
        } else {
            Arrays.fill(tableKeys, 0);
            final List<BinaryTag> palette = new ArrayList<>();
            for (int i = 0; i < PumpFormat.SECTION_BLOCKS; i++) {
                final int state = blocks.get(i);
                int slot = (state * 0x9E3779B1) >>> 19;
                while (tableKeys[slot] != 0 && tableKeys[slot] != state + 1)
                    slot = (slot + 1) & 8191;
                if (tableKeys[slot] == 0) {
                    tableKeys[slot] = state + 1;
                    tableVals[slot] = palette.size();
                    palette.add(stateTag(state));
                }
                indices[i] = tableVals[slot];
            }
            blockStates.put(
                    "palette", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, palette));
            blockStates.putLongArray(
                    "data",
                    pack(
                            indices,
                            PumpFormat.SECTION_BLOCKS,
                            Math.max(4, bitsFor(palette.size() - 1))));
        }
        out.put("block_states", blockStates.build());

        final CompoundBinaryTag.Builder biomeStates = CompoundBinaryTag.builder();
        final Biomes biomeData = section.biomes();
        if (biomeData.isUniform()) {
            biomeStates.put(
                    "palette",
                    ListBinaryTag.listBinaryTag(
                            BinaryTagTypes.STRING,
                            List.of(
                                    StringBinaryTag.stringBinaryTag(
                                            biomeName(biomeData.uniformBiome())))));
        } else {
            final List<Integer> ids = new ArrayList<>();
            final int[] biomeIndices = new int[PumpFormat.SECTION_BIOMES];
            for (int i = 0; i < PumpFormat.SECTION_BIOMES; i++) {
                int at = ids.indexOf(biomeData.get(i));
                if (at < 0) {
                    at = ids.size();
                    ids.add(biomeData.get(i));
                }
                biomeIndices[i] = at;
            }
            final List<BinaryTag> names = new ArrayList<>();
            for (int id : ids) names.add(StringBinaryTag.stringBinaryTag(biomeName(id)));
            biomeStates.put("palette", ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING, names));
            biomeStates.putLongArray(
                    "data",
                    pack(
                            biomeIndices,
                            PumpFormat.SECTION_BIOMES,
                            Math.max(1, bitsFor(ids.size() - 1))));
        }
        out.put("biomes", biomeStates.build());

        if (section.blockLight() != null) out.putByteArray("BlockLight", section.blockLight());
        if (section.skyLight() != null) out.putByteArray("SkyLight", section.skyLight());
        return out.build();
    }

    private String biomeName(int id) {
        String name = biomes.nameOf(id);
        if (name == null) name = biomes.nameOf(biomes.fallbackId());
        return name == null ? "minecraft:plains" : name;
    }

    private CompoundBinaryTag stateTag(int stateId) {
        return stateTags.computeIfAbsent(
                stateId,
                id -> {
                    final Block block = Block.fromStateId(id);
                    final Block resolved = block == null ? Block.AIR : block;
                    final CompoundBinaryTag.Builder tag = CompoundBinaryTag.builder();
                    tag.putString("Name", resolved.name());
                    final Map<String, String> properties = resolved.properties();
                    if (!properties.isEmpty()) {
                        final Map<String, String> defaults = resolved.defaultState().properties();
                        final CompoundBinaryTag.Builder props = CompoundBinaryTag.builder();
                        for (Map.Entry<String, String> p : properties.entrySet()) {
                            if (!p.getValue().equals(defaults.get(p.getKey())))
                                props.putString(p.getKey(), p.getValue());
                        }
                        final CompoundBinaryTag built = props.build();
                        if (!built.isEmpty()) tag.put("Properties", built);
                    }
                    return tag.build();
                });
    }

    private static String statusName(ChunkStatus status) {
        return switch (status) {
            case EMPTY -> "empty";
            case STRUCTURE_STARTS -> "structure_starts";
            case STRUCTURE_REFERENCES -> "structure_references";
            case BIOMES -> "biomes";
            case TERRAIN -> "noise";
            case FEATURES -> "features";
            case INITIALIZE_LIGHT -> "initialize_light";
            case LIGHT -> "light";
            case SPAWN -> "spawn";
            case FULL -> "full";
        };
    }

    private static void putHeightmap(CompoundBinaryTag.Builder maps, String key, short[] values) {
        if (values != null) {
            maps.putLongArray(key, Heightmaps.packVanilla(values, PumpFormat.HEIGHTMAP_BITS));
        }
    }

    private ListBinaryTag ticksToAnvil(
            List<ScheduledTick> ticks, boolean fluid, List<String> warnings) {
        final ListBinaryTag.Builder<CompoundBinaryTag> out =
                ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (ScheduledTick t : ticks) {
            final String target;
            if (fluid) {
                target = t.targetId() < FLUIDS.length ? FLUIDS[t.targetId()] : null;
            } else {
                final Block block = Block.fromBlockId(t.targetId());
                target = block == null ? null : block.name();
            }
            if (target == null) {
                warnings.add(
                        "dropped "
                                + (fluid ? "fluid" : "block")
                                + " tick with unknown target "
                                + t.targetId());
                continue;
            }
            out.add(
                    CompoundBinaryTag.builder()
                            .putString("i", target)
                            .putInt("p", t.priority())
                            .putInt("t", t.delay())
                            .putInt("x", t.x())
                            .putInt("y", t.y())
                            .putInt("z", t.z())
                            .build());
        }
        return out.build();
    }

    // ---------------------------------------------------------------------------------------
    // Packed arrays (Anvil layout: values never straddle two longs)
    // ---------------------------------------------------------------------------------------

    private static int bitsFor(int maxValue) {
        return maxValue <= 0 ? 0 : Integer.SIZE - Integer.numberOfLeadingZeros(maxValue);
    }

    private static void unpack(long[] data, int bits, int[] out, int paletteSize, String what)
            throws PumpCorruptException {
        final int perLong = 64 / bits;
        final int needed = (out.length + perLong - 1) / perLong;
        if (data.length < needed) {
            throw new PumpCorruptException(
                    "Anvil " + what + " data has " + data.length + " longs, expected " + needed);
        }
        final long mask = (1L << bits) - 1;
        for (int i = 0; i < out.length; i++) {
            final int value = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            if (value >= paletteSize) {
                throw new PumpCorruptException(
                        "Anvil " + what + " index " + value + " outside palette of " + paletteSize);
            }
            out[i] = value;
        }
    }

    private static long[] pack(int[] values, int count, int bits) {
        final int perLong = 64 / bits;
        final long[] out = new long[(count + perLong - 1) / perLong];
        for (int i = 0; i < count; i++) {
            out[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
        }
        return out;
    }

    /** Whether the Anvil byte tag form is used for section Y, kept for symmetry with vanilla. */
    static boolean isByte(BinaryTag tag) {
        return tag instanceof ByteBinaryTag;
    }
}
