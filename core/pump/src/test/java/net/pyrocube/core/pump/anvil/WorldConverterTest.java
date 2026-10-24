package net.pyrocube.core.pump.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.region.PumpOptions;
import net.pyrocube.core.pump.region.PumpStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldConverterTest {
    @TempDir Path dir;

    private final WorldConverter converter =
            new WorldConverter(
                    new AnvilTranscoder(AnvilFixtures.BIOMES, UnknownBlockPolicy.FAIL),
                    PumpOptions.defaults());

    @Test
    void pumpToAnvilToPumpAcrossRegions() throws Exception {
        final Path pump1 = dir.resolve("pump1");
        final List<PumpChunk> chunks = new ArrayList<>();
        for (int x = -34; x < 36; x += 5) {
            for (int z = -3; z < 40; z += 7) chunks.add(AnvilFixtures.validChunk(x, z, x * 100 + z));
        }
        try (PumpStorage s = PumpStorage.open(pump1)) {
            s.writeAll(chunks);
        }

        final Path anvil = dir.resolve("anvil");
        final ConversionReport toAnvil = converter.pumpToAnvil(pump1, anvil);
        assertEquals(chunks.size(), toAnvil.converted());
        assertEquals(0, toAnvil.failed(), toAnvil.failures().toString());
        assertTrue(Files.exists(anvil.resolve("r.-2.-1.mca")));
        assertTrue(toAnvil.regions() >= 4);

        final Path pump2 = dir.resolve("pump2");
        final ConversionReport toPump = converter.anvilToPump(anvil, pump2);
        assertEquals(chunks.size(), toPump.converted());
        assertEquals(0, toPump.failed(), toPump.failures().toString());
        assertTrue(toPump.bytesOut() > 0);

        try (PumpStorage s = PumpStorage.open(pump2)) {
            for (PumpChunk expected : chunks) {
                assertEquals(expected, s.read(expected.x(), expected.z()), expected.toString());
            }
        }
    }

    @Test
    void failuresAreReportedAndDoNotStopTheRun() throws Exception {
        final Path anvil = dir.resolve("anvil");
        Files.createDirectories(anvil);
        final AnvilTranscoder t = new AnvilTranscoder(AnvilFixtures.BIOMES, UnknownBlockPolicy.FAIL);
        final CompoundBinaryTag[] slots = new CompoundBinaryTag[1024];
        slots[0] = t.toAnvil(AnvilFixtures.validChunk(0, 0, 1)).value();
        slots[1] = t.toAnvil(AnvilFixtures.validChunk(1, 0, 2)).value();
        slots[2] = t.toAnvil(AnvilFixtures.validChunk(2, 0, 3)).value();
        // chunk 3 stored at slot 3 but claiming to be (9, 9)
        slots[3] = t.toAnvil(AnvilFixtures.validChunk(9, 9, 4)).value();
        // chunk 4 with a block this server has never heard of
        final CompoundBinaryTag bad =
                CompoundBinaryTag.builder()
                        .putInt("DataVersion", 1)
                        .putInt("xPos", 4)
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
                                                                                        List.of(CompoundBinaryTag.builder().putString("Name", "x:y").build())))
                                                                        .build())
                                                        .build())))
                        .build();
        slots[4] = bad;
        AnvilRegion.write(anvil.resolve("r.0.0.mca"), slots);

        final ConversionReport report = converter.anvilToPump(anvil, dir.resolve("pump"));
        assertEquals(3, report.converted());
        assertEquals(2, report.failed());
        assertEquals(2, report.failures().size());
        assertTrue(report.failures().stream().anyMatch(f -> f.contains("claims position")), report.failures().toString());
        assertTrue(report.failures().stream().anyMatch(f -> f.contains("x:y")), report.failures().toString());
        try (PumpStorage s = PumpStorage.open(dir.resolve("pump"))) {
            assertNotNull(s.read(0, 0));
            assertNotNull(s.read(2, 0));
            assertEquals(null, s.read(4, 0));
        }
    }

    /** An entity-region entry holding one entity, tagged so it can be recognised after a round trip. */
    private static CompoundBinaryTag entityEntry(int x, int z, String id) {
        return CompoundBinaryTag.builder()
                .putInt("DataVersion", 4000)
                .putIntArray("Position", new int[] {x, z})
                .put(
                        "Entities",
                        ListBinaryTag.listBinaryTag(
                                BinaryTagTypes.COMPOUND,
                                List.of(CompoundBinaryTag.builder().putString("id", id).build())))
                .build();
    }

    private static CompoundBinaryTag poiEntry(int y) {
        return CompoundBinaryTag.builder()
                .putInt("DataVersion", 4000)
                .put(
                        "Sections",
                        CompoundBinaryTag.builder()
                                .put(
                                        Integer.toString(y),
                                        CompoundBinaryTag.builder()
                                                .putByte("Valid", (byte) 1)
                                                .put(
                                                        "Records",
                                                        ListBinaryTag.listBinaryTag(
                                                                BinaryTagTypes.COMPOUND,
                                                                List.of(
                                                                        CompoundBinaryTag.builder()
                                                                                .putIntArray("pos", new int[] {1, y, 2})
                                                                                .putString("type", "minecraft:home")
                                                                                .putInt("free_tickets", 1)
                                                                                .build())))
                                                .build())
                                .build())
                .build();
    }

    private static CompoundBinaryTag emptyEntityEntry(int x, int z) {
        return CompoundBinaryTag.builder()
                .putInt("DataVersion", 4000)
                .putIntArray("Position", new int[] {x, z})
                .put("Entities", ListBinaryTag.empty())
                .build();
    }

    @Test
    void entitiesAndPointsOfInterestSurviveAnvilToPumpToAnvil() throws Exception {
        final AnvilTranscoder t = new AnvilTranscoder(AnvilFixtures.BIOMES, UnknownBlockPolicy.FAIL);
        final Path world = dir.resolve("world");
        final CompoundBinaryTag[] terrain = new CompoundBinaryTag[1024];
        final CompoundBinaryTag[] entities = new CompoundBinaryTag[1024];
        final CompoundBinaryTag[] poi = new CompoundBinaryTag[1024];
        for (int i = 0; i < 4; i++) terrain[i] = t.toAnvil(AnvilFixtures.validChunk(i, 0, i)).value();
        entities[0] = entityEntry(0, 0, "minecraft:cow");
        entities[1] = emptyEntityEntry(1, 0); // no content, not carried over
        entities[2] = entityEntry(2, 0, "minecraft:pig");
        poi[2] = poiEntry(3);
        entities[9] = entityEntry(9, 0, "minecraft:sheep"); // no chunk 9 in the terrain region
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("entities"));
        Files.createDirectories(world.resolve("poi"));
        AnvilRegion.write(world.resolve("region/r.0.0.mca"), terrain);
        AnvilRegion.write(world.resolve("entities/r.0.0.mca"), entities);
        AnvilRegion.write(world.resolve("poi/r.0.0.mca"), poi);
        // a sidecar region with no terrain region next to it
        final CompoundBinaryTag[] stray = new CompoundBinaryTag[1024];
        stray[0] = entityEntry(64, 0, "minecraft:cat");
        AnvilRegion.write(world.resolve("entities/r.2.0.mca"), stray);

        final Path pump = dir.resolve("out/region");
        final ConversionReport toPump = converter.anvilToPump(world.resolve("region"), pump);
        assertEquals(4, toPump.converted());
        assertEquals(0, toPump.failed(), toPump.failures().toString());
        assertEquals(2, toPump.warnings(), "the chunk 9 entry and the stray region entry");
        try (PumpStorage s = PumpStorage.open(pump)) {
            assertEquals(
                    entityEntry(0, 0, "minecraft:cow"),
                    s.read(0, 0).customData().getCompound(AnvilTranscoder.ENTITIES_KEY));
            assertFalse(
                    s.read(1, 0).customData().keySet().contains(AnvilTranscoder.ENTITIES_KEY),
                    "an empty entry is not stored");
            assertEquals(
                    poiEntry(3), s.read(2, 0).customData().getCompound(AnvilTranscoder.POI_KEY));
            assertFalse(s.read(3, 0).customData().keySet().contains(AnvilTranscoder.POI_KEY));
        }

        final Path back = dir.resolve("out2/region");
        final ConversionReport toAnvil = converter.pumpToAnvil(pump, back);
        assertEquals(0, toAnvil.failed(), toAnvil.failures().toString());
        final var entityRaws = AnvilRegion.readAll(dir.resolve("out2/entities/r.0.0.mca"), 0, 0);
        assertEquals(2, entityRaws.size());
        assertEquals(entityEntry(0, 0, "minecraft:cow"), entityRaws.get(0).parse());
        assertEquals(entityEntry(2, 0, "minecraft:pig"), entityRaws.get(1).parse());
        final var poiRaws = AnvilRegion.readAll(dir.resolve("out2/poi/r.0.0.mca"), 0, 0);
        assertEquals(1, poiRaws.size());
        assertEquals(poiEntry(3), poiRaws.get(0).parse());
        // the keys are not leaked into the terrain chunks
        for (var raw : AnvilRegion.readAll(back.resolve("r.0.0.mca"), 0, 0)) {
            assertFalse(raw.parse().keySet().contains(AnvilTranscoder.ENTITIES_KEY));
            assertFalse(raw.parse().keySet().contains(AnvilTranscoder.POI_KEY));
        }
    }

    @Test
    void aCorruptEntityEntryIsReportedButTheChunkIsKept() throws Exception {
        final AnvilTranscoder t = new AnvilTranscoder(AnvilFixtures.BIOMES, UnknownBlockPolicy.FAIL);
        final Path world = dir.resolve("world");
        final CompoundBinaryTag[] terrain = new CompoundBinaryTag[1024];
        terrain[0] = t.toAnvil(AnvilFixtures.validChunk(0, 0, 1)).value();
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("entities"));
        AnvilRegion.write(world.resolve("region/r.0.0.mca"), terrain);
        final byte[][] garbage = new byte[1024][];
        garbage[0] = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        AnvilRegion.write(world.resolve("entities/r.0.0.mca"), garbage, 2);

        final ConversionReport report = converter.anvilToPump(world.resolve("region"), dir.resolve("pump"));
        assertEquals(1, report.converted());
        assertEquals(1, report.failed());
        assertTrue(report.failures().get(0).contains("anvil:entities"), report.failures().toString());
        try (PumpStorage s = PumpStorage.open(dir.resolve("pump"))) {
            assertNotNull(s.read(0, 0));
        }
    }

    @Test
    void emptyInputProducesAnEmptyReport() throws Exception {
        final ConversionReport report = converter.anvilToPump(dir.resolve("nothing"), dir.resolve("out"));
        assertEquals(0, report.regions());
        assertEquals(0, report.converted());
    }
}
