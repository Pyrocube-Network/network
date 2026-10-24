package net.pyrocube.core.pump.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.pyrocube.core.pump.PumpCorruptException;
import org.junit.jupiter.api.Test;

class ByteIoTest {
    @Test
    void bigEndianRoundTrip() throws Exception {
        final ByteWriter w = new ByteWriter(1); // forces growth
        w.u8(0xAB).u16(0xBEEF).i32(-2).i64(Long.MIN_VALUE + 5).bytes(new byte[] {1, 2, 3});
        w.u16s(new char[] {0xFFFF, 0, 0x1234});
        final byte[] b = w.toByteArray();
        assertEquals(1 + 2 + 4 + 8 + 3 + 6, b.length);
        assertEquals((byte) 0xBE, b[1]);
        assertEquals((byte) 0xEF, b[2]);

        final ByteReader r = new ByteReader(b);
        assertEquals(0xAB, r.u8());
        assertEquals(0xBEEF, r.u16());
        assertEquals(-2, r.i32());
        assertEquals(Long.MIN_VALUE + 5, r.i64());
        assertArrayEquals(new byte[] {1, 2, 3}, r.bytes(3));
        final char[] chars = new char[3];
        r.u16s(chars);
        assertArrayEquals(new char[] {0xFFFF, 0, 0x1234}, chars);
        assertEquals(0, r.remaining());
    }

    @Test
    void unsignedAndSignedViews() throws Exception {
        final ByteReader r = new ByteReader(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertEquals(-1, r.i8());
        assertEquals(0xFFFFFFFFL, r.u32());
    }

    @Test
    void underrunIsADataFault() {
        final ByteReader r = new ByteReader(new byte[3]);
        assertThrows(PumpCorruptException.class, r::i32);
        assertThrows(PumpCorruptException.class, () -> r.bytes(4));
        assertThrows(PumpCorruptException.class, () -> r.bytes(-1));
        assertThrows(PumpCorruptException.class, () -> r.skip(4));
        assertEquals(3, r.remaining(), "a failed read consumes nothing");
    }

    @Test
    void sliceReader() throws Exception {
        final ByteReader r = new ByteReader(new byte[] {9, 9, 1, 2, 9}, 2, 2);
        assertEquals(1, r.u8());
        assertEquals(2, r.u8());
        assertThrows(PumpCorruptException.class, r::u8);
        assertThrows(IllegalArgumentException.class, () -> new ByteReader(new byte[2], 1, 5));
    }

    @Test
    void patchLength() {
        final ByteWriter w = new ByteWriter(8);
        w.i32(0).u8(7);
        w.patchI32(0, 1);
        assertArrayEquals(new byte[] {0, 0, 0, 1, 7}, w.toByteArray());
    }
}
