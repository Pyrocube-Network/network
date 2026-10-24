package net.pyrocube.core.pump.chunk;

import static net.pyrocube.core.pump.PumpFormat.HEIGHTMAP_WORDS;
import static net.pyrocube.core.pump.PumpFormat.LIGHT_BYTES;
import static net.pyrocube.core.pump.PumpFormat.SECTION_BIOMES;
import static net.pyrocube.core.pump.PumpFormat.SECTION_BLOCKS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpException;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.PumpLimitException;
import net.pyrocube.core.pump.io.ByteReader;
import net.pyrocube.core.pump.io.ByteWriter;
import net.pyrocube.core.pump.io.NbtIO;

/**
 * Serializer and parser of the uncompressed {@code PCHK} v1 payload (chapter 5).
 *
 * <p>The codec is stateless and thread-safe. The reader enforces every Valid Usage clause and the
 * resource bounds of chapter 7 and rejects trailing bytes, the writer always produces the most
 * compact of the permitted section encodings.
 */
public final class PchkCodec {
    private static final int MODE_UNIFORM = 0;
    private static final int MODE_INDEXED = 1;
    private static final int MODE_DENSE = 2;

    private static final int LIGHT_HAS_BLOCK = 0x01;
    private static final int LIGHT_HAS_SKY = 0x02;

    private static final int HASH_SLOTS = 1024;

    private PchkCodec() {}

    // ---------------------------------------------------------------------------------------
    // Encoding
    // ---------------------------------------------------------------------------------------

    /** Serializes {@code chunk} to a new array. */
    public static byte[] encode(PumpChunk chunk) throws IOException {
        final ByteWriter out = new ByteWriter(estimateSize(chunk));
        encode(chunk, out);
        return out.toByteArray();
    }

    /** Serializes {@code chunk} into {@code out}. */
    public static void encode(PumpChunk chunk, ByteWriter out) throws IOException {
        out.u8(PumpFormat.chunkMagicByte(0))
                .u8(PumpFormat.chunkMagicByte(1))
                .u8(PumpFormat.chunkMagicByte(2))
                .u8(PumpFormat.chunkMagicByte(3))
                .u8(PumpFormat.CHUNK_VERSION)
                .i32(chunk.dataVersion())
                .i32(chunk.x())
                .i32(chunk.z())
                .i32(chunk.minSectionY())
                .u8(chunk.status().id())
                .u8(chunk.flags())
                .i64(chunk.inhabitedTime());

        writeHeightmap(out, chunk.worldSurface());
        writeHeightmap(out, chunk.motionBlocking());
        writeHeightmap(out, chunk.motionBlockingNoLeaves());

        out.u16(chunk.sections().size());
        final SectionScratch scratch = new SectionScratch();
        for (PumpSection section : chunk.sections()) {
            writeBlocks(out, section.blocks(), scratch);
            writeBiomes(out, section.biomes(), scratch);
            writeLight(out, section);
        }

        writeTicks(out, chunk.blockTicks());
        writeTicks(out, chunk.fluidTicks());

        out.i32(chunk.blockEntities().size());
        for (CompoundBinaryTag be : chunk.blockEntities()) {
            writeNbt(out, be);
        }
        if (chunk.customData() != null) {
            writeNbt(out, chunk.customData());
        }
    }

    private static int estimateSize(PumpChunk chunk) {
        return 256 + chunk.sections().size() * 1024 + chunk.blockEntities().size() * 128;
    }

    private static void writeHeightmap(ByteWriter out, short[] map) {
        if (map == null) return;
        for (long word : Heightmaps.pack(map)) out.i64(word);
    }

    private static void writeNbt(ByteWriter out, CompoundBinaryTag tag) throws IOException {
        final int lengthAt = out.size();
        out.i32(0);
        NbtIO.write(tag, out);
        out.patchI32(lengthAt, out.size() - lengthAt - 4);
    }

    private static void writeTicks(ByteWriter out, List<ScheduledTick> ticks) {
        out.i32(ticks.size());
        for (ScheduledTick t : ticks) {
            out.i32(t.x()).i32(t.y()).i32(t.z()).u8(t.delay()).u8(t.priority()).u16(t.targetId());
        }
    }

    private static void writeLight(ByteWriter out, PumpSection section) {
        int flags = 0;
        if (section.blockLight() != null) flags |= LIGHT_HAS_BLOCK;
        if (section.skyLight() != null) flags |= LIGHT_HAS_SKY;
        out.u8(flags);
        if (section.blockLight() != null) out.bytes(section.blockLight());
        if (section.skyLight() != null) out.bytes(section.skyLight());
    }

    /** Reusable buffers for choosing section encodings, one per encoded chunk. */
    private static final class SectionScratch {
        final int[] slotKey = new int[HASH_SLOTS];
        final byte[] slotIndex = new byte[HASH_SLOTS];
        final char[] palette = new char[256];
        final int[] counts = new int[256];
        final byte[] indices = new byte[SECTION_BLOCKS];
        final byte[] biomeSlot = new byte[256];
        final int[] biomeIds = new int[SECTION_BIOMES];
        final int[] biomeCounts = new int[SECTION_BIOMES];
    }

    private static void writeBlocks(ByteWriter out, BlockStates blocks, SectionScratch s) {
        if (blocks.isUniform()) {
            out.u8(MODE_UNIFORM).u16(blocks.uniformState());
            return;
        }
        final char[] states = blocks.raw();
        final int[] slotKey = s.slotKey;
        final byte[] slotIndex = s.slotIndex;
        final int[] counts = s.counts;
        Arrays.fill(slotKey, 0);
        Arrays.fill(counts, 0);
        int size = 0;
        boolean fits = true;
        for (int i = 0; i < SECTION_BLOCKS; i++) {
            final int state = states[i];
            final int key = state + 1;
            int slot = (state * 0x9E3779B1) >>> 22;
            while (true) {
                final int existing = slotKey[slot];
                if (existing == key) {
                    final int index = slotIndex[slot] & 0xFF;
                    s.indices[i] = (byte) index;
                    counts[index]++;
                    break;
                }
                if (existing == 0) {
                    if (size == 256) {
                        fits = false;
                    } else {
                        slotKey[slot] = key;
                        slotIndex[slot] = (byte) size;
                        s.palette[size] = (char) state;
                        s.indices[i] = (byte) size;
                        counts[size] = 1;
                        size++;
                    }
                    break;
                }
                slot = (slot + 1) & (HASH_SLOTS - 1);
            }
            if (!fits) break;
        }

        if (!fits) {
            out.u8(MODE_DENSE).u16s(states);
            return;
        }
        out.u8(MODE_INDEXED).u16(size);
        for (int i = 0; i < size; i++) {
            out.u16(s.palette[i]).u16(counts[i]);
        }
        out.bytes(s.indices, 0, SECTION_BLOCKS);
    }

    private static void writeBiomes(ByteWriter out, Biomes biomes, SectionScratch s) {
        if (biomes.isUniform()) {
            out.u8(MODE_UNIFORM).u8(biomes.uniformBiome());
            return;
        }
        final byte[] cells = biomes.raw();
        Arrays.fill(s.biomeSlot, (byte) 0);
        int size = 0;
        for (int i = 0; i < SECTION_BIOMES; i++) {
            final int id = cells[i] & 0xFF;
            int slot = s.biomeSlot[id] & 0xFF;
            if (slot == 0) {
                s.biomeIds[size] = id;
                s.biomeCounts[size] = 0;
                slot = ++size;
                s.biomeSlot[id] = (byte) slot;
            }
            s.biomeCounts[slot - 1]++;
        }
        out.u8(MODE_INDEXED).u8(size);
        for (int i = 0; i < size; i++) {
            out.u8(s.biomeIds[i]).u16(s.biomeCounts[i]);
        }
        out.bytes(cells);
    }

    // ---------------------------------------------------------------------------------------
    // Decoding
    // ---------------------------------------------------------------------------------------

    /** Parses a complete payload without checking its position. */
    public static PumpChunk decode(byte[] payload) throws PumpException {
        return decode(payload, false, 0, 0);
    }

    /**
     * Parses a complete payload and verifies that its header coordinates equal the position the
     * region table says it was stored at (§5.1).
     */
    public static PumpChunk decode(byte[] payload, int expectedX, int expectedZ)
            throws PumpException {
        return decode(payload, true, expectedX, expectedZ);
    }

    private static PumpChunk decode(byte[] payload, boolean checkPosition, int ex, int ez)
            throws PumpException {
        final ByteReader in = new ByteReader(payload);
        if (in.remaining() < PumpFormat.CHUNK_HEADER_BYTES) {
            throw new PumpCorruptException("Chunk payload shorter than its header");
        }
        for (int i = 0; i < 4; i++) {
            if (in.u8() != (PumpFormat.chunkMagicByte(i) & 0xFF)) {
                throw new PumpCorruptException("Bad chunk magic, expected 'PCHK'");
            }
        }
        final int version = in.u8();
        if (version != PumpFormat.CHUNK_VERSION) {
            throw new PumpCorruptException("Unsupported PCHK version " + version);
        }
        final int dataVersion = in.i32();
        final int x = in.i32();
        final int z = in.i32();
        if (checkPosition && (x != ex || z != ez)) {
            throw new PumpCorruptException(
                    "Chunk header position ("
                            + x
                            + ", "
                            + z
                            + ") does not match slot ("
                            + ex
                            + ", "
                            + ez
                            + ")");
        }
        final int minSectionY = in.i32();
        final ChunkStatus status = ChunkStatus.fromId(in.u8());
        final int flags = in.u8();
        if ((flags & ChunkFlags.RESERVED) != 0) {
            throw new PumpCorruptException(
                    "Reserved chunk flag bits set: 0x" + Integer.toHexString(flags));
        }
        final long inhabitedTime = in.i64();

        final short[] worldSurface = readHeightmap(in, flags, ChunkFlags.HAS_WORLD_SURFACE);
        final short[] motionBlocking = readHeightmap(in, flags, ChunkFlags.HAS_MOTION_BLOCKING);
        final short[] noLeaves = readHeightmap(in, flags, ChunkFlags.HAS_MOTION_BLOCKING_NO_LEAVES);

        final int sectionCount = in.u16();
        if (sectionCount > PumpFormat.MAX_SECTIONS) {
            throw new PumpLimitException(
                    "PUMP_MAX_SECTIONS", sectionCount, PumpFormat.MAX_SECTIONS);
        }
        final List<PumpSection> sections = new ArrayList<>(sectionCount);
        final byte[] indexScratch = new byte[SECTION_BLOCKS];
        for (int i = 0; i < sectionCount; i++) {
            final BlockStates blocks = readBlocks(in, indexScratch, i);
            final Biomes biomes = readBiomes(in, i);
            final int lightFlags = in.u8();
            if ((lightFlags & ~(LIGHT_HAS_BLOCK | LIGHT_HAS_SKY)) != 0) {
                throw new PumpCorruptException(
                        "Section "
                                + i
                                + " has unknown light flags 0x"
                                + Integer.toHexString(lightFlags));
            }
            final byte[] blockLight =
                    (lightFlags & LIGHT_HAS_BLOCK) != 0 ? in.bytes(LIGHT_BYTES) : null;
            final byte[] skyLight =
                    (lightFlags & LIGHT_HAS_SKY) != 0 ? in.bytes(LIGHT_BYTES) : null;
            sections.add(new PumpSection(blocks, biomes, blockLight, skyLight));
        }

        final List<ScheduledTick> blockTicks = readTicks(in, "block");
        final List<ScheduledTick> fluidTicks = readTicks(in, "fluid");

        final long blockEntityCount = in.u32();
        if (blockEntityCount > PumpFormat.MAX_BLOCK_ENTITIES) {
            throw new PumpLimitException(
                    "PUMP_MAX_BLOCK_ENTITIES", blockEntityCount, PumpFormat.MAX_BLOCK_ENTITIES);
        }
        final List<CompoundBinaryTag> blockEntities = new ArrayList<>((int) blockEntityCount);
        for (int i = 0; i < blockEntityCount; i++) {
            final CompoundBinaryTag be = readNbt(in, "block entity " + i);
            final String problem = BlockEntities.problem(be);
            if (problem != null) throw new PumpCorruptException(problem + " (entry " + i + ")");
            blockEntities.add(be);
        }

        CompoundBinaryTag customData = null;
        if ((flags & ChunkFlags.HAS_CUSTOM_DATA) != 0) {
            customData = readNbt(in, "custom data");
        }
        if (in.remaining() != 0) {
            throw new PumpCorruptException(
                    "Chunk payload has " + in.remaining() + " unexpected trailing bytes");
        }

        return new PumpChunk(
                dataVersion,
                x,
                z,
                minSectionY,
                status,
                inhabitedTime,
                (flags & ChunkFlags.LIGHT_CORRECT) != 0,
                worldSurface,
                motionBlocking,
                noLeaves,
                sections,
                blockTicks,
                fluidTicks,
                blockEntities,
                customData);
    }

    private static short[] readHeightmap(ByteReader in, int flags, int mask)
            throws PumpCorruptException {
        if ((flags & mask) == 0) return null;
        final long[] words = new long[HEIGHTMAP_WORDS];
        for (int i = 0; i < words.length; i++) words[i] = in.i64();
        return Heightmaps.unpack(words);
    }

    private static BlockStates readBlocks(ByteReader in, byte[] indexScratch, int section)
            throws PumpCorruptException {
        final int mode = in.u8();
        switch (mode) {
            case MODE_UNIFORM:
                return BlockStates.uniform(in.u16());
            case MODE_INDEXED:
                {
                    final int paletteLen = in.u16();
                    if (paletteLen < 1 || paletteLen > 256) {
                        throw new PumpCorruptException(
                                "Section "
                                        + section
                                        + " block palette length "
                                        + paletteLen
                                        + " outside 1..256");
                    }
                    final char[] palette = new char[paletteLen];
                    int total = 0;
                    for (int i = 0; i < paletteLen; i++) {
                        palette[i] = (char) in.u16();
                        total += in.u16();
                    }
                    if (total != SECTION_BLOCKS) {
                        throw new PumpCorruptException(
                                "Section "
                                        + section
                                        + " block counts sum to "
                                        + total
                                        + ", expected 4096");
                    }
                    in.bytes(indexScratch);
                    final char[] states = new char[SECTION_BLOCKS];
                    for (int i = 0; i < SECTION_BLOCKS; i++) {
                        final int index = indexScratch[i] & 0xFF;
                        if (index >= paletteLen) {
                            throw new PumpCorruptException(
                                    "Section "
                                            + section
                                            + " block index "
                                            + index
                                            + " is not below palette length "
                                            + paletteLen);
                        }
                        states[i] = palette[index];
                    }
                    return BlockStates.of(states);
                }
            case MODE_DENSE:
                {
                    final char[] states = new char[SECTION_BLOCKS];
                    in.u16s(states);
                    return BlockStates.of(states);
                }
            default:
                throw new PumpCorruptException(
                        "Section " + section + " has unknown block mode " + mode);
        }
    }

    private static Biomes readBiomes(ByteReader in, int section) throws PumpCorruptException {
        final int mode = in.u8();
        switch (mode) {
            case MODE_UNIFORM:
                return Biomes.uniform(in.u8());
            case MODE_INDEXED:
                {
                    final int paletteLen = in.u8();
                    if (paletteLen < 1 || paletteLen > SECTION_BIOMES) {
                        throw new PumpCorruptException(
                                "Section "
                                        + section
                                        + " biome palette length "
                                        + paletteLen
                                        + " outside 1..64");
                    }
                    // Entries are (id, count) summaries, the 64 cells that follow hold direct ids.
                    in.skip(paletteLen * 3);
                    return Biomes.of(in.bytes(SECTION_BIOMES));
                }
            default:
                throw new PumpCorruptException(
                        "Section " + section + " has unknown biome mode " + mode);
        }
    }

    private static List<ScheduledTick> readTicks(ByteReader in, String kind)
            throws PumpCorruptException {
        final long count = in.u32();
        if (count > PumpFormat.MAX_TICKS) {
            throw new PumpLimitException(
                    "PUMP_MAX_TICKS (" + kind + ")", count, PumpFormat.MAX_TICKS);
        }
        if (count * ScheduledTick.BYTES > in.remaining()) {
            throw new PumpCorruptException("Truncated " + kind + " tick array");
        }
        final List<ScheduledTick> ticks = new ArrayList<>((int) count);
        for (int i = 0; i < count; i++) {
            final int x = in.i32();
            final int y = in.i32();
            final int z = in.i32();
            final int delay = in.u8();
            final int priority = in.i8();
            final int target = in.u16();
            if (priority < -3 || priority > 3) {
                throw new PumpCorruptException(
                        kind + " tick " + i + " has priority " + priority + " outside -3..3");
            }
            ticks.add(new ScheduledTick(x, y, z, delay, priority, target));
        }
        return ticks;
    }

    private static CompoundBinaryTag readNbt(ByteReader in, String what)
            throws PumpCorruptException {
        final long length = in.u32();
        if (length > in.remaining()) {
            throw new PumpCorruptException(
                    "Truncated NBT for "
                            + what
                            + ": declared "
                            + length
                            + ", "
                            + in.remaining()
                            + " left");
        }
        final CompoundBinaryTag tag = NbtIO.read(in.array(), in.position(), (int) length);
        in.skip((int) length);
        return tag;
    }
}
