package net.pyrocube.core.pump.minestom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.tag.Tag;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.chunk.ChunkStatus;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.region.PumpStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PumpChunkLoaderTest {
    @TempDir Path world;

    @BeforeAll
    static void bootstrap() {
        MinecraftServer.init();
    }

    private static InstanceContainer newInstance(PumpChunkLoader loader) {
        final InstanceContainer instance =
                MinecraftServer.getInstanceManager()
                        .createInstanceContainer(DimensionType.OVERWORLD, loader);
        instance.setGenerator(
                unit -> {
                    unit.modifier().fillHeight(-64, 0, Block.BEDROCK);
                    unit.modifier().fillHeight(0, 40, Block.STONE);
                    unit.modifier().fillHeight(40, 44, Block.DIRT);
                    unit.modifier().fillHeight(44, 45, Block.GRASS_BLOCK);
                    // a scattering of mixed content so sections are not uniform
                    for (int i = 0; i < 16; i++) {
                        unit.modifier()
                                .setBlock(
                                        unit.absoluteStart().blockX() + i,
                                        45,
                                        unit.absoluteStart().blockZ() + i,
                                        i % 2 == 0 ? Block.OAK_LOG : Block.DIAMOND_ORE);
                    }
                });
        return instance;
    }

    private Path regionFile(int x, int z) {
        return world.resolve("dimensions/minecraft/overworld/region").resolve(PumpFormat.regionFileName(x, z));
    }

    private static PumpChunkLoader loader(Path world) {
        return new PumpChunkLoader(world, DimensionType.OVERWORLD.key());
    }

    private static void assertSameChunk(Chunk expected, Chunk actual) {
        expected.lockReadLock();
        actual.lockReadLock();
        try {
            compareChunks(expected, actual);
        } finally {
            actual.unlockReadLock();
            expected.unlockReadLock();
        }
    }

    private static void compareChunks(Chunk expected, Chunk actual) {
        assertEquals(expected.getMinSection(), actual.getMinSection());
        assertEquals(expected.getMaxSection(), actual.getMaxSection());
        for (int y = expected.getMinSection() * 16; y < expected.getMaxSection() * 16; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    final int gx = expected.getChunkX() * 16 + x;
                    final int gz = expected.getChunkZ() * 16 + z;
                    assertEquals(
                            expected.getBlock(gx, y, gz, Block.Getter.Condition.TYPE).stateId(),
                            actual.getBlock(gx, y, gz, Block.Getter.Condition.TYPE).stateId(),
                            "block " + gx + "," + y + "," + gz);
                }
            }
        }
        for (int y = expected.getMinSection() * 16; y < expected.getMaxSection() * 16; y += 4) {
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    final int gx = expected.getChunkX() * 16 + x;
                    final int gz = expected.getChunkZ() * 16 + z;
                    assertEquals(expected.getBiome(gx, y, gz), actual.getBiome(gx, y, gz));
                }
            }
        }
    }

    @Test
    void roundTripThroughAnInstance() throws Exception {
        final Tag<String> owner = Tag.String("owner");
        final CompoundBinaryTag chestData =
                CompoundBinaryTag.builder().putString("CustomName", "{\"text\":\"loot\"}").build();

        final List<Chunk> originals = new ArrayList<>();
        try (PumpChunkLoader first = loader(world)) {
            final InstanceContainer instance = newInstance(first);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) originals.add(instance.loadChunk(x, z).join());
            }
            instance.setBlock(3, 50, 4, Block.CHEST.withNbt(chestData));
            instance.setBlock(-5, 50, 20, Block.OAK_SIGN.withNbt(chestData));
            instance.getChunk(0, 0).setTag(owner, "pyrocube");
            instance.getChunk(0, 0).setTag(Tag.Long("InhabitedTime"), 12345L);
            instance.saveChunksToStorage().join();
            assertTrue(first.stats().chunksWritten() >= 9);
            assertTrue(Files.exists(regionFile(0, 0)));
            assertTrue(Files.exists(regionFile(-1, -1)));
        }

        try (PumpChunkLoader second = loader(world)) {
            final InstanceContainer instance =
                    MinecraftServer.getInstanceManager()
                            .createInstanceContainer(DimensionType.OVERWORLD, second);
            // no generator: everything must come from the files
            for (Chunk original : originals) {
                final Chunk loaded = instance.loadChunk(original.getChunkX(), original.getChunkZ()).join();
                assertNotNull(loaded);
                assertSameChunk(original, loaded);
            }
            final Chunk origin = instance.getChunk(0, 0);
            assertEquals("pyrocube", origin.getTag(owner));
            assertEquals(12345L, origin.getTag(Tag.Long("InhabitedTime")));

            final Block chest = instance.getBlock(3, 50, 4);
            assertEquals(Block.CHEST.id(), chest.id());
            assertEquals(chestData, chest.nbt());
            assertEquals(Block.OAK_SIGN.id(), instance.getBlock(-5, 50, 20).id());
            assertNull(instance.getBlock(0, 50, 0).nbt());
        }
    }

    @Test
    void savedChunkHasTheDocumentedShape() throws Exception {
        try (PumpChunkLoader loader = loader(world)) {
            final InstanceContainer instance = newInstance(loader);
            final Chunk chunk = instance.loadChunk(2, 3).join();
            instance.setBlock(33, 60, 50, Block.CHEST.withNbt(CompoundBinaryTag.builder().putInt("a", 1).build()));
            instance.getChunk(2, 3).setTag(Tag.Long("InhabitedTime"), 99L);
            loader.saveChunk(chunk);

            final PumpChunk stored = loader.storage().read(2, 3);
            assertEquals(MinecraftServer.DATA_VERSION, stored.dataVersion());
            assertEquals(ChunkStatus.FULL, stored.status());
            assertEquals(chunk.getMinSection(), stored.minSectionY());
            assertEquals(chunk.getMaxSection() - chunk.getMinSection(), stored.sections().size());
            assertEquals(99L, stored.inhabitedTime());
            assertNull(stored.customData(), "InhabitedTime is lifted into the header");
            assertNotNull(stored.worldSurface());
            assertNotNull(stored.motionBlocking());
            assertNull(stored.motionBlockingNoLeaves());
            assertEquals(1, stored.blockEntities().size());
            final CompoundBinaryTag be = stored.blockEntities().get(0);
            assertEquals(33, be.getInt("x"));
            assertEquals(60, be.getInt("y"));
            assertEquals(50, be.getInt("z"));
            assertEquals("minecraft:chest", be.getString("id"));
            // bedrock + stone sections below the surface are uniform, which is the common case
            assertTrue(stored.sections().get(4 + 1).blocks().isUniform());
            // heightmap: the log at y=45 is the top block of column 0, stored relative to the world bottom
            final int logColumn = stored.worldSurface()[0];
            assertEquals(45 + 1 + 64, logColumn);
        }
    }

    @Test
    void heightmapsSurviveTheRoundTrip() throws Exception {
        final short[] before;
        try (PumpChunkLoader first = loader(world)) {
            final InstanceContainer instance = newInstance(first);
            final Chunk chunk = instance.loadChunk(0, 0).join();
            before = toPump(chunk).worldSurface();
            first.saveChunk(chunk);
        }
        try (PumpChunkLoader second = loader(world)) {
            final PumpChunk stored = second.storage().read(0, 0);
            assertArrayEquals(before, stored.worldSurface());
            final InstanceContainer instance =
                    MinecraftServer.getInstanceManager()
                            .createInstanceContainer(DimensionType.OVERWORLD, second);
            final Chunk loaded = instance.loadChunk(0, 0).join();
            assertEquals(45, loaded.worldSurfaceHeightmap().getHeight(0, 0));
            assertEquals(44, loaded.worldSurfaceHeightmap().getHeight(1, 0));
        }
    }

    private static PumpChunk toPump(Chunk chunk) {
        return MinestomChunkMapper.toPump(chunk);
    }

    @Test
    void missingChunksAreGenerated() throws Exception {
        try (PumpChunkLoader loader = loader(world)) {
            final InstanceContainer instance = newInstance(loader);
            final Chunk chunk = instance.loadChunk(7, 7).join();
            assertEquals(Block.GRASS_BLOCK.id(), instance.getBlock(7 * 16 + 1, 44, 7 * 16 + 1).id());
            assertNotNull(chunk);
            assertEquals(0, loader.stats().chunksRead());
        }
    }

    @Test
    void dataVersionMismatchIsRejectedByDefault() throws Exception {
        final Path dir = world.resolve("custom");
        try (PumpChunkLoader seed = PumpChunkLoader.forRegionDirectory(dir)) {
            final InstanceContainer instance = newInstance(seed);
            final Chunk chunk = instance.loadChunk(0, 0).join();
            final PumpChunk old =
                    MinestomChunkMapper.toPump(chunk).toBuilder()
                            .dataVersion(MinecraftServer.DATA_VERSION - 1)
                            .build();
            seed.storage().write(old);
        }
        try (PumpChunkLoader strict = PumpChunkLoader.forRegionDirectory(dir)) {
            final InstanceContainer instance =
                    MinecraftServer.getInstanceManager()
                            .createInstanceContainer(DimensionType.OVERWORLD, strict);
            final IllegalStateException e =
                    assertThrows(IllegalStateException.class, () -> strict.loadChunk(instance, 0, 0));
            assertTrue(e.getMessage().contains("data version"));
        }
        try (PumpChunkLoader lenient =
                PumpChunkLoader.forRegionDirectory(
                        dir,
                        PumpLoaderOptions.defaults()
                                .withDataVersionPolicy(DataVersionPolicy.ACCEPT))) {
            final InstanceContainer instance =
                    MinecraftServer.getInstanceManager()
                            .createInstanceContainer(DimensionType.OVERWORLD, lenient);
            assertNotNull(lenient.loadChunk(instance, 0, 0));
        }
    }

    @Test
    void partiallyGeneratedChunksAreLeftToTheGenerator() throws Exception {
        final Path dir = world.resolve("custom");
        try (PumpChunkLoader seed = PumpChunkLoader.forRegionDirectory(dir)) {
            final InstanceContainer instance = newInstance(seed);
            final PumpChunk chunk = MinestomChunkMapper.toPump(instance.loadChunk(0, 0).join());
            seed.storage().write(chunk.toBuilder().status(ChunkStatus.FEATURES).build());
            assertNull(seed.loadChunk(instance, 0, 0));
        }
        try (PumpChunkLoader any =
                PumpChunkLoader.forRegionDirectory(
                        dir,
                        PumpLoaderOptions.defaults().withMinimumStatus(ChunkStatus.FEATURES))) {
            final InstanceContainer instance =
                    MinecraftServer.getInstanceManager()
                            .createInstanceContainer(DimensionType.OVERWORLD, any);
            assertNotNull(any.loadChunk(instance, 0, 0));
        }
    }

    @Test
    void corruptChunkPolicies() throws Exception {
        final Path dir = world.resolve("custom");
        try (PumpChunkLoader seed = PumpChunkLoader.forRegionDirectory(dir)) {
            final InstanceContainer instance = newInstance(seed);
            seed.saveChunk(instance.loadChunk(0, 0).join());
        }
        final Path file = dir.resolve("r.0.0.pump");
        final byte[] bytes = Files.readAllBytes(file);
        bytes[PumpFormat.HEADER_BYTES + 30] ^= 0x7F;
        Files.write(file, bytes);

        try (PumpChunkLoader regenerate = PumpChunkLoader.forRegionDirectory(dir)) {
            final InstanceContainer instance =
                    MinecraftServer.getInstanceManager()
                            .createInstanceContainer(DimensionType.OVERWORLD, regenerate);
            assertNull(regenerate.loadChunk(instance, 0, 0));
        }
        try (PumpChunkLoader fail =
                PumpChunkLoader.forRegionDirectory(
                        dir,
                        PumpLoaderOptions.defaults()
                                .withCorruptChunkPolicy(CorruptChunkPolicy.FAIL))) {
            final InstanceContainer instance =
                    MinecraftServer.getInstanceManager()
                            .createInstanceContainer(DimensionType.OVERWORLD, fail);
            assertThrows(UncheckedIOException.class, () -> fail.loadChunk(instance, 0, 0));
        }
    }

    @Test
    void unknownBlockStatesAreCorruption() throws Exception {
        final Path dir = world.resolve("custom");
        try (PumpChunkLoader seed = PumpChunkLoader.forRegionDirectory(dir)) {
            final InstanceContainer instance = newInstance(seed);
            final PumpChunk chunk = MinestomChunkMapper.toPump(instance.loadChunk(0, 0).join());
            final var sections = new ArrayList<>(chunk.sections());
            sections.set(
                    5,
                    new net.pyrocube.core.pump.chunk.PumpSection(
                            net.pyrocube.core.pump.chunk.BlockStates.uniform(65_000),
                            sections.get(5).biomes()));
            seed.storage().write(chunk.toBuilder().sections(sections).build());
            assertNull(seed.loadChunk(instance, 0, 0), "reported and regenerated");
        }
    }

    @Test
    void hooksSeeEverythingTheFileHolds() throws Exception {
        final List<PumpChunk> seen = new ArrayList<>();
        final var hooks =
                new PumpChunkHooks() {
                    @Override
                    public void afterLoad(Chunk chunk, PumpChunk data) {
                        seen.add(data);
                    }

                    @Override
                    public PumpChunk beforeSave(Chunk chunk, PumpChunk data) {
                        return data.toBuilder()
                                .blockTick(
                                        new net.pyrocube.core.pump.chunk.ScheduledTick(
                                                1, 2, 3, 4, 0, 5))
                                .build();
                    }
                };
        final PumpLoaderOptions options = PumpLoaderOptions.defaults().withHooks(hooks);
        try (PumpChunkLoader first = PumpChunkLoader.forRegionDirectory(world, options)) {
            final InstanceContainer instance = newInstance(first);
            first.saveChunk(instance.loadChunk(0, 0).join());
        }
        try (PumpChunkLoader second = PumpChunkLoader.forRegionDirectory(world, options)) {
            final InstanceContainer instance =
                    MinecraftServer.getInstanceManager()
                            .createInstanceContainer(DimensionType.OVERWORLD, second);
            instance.loadChunk(0, 0).join();
        }
        assertEquals(1, seen.size());
        assertEquals(1, seen.get(0).blockTicks().size());
    }

    @Test
    void bulkSaveUsesOneCommitPerRegion() throws Exception {
        try (PumpChunkLoader loader = loader(world)) {
            final InstanceContainer instance = newInstance(loader);
            for (int x = 0; x < 8; x++) {
                for (int z = 0; z < 8; z++) instance.loadChunk(x, z).join();
            }
            final long before = loader.stats().commits();
            instance.saveChunksToStorage().join();
            assertEquals(1, loader.stats().commits() - before, "64 chunks of one region");
            assertEquals(64, loader.stats().chunksWritten());
        }
    }

    @Test
    void blockHandlersArePreserved() throws Exception {
        final BlockHandler handler =
                new BlockHandler() {
                    @Override
                    public net.kyori.adventure.key.Key getKey() {
                        return net.kyori.adventure.key.Key.key("pyrocube", "machine");
                    }
                };
        MinecraftServer.getBlockManager().registerHandler(handler.getKey(), () -> handler);
        try (PumpChunkLoader first = loader(world)) {
            final InstanceContainer instance = newInstance(first);
            instance.loadChunk(0, 0).join();
            instance.setBlock(
                    1,
                    70,
                    1,
                    Block.FURNACE
                            .withHandler(handler)
                            .withNbt(CompoundBinaryTag.builder().putInt("fuel", 7).build()));
            first.saveChunk(instance.getChunk(0, 0));
        }
        try (PumpChunkLoader second = loader(world)) {
            final InstanceContainer instance =
                    MinecraftServer.getInstanceManager()
                            .createInstanceContainer(DimensionType.OVERWORLD, second);
            instance.loadChunk(0, 0).join();
            final Block furnace = instance.getBlock(1, 70, 1);
            assertEquals(Block.FURNACE.id(), furnace.id());
            assertEquals("pyrocube:machine", furnace.handler().getKey().asString());
            assertEquals(7, furnace.nbt().getInt("fuel"));
        }
    }
}
