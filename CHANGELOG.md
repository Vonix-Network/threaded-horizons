# Changelog

## [1.0.7] - 2026-09-14

RAM and allocation-churn successor for the controlled Forge 1.18.2 candidate. This version is test-only and does not claim production readiness, universal compatibility, measured RAM reduction, or performance improvement.

### Changed

- Chunk save tracking no longer retains a `ConcurrentLinkedQueue` of store futures or walks it every tick. In-flight saves use an atomic counter; `processUnloads` / `flushWorker` wait for idle instead of allocating `CompletableFuture.allOf` nodes. The dirty-generation contract is unchanged: `unsaved` is not cleared until the store future completes, and a newer mutation still wins.
- `replaceImpl` storage no longer deep-copies an already-isolated snapshot during serialize. The public `store` API still copies so callers can mutate the tag they passed. The `ChunkMap.save` path transfers ownership of the freshly written tag when the optimized storage implementation is active.
- Async serialization lighting snapshots copy a `DataLayer` only when `ChunkSerializer.write` first reads that section, and they no longer retain a `SectionPos`-keyed map of every light section. Block-entity snapshot construction no longer uses stream collectors or a diagnostic `HashSet`.
- Combined-lock retries reuse a per-lock token array instead of allocating `LockEntry` / extra `ChunkPos` collections on every `tryAcquire`. Default pending/admitted lock ceilings follow the worldgen pipeline limit (still fail-closed, still capped at 65,536).
- Dirty-generation counters for live `ChunkAccess` instances live on the chunk via mixin. The striped `WeakHashMap` remains only as a fallback for non-chunk keys (unit tests). Block placement no longer contends on 64 global monitors.
- No-tick pending-ticket sorting is skipped when the whole pending set fits in the per-tick budget. View-distance rebuilds iterate source chunks without a `toLongArray()` copy.
- Unload `toDrop` tracking uses an unboxed `LongOpenHashSet` instead of `HashSet<Long>`.
- Reduced-task-radius `System.out.printf` is gated behind `network.vonix.threadedhorizons.debug.logTaskRadius`.

### Intentionally unchanged

- `optimizeAsyncChunkRequest` remains retired. `useGlobalBiomeCache` remains false. The ModernFix structure-warmup guard and Lithium-family `mid_tick_chunk_tasks` skip remain. YUNG's Bridges still forces FEATURES single-threading.
- IO worker `chunkDataCacheLimit` default stays 6144. Lowering it would change I/O coalescing versus 1.0.6; the existing config keys remain the owner-tunable bound.
- The global save barrier in `processUnloads` / `flushWorker` is kept. Narrowing it to unloaded positions only would require a retained per-position future set, which this candidate is trying to remove.

### Verification boundary

- Fresh Java 17 command: `./gradlew clean test build --no-daemon`; 217 tests passed, 0 failures, 0 errors, 0 skipped, and the completion scan passed.
- Current local artifact: `build/libs/threaded-horizons-mc1.18.2-1.0.7.jar`, 1,071,912 bytes, SHA-256 `e98db0a079b1dc209cbc9003889b5765748730d29551de41f0302f80781ff8c0`.
- Allocation removals are structural (copies, queues, per-tick walks, maps). They are not a measured live-server RAM result.
- The earlier 1.0.7 review packet predates the packaged-description correction and is stale for this successor; this source snapshot is not accepted or released.
- Production deployment, restart, player-path validation, long-duration RAM testing, MSPT/chunk-generation measurement, and performance claims remain outside this candidate.

## [1.0.6] - 2026-09-13

Lighting compatibility repair successor for the controlled Forge 1.18.2 candidate. This version is test-only and does not claim production readiness, universal compatibility, RAM-leak resolution, or performance improvement.

### Fixed

- The lighting `@ModifyArg` callback now matches the erased `Object` argument exposed by `ProcessorMailbox.tell(Object)` on Forge 40.3.12, preserving non-`Runnable` values and wrapping only the expected runnable callback.
- The lighting executor helper is private so Mixin does not copy an implementation helper into the target class and reject the transformed class.
- Added regression coverage for the erased callback descriptor and executor-helper visibility.

### Verification boundary

- Exact disposable Forge 40.3.12 startup reached `Done`; no Mixin fatal was observed during the bounded run.
- Production deployment, restart, player-path validation, long-duration RAM testing, MSPT/chunk-generation measurement, and performance claims remain outside this candidate.

## [1.0.5] - 2026-09-09

Standalone Forge 1.18.2 C2ME-replacement candidate. Preserves the 1.0.4 memory-retention and YUNG's Bridges work, the 1.0.3 Radium Lithium-family mixin skip, the 1.0.2 ModernFix constructor-warmup skip, and the 1.0.1 Canary mid-tick deadlock skip.

### Changed

- Product identity now states that Threaded Horizons is a standalone Forge implementation intended to replace C2ME in the supported Minecraft 1.18.2 / Forge 40.3.12 lane. Mod ID remains `threadedhorizons`. No C2ME artifact is bundled.
- Declared Forge dependency range is `[40.3.12,41)`.
- Startup rejects a loaded `c2me` mod ID because overlapping mixins are not a supported combination.
- Replacement audit documents every relevant C2ME 1.18.2 family: implemented families stay in source/tests; unsafe or unavailable families stay excluded with evidence.

### Intentionally excluded (not revived)

- `optimizeAsyncChunkRequest` remains retired. The inherited `CFUtil.join` / `mainThreadProcessor` path deadlocked `/tp`. No nonblocking replacement that avoids a server-thread join is proven here.
- Global biome cache remains disabled, matching upstream C2ME 1.18.2 (`DO NOT USE in 1.18`, empty `populateBiomes` mixin). Sampler identity is not world identity.
- Java incubator-vector acceleration remains absent on this Java 17 Forge toolchain.
- Fabric companion mixins (BetterEnd, BetterNether, Charm, Terra, The Bumblezone) remain Forge-inapplicable.

### Unchanged / unresolved

- Radium, Canary, and Lithium still share the CompoundTag mixin-skip and mid-tick chunk-access skip from 1.0.3 / 1.0.1.
- ModernFix still disables constructor-time asynchronous structure warmup (1.0.2).
- YUNG's Bridges still forces FEATURES single-threading (1.0.4).
- The Spark live-viewer protobuf / EventBus failure remains an unresolved classification. This candidate does not claim Threaded Horizons causes it and adds no Spark-specific code.
- This is not a 1:1 C2ME parity claim, RAM-leak resolution, or production authorization.

This is a controlled testing candidate. It supersedes 1.0.4 and requires fresh packaged runtime verification.

## [1.0.4] - 2026-09-09

Memory-retention and YUNG's Bridges compatibility successor. Preserves the 1.0.3 Radium Lithium-family mixin skip and the 1.0.2 ModernFix constructor-warmup skip.

### Fixed

- Worker ThreadLocal caches no longer preallocate oversized pools or grow without eviction. The feature-context pool is lazy and capacity-bounded (64 per worker). Ore `BitSet` reuse is LRU-capped. The thread-local biome cache is capped at 1024 entries with recency-aware eviction.
- ForkJoin worldgen workers clear managed ThreadLocal caches on termination. Dedicated-server post-shutdown also drops the shutting-down thread's managed ThreadLocals after executor drain.
- IO worker pending-NBT cache now honors configured `chunkDataCacheSoftLimit` / `chunkDataCacheLimit` through shared bound helpers. Explicit owner values inside the safe range are kept. Oversized persisted values are clamped (soft max 4096, hard max 8192) so legacy files cannot silently retain unbounded chunk NBT.
- Config version 5 migrates those oversized IO cache keys while leaving in-range owner tuning unchanged.
- When YUNG's Bridges (`yungsbridges`) is loaded, FEATURES generation is forced single-threaded because that mod writes `setBlock` into chunks other than the current generation chunk on Threaded Horizons workers. Other worldgen statuses stay parallel. Unrelated worldgen is not disabled.

### Unchanged / unresolved

- Radium, Canary, and Lithium still share the CompoundTag mixin-skip and mid-tick chunk-access skip from 1.0.3 / 1.0.1.
- ModernFix still disables constructor-time asynchronous structure warmup (1.0.2).
- The Spark live-viewer protobuf / EventBus failure remains an unresolved classification. This candidate does not claim Threaded Horizons causes it and adds no Spark-specific code.
- C2ME is not in the target acceptance pack and is not claimed or runtime-tested here.

This is a controlled testing candidate. It supersedes 1.0.3 and requires fresh packaged runtime verification. It does not claim universal compatibility or authorize production deployment.

## [1.0.3] - 2026-09-08

Compatibility successor for Forge Lithium-family performance mods.

### Fixed

- Prevented a startup `MixinTransformerError` when Radium 0.7.10 is present.
- Threaded Horizons now recognizes Radium as a Lithium-family implementation and yields the overlapping `CompoundTag` allocation redirects to Radium.
- Preserved the existing Lithium/Canary compatibility behavior for the same mixin family and mid-tick chunk-access path.
- Added regression coverage for Radium family detection and the shared mixin skip boundary.

This is a new controlled testing candidate. It supersedes 1.0.2 for packs containing Radium and requires fresh packaged runtime verification.

## [1.0.2] - 2026-09-07

Compatibility successor for the Minecraft 1.18.2 Forge 40.3.11 pack lane.

### Fixed

- Skip Threaded Horizons' optional constructor-time asynchronous structure warmup when ModernFix is loaded. ModernFix initializes its `StrongholdLocationCache` later in the server lifecycle; the previous warmup could race that initialization and emit a `NullPointerException` before falling back to on-demand generation.
- Preserve on-demand structure generation and add a regression test for the ModernFix compatibility boundary.

This is a candidate for controlled pack verification. It does not claim universal compatibility or authorize production deployment.

## [1.0.1] - 2026-08-23

Testing fix for a repeated 60-second Forge watchdog hang with Canary and Threaded Horizons enabled.

### Fixed

- Disable Threaded Horizons mid-tick chunk-task mixins when the Canary/Lithium-family chunk-access integration is loaded. Canary schedules a blocking `getChunkOffThread` join on the main-thread processor; polling that task from inside the server tick can execute the join on the server thread and deadlock the tick.
- Added a regression test covering both compatible and skipped mixin profiles.

This is a controlled testing artifact only. It does not authorize deployment to the Isle of Berk server or claim a live-server fix.

## [1.0.0] - 2026-08-22

Public release for controlled testing on Minecraft **1.18.2** / Forge **40.3.11** / Java **17**, promoted from the alpha.8 repair candidate after a fresh versioned rebuild. This release preserves the tested Forge SRG worldgen callback correction and the alpha.7 Desert Temple correction.

### Included

- Forge SRG `CrashReport.m_127521_` worldgen exception-cleanup target.
- Desert Temple chest-flag redirect corrected for the exact Forge bytecode.
- The existing worldgen, chunk I/O, scheduling, lighting, failsafe, and compatibility scope from alpha.8.

### Release boundary

- This is a public GitHub release for controlled testing, not a production deployment.
- A matched disposable chunk-generation comparison measured mean debug TPS of 8.95 with Threaded Horizons and 12.62 without it across two paired runs. This is workload-specific evidence, not a universal performance claim.
- No zero-data-loss, 1:1 C2ME-parity, retained-world, client/multiplayer, or universal modpack-compatibility claim is made.
- Back up worlds before use and do not enable Threaded Horizons in production without separate administrator authorization.

## [0.1.0-alpha.8] - 2026-08-21

Successor test candidate fixing the second packaged Forge startup failure found when `threadedWorldGen.enabled=true`. The worldgen holder exception-cleanup callback searched the production SRG `ChunkMap` bytecode for the official `CrashReport.forThrowable` name with remapping disabled; Forge's runtime method is `CrashReport.m_127521_`, so the callback group matched zero invocations and aborted server startup. The SRG injector now targets `m_127521_`, with a source contract test preventing regression.

This successor preserves the alpha.7 Desert Temple `fuzz=14` fix and must be treated as a fresh candidate. It does not claim client/server, multiplayer, retained-world, performance, or production proof.

### Verification boundary

- The mandatory disposable runtime gate must set `config/threadedhorizons.toml` `[threadedWorldGen] enabled = "true"`; a default-disabled boot does not exercise this fix.
- Parent clean tests, build, artifact inspection, and enabled-worldgen disposable packaged boot must pass for this alpha.8 artifact.
- Independent read-only review is required against the frozen alpha.8 pin.
- Production remains Threaded Horizons-disabled and untouched.

## [0.1.0-alpha.7] - 2026-08-21

Successor test candidate fixing the packaged Forge startup crash in the Desert Temple chest-flag mixin. The `postProcess` setter stores the result of `createChest(...)` into `hasPlacedChest`; Mixin's default array-access search window was too short to reach that `BASTORE`, so the required redirect matched zero targets and aborted startup. The redirect now uses the narrow `fuzz=14` window required by the exact Forge bytecode, with an ASM-backed boundary regression test.

This candidate still requires a fresh packaged runtime gate. It does not claim client/server, multiplayer, retained-world, or production proof.

### Verification boundary

- Parent clean tests, build, artifact inspection, and disposable packaged boot must be rerun for this alpha.7 artifact.
- Independent read-only review is required against the frozen alpha.7 pin.
- Production remains Threaded Horizons-disabled and untouched.

## [0.1.0-alpha.6] - 2026-08-21

Successor testing candidate after the accepted alpha.5 static repair scope. This version bumps the artifact identity for the next disposable validation round; it does not claim packaged runtime, multiplayer, or production proof.

### Included repair scope

- Scheduler admission now distinguishes queued, waiting, active, and terminal work.
- Obsolete queued generation is cancelled without interrupting active world mutation.
- Holder-associated world-generation cancellation state is captured and cleared on normal and exception paths.
- Scheduler shutdown and replacement complete queued work exceptionally and guard token release.

### Verification boundary

- Parent compilation, focused tests, full suite, and build evidence must be rerun for this alpha.6 artifact.
- Independent read-only review is required against the frozen alpha.6 pin.
- Production remains Threaded Horizons-disabled and untouched.

## [0.1.0-alpha.5] - 2026-08-19

Successor test candidate for the Threaded Horizons 1.18.2/Forge 40.3.11 repair wave. This version supersedes the previously existing local alpha.4 test artifact; it contains the same SOL-reviewed repair scope with corrected successor identity.

All notable Threaded Horizons releases for Minecraft **1.18.2** / Forge **40.3.11** / Java **17**.

This repository includes the historical prerelease series. Those versions were unverified candidates and should not be used in place of the `1.0.0` release.

## [0.1.0-alpha.4] - 2026-08-18

Pin `th-1182-forge-code-review-candidate-pin-027` is frozen after this rebuild. Sol-026 (`openai/gpt-5.6-sol`, read-only) independently confirmed the live alpha.3 YUNG crash as **Case A** and rejected fail-soft as a patch. This successor applies that P0. **Private unverified prerelease only. Not pack-tested. Not a live install. Not production-ready.**

### Diagnosis (Sol-026)

- YUNG Beardifier during NOISE asks `StructureFeatureManager` for referenced `STRUCTURE_STARTS` with `create=true`.
- Vanilla NOISE cache is ±8. Reduced lock radius is write/lock ownership only (NOISE reduced radius 2).
- Alpha.3 `MixinChunkRegion.hasChunk` returned false for in-cache starts at Chebyshev 3–8. That lie produced `We are asking a region for a chunk out of bound | -6188 18741`.
- Filtering YUNG starts, returning air/empty starts, skipping Beardifier, or turning `reduceLockRadius` off are rejected workarounds.

### P0 applied

- `MixinChunkRegion`: restore vanilla `hasChunk`/`getChunk`. Reduced corners stay for diagnostics only.
- IoB/VL helpers fail closed on a missing chunk. No uncached-noise biome, no `false` sky/water substitute.
- Null structure processor list throws. Null `ConfiguredFeature` throws. Graal retry is only the known stream-closed message.
- Tests: `MixinChunkRegionReadContractTest`, `WorldGenRegionReadVisibilityTest`, `FailClosedSubstituteContractTest`.

### Still not workaround-free (Sol-026 leftovers)

- Stronghold / nether piece-weight state is not generation-local.
- `MixinWeightedList` / `MixinOrder` RNG semantics are unproven.
- Quark `BackpackMenu` target miss is a pack-mod mixin warning, not a Threaded Horizons mixin. Live `a0b7f085` currently has alpha.3 disabled (`.abk`).
- Live pack boot, `/tp`, FEATURES, retained-world, and timing are unproven.

| Version | Published | Tag | Artifact | Bytes | SHA-256 |
| --- | --- | --- | --- | --- | --- |
| 0.1.0-alpha.4 | 2026-08-18 | `0.1.0-alpha.4-unverified` | `threaded-horizons-mc1.18.2-0.1.0-alpha.4.jar` | 1034856 | `fcff5459fafaa18c4dc8ce8f11c7ec0cf1e75287ea289242555a4fce6162210e` |
| 0.1.0-alpha.3 | 2026-08-18 | `0.1.0-alpha.3-unverified` | `threaded-horizons-mc1.18.2-0.1.0-alpha.3.jar` | 1034505 | `96c7d6c4d15eec6d57e4387a0693f73c4c63e1286bb4b94680f42036714844c1` |
| 0.1.0-alpha.2 | 2026-08-18 | `0.1.0-alpha.2-unverified` | `threaded-horizons-mc1.18.2-0.1.0-alpha.2.jar` | 1027757 | `7e40cc7a811431da75a401f7665949bcf77abbede6432c783a13b5d94788245e` |
| 0.1.0-alpha.1 | 2026-08-18 | `0.1.0-alpha.1-unverified` | `threaded-horizons-mc1.18.2-0.1.0-alpha.1.jar` | 1029466 | `c60f78fb499ef750f799742143dddbe06a35a2572628ac5f03a9d7a76f4e4a9a` |

## [0.1.0-alpha.3] - 2026-08-18

Pin `th-1182-forge-code-review-candidate-pin-025`. Sol-025 accepted this exact **code candidate** only. Supersedes pin-024. Pin-021 must not be used.

This is the first build that keeps threaded world generation **and** reduced lock radius on while adding Threaded Horizons-owned Isle of Berk / Variant Loader support.

### Added

- `IsleOfBerkWorldAccess`: fail-soft helper for biome, sky, and chunk-presence probes during threaded FEATURES.
- Optional mixin config `threadedhorizons-compat.iobvariantloader.mixins.json` (priority 1100):
  - `MixinSurfaceRestriction` — Variant Loader surface / `canSeeSky` probes fail soft when the neighbor chunk is not loaded.
  - `MixinDragonVariantSpawnerUtil` — Variant Loader biome / variant sampling fails soft instead of racing an unlocked chunk.
- Optional mixin config `threadedhorizons-compat.isleofberk.mixins.json` (priority 1100):
  - `MixinADragonBaseIsWaterBelow` — IoB `isWaterBelow` skips an unloaded worker-thread chunk instead of assuming a `Worker-Main` thread name.
- `ThreadedHorizonsCompatibilityModule` loads those configs only when Variant Loader `>=2.7.0` and the IoB compatibility profile are present. ARR IoB bytecode is not packaged.
- Config updater step `3 → 4` restores `threadedWorldGen.reduceLockRadius = true` after the support mixins exist.
- Tests: `ConfigVersion4LockRadiusRestoreTest`, `IsleOfBerkWorldAccessTest`, `CompatMixinRegistrationTest`.

### Changed

- `MixinChunkRegion` now fail-softs unavailable `getChunk(...)` / `hasChunk` outside the reduced lock window instead of only recording a stack.
- `ThreadedHorizonsConfig` current schema is version **4**. `reduceLockRadius` defaults to `true`.
- Docs: `docs/compatibility.md`, `docs/configuration.md`, `docs/feature-ledger.md`, `docs/limitations.md`, `README.md`.

### Fixed

- Alpha.2's `reduceLockRadius = false` workaround is no longer the intended live setting. A leftover version-3 config is rewritten on first alpha.3 boot; no manual edit is required.

### Unchanged on purpose

- Threaded world generation stays on (`threadedWorldGen.enabled` default true).
- Threaded FEATURES stays on (`allowThreadedFeatures` default true).
- `optimizeAsyncChunkRequest` stays retired / false. Re-enabling the key does not restore the `/tp`-deadlocking `CFUtil.join` path.

### Not proven by this release

- Pack boot with live Isle of Berk 1.2.0 + Variant Loader 2.7.0 mixin apply.
- Live `/tp` under FEATURES generation.
- Any MSPT / worldgen timing claim.

## [0.1.0-alpha.2] - 2026-08-18

Pin `th-1182-forge-code-review-candidate-pin-024`. Sol-024 accepted this exact **code candidate** only. Supersedes pin-022.

This is a **safety** build, not the performance restore. Threaded Horizons stays loaded; reduced lock radius is turned off.

### Removed / retired

- Inherited `optimizeAsyncChunkRequest` mixin family is hard-skipped.
- Lithium-family `getChunkOffThread` ASM workaround is a no-op. It existed only to keep the retired async-request path.

### Changed

- Config schema is version **3**.
- Version-2 files migrate `optimizeAsyncChunkRequest` to `false`.
- Version-2 files migrate `threadedWorldGen.reduceLockRadius` to `false` so Variant Loader biome / sky reads during FEATURES do not race unlocked neighbors.
- Threaded world generation and `allowThreadedFeatures` stay on.

### Fixed

- Off-thread `CFUtil.join` onto `mainThreadProcessor` was the `/tp` watchdog deadlock (`TeleportCommand → Entity.setPos → getChunkBlocking → parkNanos` while a worker waited for a future that needed the main thread).

### Known operator effect

- Worlds feel slower than a full lock-radius-on run. That is the alpha.2 workaround, not Threaded Horizons being disabled.

## [0.1.0-alpha.1] - 2026-08-18

Pin `th-1182-forge-code-review-candidate-pin-022`. First published Threaded Horizons candidate. Pin-021 remains rejected.

### Added

- First Minecraft 1.18.2 / Forge 40.3.11 / Java 17 candidate.
- Ports the public 1.18.2 functional tree into a Forge source set.
- Runtime identity `threadedhorizons` / `network.vonix.threadedhorizons` / `threaded-horizons`.
- Original 256×256 icon `threadedhorizons.png`.
- Optional `ioSystem.replaceImpl` storage: per-position generations, exact store futures, tombstones, vanilla RegionFile framing, idle-slot prune.
- Chunk load fails closed for present-but-unreadable data. Dirty generations stay until store completion.
- `/threadedhorizons status` reports executor queue depths for disposable load gates.
- Mapped-class overwrite differentials in `OfficialOverwriteDifferentialTest` and `docs/overwrite-differential.md`.

### Changed from the functional reference

- No-tick `tickChunks` hooks target official `getTickingChunk()` and `getAllEntities()` only. A `getTickingChunkFuture` redirect is not registered because that invoke is not in `tickChunks` on Forge 40.3.11.
- Removed unused empty access mixins (`IAquiferSamplerFluidLevel`, `IStructureWeightSampler`, `IChunkGenerator`, `INbtCompound`, `IRegionFile`). Region I/O uses public `RegionFile` stream APIs.
- Failsafe `SinglePoolElement` processor-null hook redirects official `getSettings` `Holder.value()`, not `place`.
- `SimplifiedAtomicSimpleRandom` constructor now calls `setSeed`, matching official `LegacyRandomSource` mixing.

### Intentionally excluded

- Java incubator-vector acceleration.
- Fabric companion mixins (BetterEnd, BetterNether, Charm, Terra, The Bumblezone).
- bStats.
- No public project URL, issue tracker, or CurseForge ID.

### Known defect found after this release

- The inherited async chunk-request / `CFUtil.join` path can deadlock `/tp`. Fixed by retirement in alpha.2.

## Config migration

| On-disk `version` | After first boot of this JAR | `reduceLockRadius` | `optimizeAsyncChunkRequest` |
| --- | --- | --- | --- |
| 2 on alpha.2 | 3 | forced `false` | forced `false` |
| 2 on alpha.3 | 4 | forced `true` after the v3 safety step | forced `false` |
| 3 leftover from alpha.2, then alpha.3 boot | 4 | forced `true` | stays `false` |
| 4 | no migrate | left as written | left as written |

Do not hand-edit a version-3 file before an alpha.3 restart. The updater rewrites it.

## Still not claimed

- Public release.
- Pack-tested release.
- Production-ready.
- 1:1 C2ME parity.
- Any measured performance number.
- Live install / restart authority.
