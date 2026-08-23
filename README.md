# Threaded Horizons

[![Latest release](https://img.shields.io/github/v/release/Vonix-Network/threaded-horizons?display_name=tag&sort=semver)](https://github.com/Vonix-Network/threaded-horizons/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Threaded Horizons is an experimental Forge mod for **Minecraft 1.18.2**. It explores concurrent chunk generation, chunk I/O, lighting, scheduling, and related thread-safety improvements.

> **Status:** `1.0.1` is available as a controlled crash-fix testing candidate. Production readiness and universal compatibility have not been established.

[Download the latest release](https://github.com/Vonix-Network/threaded-horizons/releases/latest) · [Report an issue](https://github.com/Vonix-Network/threaded-horizons/issues) · [Security policy](SECURITY.md)

## Highlights

- Concurrent chunk-generation scheduling and world-generation locking.
- Asynchronous chunk storage and region-file I/O.
- Threaded lighting and server scheduling work.
- No-tick view-distance support when enabled in configuration.
- Client-side view-distance options when the relevant client integrations are present.
- Runtime status and diagnostic commands for testing.
- Optional compatibility hooks that disable themselves when their required integration data is unavailable.

Threaded Horizons is an independent project with its own mod ID, `threadedhorizons`. It is not C2ME, does not replace C2ME, and does not bundle a C2ME artifact.

## Supported environment

| Component | Version |
| --- | --- |
| Minecraft | 1.18.2 |
| Mod loader | Forge 40.3.11 through the declared Forge 40.x range (`[40.3.11,41)`) |
| Java | 17 |
| Mod ID | `threadedhorizons` |

The mod is packaged for both client and server sides. Client-only features are kept in the client mixin configuration and are not loaded by the dedicated-server run configuration.

See [`docs/compatibility.md`](docs/compatibility.md) for optional integrations and known exclusions.

## Installation

1. Install Java 17, Minecraft 1.18.2, and a compatible Forge 40.x installation. The mod declares Forge range `[40.3.11,41)`.
2. Download `threaded-horizons-mc1.18.2-1.0.1.jar` from the [1.0.1 release](https://github.com/Vonix-Network/threaded-horizons/releases/tag/1.0.1).
3. Place the JAR in the instance's `mods/` directory. Install it on the client and server when both sides are used.
4. Back up the world before the first launch.
5. Start the game once, then review `config/threadedhorizons.toml` before enabling optional behavior.

Do not install this project alongside a C2ME-branded artifact. Do not enable it in a production world without a separate test and rollback plan.

## Configuration and commands

Configuration is stored at `config/threadedhorizons.toml`. See [`docs/configuration.md`](docs/configuration.md) for the available settings and migration notes.

The command root is `/threadedhorizons`, with `/th` as an alias:

- `/th status` — display runtime status and diagnostic counters.
- `/th notick` — show no-tick chunk counts when that feature is enabled.
- `/th debug mobcaps` — development diagnostics; not intended as a normal gameplay command.

## Known limitations

This release is for testing and evaluation, not a guarantee of production safety or compatibility.

- Client launch, multiplayer/login, retained-world reopening, and broad modpack compatibility were not established by the release test scope.
- A matched disposable chunk-generation workload measured lower debug TPS with Threaded Horizons enabled than with it disabled. That result is workload-specific and should not be generalized into a performance promise.
- Some optional integrations are intentionally deferred or fail closed when their required classes or data are unavailable.
- Back up worlds before testing and keep a rollback copy of the previous mod set.

See [`docs/limitations.md`](docs/limitations.md) for the detailed technical boundary and [`CHANGELOG.md`](CHANGELOG.md) for release-specific notes.

## Building from source

Requirements: Java 17 and a network connection for the initial Gradle/Forge dependency setup.

From the repository root:

```shell
./gradlew --no-daemon clean test build
```

The reobfuscated artifact is written to:

```text
build/libs/threaded-horizons-mc1.18.2-1.0.1.jar
```

The project also includes a GitHub Actions build workflow at [`.github/workflows/build.yml`](.github/workflows/build.yml).

## Documentation

- [`docs/install.md`](docs/install.md) — installation and first-launch notes.
- [`docs/configuration.md`](docs/configuration.md) — configuration keys and migrations.
- [`docs/compatibility.md`](docs/compatibility.md) — supported and excluded integrations.
- [`docs/limitations.md`](docs/limitations.md) — known limitations and measured boundaries.
- [`docs/feature-ledger.md`](docs/feature-ledger.md) — feature status and evidence notes.
- [`docs/overwrite-differential.md`](docs/overwrite-differential.md) — mapped-class differential scope.
- [`CHANGELOG.md`](CHANGELOG.md) — release history.
- [`NOTICE.md`](NOTICE.md) — provenance and copyright notices.
- [`SECURITY.md`](SECURITY.md) — vulnerability-reporting guidance.

## Contributing

Bug reports and compatibility reports are welcome through [GitHub Issues](https://github.com/Vonix-Network/threaded-horizons/issues). Include the Minecraft version, Forge version, Java version, relevant mod list, configuration scope, reproduction steps, and sanitized logs. Never include credentials, private server addresses, or signed URLs.

## License and provenance

Threaded Horizons is distributed under the MIT License. See [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md).
