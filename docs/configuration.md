# Configuration

File: `config/threadedhorizons.toml` (created on first launch). Config version is `5`. Version-4 files clamp oversized `ioSystem.chunkDataCacheSoftLimit` / `chunkDataCacheLimit` values (soft max 4096, hard max 8192) while preserving in-range owner tuning. Version-3 files re-enable `reduceLockRadius` after the IoB/Variant Loader support mixins. Version-2 files still retire `optimizeAsyncChunkRequest` first.

`optimizeAsyncChunkRequest` remains a retired safety exclusion: persisted `true` is migrated off and the mixin plugin never applies the family. `useGlobalBiomeCache` is not a writable 1.18.2 key; the field stays false.

| Section | Purpose |
| --- | --- |
| `globalExecutorParallelism` | Parallelism of the global worker pool |
| `ioSystem` | Asynchronous region/chunk I/O |
| `threadedWorldGen` | Concurrent chunk generation and lock radius |
| `asyncScheduling` | Async chunk task scheduling |
| `vanillaWorldGenOptimizations` | Vanilla worldgen hot-path optimizations |
| `generalOptimizations` | Compression and general optimizations |
| `noTickViewDistance` | No-tick view distance and compatibility mode. When enabled, mixins remapping player view-distance tickets plus official `tickChunks` redirects (`getTickingChunk`, `getAllEntities`) apply. There is no `getTickingChunkFuture` redirect on this Forge target. |
| `clientSide` | Client render-distance uncap |

Safe defaults follow the 1.18.2 functional reference. Unknown `chunkStreamVersion` values abort startup.

Commands:

- `/threadedhorizons notick` and `/th notick` (when no-tick view distance is enabled)
- `/threadedhorizons status` and `/th status` (executor queue depths and uncaught/rejected counters)
- `/threadedhorizons debug mobcaps` in a development environment
