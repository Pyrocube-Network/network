package net.pyrocube.core.pump;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.pyrocube.core.pump.chunk.Biomes;
import net.pyrocube.core.pump.chunk.BlockStates;
import net.pyrocube.core.pump.chunk.ChunkStatus;
import net.pyrocube.core.pump.chunk.PchkCodec;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.chunk.PumpSection;
import net.pyrocube.core.pump.chunk.ScheduledTick;
import net.pyrocube.core.pump.region.PumpStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the serialized form to bytes assembled by hand from the specification text, independently of
 * the codec, so that the layout cannot drift unnoticed.
 */
class GoldenFormatTest {
    @TempDir Path dir;

    private static PumpChunk chunk() {
        final short[] heights = new short[256];
        heights[0] = 1;
        return PumpChunk.builder(1, -1)
                .dataVersion(0x0F)
                .minSectionY(-1)
                .status(ChunkStatus.FULL)
                .inhabitedTime(2)
                .lightCorrect(true)
                .worldSurface(heights)
                .section(new PumpSection(BlockStates.uniform(5), Biomes.uniform(3)))
                .blockTick(new ScheduledTick(1, 2, 3, 4, -3, 7))
                .build();
    }

    private static byte[] expectedPayload() {
        final ByteBuffer b = ByteBuffer.allocate(4096); // big-endian by default
        b.put((byte) 'P').put((byte) 'C').put((byte) 'H').put((byte) 'K'); // magic
        b.put((byte) 1); // version
        b.putInt(0x0F); // data_version
        b.putInt(1); // x
        b.putInt(-1); // z
        b.putInt(-1); // min_section_y
        b.put((byte) 9); // status Full
        b.put((byte) 0x03); // flags: LIGHT_CORRECT | HAS_WORLD_SURFACE
        b.putLong(2); // inhabited_time
        assertEquals(31, b.position(), "PumpChunkHeader is 31 bytes");

        b.putLong(1L); // heightmap word 0: element 0 = 1, 9 bits at bit 0
        for (int i = 1; i < 37; i++) b.putLong(0L);
        b.putShort((short) 1); // section_count

        b.put((byte) 0).putShort((short) 5); // blocks: mode 0, state 5
        b.put((byte) 0).put((byte) 3); // biomes: mode 0, biome 3
        b.put((byte) 0); // light flags

        b.putInt(1); // block ticks
        b.putInt(1).putInt(2).putInt(3).put((byte) 4).put((byte) -3).putShort((short) 7);
        b.putInt(0); // fluid ticks
        b.putInt(0); // block entities
        final byte[] out = new byte[b.position()];
        b.flip().get(out);
        return out;
    }

    @Test
    void payloadBytes() throws Exception {
        assertArrayEquals(expectedPayload(), PchkCodec.encode(chunk()));
        assertEquals(chunk(), PchkCodec.decode(expectedPayload(), 1, -1));
    }

    @Test
    void regionFileHeaderBytes() throws Exception {
        try (PumpStorage storage = PumpStorage.open(dir)) {
            storage.write(chunk());
        }
        // chunk (1, -1): region (0, -1), slot (-1 & 31) * 32 + (1 & 31) = 31 * 32 + 1 = 993
        final byte[] file = Files.readAllBytes(dir.resolve("r.0.-1.pump"));
        final ByteBuffer f = ByteBuffer.wrap(file);

        final ByteArrayOutputStream signature = new ByteArrayOutputStream();
        signature.write(new byte[] {'P', 'U', 'M', 'P', 0, 0, 0, 1});
        assertArrayEquals(signature.toByteArray(), java.util.Arrays.copyOf(file, 8));
        assertEquals(0, f.getInt(8), "region_x");
        assertEquals(-1, f.getInt(12), "region_z");
        for (int i = 16; i < 256; i++) assertEquals(0, file[i], "reserved byte " + i);

        final int entry = 0x100 + 993 * 8;
        assertEquals(33, f.getInt(entry), "sector_offset: first data sector");
        final int byteLength = f.getInt(entry + 4);
        assertEquals(file.length, 33 * 256 + ((byteLength + 255) / 256) * 256);

        // frame header: u32 uncompressed_len, u64 XXH64 checksum, then a Zstandard frame
        final int frame = 33 * 256;
        assertEquals(expectedPayload().length, f.getInt(frame));
        assertEquals(net.pyrocube.core.pump.io.XXH64.hash(expectedPayload()), f.getLong(frame + 4));
        assertEquals(0x28B52FFD, f.getInt(frame + 12), "Zstandard frame magic");
    }
}
