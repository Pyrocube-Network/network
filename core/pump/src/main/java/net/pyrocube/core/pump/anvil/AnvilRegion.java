package net.pyrocube.core.pump.anvil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpException;

/**
 * Minimal reader and writer of Anvil ({@code .mca}) region files, enough to move chunks to and from
 * PUMP. Reading is split in two steps so that the sequential file I/O stays on one thread while the
 * decompression and NBT parsing, which dominate, can run in parallel.
 */
public final class AnvilRegion {
    private static final int SECTOR = 4096;
    private static final int HEADER = 8192;
    private static final int GZIP = 1;
    private static final int ZLIB = 2;
    private static final int NONE = 3;
    private static final int EXTERNAL_FLAG = 0x80;

    private AnvilRegion() {}

    public static String fileName(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".mca";
    }

    /** A chunk as stored in the file: compression id and still compressed NBT. */
    public record RawChunk(int index, int compression, byte[] data) {
        /** Decompresses and parses the chunk NBT. */
        public CompoundBinaryTag parse() throws IOException {
            final InputStream in = new ByteArrayInputStream(data);
            try {
                return switch (compression) {
                    case GZIP -> read(new GZIPInputStream(in));
                    case ZLIB -> read(new InflaterInputStream(in));
                    case NONE -> read(in);
                    default ->
                            throw new PumpException(
                                    "Unsupported Anvil compression type " + compression);
                };
            } catch (PumpException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw new PumpCorruptException("Malformed Anvil chunk NBT: " + e.getMessage(), e);
            }
        }

        private static CompoundBinaryTag read(InputStream in) throws IOException {
            try (in) {
                return BinaryTagIO.unlimitedReader().read(in, BinaryTagIO.Compression.NONE);
            }
        }
    }

    /**
     * Reads every chunk of a region file sequentially, without decoding it.
     *
     * @return the stored chunks in slot order
     */
    public static List<RawChunk> readAll(Path file, int regionX, int regionZ) throws IOException {
        final List<RawChunk> out = new ArrayList<>();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final long size = ch.size();
            if (size < HEADER) {
                if (size == 0) return out;
                throw new PumpCorruptException(
                        "Anvil region " + file.getFileName() + " is truncated");
            }
            final ByteBuffer table = ByteBuffer.allocate(SECTOR);
            readFully(ch, table, 0);
            for (int i = 0; i < 1024; i++) {
                final int location = table.getInt(i * 4);
                if (location == 0) continue;
                final long offset = (location >>> 8) * (long) SECTOR;
                final int sectors = location & 0xFF;
                if (offset < HEADER || offset + 5 > size) {
                    throw new PumpCorruptException(
                            "Anvil chunk " + i + " points outside " + file.getFileName());
                }
                final ByteBuffer head = ByteBuffer.allocate(5);
                readFully(ch, head, offset);
                final int length = head.getInt(0);
                int compression = head.get(4) & 0xFF;
                if (length < 1 || offset + 4 + length > size || length - 1 > sectors * SECTOR) {
                    throw new PumpCorruptException(
                            "Anvil chunk " + i + " has an invalid length in " + file.getFileName());
                }
                final byte[] data;
                if ((compression & EXTERNAL_FLAG) != 0) {
                    compression &= ~EXTERNAL_FLAG;
                    final int cx = (regionX << 5) + (i & 31);
                    final int cz = (regionZ << 5) + (i >> 5);
                    data = Files.readAllBytes(file.resolveSibling("c." + cx + "." + cz + ".mcc"));
                } else {
                    final ByteBuffer body = ByteBuffer.allocate(length - 1);
                    readFully(ch, body, offset + 5);
                    data = body.array();
                }
                out.add(new RawChunk(i, compression, data));
            }
        }
        return out;
    }

    /**
     * Writes a region file containing the given chunks, zlib compressed, replacing an existing one.
     *
     * @param chunks chunk NBT by slot index, {@code null} entries are left empty
     */
    public static void write(Path file, CompoundBinaryTag[] chunks) throws IOException {
        final byte[][] packed = new byte[1024][];
        for (int i = 0; i < 1024; i++) {
            if (chunks[i] == null) continue;
            final java.io.ByteArrayOutputStream bytes =
                    new java.io.ByteArrayOutputStream(16 * 1024);
            BinaryTagIO.writer().write(chunks[i], bytes, BinaryTagIO.Compression.ZLIB);
            packed[i] = bytes.toByteArray();
        }
        write(file, packed, ZLIB);
    }

    /** Writes pre-compressed chunk data of the given Anvil compression type. */
    static void write(Path file, byte[][] packed, int compression) throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(HEADER);
        final int now = (int) (System.currentTimeMillis() / 1000);
        int nextSector = 2;
        for (int i = 0; i < 1024; i++) {
            if (packed[i] == null) continue;
            final int sectors = (packed[i].length + 5 + SECTOR - 1) / SECTOR;
            if (sectors > 255) {
                throw new PumpException(
                        "Chunk "
                                + i
                                + " needs "
                                + sectors
                                + " sectors, more than an Anvil region "
                                + "entry can address (oversized chunks are not supported)");
            }
            header.putInt(i * 4, (nextSector << 8) | sectors);
            header.putInt(SECTOR + i * 4, now);
            nextSector += sectors;
        }
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel ch =
                FileChannel.open(
                        tmp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            ch.write(header.rewind(), 0);
            for (int i = 0; i < 1024; i++) {
                if (packed[i] == null) continue;
                final int location = header.getInt(i * 4);
                final int sectors = location & 0xFF;
                final ByteBuffer body = ByteBuffer.allocate(sectors * SECTOR);
                body.putInt(packed[i].length + 1).put((byte) compression).put(packed[i]);
                ch.write(body.rewind(), (long) (location >>> 8) * SECTOR);
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position)
            throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            final int n = ch.read(buf, pos);
            if (n < 0) throw new PumpCorruptException("Unexpected end of Anvil file");
            pos += n;
        }
    }
}
