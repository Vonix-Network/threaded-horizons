# Changelog

All notable Threaded Horizons releases for Minecraft **1.18.2** / Forge **40.3.11** / Java **17**.

This project is a private Vonix **unverified** prerelease series. Code-candidate acceptance is not pack proof, not a live `/tp` proof, and not a production install.

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
