You are continuing repair work on Threaded Horizons, a standalone C2ME replacement for Minecraft 1.18.2 / Forge 40.3.12 / Java 17.

## Current State

Repo: /root/work/threaded-horizons-release-1.0.0
Branch: fix/1.0.1-canary-midtick-deadlock
HEAD: 37d8d7e0c24b13a2981a7ed41809c200b295b68b (unchanged; all changes are uncommitted working-tree edits)
Last artifact SHA-256: 9f8295dd4e7e9eee2cd5a047711f710a585b3269102be061c0cb95ffa8d81875

Two implementation waves and two independent verification rounds have been completed. The LAST VERIFIER returned FAIL with 4 findings that still need repair:

### Finding 1 — HIGH: Lighting rejection handler silently drops ProcessorMailbox work
Path: src/main/java/network/vonix/threadedhorizons/mixin/threading/lighting/MixinThreadedAnvilChunkStorage.java (lighting section, ~lines 31-34, 51-58)
Problem: Custom RejectedExecutionHandler records rejection and returns normally. Forge 40.3.12 ProcessorMailbox sets its scheduled bit BEFORE dispatcher.execute() and only retries when RejectedExecutionException is thrown. A non-throwing handler leaves lighting work permanently scheduled but never executed.
Fix required: The rejection handler MUST throw RejectedExecutionException (or subclass) so ProcessorMailbox's built-in retry logic engages. Log/metric the rejection BEFORE throwing. Inspect cached Forge ProcessorMailbox bytecode to confirm the retry-on-exception contract. Update LightingExecutorBackpressureTest to verify correct exception type and mailbox rescheduling behavior.

### Finding 2 — MEDIUM: No-tick queue compensation ordering and dropped actions
Path: src/main/java/network/vonix/threadedhorizons/common/notickvd/NoTickSystem.java (~lines 62-94, 280-310)
Problem A: Rejected ticket add/remove applied synchronously while older actions remain queued → later queued actions overwrite compensated state.
Problem B: Player-source add/remove and view-distance actions ignore enqueueAction(false) return and are dropped entirely despite log claiming synchronous application.
Fix required: Either drain pending queue BEFORE applying synchronous compensation, or enqueue a compensating action rather than applying inline, or make queue unbounded for critical ticket actions. Ensure ALL action types (ticket, player-source, view-distance) respect enqueue result and apply synchronous fallback when rejected. Update NoTickQueueBackpressureTest to cover ordering, player-source rejection, and view-distance rejection.

### Finding 3 — MEDIUM: Autosave deferral has no deferred record
Path: src/main/java/network/vonix/threadedhorizons/mixin/optimization/chunkscheduling/idle_tasks/autosave/enhanced_autosave/MixinThreadedAnvilChunkStorage.java (~lines 47-54, 59-88)
Problem: When MAX_AUTOSAVE_QUEUE reached, dirty ChunkPos is logged but NOT retained. runOneChunkAutoSave only scans dirtyChunkPosForAutoSave, so rejected chunks are silently lost until next mutation.
Fix required: Maintain a separate overflow/deferred set (e.g., LinkedHashSet<ChunkPos>). When queue full, add to deferred set. In runOneChunkAutoSave or end-of-tick hook, re-enqueue deferred entries when capacity allows. Update AutosaveBackpressureTest to verify rejected chunks ARE eventually saved.

### Finding 4 — LOW: Profiler pop/push mismatch
Path: src/main/java/network/vonix/threadedhorizons/mixin/threading/chunkio/MixinThreadedAnvilChunkStorage.java (~lines 202-208, 210, 241-242)
Problem: Unconditional profiler.pop() in finally runs on early returns before profiler.push().
Fix required: Track whether push was called (boolean flag). Only pop if push succeeded. Or restructure so finally only covers region after push.

## Your Task

1. First, review the current source state of all files listed above. Understand what the previous repair waves already changed.
2. Implement targeted fixes for all 4 findings using TDD: extend/add failing regression tests first, run RED, implement minimal fix, run GREEN.
3. Run focused tests after each repair family, then full suite.
4. Run git diff --check.
5. Build: export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && export PATH=$JAVA_HOME/bin:$PATH && ./gradlew clean test build --no-daemon
6. Report exact changed paths, commands/results, new artifact SHA-256.
7. Return candidate-only status. Do NOT deploy, publish, restart, modify live worlds, inspect secrets, or touch third-party JARs. EFFECTS=NOT_PERFORMED.

## Constraints
- Preserve ALL pre-existing dirty/untracked files. Do not reset, clean, stash, rebase, or overwrite unrelated work.
- Threaded Horizons is standalone C2ME replacement; do not add C2ME dependency or coexistence layer.
- Target MC 1.18.2, Forge 40.3.12, Java 17.
- Keep existing ModernFix/Canary/Radium compatibility gates intact.
