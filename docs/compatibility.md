# Compatibility

| Component | Status |
| --- | --- |
| Minecraft 1.18.2 | Required |
| Forge 40.3.12 | Required (`[40.3.12,41)`) |
| Java 17 | Required |
| Dedicated server | Intended; client-only mixins isolated |
| C2ME-branded artifact (`c2me`) | Unsupported coexistence. Threaded Horizons is the standalone Forge replacement for this lane; overlapping mixins abort startup |
| ModernFix | Constructor-time async structure warmup is disabled; structure generation remains on-demand to avoid racing `StrongholdLocationCache` initialization |
| Radium | Treated as Lithium-family: overlapping `CompoundTag` allocation redirects and mid-tick chunk-task mixins are skipped |
| YUNG's Bridges (`yungsbridges`) | FEATURES generation is forced single-threaded; other threaded worldgen statuses stay parallel |
| Client render-distance uncap | Client mixin config |
| Canary / Lithium-family chunk access | Mid-tick chunk-task mixins disabled to avoid executing Canary's blocking `getChunkOffThread` join on the server thread |
| Sodium option pages | Mixin present; applies only if Sodium is loaded |
| BetterEnd / BetterNether / Charm / Terra / The Bumblezone | Not ported; fail closed |
| Isle of Berk 1.2.0 / Variant Loader 2.7.0 | Compat mixins require loaded FEATURES biome/sky/`isWaterBelow` chunks and fail closed if missing; ARR IoB bytecode is not packaged |
| Incubator vector noise | Deferred / unavailable; not in source or JAR |

Do not install alongside a C2ME-branded artifact. This candidate uses a separate mod ID (`threadedhorizons`) and does not bundle a C2ME artifact. Overlapping mixins are not a supported combination.
