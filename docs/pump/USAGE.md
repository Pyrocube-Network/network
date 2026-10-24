# Usage guide

## 1. Minestom loader

```java
PumpChunkLoader loader = new PumpChunkLoader(world, dimensionKey);                 // defaults
PumpChunkLoader custom = new PumpChunkLoader(world, dimensionKey, PumpLoaderOptions.defaults()
        .withStorage(PumpOptions.defaults().withCompressionLevel(6))
        .withDataVersionPolicy(DataVersionPolicy.REJECT)
        .withCorruptChunkPolicy(CorruptChunkPolicy.REGENERATE)
        .withMinimumStatus(ChunkStatus.FULL)
        .withHooks(myHooks));
```

`PumpChunkLoader.forRegionDirectory(dir)` uses a directory of `r.X.Z.pump` files directly instead
of the `dimensions/<ns>/<value>/region` layout.

### What a save does

`MinestomChunkMapper.toPump(chunk)` takes the chunk's write lock for the copy only (Minestom's lazy
light and heightmap evaluation mutate internal state) and produces an immutable `PumpChunk`:

| Chunk part | Stored as |
|---|---|
| block palette | §5.3.2 section modes (uniform / indexed / dense), global state ids |
| biome palette | §5.3.3 biome cells, ids of the instance's biome registry |
| sky and block light | §5.3.4 nibble arrays; `LIGHT_CORRECT` only if every section has both and none needs a recompute |
| world surface and motion blocking heightmaps | §5.2, omitted for dimensions taller than 511 blocks |
| blocks with a handler or NBT | block entities (§5.5); `id` is the handler key, else the block entity type, else the block name |
| chunk tags (`chunk.tagHandler()`) | custom data (§5.6); the `InhabitedTime` tag is lifted into the header field |
| status | always `Full` |

Compression, checksumming and the file commit happen after the lock is released.

### Loading

`loadChunk` reads, validates and decodes the chunk, then applies it to a chunk made by the
instance's chunk supplier. Problems are handled by policy:

| Situation | Behaviour |
|---|---|
| chunk absent | `null`, Minestom generates it |
| status below `minimumStatus` (default `FULL`) | `null`, the generator completes it |
| data version differs | `DataVersionPolicy.REJECT` (default) throws, `ACCEPT` loads anyway |
| chunk violates the format (checksum, indices, limits…) | `CorruptChunkPolicy.REGENERATE` (default) reports to Minestom's exception manager and returns `null`; `FAIL` throws and keeps the bytes for recovery |
| block state id unknown to this server | treated as corruption |
| I/O error | `UncheckedIOException` — never silently regenerated |
| biome id not in the registry | replaced by plains |

### Saving many chunks

`saveChunks(Collection<Chunk>)` (called by `Instance#saveChunksToStorage`) snapshots all chunks in
parallel, compresses them in parallel and commits each region once. `saveChunk(chunk)` pays two
flush barriers for a single chunk (about 3 000 chunks/s on the test machine against about
17 000 for the batched path), so prefer batches.

### Hooks

```java
PumpChunkHooks hooks = new PumpChunkHooks() {
    public void afterLoad(Chunk chunk, PumpChunk data) {
        data.blockTicks().forEach(t -> myTickScheduler.restore(chunk, t));
    }
    public PumpChunk beforeSave(Chunk chunk, PumpChunk data) {
        return data.toBuilder().blockTick(new ScheduledTick(x, y, z, delay, 0, blockId)).build();
    }
};
```

Hooks run on the loading or saving thread, outside the chunk locks, and must be thread-safe.

### Lifecycle

`loader.close()` closes the region files and must be called once, after the last save. With the
default strict durability everything committed earlier is already on stable storage.
PUMP defines only chunk storage: Minestom's instance-level `loadInstance`/`saveInstance` hooks are
not implemented, persist instance data yourself.

## 2. `PumpStorage`

The thread-safe chunk store behind the loader, usable on its own.

```java
PumpOptions options = PumpOptions.defaults()
        .withCompressionLevel(3)          // zstd level
        .withMaxOpenRegions(64)           // LRU of open file descriptors
        .withDurability(Durability.STRICT)
        .withParallelism(16)              // concurrent encode/decode/commit tasks
        .withExecutor(null)               // null = internal virtual-thread executor
        .withReadOnly(false);

try (PumpStorage s = PumpStorage.open(regionDirectory, options)) {
    s.write(chunk);                                   // one chunk, two flush barriers
    s.writeAll(chunks);                               // parallel, one commit per region
    PumpChunk c = s.read(x, z);                       // null if absent
    List<PumpChunk> cs = s.readAll(positions);        // parallel, null for absent
    s.delete(x, z);
    CompletableFuture<PumpChunk> f = s.readAsync(x, z);   // also writeAsync, writeAllAsync
    s.regions(); s.chunksIn(new RegionPos(0, 0));
    s.stats();                                        // counters, compression ratio
}
```

* `read` validates everything: frame size limits, Zstandard, XXH64, `PCHK` structure and the slot
  position. Failures are `PumpCorruptException` (`PumpLimitException` for resource bounds), the
  message names the chunk and region file.
* `PumpStorage` never loses unknown data: `PumpChunk` carries custom data, ticks and the third
  heightmap, so read → write round trips are lossless.
* `close()` must not race with running operations.

### Durability

| Mode | Behaviour |
|---|---|
| `STRICT` (default) | both flush barriers of §6.2 on every commit; the only conforming mode for a live world |
| `DEFERRED` | no barriers until `flush()`/`close()`; write ordering is unchanged but a crash can lose recent commits or leave a region unreadable. For restartable bulk jobs such as conversion. |

### The chunk model

`PumpChunk` is an immutable record of everything in a `PCHK` payload; use `PumpChunk.builder(x, z)`
or `chunk.toBuilder()`. Block states and biomes are `BlockStates` / `Biomes` (a single id or an
array in `Y, Z, X` order); the codec picks the compact on-disk mode. Heightmaps are `short[256]`
with element `x + z * 16`.

## 3. Anvil conversion

```java
AnvilTranscoder transcoder = new AnvilTranscoder(
        BiomeIds.of(instance.registries().biome()), UnknownBlockPolicy.FAIL);
WorldConverter converter = new WorldConverter(transcoder, PumpOptions.defaults());

ConversionReport report = converter.anvilToPump(anvilRegionDir, pumpRegionDir);
converter.pumpToAnvil(pumpRegionDir, anvilRegionDir);
```

Regions are processed one after another (memory stays proportional to one region); inside a region
reading, parsing, transcoding and compressing run in parallel. Failures of single chunks (unknown
block, malformed NBT, a chunk stored in the wrong slot) are collected in the `ConversionReport`
and do not stop the run; the exit code of the CLI is 1 if anything failed. PUMP output is written
with deferred durability and flushed once at the end: an interrupted conversion must be restarted.

Mapping (Appendix B) in short: `Name` + `Properties` ⇄ state id, biome names ⇄ ids through
`BiomeIds`, `BlockLight`/`SkyLight` copied, heightmaps repacked, block entities copied, everything
Anvil stores that PCHK has no field for goes to the custom data and is merged back on the way out.
Tick targets use `Block#id()` for block ticks and 0 empty / 1 flowing water / 2 water /
3 flowing lava / 4 lava for fluid ticks. Statuses `noise`, `surface`, `carvers` and
`liquid_carvers` all become `Terrain` (and `Terrain` returns as `noise`).

**Entity and point-of-interest regions.** Vanilla keeps entities in `entities/r.X.Z.mca` and villager
points of interest in `poi/r.X.Z.mca`, next to `region/`. PUMP has no storage for either, so the
converter carries them inside the chunk: the chunk's entry of each file is stored unchanged in its
custom data (§5.6) under `anvil:entities` and `anvil:poi`. `anvil-to-pump` looks for sibling
`entities/` and `poi/` directories of the source directory by itself, `pump-to-anvil` writes them
back next to the target directory and keeps the two keys out of the terrain chunks. Notes:

* Entries without content (no entities, no point of interest) are dropped, vanilla treats them as absent.
* An entry whose chunk is missing from the terrain region, and a sidecar region with no terrain region
  of the same coordinates, cannot be attached to anything. They are counted in `warnings`.
* An entry that cannot be parsed is reported as a failure (exit code 1) but its chunk is still converted.
* Minestom itself does not read entities or points of interest. A `PumpChunkLoader` keeps the two keys
  as chunk tags, so they survive a load and save cycle, but they only become useful again after
  converting back to Anvil for a vanilla server.
* The data version stored in each entry is kept as is, it is not upgraded.

Not supported: LZ4-compressed Anvil chunks, and writing Anvil chunks that exceed 255 sectors
(reading external `.mcc` files works).

CLI: `./gradlew :core:pump:convertWorld -Pmode=anvil-to-pump|pump-to-anvil -Psource=<dir> -Ptarget=<dir>
[-PextraArgs="--level=6 --threads=8 --unknown-blocks=air"]`.

## 4. Custom executors and virtual threads

The default executor is one virtual thread per task, which suits the blocking positional file I/O.
A custom executor is used for the parallel stages only; it is never shut down by the storage. The
`*Async` methods run the whole operation on their own virtual thread and wait for the stage tasks,
so a small fixed pool as stage executor cannot deadlock them.
