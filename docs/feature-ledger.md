# Feature ledger

Statuses: `proven` (local tests exist), `experimental` (packaged and gated), `intentionally_excluded`.

This ledger is the replacement audit for Threaded Horizons 1.0.7 against the 1.18.2 C2ME Fabric functional reference (`2cfe711f1cb90475387dd63d6b8741df2680e61f`). Threaded Horizons is a standalone Forge implementation intended to replace C2ME in this lane. It is not a 1:1 parity claim.

| Family | Status | Evidence |
| --- | --- | --- |
| Worldgen scheduling / locking | proven | `LockAndSchedulerTest`, `ChunkStatusUtilsAdmissionCancellationTest`, `SchedulerPipelineCancellationTest`, `SchedulerShutdownAdmissionTest`, `WorldGenSchedulingStateTest`. Reduced lock radius is write/lock ownership only (`MixinChunkRegionReadContractTest`, `WorldGenRegionReadVisibilityTest`). |
| Chunk I/O | proven / experimental | Optional `replaceImpl` storage is proven (`StorageStateMachineTest`, `VanillaRegionCycleTest`, `StorageCrashReopenTest`, `CrossFileConsistencyTest`). Async chunk I/O mixins are experimental and gated by `ioSystem.async`. Dirty save generations join `IOWorker.store` (`ChunkMap.save` overwrite, `SaveFutureTrackerTest`). |
| Async chunk load fail-closed | experimental | Mixin overwrite + unit types; live dedicated-server 100-cycle evidence is recorded under `evidence/logs/sol006-gaps-001` when that gate is executed |
| Chunk scheduling | experimental | Mid-tick chunk tasks, enhanced autosave, unload fix, and task-scheduling mixins are packaged. Mid-tick is skipped when Lithium-family mods are loaded. Async scheduling stays off by default. |
| Lighting | experimental | `threading.lighting` mixins apply unless `lightbench` is loaded. |
| No-tick view distance | experimental | Official `tickChunks` redirects `getTickingChunk` and `getAllEntities` (javap-proven). The Fabric `getTickingChunkFuture` hook is an intentional exclusion: that invoke is in `isPositionTicking(long)`, not `tickChunks`. Empty Fabric `MixinPlayerManager` is not ported. |
| Allocation reductions | proven | `SimpleObjectPoolTest`, `ObjectCachingUtilsBoundTest`, `SaveFutureTrackerTest`, `CombinedLockRetryAllocationContractTest`, `LongHashSetTest`. Lithium-family `CompoundTag` redirects are skipped (`LithiumFamilyNbtMixinSkipTest`). |
| Random / biome caches | proven (thread-local + End) / intentionally_excluded (global) | Thread-local biome cache is bounded (`BiomeSourceCacheBoundTest`). End biome cache decision matches official mapped classes (`OfficialOverwriteDifferentialTest`). Global biome cache stays off. |
| Structure / thread-safety fixes | proven / experimental | Stronghold latch (`StrongholdGenerationSafetyTest`), Desert Temple bytecode (`MixinDesertTempleBytecodeContractTest`), structure isolation (`StructureIsolationRegressionTest`), failsafe `SinglePoolElement.getSettings`. |
| Lifecycle / shutdown | proven | Ordered post-shutdown (`ServerShutdownHooksTest`). ForkJoin workers clear managed ThreadLocals (`WorkerThreadLocalsTest`). IO NBT cache is clamped (config v5). |
| Official overwrite math/random | proven | `OfficialOverwriteDifferentialTest` vs mapped Forge 40.3.12; see `docs/overwrite-differential.md` |
| Lithium-family NBT / chunk-access | proven | Skip `MixinNbtCompound*` when `lithium`, `canary`, or `radium` is present (`LithiumFamilyNbtMixinSkipTest`, `LithiumFamilyDetectionTest`). Early `FMLLoader.getLoadingModList()` detection. Canary `getChunkOffThread` is the same blocking path as Lithium. The TH async-request rewrite of that method is retired. |
| YUNG's Bridges FEATURES guard | proven | `FeatureThreadingPolicy` forces FEATURES `SINGLE_THREADED` when `yungsbridges` is loaded (`FeatureThreadingPolicyTest`). Other statuses stay parallel. |
| Worker ThreadLocal / IO NBT bounds | proven | Lazy bounded `SimpleObjectPool`, LRU bitset/biome caches, IO cache clamp + config v5 (`SimpleObjectPoolTest`, `ObjectCachingUtilsBoundTest`, `BiomeSourceCacheBoundTest`, `IoSystemConfigCacheLimitTest`, `ConfigVersion5IoCacheMigrationTest`, `WorkerThreadLocalsTest`). |
| Reduced lock radius | experimental | Default and migrate `true` on config v4. Write/lock only; WorldGenRegion reads stay vanilla (`MixinChunkRegionReadContractTest`, `WorldGenRegionReadVisibilityTest`). IoB/VL missing-chunk probes fail closed (`IsleOfBerkWorldAccessTest`, `FailClosedSubstituteContractTest`). |
| Stronghold / structure warmup | proven | Latch `hasGeneratedPositions` only after `generatePositions()` returns (`StrongholdGenerationSafetyTest`). Async warmup logs companion NPEs and retries. Constructor warmup remains skipped under ModernFix. |
| C2ME coexistence | proven | Startup rejects a loaded `c2me` mod ID (`C2meCoexistenceRejectionTest`). Overlapping mixins are not a supported combination. |
| Async chunk request (`optimizeAsyncChunkRequest`) | intentionally_excluded | Hard-skipped mixin + config v3 migrate-off. Inherited C2ME 1.18.2 `CFUtil.join` onto `mainThreadProcessor` deadlocked `/tp`. No nonblocking replacement that avoids a server-thread join is proven on this Forge target (`ConfigVersion3SafetyMigrationTest`, `AsyncChunkRequestMixinRetiredTest`). |
| Global biome cache | intentionally_excluded | Upstream C2ME 1.18.2 hardcoded `useGlobalBiomeCache = false` with "DO NOT USE in 1.18" and left `MixinChunkGenerator.populateBiomes` empty. Threaded Horizons keeps the same disable: sampler identity is not world identity, the empty createBiomes mixin is not restored, and mixins stay gated off (`GlobalBiomeCacheExclusionTest`, `MixinProfileMatrixTest`). Isolation tests for the unused cache type remain. |
| Vector acceleration | intentionally_excluded | Java 17 incubator-vector source trees (`libs/vectorized_algorithms`, `mixin/optimization/vectorizations`) are absent. No incubator dependency is added (`ReplacementExclusionContractTest`). |
| Fabric companions | intentionally_excluded | BetterEnd, BetterNether, Charm, Terra, and The Bumblezone companion mixins are Fabric-only and are not compiled. Missing companions fail closed. Forge IoB / Variant Loader modules remain. |

## Replacement audit notes

Safe families already present on this Forge target were not reimplemented. Empty Fabric stubs (`MixinPlayerManager`, `MixinServerChunkManagerMainThreadExecutor`, commented `MixinNoiseChunkGenerator` structure-weight redirects, empty global-biome `populateBiomes`) were not revived. Unsafe or unavailable families stay excluded with the evidence above.
