# Threaded Horizons — FULL MOD REVIEW & REPAIR BRIEF

You are the sole engineer on this task. Fix and review the entire mod. Work autonomously.

## Repository

`/home/pargon/vonix-mc/threaded-horizons` — Forge 1.18.2 (Forge 40.3.12, Java 17), Mixin-based
chunk-threading/performance mod, mod id `threadedhorizons`. 237+ unit tests, custom Gradle gates.

Build: `export JAVA_HOME=$HOME/jdks/jdk-17 && ./gradlew clean test build --no-daemon`

## CRITICAL: two real production data-loss incidents, both on live servers

This mod has caused **two distinct, severe, reproducible data-loss defects on a live modpack
server** ("Isle of Berk"). Both must be fixed. We have already shipped two bad releases.

### Incident 1 — delayed flush lost container NBT (already partially addressed)

Symptom: after a restart, chests/containers reverted to an *older* block-entity state while
`playerdata` stayed current (empty chests, intact inventory, no death).

Evidence from the live server:
- stop sequence emitted `All chunks are saved`, then `Stopping server`, then a **second**
  `All chunks are saved` ~2.5 s later
- **68 region files were written with one identical mtime during process exit**

Root cause found: `MixinRegionBasedStorage` honoured system property
`network.vonix.threadedhorizons.chunkio.syncDiskWrites` allowing `RegionFileStorage.sync=false`.
With `sync=false`, region writes buffer in a queue invisible to `SaveFutureTracker`, so
`ChunkMap.flushWorker()` joined only TH-owned stores, saw "idle", and returned with bytes still in
memory. Separately, `joinSaveFutures()` used a bare single `isIdle()` read, which can observe the
gap between a completing store and the follow-on store its own completion schedules.

Our partial fix (UNSHIPPED, in tree): force `sync=true` unconditionally, and replace the barrier
with `SaveFutureTracker.joinFully(timeoutMs)` (quiet-window drain, fail-closed on timeout);
`flushWorker()` drains storage then re-confirms.

### Incident 2 — block-entity IDENTITY bleed: backpacks merged/duplicated (STILL UNRESOLVED, WORSE)

This is the defect you must prioritise. Players reported live:

> "all my backpacks are weird, same content in all of them and not my content"
> "They all have the exact same small set of items."
> "all her backpacks are now the same color"
> "When i checked before she came on all her backpacks were color-coded and had her items, but
>  when she logged on they changed to all grey and no items, and when we relogged they changed to
>  all brown with no items"

Interpretation: **multiple distinct backpack block entities resolved to ONE shared/foreign storage
object.** Colour is stored per block entity; colour AND contents both collapsing to identical
values means distinct block-entity positions bound to the same inventory record. It escalated
across relogs (grey -> brown), i.e. it is progressive, not a one-time stale read.

Region-file evidence from the corrupted world copy (`world-broken/region`, 3420 files) vs a
rollback copy of the same world:
- at `2026-10-13T23:24:56` + `23:24:57`: world-broken has **30 + 99 = 129 files** stamped,
  while the rollback copy has **77 + 52 = 129** at the same two timestamps
- same total, different distribution -> a BULK region rewrite that wrote SUBSTITUTED content,
  not merely stale content

Hypothesis to confirm or refute: during a bulk/parallel save window, the per-chunk block-entity
snapshot used by serialization binds positions to the wrong block entities, so one BE's NBT is
written to many positions. Prime suspects: `AsyncSerializationManager.Scope` snapshot lifecycle
and its interaction with:
  `threading.chunkio.MixinThreadedAnvilChunkStorage` (save path, @Overwrite)
  `threading.chunkio.MixinChunkSerializer`
  `threading.chunkio.MixinChunkDirtyGenerations` / `MixinWorldChunkDirtyGenerations`
  `threading.chunkio.MixinSerializingRegionBasedStorage`
  `optimization.chunkscheduling.idle_tasks.autosave.enhanced_autosave.*`
TH's own diagnostics for a botched save are the log strings `Scope position mismatch`,
`blockEntityPositions`, and `Block entities size mismatch`. Grep for them.

## What I need from you

1. **Read the ENTIRE mod source** and audit every chunk save/load/serialization path, with
   special focus on shared mutable state, block-entity snapshots, position<->BE binding,
   threading races, and any place one chunk's data can be written into another's slot.
2. **Confirm or refute the identity-bleed mechanism** with concrete code evidence (file:line +
   quoted code). If you cannot confirm it from source, say so plainly and list what runtime
   evidence would settle it — do not invent a cause.
3. **Fix both incidents** in source. For incident 2 the fix must be structural (correct binding /
   correct isolation / correct scope lifecycle), not a symptom guard. Prefer removing the unsafe
   mechanism over adding a check that can be bypassed by config or a system property.
4. **Add regression tests** that would FAIL on the current code and pass after your fix. For the
   identity defect a test must exercise the actual multi-chunk concurrent save path with
   distinguishable per-chunk payloads and assert no cross-contamination.
5. **Add a structural gate** so this class of defect cannot be reintroduced (in the style of the
   existing `src/test/java/network/vonix/threadedhorizons/ci/` gates).
6. **Keep all 241 existing tests green.** Do not weaken or delete an existing test to make your
   change pass; if an existing test encodes a bug, say so explicitly and explain why.

## Hard constraints — violating any of these fails the task

- Do NOT deploy, upload, publish, or restart anything. Local source + tests only.
- Do NOT edit any third-party JAR.
- Do NOT reintroduce any user-reachable opt-out that disables durability (no system property,
  no env var, no config key may turn off durable writes).
- Do NOT add a non-private static field to any `@Mixin` class. Mixin rejects that at APPLY time
  with `InvalidMixinException: contains non-private static field`, which hard-crashes server boot.
  This exact mistake shipped once as `public static final boolean DURABLE_WRITES_FORCED = true;`
  in `MixinRegionBasedStorage` and crashed the live server. There is a repo-wide gate for it.
- A clean compile and green tests do NOT prove a mixin works. Mixin apply rules and load-time
  validation are only exercised by a real Forge boot.
- Be honest about what you verified vs assumed. If something is unproven, label it unproven.

## Output

Write your work to disk. Produce:
- the modified source tree
- `SOL-REVIEW-REPORT.md` at repo root containing:
  - incident-2 mechanism: CONFIRMED / REFUTED / UNPROVEN, with file:line evidence
  - every change made, with rationale
  - new tests added and what they prove
  - exact build command run and its real pass/fail counts
  - anything you could not verify, stated plainly
- Commit your changes to git with a clear message.

Do not report success from a compile alone. Run the full test suite and report the real numbers.
