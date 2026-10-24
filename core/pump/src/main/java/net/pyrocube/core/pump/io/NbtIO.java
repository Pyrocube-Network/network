package net.pyrocube.core.pump.io;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.pyrocube.core.pump.PumpCorruptException;

/**
 * Serialization of the unnamed {@code TAG_Compound} payloads of §5.5 and §5.6.
 *
 * <p>The specification does not say whether "unnamed" means a root without a name field (the Java
 * network encoding: {@code 0x0A} followed directly by the entries) or a root with an empty name
 * (the Java file encoding: {@code 0x0A 0x00 0x00} and then the entries). Writers emit the nameless
 * form, readers accept both: the two are unambiguous because the first byte after {@code 0x0A} is
 * either a tag id (never zero for a non-empty compound) or the high byte of the name length.
 */
public final class NbtIO {
    private static final int TAG_COMPOUND = 10;
    private static final long ACCOUNTING_FACTOR = 48;

    private NbtIO() {}

    /** Appends {@code tag} in nameless form. */
    public static void write(CompoundBinaryTag tag, ByteWriter out) throws IOException {
        final DataOutput data = new DataOutputStream(new Sink(out));
        BinaryTagIO.writer().writeNameless(tag, data);
    }

    /** Parses a compound occupying exactly {@code buf[off, off + len)}. */
    public static CompoundBinaryTag read(byte[] buf, int off, int len) throws PumpCorruptException {
        if (len < 2) throw new PumpCorruptException("NBT payload too short: " + len + " bytes");
        if ((buf[off] & 0xFF) != TAG_COMPOUND) {
            throw new PumpCorruptException(
                    "NBT payload is not a TAG_Compound (tag id " + (buf[off] & 0xFF) + ")");
        }
        final boolean emptyNamed = len >= 4 && buf[off + 1] == 0 && buf[off + 2] == 0;
        final ByteArrayInputStream raw = new ByteArrayInputStream(buf, off, len);
        try {
            // The reader limits accounted memory, not wire bytes: tags cost more in memory than on
            // the
            // wire. The multiple bounds the amplification a hostile payload can cause.
            final BinaryTagIO.Reader reader =
                    BinaryTagIO.reader(Math.max(4096L, len * ACCOUNTING_FACTOR));
            final DataInput in = new DataInputStream(raw);
            final CompoundBinaryTag tag = emptyNamed ? reader.read(in) : reader.readNameless(in);
            if (raw.available() != 0) {
                throw new PumpCorruptException(
                        "NBT payload has " + raw.available() + " trailing bytes");
            }
            return tag;
        } catch (PumpCorruptException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new PumpCorruptException("Malformed NBT payload: " + e.getMessage(), e);
        }
    }

    private static final class Sink extends OutputStream {
        private final ByteWriter out;

        Sink(ByteWriter out) {
            this.out = out;
        }

        @Override
        public void write(int b) {
            out.u8(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            out.bytes(b, off, len);
        }
    }
}
