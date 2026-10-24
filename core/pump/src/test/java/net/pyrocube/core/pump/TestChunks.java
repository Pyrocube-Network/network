package net.pyrocube.core.pump;

import java.util.Random;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.pyrocube.core.pump.chunk.Biomes;
import net.pyrocube.core.pump.chunk.BlockStates;
import net.pyrocube.core.pump.chunk.ChunkStatus;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.chunk.PumpSection;
import net.pyrocube.core.pump.chunk.ScheduledTick;

/** Deterministic generators of chunks with realistic and adversarial content. */
public final class TestChunks {
    public static final int DATA_VERSION = 4000;

    private TestChunks() {}

    /** A chunk that looks like terrain: stone below, a few layers of dirt, air above. */
    public static PumpChunk terrain(int x, int z, long seed, int sections) {
        final Random r = new Random(seed ^ ((long) x << 32) ^ z);
        final PumpChunk.Builder b =
                PumpChunk.builder(x, z)
                        .dataVersion(DATA_VERSION)
                        .minSectionY(-4)
                        .status(ChunkStatus.FULL)
                        .inhabitedTime(r.nextInt(100_000))
                        .lightCorrect(true);
        final int surfaceSection = sections / 3;
        for (int s = 0; s < sections; s++) {
            final PumpSection section;
            final Biomes biomes =
                    s % 5 == 0 ? Biomes.of(mixedBiomes(r)) : Biomes.uniform(1 + (s % 3));
            if (s < surfaceSection) {
                section =
                        new PumpSection(
                                BlockStates.uniform(1), biomes, light(r, 0.1), light(r, 0.0));
            } else if (s == surfaceSection) {
                section =
                        new PumpSection(
                                BlockStates.of(surfaceStates(r)), biomes, light(r, 0.3), light(r, 0.9));
            } else {
                section =
                        new PumpSection(
                                BlockStates.uniform(0), biomes, null, fullSky());
            }
            b.section(section);
        }
        b.worldSurface(heights(r, surfaceSection * 16 + 8));
        b.motionBlocking(heights(r, surfaceSection * 16 + 8));
        return b.build();
    }

    /** A chunk exercising every optional feature of the format. */
    public static PumpChunk everything(int x, int z, long seed) {
        final Random r = new Random(seed);
        final PumpChunk.Builder b = terrain(x, z, seed, 12).toBuilder();
        b.motionBlockingNoLeaves(heights(r, 70));
        b.sections(
                java.util.stream.Stream.concat(
                                terrain(x, z, seed, 12).sections().stream(),
                                java.util.stream.Stream.of(
                                        new PumpSection(
                                                BlockStates.of(denseStates(r)),
                                                Biomes.of(mixedBiomes(r)),
                                                light(r, 1.0),
                                                light(r, 1.0))))
                        .toList());
        for (int i = 0; i < 20; i++) {
            b.blockTick(
                    new ScheduledTick(
                            x * 16 + r.nextInt(16),
                            r.nextInt(300) - 64,
                            z * 16 + r.nextInt(16),
                            r.nextInt(256),
                            r.nextInt(7) - 3,
                            r.nextInt(65536)));
            b.fluidTick(
                    new ScheduledTick(
                            x * 16 + r.nextInt(16),
                            r.nextInt(300) - 64,
                            z * 16 + r.nextInt(16),
                            r.nextInt(256),
                            r.nextInt(7) - 3,
                            r.nextInt(5)));
        }
        for (int i = 0; i < 5; i++) {
            b.blockEntity(
                    CompoundBinaryTag.builder()
                            .putString("id", "minecraft:chest")
                            .putInt("x", x * 16 + i)
                            .putInt("y", 64 + i)
                            .putInt("z", z * 16 + i)
                            .putString("CustomName", "chest " + i)
                            .putByteArray("blob", new byte[] {1, 2, 3, (byte) i})
                            .build());
        }
        b.customData(
                CompoundBinaryTag.builder()
                        .putString("plugin", "pyrocube")
                        .putLong("stamp", seed)
                        .put(
                                "nested",
                                CompoundBinaryTag.builder().putIntArray("v", new int[] {1, 2}).build())
                        .build());
        return b.build();
    }

    public static short[] heights(Random r, int base) {
        final short[] h = new short[256];
        for (int i = 0; i < 256; i++) h[i] = (short) Math.max(0, Math.min(511, base + r.nextInt(9) - 4));
        return h;
    }

    public static char[] surfaceStates(Random r) {
        final char[] s = new char[4096];
        for (int i = 0; i < 4096; i++) {
            final int y = i >> 8;
            s[i] = (char) (y < 8 ? 1 : y < 11 ? 10 : y == 11 ? 9 : r.nextInt(20) == 0 ? 100 + r.nextInt(8) : 0);
        }
        return s;
    }

    /** More than 256 distinct states, forcing the dense mode. */
    public static char[] denseStates(Random r) {
        final char[] s = new char[4096];
        for (int i = 0; i < 4096; i++) s[i] = (char) (i % 1000 + 1);
        for (int i = 0; i < 100; i++) s[r.nextInt(4096)] = (char) r.nextInt(30_000);
        return s;
    }

    public static byte[] mixedBiomes(Random r) {
        final byte[] b = new byte[64];
        for (int i = 0; i < 64; i++) b[i] = (byte) (i < 32 ? 1 : 2 + r.nextInt(3));
        return b;
    }

    public static byte[] light(Random r, double density) {
        final byte[] l = new byte[2048];
        for (int i = 0; i < l.length; i++) {
            if (r.nextDouble() < density) l[i] = (byte) r.nextInt(256);
        }
        return l;
    }

    public static byte[] fullSky() {
        final byte[] l = new byte[2048];
        java.util.Arrays.fill(l, (byte) 0xFF);
        return l;
    }
}
