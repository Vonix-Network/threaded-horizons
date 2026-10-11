# Threaded Horizons 1.0.7

> **Controlled testing candidate for Minecraft 1.18.2 / Forge 40.3.12 / Java 17.** Back up your world before testing. Production readiness, universal compatibility, and a universal performance improvement have **not** been established.

Threaded Horizons is a standalone Forge optimization mod intended to replace C2ME in the Minecraft 1.18.2 Forge lane. It works on chunk generation, chunk storage, lighting, and server scheduling while adding bounded safety checks, compatibility guards, and optional client-side view-distance features.

Threaded Horizons has its own mod ID, `threadedhorizons`. It is **not** a C2ME rename, does not bundle a C2ME artifact, and does not claim 1:1 C2ME parity.

## Highlights

- Concurrent chunk-generation scheduling with bounded world-generation admission.
- Asynchronous chunk storage and region-file I/O.
- Threaded lighting and server scheduling work.
- Optional no-tick view distance support.
- Optional client-side render-distance settings.
- Reduced temporary allocation and collection work in save, serialization, locking, and unload paths.
- Bounded autosave overflow and fail-closed queue limits.
- Runtime diagnostics through `/threadedhorizons` or `/th` commands.
- Compatibility guards for common Forge optimization and content mods.

## Requirements

- **Minecraft:** 1.18.2
- **Forge:** 40.3.12 through the declared Forge 40.x range (`[40.3.12,41)`)
- **Java:** 17
- **Side:** Client and dedicated server

Install the mod on both the client and server when playing on a multiplayer server. Client-only functionality is isolated from dedicated-server loading.

## Installation

1. Install Minecraft 1.18.2 with a compatible Forge 40.x profile.
2. Install Java 17.
3. Back up the world and keep a rollback copy of the previous mod set.
4. Place the Threaded Horizons JAR in the `mods/` directory.
5. Start the game once to create `config/threadedhorizons.toml`.
6. Review the configuration before enabling optional features.

**Do not install Threaded Horizons alongside a C2ME-branded artifact.** Their overlapping mixins are not a supported combination, and Threaded Horizons will reject a loaded `c2me` mod ID.

## Configuration and commands

Configuration is stored in:

```text
config/threadedhorizons.toml
```

The main configuration areas cover global worker parallelism, asynchronous I/O, threaded world generation, asynchronous scheduling, vanilla world-generation optimizations, general optimizations, no-tick view distance, and client-side options.

Available commands include:

- `/th status` — show executor queue depths and diagnostic counters.
- `/th notick` — show no-tick chunk counts when no-tick view distance is enabled.
- `/th debug mobcaps` — development diagnostics.

## Compatibility notes

- **ModernFix:** constructor-time asynchronous structure warmup is disabled to avoid racing ModernFix initialization; structure generation remains available on demand.
- **Radium, Lithium, and Canary:** overlapping allocation and mid-tick chunk-access mixins are skipped where necessary. Canary's blocking chunk-access path is not re-enabled.
- **YUNG's Bridges:** FEATURES placement is forced to single-threaded execution because it can write outside the currently generated chunk. Other world-generation stages remain eligible for parallel execution.
- **Isle of Berk / Variant Loader:** optional compatibility hooks fail closed when required chunk data is unavailable.
- **BetterEnd, BetterNether, Charm, Terra, and The Bumblezone:** Fabric companion integrations are not included in this Forge project.

## What's new in 1.0.7

### Changed

- Reduced temporary allocations in chunk-save tracking, serialization, combined-lock retries, unload tracking, and no-tick view-distance updates.
- Reworked save tracking to use bounded in-flight accounting while preserving dirty-generation safety: a chunk remains dirty until its save completes, and newer changes still take precedence.
- Kept owner-configured I/O cache limits within safe bounds while preserving values that are already valid.
- Gated reduced-task-radius console output behind its debug setting.

### Fixed

- World-generation admission is now separated by **dimension and chunk position**, so the same coordinates in different dimensions no longer share one admission slot.
- Enhanced-autosave overflow now respects the existing bounded queue limit instead of growing without limit. Rejected entries remain dirty and can be saved by a later cycle.

### Safety boundary

- This release does not claim measured RAM reduction, universal compatibility, or a universal performance improvement.
- Client launch, multiplayer/login behavior, retained-world reopening, broad modpack compatibility, long-duration testing, and production deployment remain outside the established testing boundary.
- Back up worlds before use and test in a disposable or recoverable environment first.

## License

Threaded Horizons is released under the MIT License. See the project license and notices for full details.
