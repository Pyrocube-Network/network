# core:pump — the PUMP world format for Minestom

A complete reader and writer of the **PUMP** region and chunk storage format
([specification 1.0.0](https://github.com/Pumpkin-MC/pump-spec/blob/master/SPEC.md)) for the
Minestom `2026.10.07-26.2` used by this network, with a drop-in `ChunkLoader`, multithreaded bulk
I/O, Anvil conversion in both directions, tests and benchmarks.

| | |
|---|---|
| Format | `.pump` region files (`r.X.Z.pump`), `PCHK` v1 chunk payloads, Zstandard frames with XXH64 |
| Minestom | `PumpChunkLoader` — set it on an `InstanceContainer`, parallel load and save |
| Threading | lock-free optimistic reads, parallel compression, one commit per region, bounded open files |
| Crash safety | the four phase copy-on-write commit of §6.2 with both flush barriers |
| Conversion | Anvil `.mca` ⇄ PUMP, multithreaded, Gradle task and API |
| Tests | 125 unit and integration tests, plus a tagged performance suite |

## Quick start

```java
// Server setup: one loader per dimension, regions under
// <world>/dimensions/<namespace>/<value>/region/r.X.Z.pump
PumpChunkLoader loader = new PumpChunkLoader(Path.of("world"), DimensionType.OVERWORLD.key());
InstanceContainer instance = MinecraftServer.getInstanceManager()
        .createInstanceContainer(DimensionType.OVERWORLD, loader);

// Persist: Instance#saveChunksToStorage() batches every loaded chunk
// (snapshot, compress and commit run in parallel, one commit per region).
instance.saveChunksToStorage().join();

// On shutdown release the region files.
MinecraftServer.getSchedulerManager().buildShutdownTask(() -> {
    try { loader.close(); } catch (PumpException e) { /* log */ }
});
```

Convert an existing Anvil world (needs no running server, it uses Minestom's registries):

```
./gradlew :core:pump:convertWorld -Pmode=anvil-to-pump \
    -Psource=world/region -Ptarget=world/dimensions/minecraft/overworld/region
```

Use the format without Minestom (the chunk model has no server dependencies):

```java
try (PumpStorage storage = PumpStorage.open(Path.of("regions"))) {
    storage.writeAll(chunks);                       // parallel encode, one commit per region
    PumpChunk chunk = storage.read(12, -3);         // null when absent, fully validated
    List<PumpChunk> many = storage.readAll(positions);
}
```

## Build and run

The module is `:core:pump` and is wired into the `network.minestom-server` convention plugin, so
`lobby`, `skyblock` and `mmorpg` get it (and `zstd-jni`, whose native library is bundled in their
shadow jars) automatically.

| Task | What it does |
|---|---|
| `./gradlew :core:pump:test` | all unit and integration tests (performance tests excluded) |
| `./gradlew :core:pump:perfTest` | performance suite, report in `core/pump/build/perf/report.md` |
| `./gradlew :core:pump:convertWorld -Pmode=… -Psource=… -Ptarget=…` | Anvil ⇄ PUMP conversion |

`zstd-jni` loads a native library through JNI, which the JDK 24+ warns about unless the JVM is
started with `--enable-native-access=ALL-UNNAMED`. The Gradle tasks above pass it; add it to your
server start script.

## Documentation

* [Usage guide](../../docs/pump/USAGE.md) — loader options, policies, hooks, direct storage API, conversion.
* [Architecture](../../docs/pump/ARCHITECTURE.md) — layers, locking protocol, commit protocol and why it is
  crash safe, memory behaviour.
* [Specification compliance](../../docs/pump/SPEC_COMPLIANCE.md) — clause by clause mapping to code and tests,
  and every place where the specification is ambiguous and which reading was implemented.
* [Performance](../../docs/pump/PERFORMANCE.md) — methodology, results, caveats, tuning.

## Things worth knowing before you rely on it

* **Block state and biome ids are version bound.** They are ids of the running Minestom registries;
  a chunk records the data version it was written with and the loader refuses chunks of another
  version by default (`DataVersionPolicy.REJECT`). Convert worlds when you upgrade Minestom.
* **Minestom has no scheduled ticks, generation status or no-leaves heightmap.** Chunks are saved
  as `Full` without them; `PumpChunkHooks` lets you supply or consume them. Data PUMP stores that
  Minestom cannot model is still preserved when it is read and rewritten through `PumpStorage` or
  the converters.
* **Heightmap bit layout is an interpretation.** The specification text and its "37 words" remark
  can be read two ways; see [SPEC_COMPLIANCE.md](../../docs/pump/SPEC_COMPLIANCE.md#heightmap-layout). If you
  exchange files with another implementation, test that first.
* **One writer per world.** Region files are locked against a second writer process.
* The format is not designed for dimensions taller than 511 blocks (9 bit heightmaps); Minestom
  heightmaps are then simply not stored.
