# Threaded Horizons

Threaded Horizons is an independent MIT-licensed concurrent chunk-management mod for **Minecraft 1.18.2** on **Forge**. It uses the runtime identity `threadedhorizons` and is not an official RelativityMC, Yatopia, C2ME, or Fabric release.

**Public release:** [`1.0.0`](https://github.com/Vonix-Network/threaded-horizons/releases/tag/1.0.0)

> **Release boundary:** This release is intended for controlled testing and evaluation. It is not a claim of production readiness, universal modpack compatibility, 1:1 C2ME parity, zero data loss, or a performance improvement. Back up worlds before use.

## Supported target

| Item | Value |
| --- | --- |
| Minecraft | 1.18.2 |
| Loader | Forge |
| Declared Forge range | `[40.3.11,41)` |
| Java | 17 |
| Mod ID | `threadedhorizons` |
| Release artifact | `threaded-horizons-mc1.18.2-1.0.0.jar` |
| Artifact SHA-256 | `83358ec583361edcd64ace0df72e4c03e55c8c483f5cd19299b5b514e4411f9f` |

## Verification scope

The `1.0.0` release passed a Java 17 clean Gradle test/build gate, archive and metadata inspection, and an exact packaged-JAR disposable dedicated-server boot on Forge 40.3.12. The server reached `Done`, applied the Threaded Horizons mixins, and stopped cleanly. Local tests cover storage state transitions, region-file save/reopen behavior, process-kill reopen of flushed region files, lock and scheduler contracts, Forge mixin target presence, and mapped-class overwrite differentials.

A matched disposable chunk-generation comparison was also run twice with Threaded Horizons enabled and disabled. The measured debug TPS means were **8.95 with Threaded Horizons** and **12.62 without it** for that workload. This is an observed workload-specific regression, not a universal performance claim. See [`docs/limitations.md`](docs/limitations.md) and [`docs/feature-ledger.md`](docs/feature-ledger.md).

The following remain outside the accepted evidence boundary: client launch, multiplayer/login, retained-world reopen, broad modpack compatibility, zero-data-loss behavior, and production readiness.

## Installation

1. Install Java 17, Minecraft 1.18.2, and Forge 40.3.11 or another Forge 40.x version inside the declared range.
2. Download the JAR from the [`1.0.0` release](https://github.com/Vonix-Network/threaded-horizons/releases/tag/1.0.0).
3. Copy `threaded-horizons-mc1.18.2-1.0.0.jar` into the instance `mods/` directory.
4. Back up the world before first launch.
5. Start once so `config/threadedhorizons.toml` is created, then review the configuration before use.

Do not install this release alongside a C2ME-branded artifact or enable it in a production world without an administrator-approved test and rollback plan.

## Build from source

From the repository root with Java 17:

```shell
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew --no-daemon clean test build
```

The reobfuscated artifact is written to:

```text
build/libs/threaded-horizons-mc1.18.2-1.0.0.jar
```

## Configuration and commands

Configuration is stored at `config/threadedhorizons.toml`; see [`docs/configuration.md`](docs/configuration.md).

Available commands include:

- `/threadedhorizons` and `/th`
- `/threadedhorizons notick` / `/th notick` when no-tick view distance is enabled
- `/threadedhorizons status` / `/th status`
- Development-only `/threadedhorizons debug mobcaps`

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — release history and evidence boundaries
- [`docs/compatibility.md`](docs/compatibility.md) — supported and excluded integrations
- [`docs/feature-ledger.md`](docs/feature-ledger.md) — feature status and evidence
- [`docs/install.md`](docs/install.md) — installation and first-launch notes
- [`docs/limitations.md`](docs/limitations.md) — known limitations and measured boundary
- [`docs/overwrite-differential.md`](docs/overwrite-differential.md) — mapped-class differential scope
- [`NOTICE.md`](NOTICE.md) — upstream provenance and copyright notices
- [`SECURITY.md`](SECURITY.md) — vulnerability-reporting guidance

## License and provenance

Threaded Horizons is distributed under the MIT License. See [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md).
