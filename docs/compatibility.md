# Compatibility

| Component | Status |
| --- | --- |
| Minecraft 1.18.2 | Required |
| Forge 40.3.11 | Required |
| Java 17 | Required |
| Dedicated server | Intended; client-only mixins isolated |
| ModernFix | Constructor-time async structure warmup is disabled; structure generation remains on-demand to avoid racing `StrongholdLocationCache` initialization |
| Client render-distance uncap | Client mixin config |
| Canary / Lithium-family chunk access | Mid-tick chunk-task mixins disabled to avoid executing Canary's blocking `getChunkOffThread` join on the server thread |
| Sodium option pages | Mixin present; applies only if Sodium is loaded |
| BetterEnd / BetterNether / Charm / Terra / The Bumblezone | Not ported; fail closed |
| Isle of Berk 1.2.0 / Variant Loader 2.7.0 | Compat mixins require loaded FEATURES biome/sky/`isWaterBelow` chunks and fail closed if missing; ARR IoB bytecode is not packaged |
| Incubator vector noise | Deferred / unavailable; not in source or JAR |

Do not install alongside a C2ME-branded artifact. This release uses a separate mod ID.
