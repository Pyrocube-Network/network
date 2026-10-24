# Architecture

## Layers

```
 Minestom Instance
        │ ChunkLoader
 ┌──────▼───────────────────┐
 │ PumpChunkLoader          │  policies (data version, corruption, status), hooks, batching
 │ MinestomChunkMapper      │  Chunk  ⇄  PumpChunk
 └──────┬───────────────────┘
        │ PumpChunk (immutable, server independent)
 ┌──────▼───────────────────┐        ┌────────────────────────┐
 │ PumpStorage              │───────▶│ Parallel               │ bounded fan-out of the
 │ region cache, bulk ops,  │        └────────────────────────┘ CPU heavy stages
 │ async, statistics        │
 └──────┬───────────────────┘
        │ frames (bytes)                     ┌────────────────────────┐
 ┌──────▼───────────────────┐        ┌─────▶│ ChunkFrame             │ u32 len, XXH64, Zstd
 │ PumpRegionFile           │        │      │ PchkCodec              │ PCHK v1 bytes ⇄ PumpChunk
 │ superblock, table,       │        │      │ ZstdCodec, XXH64, NbtIO│
 │ sector bitmap, COW commit│        │      └────────────────────────┘
 └──────────────────────────┘   encode/decode happen OUTSIDE the file's locks

 WorldConverter ─ AnvilTranscoder ─ AnvilRegion      (Anvil ⇄ PumpChunk, parallel per region)
```

| Package | Responsibility |
|---|---|
| `net.pyrocube.core.pump` | `PumpFormat` constants and index maths, the exception types |
| `…io` | big-endian `ByteReader`/`ByteWriter`, `XXH64`, pooled `ZstdCodec`, `NbtIO` (nameless compounds) |
| `…chunk` | the model (`PumpChunk`, `PumpSection`, `BlockStates`, `Biomes`, `ScheduledTick`), `PchkCodec`, `ChunkFrame`, `Heightmaps` |
| `…region` | `PumpRegionFile`, `PumpStorage`, `PumpOptions`, `Durability`, `PumpStats` |
| `…concurrent` | `Parallel.map`, order preserving bounded parallelism |
| `…minestom` | `PumpChunkLoader`, `MinestomChunkMapper`, policies and hooks |
| `…anvil` | `AnvilRegion` (.mca), `AnvilTranscoder`, `WorldConverter`, `PumpConvertTool`, `BiomeIds` |

The chunk model is the seam: everything above it knows Minestom, everything below it knows bytes.
`chunk` and `region` do not import any Minestom type (they use `adventure-nbt` for block entity
compounds only), so the format can be used by tools and tests without a server.

## Read path

1. `PumpStorage.read(x, z)` finds the region (`ConcurrentHashMap` hit, one atomic increment).
2. `PumpRegionFile.readFrame(slot)` returns the `byte_length` bytes of the frame (optimistic read,
   below).
3. **Outside every lock**: `ChunkFrame.decode` checks the 12 byte header, rejects declared sizes
   above 64 MiB *before allocating*, cross-checks the size the Zstandard frame declares, decompresses
   into an array of exactly the declared size, verifies XXH64.
4. `PchkCodec.decode` parses and validates the payload, including that the header position equals
   the slot.
5. The region's use count is released.

## Write path (batched)

1. **Prepare, parallel and lock free.** Each chunk is serialized (`PchkCodec`), checksummed and
   compressed into an `EncodedChunk`: the frame, zero padded to whole sectors. The padding is
   cleared explicitly because the compressor may scribble past the size it reports.
2. Chunks are grouped by region and each region gets one `commit(List<Write>)`; regions commit in
   parallel.
3. `PumpRegionFile.commit` runs the four phases of §6.2:

| Phase | Action | Lock |
|---|---|---|
| 2a | first-fit allocate sectors from the bitmap; they are *occupied* but not referenced by the table | write lock, short |
| 2b | write the frames into the staged sectors, **Barrier 1** (`force`) | none |
| 3 | swap the in-memory table entries, snapshot the 33 sector header, write it at offset 0, **Barrier 2** | write lock for the swap only; `commitLock` for the whole phase |
| 4 | free the previous runs, truncate the file to the highest occupied sector | write lock, short |

If a phase fails, the staged sectors are released and (after phase 3 started) the table entries are
restored, so the in-memory state keeps matching the disk.

### Why a crash is safe

| Crash point | State on disk | After reopening |
|---|---|---|
| during 2b, before Barrier 1 | table untouched, some new sectors written | old table; the new sectors are not referenced, they are free again and a tail beyond the highest referenced sector is truncated on open (§6.3) |
| after Barrier 1, before the header reaches the disk | complete new frames, old table | same as above, old version |
| during the header write | old or new header sector-wise (spec assumption) | each entry resolves to a fully written frame, old or new |
| after Barrier 2, before phase 4 | new table, old runs still present | new version; the old runs are unreferenced and reclaimed |

`PumpRegionFileTest.crashBetweenDataAndHeaderLeavesTheOldVersion` and
`crashAfterHeaderBeforeReclaimLeavesTheNewVersion` reproduce the second and fourth rows from real
file images. As the specification itself notes, the header write being atomic per sector is an
assumption about the storage, not something this code can guarantee. The checksum of every chunk
makes a violation detectable instead of silent.

Reserved superblock bytes are written back exactly as found, so a file touched by a newer writer
keeps its extension flags.

## Concurrency design

`PumpRegionFile` uses a `StampedLock` for the table and the bitmap plus a separate `commitLock`.

**Optimistic reads.** A reader takes a stamp, looks the slot up, reads the frame with a positional
read and validates the stamp. The sectors of an entry can only be reclaimed and then reused by a
writer holding the write lock; any such writer invalidates the stamp, so a validated read provably
returned the bytes of the entry it looked up. If validation fails the read is repeated under the
read lock. Readers never write to shared lock state, which is why read throughput scales with
cores (see [PERFORMANCE.md](PERFORMANCE.md)). The optimistic path re-checks the entry's bounds
before allocating, because a torn read of `offset`/`length` can only produce two valid values but
must never be trusted to allocate.

**Writers do not block readers during I/O.** Data is written into staged sectors, which readers
cannot reach because no table entry points to them. Only the in-memory swap and the reclaim hold
the write lock; the flush barriers run without it. `commitLock` serializes the header writes so an
older table snapshot can never overwrite a newer one on disk.

**Region cache.** `PumpStorage` keeps open regions in a `ConcurrentHashMap`. A hit is `get` plus a
CAS increment of the slot's use count; `openLock` is only taken to open a region or evict. Eviction
(least recently used first, only regions with use count 0, retired by CAS to −1) happens when the
limit is exceeded. A last-use timestamp is written at most once per millisecond so hot regions do
not bounce a cache line.

**Parallel stages.** `Parallel.map` runs items on an executor with a semaphore bounding the number
in flight, returns results in input order and waits for all tasks before rethrowing the first
failure, so no work survives a failed call. Bulk calls hold the compressed batch in memory; use
batches of a region's size (≤1024 chunks) for conversions.

**Zstd contexts** are pooled in a capped, lock free queue (a `ThreadLocal` would create one native
context per virtual thread). A context that raised an error is discarded.

**Cross-process safety.** A writable region takes an exclusive `FileLock`; a second server opening
the same world fails with a clear message instead of corrupting it.

## Validation model

Faults are classified by how much they poison:

* **Container fatal (open fails):** bad signature or version, coordinates not matching the file
  name, truncated header, an entry pointing into the header, two entries sharing sectors.
* **Per chunk (that chunk's read fails, the region stays usable):** entry range outside the file,
  frame ≤ 12 bytes, oversized or inconsistent sizes, checksum mismatch, any `PCHK` violation. A
  damaged slot can be overwritten by a normal write.

All limits of chapter 7 are enforced before memory is committed (declared decompressed size,
section count, tick counts, block entity count; every length prefix is checked against the bytes
that remain).

## Minestom specifics

* Loading calls the instance's `ChunkSupplier`, fills the sections under the chunk write lock and
  places block entities with `setBlock`, exactly as `AnvilLoader` does, so handlers get `onPlace`.
* `supportsParallelLoading`/`supportsParallelSaving` are true; `loadChunk` never touches another
  chunk of the instance (a requirement of the `ChunkLoader` contract).
* Palette values are written/read through `Palette.getAll`/`setAll`/`fill`, the public API;
  `singleValue()` (internal API, also used by `AnvilLoader`) short-circuits uniform sections.

## Extending

* **A second payload version:** `PchkCodec` switches on the header version; add `PCHK` v2 beside it
  and a new model if it changes the layout.
* **Another server:** write a mapper `Chunk ⇄ PumpChunk` and a `ChunkLoader` equivalent; nothing
  below the model changes.
* **Storing extra data per chunk:** the custom data compound (§5.6) is preserved by every code
  path.
