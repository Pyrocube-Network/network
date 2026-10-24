package net.pyrocube.core.pump.region;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.StampedLock;
import net.pyrocube.core.pump.PumpCorruptException;
import net.pyrocube.core.pump.PumpException;
import net.pyrocube.core.pump.PumpFormat;
import net.pyrocube.core.pump.chunk.EncodedChunk;
import net.pyrocube.core.pump.io.ZstdCodec;

/**
 * A single {@code r.X.Z.pump} region container (chapter 3).
 *
 * <p>The class owns the superblock, the chunk location table and the sector occupancy bitmap, and
 * implements the copy-on-write commit protocol of §6.2. It deals in raw chunk frames: compression,
 * checksums and payload parsing are the job of {@link net.pyrocube.core.pump.chunk.ChunkFrame}.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Instances are fully thread-safe. Reads of different chunks run in parallel, and so do the data
 * writes of concurrent commits: only the in-memory table swap and the reclamation need exclusive
 * access, the header write is serialized by a separate commit lock so that readers are never
 * blocked behind a flush barrier.
 *
 * <ul>
 *   <li>{@code lock} (a {@link StampedLock}) guards the table and the bitmap. Reads are
 *       <em>optimistic</em>: a reader looks the slot up, reads the bytes and then checks that no
 *       writer took the lock in the meantime. Since sectors can only be reclaimed and reused by a
 *       writer holding the lock, an unchanged stamp proves the bytes belong to the entry that was
 *       looked up. Readers therefore never write to shared lock state, which is what keeps them
 *       scaling with the core count. If a writer interfered, the read is repeated under the read
 *       lock.
 *   <li>{@code commitLock} serializes header commits so an older table snapshot can never overwrite
 *       a newer one on disk.
 * </ul>
 *
 * <p>Writes are staged in sectors that are marked occupied but not yet referenced by the table, so
 * the data phase needs no lock at all and concurrent commits never allocate the same sectors.
 */
public final class PumpRegionFile implements AutoCloseable {
    /** Open mode of a region file. */
    public enum Mode {
        /** The file must exist and is never modified. */
        READ_ONLY,
        /** The file is created if missing and is locked against other processes. */
        READ_WRITE
    }

    /** One entry of a {@link #commit batch}: a new frame for a slot, or a deletion. */
    public record Write(int chunkIndex, EncodedChunk frame) {
        public static Write put(int chunkIndex, EncodedChunk frame) {
            return new Write(chunkIndex, java.util.Objects.requireNonNull(frame));
        }

        public static Write delete(int chunkIndex) {
            return new Write(chunkIndex, null);
        }
    }

    private static final VarHandle INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);

    /** Largest legal chunk frame: header plus the Zstd bound of a 64 MiB payload. */
    private static final long MAX_FRAME_BYTES =
            PumpFormat.FRAME_HEADER_BYTES
                    + ZstdCodec.compressBound(PumpFormat.MAX_DECOMPRESSED_CHUNK_BYTES);

    private final Path path;
    private final int regionX;
    private final int regionZ;
    private final boolean writable;
    private final Durability durability;
    private final FileChannel channel;
    private final FileLock fileLock;

    private final StampedLock lock = new StampedLock();
    private final ReentrantLock commitLock = new ReentrantLock();

    /** Sector offset per slot, 0 when absent. Guarded by {@code lock}. */
    private final int[] offsets = new int[PumpFormat.CHUNK_COUNT];

    /** Frame byte length per slot. Guarded by {@code lock}. */
    private final int[] lengths = new int[PumpFormat.CHUNK_COUNT];

    /** Slots whose table entry violates the frame bounds, reading them fails. */
    private final BitSet damaged = new BitSet(PumpFormat.CHUNK_COUNT);

    /** Occupied sectors, including the header and staged but uncommitted runs. */
    private final BitSet used = new BitSet();

    /** The superblock exactly as found on disk, so reserved bytes survive rewrites (§3.3.1). */
    private final byte[] superblock = new byte[PumpFormat.SECTOR_SIZE];

    private final AtomicBoolean unsynced = new AtomicBoolean();
    private volatile boolean closed;

    private PumpRegionFile(
            Path path,
            int regionX,
            int regionZ,
            Mode mode,
            Durability durability,
            FileChannel channel,
            FileLock fileLock) {
        this.path = path;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.writable = mode == Mode.READ_WRITE;
        this.durability = durability;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    /** Path of the region file for the given coordinates inside {@code directory}. */
    public static Path pathFor(Path directory, int regionX, int regionZ) {
        return directory.resolve(PumpFormat.regionFileName(regionX, regionZ));
    }

    /**
     * Opens and validates a region file.
     *
     * @throws NoSuchFileException if {@code mode} is {@link Mode#READ_ONLY} and the file is missing
     * @throws PumpCorruptException if the superblock or the location table is invalid
     */
    public static PumpRegionFile open(
            Path path, int regionX, int regionZ, Mode mode, Durability durability)
            throws IOException {
        final boolean writable = mode == Mode.READ_WRITE;
        final Set<StandardOpenOption> options = EnumSet.of(StandardOpenOption.READ);
        if (writable) {
            options.add(StandardOpenOption.WRITE);
            options.add(StandardOpenOption.CREATE);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
        }
        final FileChannel channel = FileChannel.open(path, options);
        FileLock fileLock = null;
        try {
            if (writable) {
                try {
                    fileLock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    fileLock = null;
                }
                if (fileLock == null) {
                    throw new PumpException(
                            "Region file is locked by another writer: "
                                    + path
                                    + " (a world must only be opened by one server at a time)");
                }
            }
            final PumpRegionFile region =
                    new PumpRegionFile(path, regionX, regionZ, mode, durability, channel, fileLock);
            region.load();
            return region;
        } catch (Throwable t) {
            try {
                channel.close();
            } catch (IOException suppressed) {
                t.addSuppressed(suppressed);
            }
            throw t;
        }
    }

    private void load() throws IOException {
        long size = channel.size();
        if (size == 0 && writable) {
            initialize();
            size = PumpFormat.HEADER_BYTES;
        }
        if (size < PumpFormat.HEADER_BYTES) {
            throw new PumpCorruptException(
                    describe() + " is truncated: " + size + " bytes, header needs 8448");
        }
        final byte[] header = new byte[PumpFormat.HEADER_BYTES];
        readFully(header, 0);

        for (int i = 0; i < 8; i++) {
            if (header[i] != PumpFormat.signatureByte(i)) {
                final boolean sameMagic = i == 7;
                throw new PumpCorruptException(
                        describe()
                                + (sameMagic
                                        ? " has unsupported format version " + (header[7] & 0xFF)
                                        : " has a bad signature, not a PUMP region"));
            }
        }
        final int fileX = (int) INT.get(header, 8);
        final int fileZ = (int) INT.get(header, 12);
        if (fileX != regionX || fileZ != regionZ) {
            throw new PumpCorruptException(
                    describe()
                            + " superblock says region ("
                            + fileX
                            + ", "
                            + fileZ
                            + ") but the file name says ("
                            + regionX
                            + ", "
                            + regionZ
                            + ")");
        }
        System.arraycopy(header, 0, superblock, 0, superblock.length);

        used.set(0, PumpFormat.HEADER_SECTORS);
        for (int i = 0; i < PumpFormat.CHUNK_COUNT; i++) {
            final int at = PumpFormat.LOCATION_TABLE_OFFSET + i * PumpFormat.CHUNK_ENTRY_BYTES;
            final int offset = (int) INT.get(header, at);
            final int length = (int) INT.get(header, at + 4);
            if (offset == 0 || length == 0) continue; // absent (§6.1 step 4)

            final long sector = offset & 0xFFFFFFFFL;
            final long byteLength = length & 0xFFFFFFFFL;
            if (sector < PumpFormat.HEADER_SECTORS) {
                throw new PumpCorruptException(
                        describe()
                                + " slot "
                                + i
                                + " points into the header (sector "
                                + sector
                                + "), fatal container fault");
            }
            offsets[i] = offset;
            lengths[i] = length;
            final long end = sector * PumpFormat.SECTOR_SIZE + byteLength;
            if (byteLength <= PumpFormat.FRAME_HEADER_BYTES
                    || byteLength > MAX_FRAME_BYTES
                    || end > size) {
                // Reading this slot will fail; the sectors are not claimed because their extent
                // cannot be trusted. Rewriting the chunk replaces the entry.
                damaged.set(i);
                continue;
            }
            final int count = PumpFormat.sectorsFor(byteLength);
            final int from = (int) sector;
            final int to = from + count;
            if (overlaps(from, to)) {
                throw new PumpCorruptException(
                        describe() + " slot " + i + " overlaps another chunk or the header");
            }
            used.set(from, to);
        }

        if (writable) {
            // §6.3: sectors orphaned by an interrupted write are free again simply because no
            // table entry claims them. Give back a tail the table no longer references.
            final long wanted = (long) used.length() * PumpFormat.SECTOR_SIZE;
            if (size > wanted) channel.truncate(wanted);
        }
    }

    private boolean overlaps(int from, int to) {
        final int found = used.nextSetBit(from);
        return found >= 0 && found < to;
    }

    private void initialize() throws IOException {
        final byte[] header = new byte[PumpFormat.HEADER_BYTES];
        for (int i = 0; i < 8; i++) header[i] = PumpFormat.signatureByte(i);
        INT.set(header, 8, regionX);
        INT.set(header, 12, regionZ);
        writeFully(header, 0, header.length, 0);
        if (durability == Durability.STRICT) {
            channel.force(true);
        } else {
            unsynced.set(true);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------------

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public Path path() {
        return path;
    }

    /** Whether slot {@code chunkIndex} holds a chunk. */
    public boolean hasChunk(int chunkIndex) {
        checkIndex(chunkIndex);
        final long stamp = lock.readLock();
        try {
            return offsets[chunkIndex] != 0;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /** Byte length of the frame in a slot, 0 if the slot is empty. */
    public int frameLength(int chunkIndex) {
        checkIndex(chunkIndex);
        final long stamp = lock.readLock();
        try {
            return offsets[chunkIndex] == 0 ? 0 : lengths[chunkIndex];
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /** Number of stored chunks. */
    public int chunkCount() {
        final long stamp = lock.readLock();
        try {
            int n = 0;
            for (int offset : offsets) if (offset != 0) n++;
            return n;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /** The occupied slots, as a set of indices into the 32 x 32 grid. */
    public BitSet storedChunks() {
        final long stamp = lock.readLock();
        try {
            final BitSet set = new BitSet(PumpFormat.CHUNK_COUNT);
            for (int i = 0; i < offsets.length; i++) if (offsets[i] != 0) set.set(i);
            return set;
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /** Number of sectors currently claimed, header included. */
    public int usedSectors() {
        final long stamp = lock.readLock();
        try {
            return used.cardinality();
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Reads the raw frame stored in a slot (steps 3 to 5 of §6.1).
     *
     * @return the frame of exactly {@code byte_length} bytes, or {@code null} if the slot is empty
     * @throws PumpCorruptException if the slot's entry is out of bounds
     */
    public byte[] readFrame(int chunkIndex) throws IOException {
        checkIndex(chunkIndex);

        // Optimistic attempt: no lock state is written, the stamp only proves nothing changed.
        final long stamp = lock.tryOptimisticRead();
        if (stamp != 0 && !closed) {
            final int offset = offsets[chunkIndex];
            final int length = lengths[chunkIndex];
            final boolean flagged = damaged.get(chunkIndex);
            if (offset == 0) {
                if (lock.validate(stamp)) return null;
            } else if (!flagged
                    && length > PumpFormat.FRAME_HEADER_BYTES
                    && length <= MAX_FRAME_BYTES) {
                final byte[] frame = new byte[length];
                if (tryReadFully(frame, (offset & 0xFFFFFFFFL) * PumpFormat.SECTOR_SIZE)
                        && lock.validate(stamp)) {
                    return frame;
                }
            }
        }

        // A writer interfered, or the entry is unusual: repeat under the read lock, which also
        // produces the precise error for damaged entries.
        final long readStamp = lock.readLock();
        try {
            ensureOpen();
            final int offset = offsets[chunkIndex];
            if (offset == 0) return null;
            if (damaged.get(chunkIndex)) {
                throw new PumpCorruptException(
                        describe()
                                + " slot "
                                + chunkIndex
                                + " has an invalid location entry (sector "
                                + (offset & 0xFFFFFFFFL)
                                + ", length "
                                + (lengths[chunkIndex] & 0xFFFFFFFFL)
                                + ")");
            }
            final byte[] frame = new byte[lengths[chunkIndex]];
            readFully(frame, (offset & 0xFFFFFFFFL) * PumpFormat.SECTOR_SIZE);
            return frame;
        } finally {
            lock.unlockRead(readStamp);
        }
    }

    /** Positional read that reports failure instead of throwing, for the optimistic path. */
    private boolean tryReadFully(byte[] dst, long position) {
        try {
            final ByteBuffer buf = ByteBuffer.wrap(dst);
            long pos = position;
            while (buf.hasRemaining()) {
                final int n = channel.read(buf, pos);
                if (n < 0) return false;
                pos += n;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------------------------

    /** Stores or replaces one chunk. Shorthand for a single entry {@link #commit}. */
    public void write(int chunkIndex, EncodedChunk frame) throws IOException {
        commit(List.of(Write.put(chunkIndex, frame)));
    }

    /** Removes one chunk. */
    public void delete(int chunkIndex) throws IOException {
        commit(List.of(Write.delete(chunkIndex)));
    }

    /**
     * Applies a batch of writes with the four phase protocol of §6.2, paying the two flush barriers
     * once for the whole batch. The batch is atomic from the point of view of a crash: after
     * recovery every entry is either the old or the new version, never torn, and entries of a batch
     * are committed by a single header write.
     *
     * <p>If a slot appears more than once the last entry wins.
     */
    public void commit(List<Write> writes) throws IOException {
        if (!writable) throw new PumpException("Region is open read-only: " + path);
        if (writes.isEmpty()) return;

        // Keep only the last entry per slot, preserving the caller's order otherwise.
        final Write[] bySlot = new Write[PumpFormat.CHUNK_COUNT];
        for (Write w : writes) {
            checkIndex(w.chunkIndex());
            bySlot[w.chunkIndex()] = w;
        }
        final List<Write> batch = new ArrayList<>(writes.size());
        for (Write w : bySlot) if (w != null) batch.add(w);

        // Phase 2a: stage sectors. They are occupied in the bitmap but not in the table yet.
        final int[] staged = new int[batch.size()];
        long stamp = lock.writeLock();
        try {
            ensureOpen();
            for (int i = 0; i < staged.length; i++) {
                final Write w = batch.get(i);
                staged[i] = w.frame() == null ? 0 : allocate(w.frame().sectors());
            }
        } catch (Throwable t) {
            releaseStagedLocked(batch, staged);
            throw t;
        } finally {
            lock.unlockWrite(stamp);
        }

        try {
            // Phase 2b: write the frames into the staged sectors, then Barrier 1.
            for (int i = 0; i < staged.length; i++) {
                final EncodedChunk frame = batch.get(i).frame();
                if (frame != null) {
                    writeFully(
                            frame.array(),
                            0,
                            frame.paddedLength(),
                            (long) staged[i] * PumpFormat.SECTOR_SIZE);
                }
            }
            if (durability == Durability.STRICT) {
                channel.force(false);
            } else {
                unsynced.set(true);
            }
        } catch (Throwable t) {
            releaseStaged(batch, staged);
            throw t;
        }

        // Phases 3 and 4 are serialized so table snapshots reach the disk in order.
        commitLock.lock();
        try {
            final int[] oldOffsets = new int[staged.length];
            final int[] oldLengths = new int[staged.length];
            final boolean[] oldDamaged = new boolean[staged.length];
            final byte[] header;

            stamp = lock.writeLock();
            try {
                ensureOpen();
                for (int i = 0; i < staged.length; i++) {
                    final int slot = batch.get(i).chunkIndex();
                    oldOffsets[i] = offsets[slot];
                    oldLengths[i] = lengths[slot];
                    oldDamaged[i] = damaged.get(slot);
                    final EncodedChunk frame = batch.get(i).frame();
                    offsets[slot] = frame == null ? 0 : staged[i];
                    lengths[slot] = frame == null ? 0 : frame.length();
                    damaged.clear(slot);
                }
                header = serializeHeader();
            } finally {
                lock.unlockWrite(stamp);
            }

            // Phase 3: publish the table, then Barrier 2.
            try {
                writeFully(header, 0, header.length, 0);
                if (durability == Durability.STRICT) channel.force(false);
            } catch (Throwable t) {
                stamp = lock.writeLock();
                try {
                    for (int i = 0; i < staged.length; i++) {
                        final int slot = batch.get(i).chunkIndex();
                        offsets[slot] = oldOffsets[i];
                        lengths[slot] = oldLengths[i];
                        damaged.set(slot, oldDamaged[i]);
                    }
                    releaseStagedLocked(batch, staged);
                } finally {
                    lock.unlockWrite(stamp);
                }
                throw t;
            }

            // Phase 4: the previous runs are unreferenced on disk now, reclaim them.
            stamp = lock.writeLock();
            try {
                for (int i = 0; i < staged.length; i++) {
                    if (oldOffsets[i] != 0 && !oldDamaged[i]) {
                        final int from = oldOffsets[i];
                        used.clear(from, from + PumpFormat.sectorsFor(oldLengths[i] & 0xFFFFFFFFL));
                    }
                }
                truncateTail();
            } finally {
                lock.unlockWrite(stamp);
            }
        } finally {
            commitLock.unlock();
        }
    }

    /** First fit allocation of {@code count} sectors, appending when no gap is large enough. */
    private int allocate(int count) throws IOException {
        int from = PumpFormat.HEADER_SECTORS;
        while (true) {
            final int start = used.nextClearBit(from);
            final int blocker = used.nextSetBit(start);
            if (blocker == -1 || blocker - start >= count) {
                if ((long) start + count > Integer.MAX_VALUE / 2) {
                    throw new PumpException("Region file is full: " + path);
                }
                used.set(start, start + count);
                return start;
            }
            from = blocker;
        }
    }

    private void releaseStaged(List<Write> batch, int[] staged) {
        final long stamp = lock.writeLock();
        try {
            releaseStagedLocked(batch, staged);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    private void releaseStagedLocked(List<Write> batch, int[] staged) {
        for (int i = 0; i < staged.length; i++) {
            final EncodedChunk frame = batch.get(i).frame();
            // staged[i] stays 0 for deletions and for entries never reached by a failed allocation
            if (frame != null && staged[i] != 0) used.clear(staged[i], staged[i] + frame.sectors());
        }
    }

    /** Shrinks the file to the highest claimed sector (§3.4). Caller holds the write lock. */
    private void truncateTail() throws IOException {
        final long wanted = (long) used.length() * PumpFormat.SECTOR_SIZE;
        if (channel.size() > wanted) channel.truncate(wanted);
    }

    private byte[] serializeHeader() {
        final byte[] header = new byte[PumpFormat.HEADER_BYTES];
        System.arraycopy(superblock, 0, header, 0, superblock.length);
        for (int i = 0; i < PumpFormat.CHUNK_COUNT; i++) {
            final int at = PumpFormat.LOCATION_TABLE_OFFSET + i * PumpFormat.CHUNK_ENTRY_BYTES;
            INT.set(header, at, offsets[i]);
            INT.set(header, at + 4, lengths[i]);
        }
        return header;
    }

    /** Issues a flush barrier for everything written so far. */
    public void sync() throws IOException {
        if (writable && unsynced.getAndSet(false)) channel.force(true);
    }

    @Override
    public void close() throws IOException {
        final long stamp = lock.writeLock();
        try {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try {
                if (writable && unsynced.getAndSet(false)) channel.force(true);
            } catch (IOException e) {
                failure = e;
            }
            try {
                if (fileLock != null && fileLock.isValid()) fileLock.release();
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
            if (failure != null) throw failure;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    // ---------------------------------------------------------------------------------------
    // I/O helpers
    // ---------------------------------------------------------------------------------------

    private void ensureOpen() throws IOException {
        if (closed) throw new PumpException("Region file is closed: " + path);
    }

    private static void checkIndex(int chunkIndex) {
        if (chunkIndex < 0 || chunkIndex >= PumpFormat.CHUNK_COUNT) {
            throw new IndexOutOfBoundsException("Chunk index " + chunkIndex);
        }
    }

    private void readFully(byte[] dst, long position) throws IOException {
        final ByteBuffer buf = ByteBuffer.wrap(dst);
        long pos = position;
        while (buf.hasRemaining()) {
            final int n = channel.read(buf, pos);
            if (n < 0) {
                throw new PumpCorruptException(
                        describe() + " ended unexpectedly while reading at byte " + position);
            }
            pos += n;
        }
    }

    private void writeFully(byte[] src, int off, int len, long position) throws IOException {
        final ByteBuffer buf = ByteBuffer.wrap(src, off, len);
        long pos = position;
        while (buf.hasRemaining()) pos += channel.write(buf, pos);
    }

    private String describe() {
        return "Region file " + path.getFileName();
    }

    @Override
    public String toString() {
        return "PumpRegionFile[" + path + "]";
    }
}
