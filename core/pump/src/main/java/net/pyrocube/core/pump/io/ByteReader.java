package net.pyrocube.core.pump.io;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import net.pyrocube.core.pump.PumpCorruptException;

/**
 * Bounds checked big-endian cursor over a byte array. Every underrun surfaces as a {@link
 * PumpCorruptException} rather than an unchecked exception, as truncated input is a data fault.
 */
public final class ByteReader {
    private static final VarHandle SHORT =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private final byte[] buf;
    private final int end;
    private int pos;

    public ByteReader(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public ByteReader(byte[] buf, int offset, int length) {
        if (offset < 0 || length < 0 || offset > buf.length - length) {
            throw new IllegalArgumentException("slice out of bounds");
        }
        this.buf = buf;
        this.pos = offset;
        this.end = offset + length;
    }

    public int remaining() {
        return end - pos;
    }

    public int position() {
        return pos;
    }

    private void require(int n) throws PumpCorruptException {
        if (n < 0 || n > end - pos) {
            throw new PumpCorruptException(
                    "Unexpected end of data: need " + n + " bytes, " + (end - pos) + " left");
        }
    }

    public int u8() throws PumpCorruptException {
        require(1);
        return buf[pos++] & 0xFF;
    }

    public int i8() throws PumpCorruptException {
        require(1);
        return buf[pos++];
    }

    public int u16() throws PumpCorruptException {
        require(2);
        final int v = (short) SHORT.get(buf, pos) & 0xFFFF;
        pos += 2;
        return v;
    }

    public int i32() throws PumpCorruptException {
        require(4);
        final int v = (int) INT.get(buf, pos);
        pos += 4;
        return v;
    }

    /** Reads an unsigned 32 bit value as a {@code long}. */
    public long u32() throws PumpCorruptException {
        return i32() & 0xFFFFFFFFL;
    }

    public long i64() throws PumpCorruptException {
        require(8);
        final long v = (long) LONG.get(buf, pos);
        pos += 8;
        return v;
    }

    /** Fills {@code dst} with {@code dst.length} big-endian unsigned 16 bit values. */
    public void u16s(char[] dst) throws PumpCorruptException {
        require(dst.length * 2);
        int p = pos;
        for (int i = 0; i < dst.length; i++, p += 2) {
            dst[i] = (char) (short) SHORT.get(buf, p);
        }
        pos = p;
    }

    /** Copies {@code n} bytes into a new array. */
    public byte[] bytes(int n) throws PumpCorruptException {
        require(n);
        final byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** Copies {@code dst.length} bytes into {@code dst}. */
    public void bytes(byte[] dst) throws PumpCorruptException {
        require(dst.length);
        System.arraycopy(buf, pos, dst, 0, dst.length);
        pos += dst.length;
    }

    public void skip(int n) throws PumpCorruptException {
        require(n);
        pos += n;
    }

    /** Backing array, for readers that parse a slice in place. */
    public byte[] array() {
        return buf;
    }
}
