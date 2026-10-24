package net.pyrocube.core.pump.chunk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;
import java.util.stream.IntStream;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpException;
import net.pyrocube.core.pump.PumpLimitException;
import net.pyrocube.core.pump.TestChunks;
import net.pyrocube.core.pump.io.ByteWriter;
import org.junit.jupiter.api.Test;

class PchkCodecTest {
    // ---- round trips -----------------------------------------------------------------------

    @Test
    void roundTripTerrain() throws Exception {
        final PumpChunk chunk = TestChunks.terrain(5, -9, 1, 24);
        assertEquals(chunk, PchkCodec.decode(PchkCodec.encode(chunk), 5, -9));
    }

    @Test
    void roundTripEveryFeature() throws Exception {
        final PumpChunk chunk = TestChunks.everything(-33, 70, 42);
        final PumpChunk back = PchkCodec.decode(PchkCodec.encode(chunk), -33, 70);
        assertEquals(chunk, back);
        assertEquals(0x1F, back.flags());
    }

    @Test
    void roundTripManyRandomChunks() throws Exception {
        for (int i = 0; i < 50; i++) {
            final PumpChunk chunk = TestChunks.everything(i, -i, i);
            assertEquals(chunk, PchkCodec.decode(PchkCodec.encode(chunk)));
        }
    }

    @Test
    void emptyChunk() throws Exception {
        final PumpChunk chunk = PumpChunk.builder(0, 0).status(ChunkStatus.EMPTY).build();
        final byte[] bytes = PchkCodec.encode(chunk);
        assertEquals(45, bytes.length);
        assertEquals(chunk, PchkCodec.decode(bytes));
    }

    @Test
    void emptyCustomDataIsPreservedAsPresent() throws Exception {
        final PumpChunk chunk =
                PumpChunk.builder(1, 1).customData(CompoundBinaryTag.empty()).build();
        final PumpChunk back = PchkCodec.decode(PchkCodec.encode(chunk));
        assertEquals(CompoundBinaryTag.empty(), back.customData());
        assertEquals(ChunkFlags.HAS_CUSTOM_DATA, back.flags());
    }

    // ---- layout ----------------------------------------------------------------------------

    @Test
    void headerLayoutMatchesSpecOffsets() throws Exception {
        final PumpChunk chunk =
                PumpChunk.builder(0x01020304, 0x05060708)
                        .dataVersion(0x11121314)
                        .minSectionY(-4)
                        .status(ChunkStatus.LIGHT)
                        .inhabitedTime(0x0102030405060708L)
                        .lightCorrect(true)
                        .build();
        final byte[] b = PchkCodec.encode(chunk);
        assertEquals("PCHK", new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(1, b[0x04]);
        assertEquals(0x11121314, readInt(b, 0x05));
        assertEquals(0x01020304, readInt(b, 0x09));
        assertEquals(0x05060708, readInt(b, 0x0D));
        assertEquals(-4, readInt(b, 0x11));
        assertEquals(7, b[0x15]);
        assertEquals(0x01, b[0x16]);
        assertEquals(0x0102030405060708L, readLong(b, 0x17));
        assertEquals(0, b[0x1F]); // section_count
        assertEquals(0, b[0x20]);
    }

    @Test
    void sectionEncodingsPickTheCompactMode() throws Exception {
        final Random r = new Random(5);
        final PumpChunk chunk =
                PumpChunk.builder(0, 0)
                        .section(new PumpSection(BlockStates.uniform(77), Biomes.uniform(9)))
                        .section(
                                new PumpSection(
                                        BlockStates.of(TestChunks.surfaceStates(r)),
                                        Biomes.of(TestChunks.mixedBiomes(r))))
                        .section(
                                new PumpSection(
                                        BlockStates.of(TestChunks.denseStates(r)),
                                        Biomes.uniform(1)))
                        .build();
        final byte[] b = PchkCodec.encode(chunk);
        int p = 31 + 2;
        // section 0: mode 0 u16 id, mode 0 u8 biome, light flags
        assertEquals(0, b[p]);
        assertEquals(77, readShort(b, p + 1));
        assertEquals(0, b[p + 3]);
        assertEquals(9, b[p + 4]);
        assertEquals(0, b[p + 5]);
        p += 6;
        // section 1: indexed blocks
        assertEquals(1, b[p]);
        final int paletteLen = readShort(b, p + 1);
        assertTrue(paletteLen > 1 && paletteLen <= 256);
        int total = 0;
        for (int i = 0; i < paletteLen; i++) total += readShort(b, p + 3 + i * 4 + 2);
        assertEquals(4096, total);
        p += 3 + paletteLen * 4 + 4096;
        // indexed biomes: palette_len(u8) entries(3 each) then 64 direct ids
        assertEquals(1, b[p]);
        final int biomeLen = b[p + 1] & 0xFF;
        p += 2 + biomeLen * 3 + 64;
        assertEquals(0, b[p]); // light flags
        p += 1;
        // section 2: dense
        assertEquals(2, b[p]);
        assertEquals(chunk.sections().get(2).blocks().get(0), readShort(b, p + 1));
    }

    @Test
    void exactly256DistinctStatesStayIndexed() throws Exception {
        final char[] states = new char[4096];
        for (int i = 0; i < 4096; i++) states[i] = (char) (i % 256);
        assertSectionMode(states, 1);
        states[4095] = 300; // 257th distinct state
        assertSectionMode(states, 2);
    }

    private static void assertSectionMode(char[] states, int expectedMode) throws Exception {
        final PumpChunk chunk =
                PumpChunk.builder(0, 0)
                        .section(new PumpSection(BlockStates.of(states), Biomes.uniform(0)))
                        .build();
        final byte[] b = PchkCodec.encode(chunk);
        assertEquals(expectedMode, b[33]);
        assertEquals(chunk, PchkCodec.decode(b));
    }

    @Test
    void nbtIsWrittenWithoutRootName() throws Exception {
        final CompoundBinaryTag be =
                CompoundBinaryTag.builder()
                        .putInt("x", 1)
                        .putInt("y", 2)
                        .putInt("z", 3)
                        .putString("id", "a:b")
                        .build();
        final byte[] b = PchkCodec.encode(PumpChunk.builder(0, 0).blockEntity(be).build());
        // header(31) + sections(2) + 2 tick counts(8) + count(4) + length(4)
        final int nbt = 31 + 2 + 8 + 4 + 4;
        assertEquals(10, b[nbt]);
        assertEquals(3, b[nbt + 1], "first entry is a TAG_Int, directly after the type id");
    }

    @Test
    void readerAcceptsRootsWithEmptyName() throws Exception {
        final CompoundBinaryTag be =
                CompoundBinaryTag.builder()
                        .putInt("x", 1)
                        .putInt("y", 2)
                        .putInt("z", 3)
                        .putString("id", "a:b")
                        .build();
        final ByteWriter named = new ByteWriter(64);
        named.u8(10).u16(0);
        final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        net.kyori.adventure.nbt.BinaryTagIO.writer()
                .writeNameless(be, (java.io.OutputStream) body);
        named.bytes(body.toByteArray(), 1, body.size() - 1);

        final ByteWriter w = new ByteWriter(128);
        w.bytes(PchkCodec.encode(PumpChunk.builder(0, 0).build()), 0, 41);
        w.i32(1).i32(named.size()).bytes(named.array(), 0, named.size());
        assertEquals(be, PchkCodec.decode(w.toByteArray()).blockEntities().get(0));
    }

    // ---- rejection -------------------------------------------------------------------------

    private static byte[] minimal() throws IOException {
        return PchkCodec.encode(PumpChunk.builder(2, 3).dataVersion(1).build());
    }

    private static void assertCorrupt(byte[] payload, String messagePart) {
        final PumpCorruptException e =
                assertThrows(PumpCorruptException.class, () -> PchkCodec.decode(payload));
        assertTrue(
                e.getMessage().toLowerCase().contains(messagePart.toLowerCase()),
                () -> "message '" + e.getMessage() + "' should mention '" + messagePart + "'");
    }

    @Test
    void rejectsBadHeader() throws Exception {
        byte[] b = minimal();
        b[0] = 'X';
        assertCorrupt(b, "magic");
        b = minimal();
        b[4] = 2;
        assertCorrupt(b, "version");
        b = minimal();
        b[0x15] = 10;
        assertCorrupt(b, "status");
        b = minimal();
        b[0x16] = 0x20;
        assertCorrupt(b, "reserved");
        assertCorrupt(new byte[10], "header");
    }

    @Test
    void rejectsPositionMismatch() throws Exception {
        final byte[] b = minimal();
        assertThrows(PumpCorruptException.class, () -> PchkCodec.decode(b, 2, 4));
        assertEquals(2, PchkCodec.decode(b, 2, 3).x());
    }

    @Test
    void rejectsTruncationAtEveryPrefix() throws Exception {
        final byte[] full = PchkCodec.encode(TestChunks.everything(1, 1, 9));
        // Every strict prefix must fail cleanly with a data fault, never another exception.
        for (int len = 0; len < full.length; len += 37) {
            final byte[] prefix = java.util.Arrays.copyOf(full, len);
            assertThrows(PumpException.class, () -> PchkCodec.decode(prefix), "prefix " + len);
        }
    }

    @Test
    void rejectsTrailingBytes() throws Exception {
        final byte[] b = java.util.Arrays.copyOf(minimal(), minimal().length + 1);
        assertCorrupt(b, "trailing");
    }

    @Test
    void rejectsTooManySections() throws Exception {
        final byte[] b = minimal();
        b[0x1F] = 0x01; // 256 sections is allowed, the section data is missing though
        assertCorrupt(b, "unexpected end");
        b[0x1F] = 0x01;
        b[0x20] = 0x01; // 257
        final PumpLimitException e =
                assertThrows(PumpLimitException.class, () -> PchkCodec.decode(b));
        assertTrue(e.getMessage().contains("PUMP_MAX_SECTIONS"));
    }

    @Test
    void rejectsTooManyTicksAndBlockEntities() throws Exception {
        byte[] b = minimal();
        writeInt(b, 33, 65_537);
        assertInstanceOf(
                PumpLimitException.class,
                assertThrows(PumpException.class, () -> PchkCodec.decode(minimalWith(33, 65_537))));
        assertInstanceOf(
                PumpLimitException.class,
                assertThrows(PumpException.class, () -> PchkCodec.decode(minimalWith(37, 65_537))));
        assertInstanceOf(
                PumpLimitException.class,
                assertThrows(PumpException.class, () -> PchkCodec.decode(minimalWith(41, 65_537))));
        // 0xFFFFFFFF must not be mistaken for -1
        assertInstanceOf(
                PumpLimitException.class,
                assertThrows(PumpException.class, () -> PchkCodec.decode(minimalWith(33, -1))));
    }

    private static byte[] minimalWith(int offset, int value) throws IOException {
        final byte[] b = minimal();
        writeInt(b, offset, value);
        return b;
    }

    @Test
    void rejectsBadBlockPalettes() throws Exception {
        final Random r = new Random(1);
        final byte[] good =
                PchkCodec.encode(
                        PumpChunk.builder(0, 0)
                                .section(
                                        new PumpSection(
                                                BlockStates.of(TestChunks.surfaceStates(r)),
                                                Biomes.uniform(0)))
                                .build());
        assertEquals(1, good[33]);
        final int paletteLen = readShort(good, 34);

        byte[] b = good.clone();
        b[34] = 0;
        b[35] = 0;
        assertCorrupt(b, "palette length");

        b = good.clone();
        b[34] = 0x01;
        b[35] = 0x01; // 257
        assertCorrupt(b, "palette length");

        b = good.clone();
        b[36 + 2] ^= 0x01; // first count off by one
        assertCorrupt(b, "sum");

        b = good.clone();
        b[36 + paletteLen * 4] = (byte) paletteLen; // first index == palette_len
        assertCorrupt(b, "index");

        b = good.clone();
        b[33] = 3;
        assertCorrupt(b, "block mode");
    }

    @Test
    void rejectsBadBiomePalette() throws Exception {
        final Random r = new Random(1);
        final byte[] good =
                PchkCodec.encode(
                        PumpChunk.builder(0, 0)
                                .section(
                                        new PumpSection(
                                                BlockStates.uniform(0),
                                                Biomes.of(TestChunks.mixedBiomes(r))))
                                .build());
        assertEquals(1, good[33 + 3]);
        byte[] b = good.clone();
        b[33 + 4] = 0;
        assertCorrupt(b, "biome palette length");
        b = good.clone();
        b[33 + 4] = 65;
        assertCorrupt(b, "biome palette length");
        b = good.clone();
        b[33 + 3] = 2;
        assertCorrupt(b, "biome mode");
    }

    @Test
    void rejectsBadTickPriority() throws Exception {
        final byte[] good =
                PchkCodec.encode(
                        PumpChunk.builder(0, 0)
                                .blockTick(new ScheduledTick(1, 2, 3, 4, 3, 5))
                                .build());
        assertEquals(3, good[33 + 4 + 12 + 1]);
        final byte[] b = good.clone();
        b[33 + 4 + 12 + 1] = 4;
        assertCorrupt(b, "priority");
        b[33 + 4 + 12 + 1] = -4;
        assertCorrupt(b, "priority");
    }

    @Test
    void rejectsInvalidBlockEntities() throws Exception {
        final byte[] good = PchkCodec.encode(TestChunks.everything(0, 0, 3));
        // Truncating the payload inside the NBT area must be reported as corrupt
        assertThrows(
                PumpException.class,
                () -> PchkCodec.decode(java.util.Arrays.copyOf(good, good.length - 5)));

        final ByteWriter w = new ByteWriter(64);
        w.bytes(PchkCodec.encode(PumpChunk.builder(0, 0).build()), 0, 41);
        w.i32(1);
        final CompoundBinaryTag noId =
                CompoundBinaryTag.builder().putInt("x", 1).putInt("y", 2).putInt("z", 3).build();
        final ByteWriter nbt = new ByteWriter(32);
        net.pyrocube.core.pump.io.NbtIO.write(noId, nbt);
        w.i32(nbt.size()).bytes(nbt.array(), 0, nbt.size());
        assertCorrupt(w.toByteArray(), "id");
    }

    @Test
    void writerRejectsInvalidModel() {
        final CompoundBinaryTag bad = CompoundBinaryTag.builder().putInt("x", 1).build();
        assertThrows(
                IllegalArgumentException.class,
                () -> PumpChunk.builder(0, 0).blockEntity(bad).build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        PumpChunk.builder(0, 0)
                                .sections(
                                        IntStream.range(0, 257)
                                                .mapToObj(i -> PumpSection.empty(0))
                                                .toList())
                                .build());
        assertThrows(IllegalArgumentException.class, () -> new ScheduledTick(0, 0, 0, 256, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScheduledTick(0, 0, 0, 0, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> new ScheduledTick(0, 0, 0, 0, 0, 65536));
        assertThrows(IllegalArgumentException.class, () -> BlockStates.uniform(65536));
        assertThrows(IllegalArgumentException.class, () -> Biomes.uniform(256));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PumpSection(BlockStates.uniform(0), Biomes.uniform(0), new byte[5], null));
    }

    @Test
    void maxSectionsRoundTrips() throws Exception {
        final PumpChunk chunk =
                PumpChunk.builder(0, 0)
                        .sections(
                                IntStream.range(0, 256)
                                        .mapToObj(i -> PumpSection.empty(i % 200))
                                        .toList())
                        .build();
        assertEquals(chunk, PchkCodec.decode(PchkCodec.encode(chunk)));
    }

    @Test
    void decodedArraysAreIndependentOfThePayload() throws Exception {
        final PumpChunk chunk = TestChunks.terrain(0, 0, 3, 8);
        final byte[] bytes = PchkCodec.encode(chunk);
        final PumpChunk back = PchkCodec.decode(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
        assertEquals(chunk, back);
        assertArrayEquals(chunk.worldSurface(), back.worldSurface());
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static int readShort(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static int readInt(byte[] b, int p) {
        return (readShort(b, p) << 16) | readShort(b, p + 2);
    }

    private static long readLong(byte[] b, int p) {
        return ((long) readInt(b, p) << 32) | (readInt(b, p + 4) & 0xFFFFFFFFL);
    }

    private static void writeInt(byte[] b, int p, int v) {
        b[p] = (byte) (v >>> 24);
        b[p + 1] = (byte) (v >>> 16);
        b[p + 2] = (byte) (v >>> 8);
        b[p + 3] = (byte) v;
    }
}
