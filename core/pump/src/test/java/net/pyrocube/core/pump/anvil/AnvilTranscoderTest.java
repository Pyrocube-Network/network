package net.pyrocube.core.pump.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minestom.server.instance.block.Block;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.chunk.ChunkStatus;
import net.pyrocube.core.pump.chunk.Heightmaps;
import net.pyrocube.core.pump.chunk.PumpChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnvilTranscoderTest {
    private final AnvilTranscoder transcoder =
            new AnvilTranscoder(AnvilFixtures.BIOMES, UnknownBlockPolicy.FAIL);

    @TempDir Path dir;

    @Test
    void pumpToAnvilToPumpIsLossless() throws Exception {
        for (int i = 0; i < 10; i++) {
            final PumpChunk chunk = AnvilFixtures.validChunk(i - 5, 3 * i, i);
            final Transcoded<CompoundBinaryTag> anvil = transcoder.toAnvil(chunk);
            assertEquals(List.of(), anvil.warnings());
            final Transcoded<PumpChunk> back = transcoder.toPump(anvil.value());
            assertEquals(List.of(), back.warnings());
            assertEquals(chunk, back.value(), "seed " + i);
        }
    }

    @Test
    void survivesTheNbtFileFormat() throws Exception {
        final PumpChunk chunk = AnvilFixtures.validChunk(1, 2, 3);
        final CompoundBinaryTag[] slots = new CompoundBinaryTag[1024];
        slots[2 * 32 + 1] = transcoder.toAnvil(chunk).value();
        final Path file = dir.resolve("r.0.0.mca");
        AnvilRegion.write(file, slots);

        final var raws = AnvilRegion.readAll(file, 0, 0);
        assertEquals(1, raws.size());
        assertEquals(2 * 32 + 1, raws.get(0).index());
        assertEquals(chunk, transcoder.toPump(raws.get(0).parse()).value());
    }

    @Test
    void anvilFormatDetails() throws Exception {
        final PumpChunk chunk = AnvilFixtures.validChunk(0, 0, 1);
        final CompoundBinaryTag root = transcoder.toAnvil(chunk).value();
        assertEquals("minecraft:full", root.getString("status"));
        assertEquals(-4, root.getInt("yPos"));
        assertEquals(1, root.getByte("isLightOn"));
        assertEquals(chunk.sections().size(), root.getList("sections", BinaryTagTypes.COMPOUND).size());

        final CompoundBinaryTag first = root.getList("sections", BinaryTagTypes.COMPOUND).getCompound(0);
        assertEquals(-4, first.getByte("Y"));
        // uniform section: a one element palette and no data array
        assertEquals(1, first.getCompound("block_states").getList("palette", BinaryTagTypes.COMPOUND).size());
        assertTrue(first.getCompound("block_states").getLongArray("data").length == 0);

        // heightmaps are in Minecraft's own layout: 7 values per long, 37 longs
        final long[] ws = root.getCompound("Heightmaps").getLongArray("WORLD_SURFACE");
        assertEquals(37, ws.length);
        assertArrayEquals(chunk.worldSurface(), Heightmaps.unpackVanilla(ws, 9));
        // entries the format has no field for stay available
        assertEquals(3, root.getCompound("Heightmaps").getLongArray("OCEAN_FLOOR").length);
        assertEquals(777L, root.getLong("LastUpdate"));
        assertEquals("pyrocube", root.getString("plugin"));
    }

    @Test
    void decodesVanillaStylePackedSections() throws Exception {
        // 5 palette entries -> 4 bits, 16 values per long, 256 longs
        final long[] data = new long[256];
        for (int i = 0; i < 4096; i++) data[i / 16] |= (long) (i % 5) << ((i % 16) * 4);
        final CompoundBinaryTag section =
                CompoundBinaryTag.builder()
                        .putByte("Y", (byte) 0)
                        .put(
                                "block_states",
                                CompoundBinaryTag.builder()
                                        .put(
                                                "palette",
                                                ListBinaryTag.listBinaryTag(
                                                        BinaryTagTypes.COMPOUND,
                                                        List.of(
                                                                entry("minecraft:air"),
                                                                entry("minecraft:stone"),
                                                                entry("minecraft:oak_log", "axis", "x"),
                                                                entry("minecraft:oak_log", "axis", "z"),
                                                                entry("minecraft:water", "level", "3"))))
                                        .putLongArray("data", data)
                                        .build())
                        .put(
                                "biomes",
                                CompoundBinaryTag.builder()
                                        .put(
                                                "palette",
                                                ListBinaryTag.listBinaryTag(
                                                        BinaryTagTypes.STRING,
                                                        List.of(
                                                                net.kyori.adventure.nbt.StringBinaryTag.stringBinaryTag("minecraft:desert"),
                                                                net.kyori.adventure.nbt.StringBinaryTag.stringBinaryTag("minecraft:swamp"))))
                                        // 1 bit per cell, 64 cells: alternate
                                        .putLongArray("data", new long[] {0xAAAAAAAAAAAAAAAAL})
                                        .build())
                        .build();
        final CompoundBinaryTag root =
                CompoundBinaryTag.builder()
                        .putInt("DataVersion", 4903)
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .putInt("yPos", 0)
                        .putString("status", "minecraft:full")
                        .put("sections", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.of(section)))
                        .build();

        final PumpChunk chunk = transcoder.toPump(root).value();
        assertEquals(1, chunk.sections().size());
        final var blocks = chunk.sections().get(0).blocks();
        final int[] expected = {
            Block.AIR.stateId(),
            Block.STONE.stateId(),
            Block.OAK_LOG.withProperty("axis", "x").stateId(),
            Block.OAK_LOG.withProperty("axis", "z").stateId(),
            Block.WATER.withProperty("level", "3").stateId()
        };
        for (int i = 0; i < 4096; i++) assertEquals(expected[i % 5], blocks.get(i), "block " + i);
        final var biomes = chunk.sections().get(0).biomes();
        for (int i = 0; i < 64; i++) assertEquals(i % 2 == 0 ? 2 : 5, biomes.get(i), "cell " + i);
        assertNull(chunk.worldSurface());
        assertEquals(ChunkStatus.FULL, chunk.status());
    }

    private static CompoundBinaryTag entry(String name, String... props) {
        final CompoundBinaryTag.Builder b = CompoundBinaryTag.builder().putString("Name", name);
        if (props.length > 0) {
            final CompoundBinaryTag.Builder p = CompoundBinaryTag.builder();
            for (int i = 0; i < props.length; i += 2) p.putString(props[i], props[i + 1]);
            b.put("Properties", p.build());
        }
        return b.build();
    }

    @Test
    void nonStandardTagsLandInTheCustomData() throws Exception {
        final CompoundBinaryTag root =
                CompoundBinaryTag.builder()
                        .putInt("DataVersion", 1)
                        .putInt("xPos", 4)
                        .putInt("zPos", 5)
                        .putInt("yPos", -4)
                        .putString("Status", "minecraft:features")
                        .putLong("LastUpdate", 99L)
                        .putLong("InhabitedTime", 1234L)
                        .put("structures", CompoundBinaryTag.builder().putString("s", "village").build())
                        .put("forge_caps", CompoundBinaryTag.builder().putInt("mana", 3).build())
                        .put(
                                "Heightmaps",
                                CompoundBinaryTag.builder().putLongArray("OCEAN_FLOOR_WG", new long[] {7}).build())
                        .build();
        final PumpChunk chunk = transcoder.toPump(root).value();
        assertEquals(ChunkStatus.FEATURES, chunk.status());
        assertEquals(1234L, chunk.inhabitedTime());
        final CompoundBinaryTag custom = chunk.customData();
        assertNotNull(custom);
        assertEquals(99L, custom.getLong("LastUpdate"));
        assertEquals(3, custom.getCompound("forge_caps").getInt("mana"));
        assertEquals("village", custom.getCompound("structures").getString("s"));
        assertEquals(7, custom.getCompound("Heightmaps").getLongArray("OCEAN_FLOOR_WG")[0]);
        assertFalse(custom.keySet().contains("InhabitedTime"));
        assertFalse(custom.keySet().contains("xPos"));

        final CompoundBinaryTag back = transcoder.toAnvil(chunk).value();
        assertEquals(99L, back.getLong("LastUpdate"));
        assertEquals(3, back.getCompound("forge_caps").getInt("mana"));
        assertEquals(7, back.getCompound("Heightmaps").getLongArray("OCEAN_FLOOR_WG")[0]);
    }

    @Test
    void unknownBlocksFollowThePolicy() throws Exception {
        final CompoundBinaryTag root =
                CompoundBinaryTag.builder()
                        .putInt("DataVersion", 1)
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .putInt("yPos", 0)
                        .put(
                                "sections",
                                ListBinaryTag.listBinaryTag(
                                        BinaryTagTypes.COMPOUND,
                                        List.of(
                                                CompoundBinaryTag.builder()
                                                        .putByte("Y", (byte) 0)
                                                        .put(
                                                                "block_states",
                                                                CompoundBinaryTag.builder()
                                                                        .put(
                                                                                "palette",
                                                                                ListBinaryTag.listBinaryTag(
                                                                                        BinaryTagTypes.COMPOUND,
                                                                                        List.of(entry("mod:unobtainium"))))
                                                                        .build())
                                                        .build())))
                        .build();
        assertThrows(PumpCorruptException.class, () -> transcoder.toPump(root));

        final AnvilTranscoder lenient = new AnvilTranscoder(AnvilFixtures.BIOMES, UnknownBlockPolicy.AIR);
        final Transcoded<PumpChunk> result = lenient.toPump(root);
        assertEquals(0, result.value().sections().get(0).blocks().uniformState());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("unobtainium"));
    }

    @Test
    void rejectsMalformedAnvil() {
        final CompoundBinaryTag noPos = CompoundBinaryTag.builder().putInt("DataVersion", 1).build();
        assertThrows(PumpCorruptException.class, () -> transcoder.toPump(noPos));

        // palette of 2 but index 3 in the data
        final long[] data = new long[256];
        data[0] = 3;
        final CompoundBinaryTag root =
                CompoundBinaryTag.builder()
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .putInt("yPos", 0)
                        .put(
                                "sections",
                                ListBinaryTag.listBinaryTag(
                                        BinaryTagTypes.COMPOUND,
                                        List.of(
                                                CompoundBinaryTag.builder()
                                                        .putByte("Y", (byte) 0)
                                                        .put(
                                                                "block_states",
                                                                CompoundBinaryTag.builder()
                                                                        .put(
                                                                                "palette",
                                                                                ListBinaryTag.listBinaryTag(
                                                                                        BinaryTagTypes.COMPOUND,
                                                                                        List.of(entry("minecraft:air"), entry("minecraft:stone"))))
                                                                        .putLongArray("data", data)
                                                                        .build())
                                                        .build())))
                        .build();
        assertThrows(PumpCorruptException.class, () -> transcoder.toPump(root));
    }

    @Test
    void ticksMapToRegistryIds() throws Exception {
        final PumpChunk chunk = AnvilFixtures.validChunk(0, 0, 4);
        final CompoundBinaryTag root = transcoder.toAnvil(chunk).value();
        final CompoundBinaryTag firstBlockTick = root.getList("block_ticks", BinaryTagTypes.COMPOUND).getCompound(0);
        final Block target = Block.fromBlockId(chunk.blockTicks().get(0).targetId());
        assertEquals(target.name(), firstBlockTick.getString("i"));
        final String fluid = root.getList("fluid_ticks", BinaryTagTypes.COMPOUND).getCompound(0).getString("i");
        assertTrue(Map.of(0, "minecraft:empty", 1, "minecraft:flowing_water", 2, "minecraft:water", 3, "minecraft:flowing_lava", 4, "minecraft:lava").containsValue(fluid));
    }

    @Test
    void ticksOutsideTheFormatRangesAreClampedWithAWarning() throws Exception {
        final CompoundBinaryTag root =
                CompoundBinaryTag.builder()
                        .putInt("DataVersion", 1)
                        .putInt("xPos", 0)
                        .putInt("zPos", 0)
                        .put(
                                "block_ticks",
                                ListBinaryTag.listBinaryTag(
                                        BinaryTagTypes.COMPOUND,
                                        List.of(
                                                CompoundBinaryTag.builder()
                                                        .putString("i", "minecraft:stone")
                                                        .putInt("t", 5000)
                                                        .putInt("p", 0)
                                                        .putInt("x", 1)
                                                        .putInt("y", 2)
                                                        .putInt("z", 3)
                                                        .build(),
                                                CompoundBinaryTag.builder()
                                                        .putString("i", "mod:nothing")
                                                        .putInt("t", 1)
                                                        .putInt("p", 0)
                                                        .putInt("x", 1)
                                                        .putInt("y", 2)
                                                        .putInt("z", 3)
                                                        .build())))
                        .build();
        final Transcoded<PumpChunk> result = transcoder.toPump(root);
        assertEquals(1, result.value().blockTicks().size());
        assertEquals(255, result.value().blockTicks().get(0).delay());
        assertEquals(2, result.warnings().size());
    }
}
