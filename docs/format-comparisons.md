# World format comparison: PUMP vs Linear vs Anvil

Produced by `FormatComparisonPerfTest` (`core/pump/src/test/java/net/pyrocube/core/pump/perf/`):

```
./gradlew :core:pump:perfTest --tests '*FormatComparisonPerfTest'
```

The raw report is written to `core/pump/build/perf/formats.md`. Numbers are medians of 3 to 5 runs after a
warm-up, from one machine (16 logical cores, JVM 25). They are meant to be read, not enforced: the test only
asserts that every chunk round-trips.

## 1. Container formats, full pipeline

One full region (1024 chunks) of synthetic overworld terrain (24 sections, caves, ores, water), the same
chunks in every format. Each number covers the whole path between `PumpChunk` and the file, so the NBT build
and parse that Anvil and Linear need is counted for them. Nothing is fsynced (Anvil does not, Linear is set not
to, PUMP is `DEFERRED`).

|  | PUMP (zstd) | Linear v2 grid 8 (zstd) | Linear v1 (zstd) | Anvil (zlib) |
| --- | --- | --- | --- | --- |
| file size (KiB) | 7,812 | 7,578 | 7,628 | 9,940 |
| write, 1 thread (ms) | 225 | 480 | 479 | 652 |
| write, 16 threads (ms) | 56 | 161 | 125 | 440 |
| read all, 1 thread (ms) | 106 | 333 | 323 | 319 |
| read all, 16 threads (ms) | 21 | 114 | 162 | 55 |
| cold single chunk: open + read one (ms) | 0.2 | 5.2 | 49.9 | 4.3 |

* PUMP is the fastest in every row; its cold single-chunk read is about 20x faster than Linear v2 and Anvil.
* Linear is about 3% smaller than PUMP, Anvil about 27% larger.
* Linear v1 compresses the region as one zstd stream, so reading one chunk inflates all of it (50 ms), and
  parallel reads do not help (162 ms on 16 threads is slower than one thread).
* Anvil's write parallelises only the NBT build; its zlib and file write stay single threaded.
* The Linear and Anvil cold rows read the whole file or chunk table first, so they are an upper bound, not a
  true random-access read.

## 2. Minestom `ChunkLoader` end to end

The same 1024 chunks through each format's own `ChunkLoader` with default options, so PUMP strict durability
and Linear fsync are on (unlike section 1). The terrain is simple (stone, dirt, grass), so file sizes are much
smaller than in section 1 and the two sections' sizes are not comparable. Anvil is Minestom's `AnvilLoader`.

| Loader | size (KiB) | save all (ms) | load all, 1 thread (ms) | load all, 16 threads (ms) | load one, cold (ms) |
| --- | --- | --- | --- | --- | --- |
| PUMP | 327 | 13.9 | 50.0 | 8.6 | 0.1 |
| Linear v2 | 120 | 19.4 | 45.5 | 9.3 | 0.1 |
| Linear v1 | 103 | 23.7 | 38.7 | 62.5 | 2.3 |
| Anvil (`AnvilLoader`) | 4,104 | 258.8 | 49.4 | 38.1 | 0.2 |

* Loading is close to a tie between PUMP and Linear v2 (single thread about 45 to 50 ms, 16 threads about 9 ms).
* Both are far ahead of Anvil on saving (about 14 to 19 ms against 259 ms) and on size, because Anvil pads each
  chunk to a 4 KiB sector (1024 chunks is 4,104 KiB).
* In this run Linear is smaller than PUMP (120 vs 327 KiB) on simple terrain.
* Linear v1 again scales backwards on parallel loads (62.5 ms vs 38.7 ms on one thread).

## Conclusions

* **Drop Anvil for anything we write at runtime.** It is the slowest to write (652 ms single thread in
  section 1, 259 ms vs 14 to 19 ms through the loader) and the largest (27% over PUMP, and about 12x over
  PUMP on small chunks because of 4 KiB sector padding). Keep it only as an import format; PUMP already
  has the converter.
* **PUMP is the best default.** It wins every container benchmark (writes 2x faster than Linear single
  threaded and about 3x on 16 threads, reads 3x faster single threaded and 5x on 16 threads), has
  near-instant random access (0.1 to 0.2 ms), and its loader is at least as fast as Linear's. Part of that
  lead is structural: PUMP reads and writes its own chunk model directly, while Linear stores vanilla NBT and
  pays an NBT build and parse on every chunk.
* **Linear v2 is the closer alternative.** Loader performance matches PUMP and it is slightly smaller
  (3% in section 1, much smaller on simple terrain in section 2). Choose it only if staying close to the
  vanilla NBT payload matters, for example to keep using existing Linear tooling.
* **Linear v1 should not be used for a live world.** Whole-region compression makes a single chunk read cost
  about 50 ms and parallel loading gets slower, not faster. v2's grid fixes both.
* **Size is a wash between the zstd formats.** The 3% difference in section 1 is within what real terrain
  will change; do not pick on size alone.
* **What these numbers do not settle.** Durability: PUMP is crash safe with strict flush barriers, and the
  fsync-on loader numbers (section 2) already include that cost, but nothing here tests recovery after a
  crash. Real-world terrain and the large-world, many-region case are also unmeasured. Re-run on a converted
  copy of a real world before committing to a format.

## Caveats

* Single machine, single run of the suite, synthetic terrain: treat differences under about 20% as noise.
* Real worlds have more varied chunks than the synthetic terrain, which shifts sizes and compression times.
* `AnvilLoader(Path)` is deprecated for removal in this Minestom version, the test still uses it for the
  baseline.
