# Specification compliance

Implemented against
[PUMP SPEC 1.0.0](https://github.com/Pumpkin-MC/pump-spec/blob/master/SPEC.md) (ratified standard),
reader and writer profile. This page maps each clause to code and tests and lists every place where
the specification is silent or ambiguous together with the reading that was implemented.

## Clause matrix

| § | Requirement | Implementation | Verified by |
|---|---|---|---|
| 2.1 | big-endian primitives | `ByteReader`, `ByteWriter` (VarHandles) | `ByteIoTest`, `GoldenFormatTest` |
| 2.2, 2.3.1 | chunk / region maths, index `(z&31)*32+(x&31)`, floored division for negatives | `PumpFormat` | `PumpFormatTest` |
| 2.3.2, 2.3.3 | `Y,Z,X` block and biome indices | `PumpFormat.blockIndex/biomeIndex` | `PumpFormatTest`, codec round trips |
| 3.1 | `r.X.Z.pump` | `PumpFormat.regionFileName` | `PumpRegionFileTest`, `GoldenFormatTest` |
| 3.2 | 256 byte sectors, zero filled slack | `ChunkFrame.frame`, `EncodedChunk` | `ChunkFrameTest.headerAndPadding`, `PumpRegionFileTest.tableEntryAndFrame…` |
| 3.3 | 33 sector header | `PumpRegionFile` | `PumpRegionFileTest.newFileHasExactlyTheHeader` |
| 3.3.1 | signature, region x/z must match the name, reserved zero on creation and ignored on read | `PumpRegionFile.load`, `initialize` | `rejectsBrokenContainers`, `reservedSuperblockBytesSurviveRewrites` |
| 3.3.2 | entry rules: absent = 0, allocated ≥ 33 and inside the file, no overlaps | `PumpRegionFile.load`, `commit` | `rejectsBrokenContainers`, `entriesOutsideTheFileFailOnlyWhenRead`, `manyRandomOperations…` |
| 3.4 | bitmap, first fit, append, truncate trailing sectors | `PumpRegionFile.allocate`, `truncateTail` | `appendsThenReusesTheFirstFittingGap`, `fileIsTruncatedWhen…` |
| 4.1 | 12 byte frame header, ≤ 64 MiB, `byte_length` > 12, exact decompressed size, checksum | `ChunkFrame` | `ChunkFrameTest` (all rejection cases) |
| 4.2 | Zstandard (RFC 8878) frame | `ZstdCodec` (zstd-jni) | `ZstdCodecTest`, `GoldenFormatTest` (frame magic) |
| 4.3 | XXH64, seed 0, abort on mismatch | `XXH64`, `ChunkFrame.decode` | `XXH64Test` (published vectors and a Zstandard checksum oracle) |
| 5.1 | 31 byte header, magic, version 1, position match, status 0–9, reserved flags zero | `PchkCodec` | `headerLayoutMatchesSpecOffsets`, `rejectsBadHeader`, `rejectsPositionMismatch`, `GoldenFormatTest` |
| 5.2 | heightmaps in flag order, 37 words | `PchkCodec`, `Heightmaps` | `HeightmapsTest` (see [layout](#heightmap-layout)) |
| 5.3 | ≤ 256 sections, ascending Y from `min_section_y` | `PchkCodec` | `maxSectionsRoundTrips`, `rejectsTooManySections` |
| 5.3.2 | block modes 0/1/2, palette 1–256, indices < length, counts sum 4096 | `PchkCodec.writeBlocks/readBlocks` | `sectionEncodingsPickTheCompactMode`, `exactly256DistinctStatesStayIndexed`, `rejectsBadBlockPalettes` |
| 5.3.3 | biome modes 0/1, palette 1–64, 64 direct ids | `PchkCodec.writeBiomes/readBiomes` | `rejectsBadBiomePalette`, round trips |
| 5.3.4 | light flags, 2048 byte nibble arrays, absent = zero | `PchkCodec.writeLight` | round trips |
| 5.4 | tick arrays, 16 byte records, ≤ 65 536, priority −3..3 | `PchkCodec` | `rejectsTooManyTicksAndBlockEntities`, `rejectsBadTickPriority`, `GoldenFormatTest` |
| 5.5 | length prefixed nameless compounds with `x`,`y`,`z` ints and `id` string, ≤ 65 536 | `PchkCodec`, `BlockEntities`, `NbtIO` | `rejectsInvalidBlockEntities`, `nbtIsWrittenWithoutRootName` |
| 5.6 | custom data preserved across transcoding and writes | `PumpChunk.customData`, `AnvilTranscoder` | `roundTripEveryFeature`, `nonStandardTagsLandInTheCustomData` |
| 6.1 | read sequence | `PumpStorage.read` → `PumpRegionFile.readFrame` → `ChunkFrame.decode` → `PchkCodec.decode` | `PumpStorageTest` |
| 6.2 | four phase commit with two barriers | `PumpRegionFile.commit`, `Durability.STRICT` | `PumpRegionFileTest` crash tests, concurrency test |
| 6.3 | crash consistency, orphans reclaimed on open | `PumpRegionFile.load` | `crashBetweenDataAndHeaderLeavesTheOldVersion` |
| 7.1 | resource bounds enforced before allocation | `ChunkFrame.decode`, `PchkCodec`, `PumpLimitException` | `rejectsOversizedDeclarationBeforeDecompressing`, `rejectsTooMany…` |
| 7.2 | `byte_length` ≤ 12 invalid, sector < 33 fatal, range inside the file | `PumpRegionFile.load` | `rejectsBrokenContainers`, `entriesOutsideTheFileFailOnlyWhenRead` |
| App. B | Anvil ⇄ PUMP: palettes, biomes, light, auxiliary data | `AnvilTranscoder`, `WorldConverter` | `AnvilTranscoderTest`, `WorldConverterTest`, `PumpConvertToolTest` |

Test classes live in `../../core/pump/src/test/java/net/pyrocube/core/pump`.

## Interpretations

The specification leaves some points open. These are the readings implemented, ordered by how
likely they are to matter when exchanging files with another implementation.

### Heightmap layout

§5.2 says each heightmap is 37 `i64` words holding 256 nine-bit values "packed continuously",
that this is 2304 bits = 36 words and that the array is "padded to 37 entries to maintain direct
parity with Minecraft's heightmap structures". Minecraft's own layout stores seven 9-bit values per
long without straddling and also needs 37 longs, so "37" could come from either layout.

**Implemented:** the normative text. Element `i` occupies bits `[9i, 9i+9)` of the little-endian
bit stream formed by the words, the 37th word is zero padding and is ignored on read
(`Heightmaps.pack/unpack`). The Anvil transcoder and the Minestom mapper convert to and from
Minecraft's layout (`Heightmaps.packVanilla/unpackVanilla`).

If a peer implementation copies Minecraft's longs verbatim, its heightmaps will decode to wrong
values here (chunks still load, but the heightmaps Minestom then uses would be wrong), so verify
this first when interoperating. Switching layouts is a change confined to `Heightmaps.pack/unpack`.

Related details the text does not give: element order is `x + z * 16`; the value is the height of
the highest matching block above the world's bottom plus one (Minecraft's convention).

### Nameless NBT

"Unnamed `TAG_Compound`" is implemented as the nameless root (tag id 10 followed directly by the
entries), the encoding of Minecraft's network protocol since 1.20.2. The
reader also accepts a root with an empty name (`0A 00 00 …`), which is unambiguous because the
first byte after `0A` is a non-zero tag id otherwise.

### Biome palettes

For mode 1 the structure lists `palette` entries `(id, count)` and then `biomes: [u8; 64]` that
hold *direct* biome ids (unlike block mode 1, whose bytes index the palette). The reader validates
only `palette_len ∈ [1, 64]` and takes the 64 ids as the truth; the palette is a summary and the
specification states no validity rule for it. The writer emits a consistent palette (first
occurrence order, counts summing to 64).

### Section mode choice

The writer is canonical: mode 0 if all values are equal, mode 1 for 2–256 distinct block states,
mode 2 beyond. The reader accepts any valid mode, including mode 1 with a single entry.

### Block counts

The only validity rules for `block_count` are "sum to 4096" and "indices < `palette_len`"; the
reader enforces those and does not recount frequencies. The writer computes exact counts.

### Absent entries and damaged slots

§3.3.2 says an absent entry has both fields zero; §6.1 step 4 says the chunk is absent if *either*
is zero. The reader follows §6.1. At open:

* a non-zero `sector_offset` below 33 is fatal (§7.2),
* entries whose range leaves the file or whose length is ≤ 12 are marked damaged: reading them
  fails with a `PumpCorruptException`, the rest of the region keeps working, and writing the slot
  replaces the entry,
* overlapping runs are fatal for the whole region. The specification only forbids writers from
  producing them; refusing to open is a deliberate safety choice, since writing into overlapped
  sectors would destroy another chunk.

### Light flags

Bits other than `0x01` and `0x02` of a section's `light_flags` are undefined and are rejected.

### Scheduled tick targets

`target_id` is "a numeric block or fluid id" without a registry. Implemented: `Block#id()`
(registry id, not state id) for block ticks; 0 empty, 1 flowing water, 2 water, 3 flowing lava,
4 lava for fluid ticks. This only matters to the Anvil transcoder; the format layer treats the
value as opaque.

### Other

* `inhabited_time` is a `u64` stored in a Java `long`; values ≥ 2⁶³ round trip bit-exactly.
* Block state and biome ids, and `data_version`, are opaque to the format layer. The Minestom
  loader enforces the data version (`DataVersionPolicy`).
* Declared and Zstandard-embedded decompressed sizes are cross-checked before decompression, an
  extra guard against crafted frames.
* The superblock's reserved bytes are preserved on rewrite instead of being zeroed, which keeps the
  forward compatibility §3.3.1 asks readers to allow.

## Beyond the specification

* Single-writer file lock, `Durability.DEFERRED` for restartable bulk jobs (non-conforming for live
  worlds, documented as such).
* Multithreaded storage, batching, asynchronous API, open file LRU.
* The Minestom loader, hooks and policies; the Anvil converters and CLI.
* Anvil entity and point-of-interest regions. The specification defines no storage for them, so each
  chunk's entry is kept in its custom data (§5.6, which requires readers to preserve it) under the
  keys `anvil:entities` and `anvil:poi`, and split back into `entities/` and `poi/` region files by
  `pump-to-anvil`. Other implementations ignore the keys but must preserve them.

## Not implemented

* LZ4 Anvil chunks, writing Anvil chunks over 255 sectors.
* Instance-level persistence (`level.dat` equivalent): the specification does not define one.
* Heightmaps for dimensions taller than 511 blocks (the format's elements are 9 bits).
