package net.pyrocube.core.pump.anvil;

import java.util.List;
import java.util.Random;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.block.Block;
import net.pyrocube.core.pump.TestChunks;
import net.pyrocube.core.pump.chunk.Biomes;
import net.pyrocube.core.pump.chunk.BlockStates;
import net.pyrocube.core.pump.chunk.ChunkStatus;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.chunk.PumpSection;
import net.pyrocube.core.pump.chunk.ScheduledTick;

/** Chunks whose ids are valid in the running Minestom registries, so they survive Anvil. */
final class AnvilFixtures {
    static final BiomeIds BIOMES =
            BiomeIds.ofList(
                    List.of(
                            "minecraft:the_void",
                            "minecraft:plains",
                            "minecraft:desert",
                            "minecraft:forest",
                            "minecraft:taiga",
                            "minecraft:swamp"),
                    "minecraft:plains");

    private AnvilFixtures() {}

    static PumpChunk validChunk(int x, int z, long seed) {
        final Random r = new Random(seed * 31 + x * 7 + z);
        final int states = Block.statesCount();
        final PumpChunk.Builder b =
                PumpChunk.builder(x, z)
                        .dataVersion(TestChunks.DATA_VERSION)
                        .minSectionY(-4)
                        .status(ChunkStatus.FULL)
                        .inhabitedTime(r.nextInt(1000))
                        .lightCorrect(true)
                        .worldSurface(TestChunks.heights(r, 70))
                        .motionBlocking(TestChunks.heights(r, 68))
                        .motionBlockingNoLeaves(TestChunks.heights(r, 66));
        for (int s = 0; s < 12; s++) {
            final BlockStates blocks;
            switch (s % 4) {
                case 0 -> blocks = BlockStates.uniform(r.nextInt(states));
                case 1 -> {
                    final char[] c = new char[4096];
                    final int[] palette = {r.nextInt(states), r.nextInt(states), r.nextInt(states)};
                    for (int i = 0; i < 4096; i++) c[i] = (char) palette[r.nextInt(3)];
                    blocks = BlockStates.of(c);
                }
                case 2 -> {
                    final char[] c = new char[4096];
                    for (int i = 0; i < 4096; i++) c[i] = (char) (r.nextInt(states));
                    blocks = BlockStates.of(c);
                }
                default -> {
                    final char[] c = new char[4096];
                    for (int i = 0; i < 4096; i++) c[i] = (char) (i % 20 == 0 ? 1 + r.nextInt(50) : 0);
                    blocks = BlockStates.of(c);
                }
            }
            final Biomes biomes =
                    s % 3 == 0
                            ? Biomes.uniform(1 + r.nextInt(5))
                            : Biomes.of(randomBiomes(r));
            b.section(
                    new PumpSection(
                            blocks,
                            biomes,
                            s % 2 == 0 ? TestChunks.light(r, 0.3) : null,
                            s % 5 != 4 ? TestChunks.light(r, 0.8) : null));
        }
        for (int i = 0; i < 10; i++) {
            b.blockTick(
                    new ScheduledTick(
                            x * 16 + i,
                            60 + i,
                            z * 16,
                            r.nextInt(256),
                            r.nextInt(7) - 3,
                            1 + r.nextInt(100)));
            b.fluidTick(
                    new ScheduledTick(
                            x * 16 + i, 50 + i, z * 16 + 1, r.nextInt(256), r.nextInt(7) - 3, r.nextInt(5)));
        }
        b.blockEntity(
                CompoundBinaryTag.builder()
                        .putString("id", "minecraft:chest")
                        .putInt("x", x * 16 + 1)
                        .putInt("y", 64)
                        .putInt("z", z * 16 + 2)
                        .putString("note", "hello")
                        .build());
        b.customData(
                CompoundBinaryTag.builder()
                        .putString("plugin", "pyrocube")
                        .put("Heightmaps", CompoundBinaryTag.builder().putLongArray("OCEAN_FLOOR", new long[] {1, 2, 3}).build())
                        .putLong("LastUpdate", 777L)
                        .build());
        return b.build();
    }

    private static byte[] randomBiomes(Random r) {
        final byte[] c = new byte[64];
        for (int i = 0; i < 64; i++) c[i] = (byte) (1 + r.nextInt(5));
        return c;
    }
}
