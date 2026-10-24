package net.pyrocube.core.pump.io;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Growable big-endian byte sink. Not thread-safe; one instance per encoding operation. */
public final class ByteWriter {
    private static final VarHandle SHORT =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private static final int MAX_ARRAY = Integer.MAX_VALUE - 8;

    private byte[] buf;
    private int size;

    public ByteWriter(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
    }

    private void ensure(int extra) {
        final long needed = (long) size + extra;
        if (needed > buf.length) {
            if (needed > MAX_ARRAY) throw new IllegalStateException("Buffer too large");
            buf = Arrays.copyOf(buf, (int) Math.min(MAX_ARRAY, Math.max(needed, buf.length * 2L)));
        }
    }

    public int size() {
        return size;
    }

    public ByteWriter u8(int v) {
        ensure(1);
        buf[size++] = (byte) v;
        return this;
    }

    public ByteWriter u16(int v) {
        ensure(2);
        SHORT.set(buf, size, (short) v);
        size += 2;
        return this;
    }

    public ByteWriter i32(int v) {
        ensure(4);
        INT.set(buf, size, v);
        size += 4;
        return this;
    }

    public ByteWriter i64(long v) {
        ensure(8);
        LONG.set(buf, size, v);
        size += 8;
        return this;
    }

    /** Appends every element of {@code src} as an unsigned 16 bit value. */
    public ByteWriter u16s(char[] src) {
        ensure(src.length * 2);
        int p = size;
        for (char c : src) {
            SHORT.set(buf, p, (short) c);
            p += 2;
        }
        size = p;
        return this;
    }

    public ByteWriter bytes(byte[] src) {
        return bytes(src, 0, src.length);
    }

    public ByteWriter bytes(byte[] src, int off, int len) {
        ensure(len);
        System.arraycopy(src, off, buf, size, len);
        size += len;
        return this;
    }

    /** Overwrites four bytes at an earlier position, used to back-patch length prefixes. */
    public void patchI32(int position, int v) {
        INT.set(buf, position, v);
    }

    /** The live backing array. Valid up to {@link #size()} and invalidated by later writes. */
    public byte[] array() {
        return buf;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, size);
    }
}
