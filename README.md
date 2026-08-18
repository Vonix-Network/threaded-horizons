# Threaded Horizons — UNVERIFIED private staging

Private Vonix staging only. **Not a public release. Not pack-tested. Not production-ready.**

This repository exists so the Sol-reviewed Forge 1.18.2 code JAR can be downloaded from a GitHub release.

| Item | Value |
| --- | --- |
| Minecraft | 1.18.2 |
| Loader | Forge 40.3.11 |
| Java | 17 |
| Artifact | `threaded-horizons-mc1.18.2-0.1.0-alpha.4.jar` |
| Bytes | 1034856 |
| SHA-256 | `fcff5459fafaa18c4dc8ce8f11c7ec0cf1e75287ea289242555a4fce6162210e` |
| Pin | `th-1182-forge-code-review-candidate-pin-027` |
| Status | UNVERIFIED private prerelease |

This successor restores vanilla `WorldGenRegion` reads while keeping reduced lock radius as write/lock ownership only. Isle of Berk / Variant Loader helpers fail closed instead of substituting air, biome, sky, or water. Threaded world generation and FEATURES stay on. `optimizeAsyncChunkRequest` stays retired.

Do not treat this as C2ME, a Fabric port, or a verified world/pack release.

Full release history since alpha.1 is in [`CHANGELOG.md`](CHANGELOG.md).

See `LICENSE` and `NOTICE.md`.
