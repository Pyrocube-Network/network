package net.pyrocube.core.pump.perf;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.block.Block;
import net.pyrocube.core.pump.TestChunks;
import net.pyrocube.core.pump.chunk.Biomes;
import net.pyrocube.core.pump.chunk.BlockStates;
import net.pyrocube.core.pump.chunk.ChunkStatus;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.chunk.PumpSection;

/**
 * Deterministic overworld-like chunks: rolling hills, a sea, layered stone and deepslate, caves, ores
 * and sky light. Realistic enough that section modes and compression ratios resemble real worlds.
 */
final class SyntheticTerrain {
    static final int MIN_Y = -64;
    static final int SECTIONS = 24;

    private static final int BEDROCK = Block.BEDROCK.stateId();
    private static final int STONE = Block.STONE.stateId();
    private static final int DEEPSLATE = Block.DEEPSLATE.stateId();
    private static final int DIRT = Block.DIRT.stateId();
    private static final int GRASS = Block.GRASS_BLOCK.stateId();
    private static final int SAND = Block.SAND.stateId();
    private static final int WATER = Block.WATER.stateId();
    private static final int SHORT_GRASS = Block.SHORT_GRASS.stateId();
    private static final int POPPY = Block.POPPY.stateId();
    private static final int DANDELION = Block.DANDELION.stateId();
    private static final int OAK_LOG = Block.OAK_LOG.stateId();
    private static final int OAK_LEAVES = Block.OAK_LEAVES.stateId();
    private static final int[] POCKETS = {
        Block.GRAVEL.stateId(),
        Block.ANDESITE.stateId(),
        Block.DIORITE.stateId(),
        Block.GRANITE.stateId()
    };
    private static final int[] ORES = {
        Block.COAL_ORE.stateId(),
        Block.IRON_ORE.stateId(),
        Block.GOLD_ORE.stateId(),
        Block.DIAMOND_ORE.stateId(),
        Block.COPPER_ORE.stateId(),
        Block.LAPIS_ORE.stateId(),
        Block.REDSTONE_ORE.stateId(),
        Block.EMERALD_ORE.stateId()
    };

    private SyntheticTerrain() {}

    static int surface(int wx, int wz) {
        return (int)
                (64
                        + 22 * Math.sin(wx / 41.0)
                        + 14 * Math.cos(wz / 27.0)
                        + 6 * Math.sin((wx + wz) / 9.0)
                        + 3 * Math.cos((wx - wz) / 5.0));
    }

    static PumpChunk chunk(int cx, int cz) {
        final int[] height = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) height[z * 16 + x] = surface(cx * 16 + x, cz * 16 + z);
        }

        // Generate every section first: heightmaps and sky light depend on the highest block.
        final char[][] sections = new char[SECTIONS][4096];
        final int[] top = new int[256];
        java.util.Arrays.fill(top, MIN_Y);
        for (int s = 0; s < SECTIONS; s++) {
            final int baseY = MIN_Y + s * 16;
            for (int y = 0; y < 16; y++) {
                final int wy = baseY + y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int column = z * 16 + x;
                        final int state = blockAt(cx * 16 + x, wy, cz * 16 + z, height[column]);
                        sections[s][(y << 8) | column] = (char) state;
                        if (state != 0) top[column] = Math.max(top[column], wy);
                    }
                }
            }
        }

        final short[] heightmap = new short[256];
        for (int i = 0; i < 256; i++) heightmap[i] = (short) Math.min(511, top[i] + 1 - MIN_Y);

        final PumpChunk.Builder b =
                PumpChunk.builder(cx, cz)
                        .dataVersion(TestChunks.DATA_VERSION)
                        .minSectionY(MIN_Y / 16)
                        .status(ChunkStatus.FULL)
                        .inhabitedTime(Math.abs(cx * 31L + cz) * 20)
                        .lightCorrect(true)
                        .worldSurface(heightmap)
                        .motionBlocking(heightmap)
                        .motionBlockingNoLeaves(heightmap);

        for (int s = 0; s < SECTIONS; s++) {
            final int baseY = MIN_Y + s * 16;
            final byte[] sky = new byte[2048];
            for (int y = 0; y < 16; y++) {
                for (int column = 0; column < 256; column++) {
                    if (baseY + y > top[column]) {
                        final int index = (y << 8) | column;
                        sky[index >> 1] |= (byte) ((index & 1) == 0 ? 0x0F : 0xF0);
                    }
                }
            }
            final int biome = 1 + (Math.floorDiv(cx, 4) + Math.floorDiv(cz, 4) & 3) % 3;
            final byte[] cells = new byte[64];
            for (int i = 0; i < 64; i++) {
                cells[i] = (byte) (baseY < 0 && (i & 3) == 0 ? biome + 1 : biome);
            }
            b.section(
                    new PumpSection(
                            BlockStates.of(sections[s]),
                            Biomes.of(cells),
                            null,
                            isAllZero(sky) ? null : sky));
        }

        b.blockEntity(
                CompoundBinaryTag.builder()
                        .putString("id", "minecraft:chest")
                        .putInt("x", cx * 16)
                        .putInt("y", top[0] + 1)
                        .putInt("z", cz * 16)
                        .putString("LootTable", "minecraft:chests/simple_dungeon")
                        .build());
        return b.build();
    }

    private static int hash(int a, int b, int c) {
        int h = a * 73856093 ^ b * 19349663 ^ c * 83492791;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h & 0x7FFFFFFF;
    }

    /** Height of the trunk of a tree rooted in column (wx, wz), or 0 if none grows there. */
    private static int treeHeight(int wx, int wz) {
        if (hash(wx, 0, wz) % 90 != 0) return 0;
        return surface(wx, wz) > 63 ? 5 + hash(wx, 1, wz) % 3 : 0;
    }

    private static int blockAt(int wx, int wy, int wz, int h) {
        if (wy == MIN_Y) return BEDROCK;
        if (wy > h) {
            if (wy <= 62) return WATER;
            if (h <= 63) return 0;
            // vegetation and trees on dry land
            if (wy == h + 1) {
                final int r = hash(wx, wy, wz) % 40;
                if (r < 12) return SHORT_GRASS;
                if (r == 12) return POPPY;
                if (r == 13) return DANDELION;
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    final int th = treeHeight(wx + dx, wz + dz);
                    if (th == 0) continue;
                    final int base = surface(wx + dx, wz + dz);
                    if (dx == 0 && dz == 0 && wy > base && wy <= base + th) return OAK_LOG;
                    final int crown = base + th;
                    if (wy >= crown - 1 && wy <= crown + 1 && Math.abs(dx) + Math.abs(dz) < 4) {
                        return OAK_LEAVES;
                    }
                }
            }
            return 0;
        }
        if (wy == h) return h <= 63 ? SAND : GRASS;
        if (wy >= h - 3) return h <= 63 ? SAND : DIRT;
        // caves
        if (wy < h - 6 && Math.sin(wx / 9.0) * Math.sin(wy / 7.0) * Math.sin(wz / 11.0) > 0.93) {
            return 0;
        }
        final int pocket = hash(wx >> 2, wy >> 2, wz >> 2) % 31;
        if (pocket < 4) return POCKETS[pocket];
        final int ore = hash(wx, wy, wz) % 70;
        if (wy < h - 8 && ore < ORES.length) return ORES[ore];
        return wy < 0 ? DEEPSLATE : STONE;
    }

    private static byte[] fullBright() {
        final byte[] light = new byte[2048];
        java.util.Arrays.fill(light, (byte) 0xFF);
        return light;
    }

    private static boolean isAllZero(byte[] a) {
        for (byte v : a) if (v != 0) return false;
        return true;
    }
}
