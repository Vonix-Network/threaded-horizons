# Threaded Horizons save review

## Incident 2: UNPROVEN

The source proves a dangerous binding error, but does not prove that it caused the live backpack corruption. In the pre-change `AsyncSerializationManager.java:72-76`, the scope iterated `blockPos` from the chunk, then inserted the entity with `entities.put(blockEntity.getBlockPos(), blockEntity)`. The pre-change `MixinChunkSerializer.java:42-48` intercepted `getBlockEntityNbtForSaving(pos)`, looked up `scope.blockEntities.get(pos)`, and called `blockEntity.saveWithId()`. Thus the serializer could use an entity's internal position instead of the chunk's requested position, and kept mutable entity objects in the snapshot. A stale or incorrect internal position could omit or substitute NBT. The source alone does not establish that backpacks shared an object or that this path generated the particular saved world.

The current `MixinThreadedAnvilChunkStorage.java:214-230` constructs and pushes the scope, calls `ChunkSerializer.write` synchronously, pops the scope, then queues the resulting tag to storage. `AsyncSerializationManager.java:35-48` keeps scopes in a thread-local stack and checks chunk position. The enhanced autosave queue chooses positions and calls `saveChunkIfNeeded`; it does not itself serialize BE NBT. The POI region storage mixin calls `readColumn` on load. This audit found no source evidence that those paths intentionally share a BE map across chunks. It was a focused audit of persistence paths, not a line-by-line review of every mod source file.

To establish the live mechanism, compare the same chunk positions' BE NBT in pre-corruption, corrupted, and rollback region files, including `x/y/z`, backpack inventory identifiers, color, and contents. Capture the `Scope position mismatch` and `Block entities size mismatch` diagnostics around the save window, and reproduce with the actual backpack mod under a real Forge boot. These artifacts were not available in this repository.

## Changes

- Removed the scope's `BlockPos -> BlockEntity` and packed-NBT maps and the `ChunkSerializer.write` BE-NBT redirect. Vanilla `ChunkAccess.getBlockEntityNbtForSaving(pos)` now supplies BE NBT for the position requested by the serializer. The scope still captures the position set and lighting views.
- Kept the prior incident-1 changes: the region-storage mixin forces `sync = true`, and the save path drains owned stores, flushes worker storage, and reconfirms. Changed `joinSaveFutures` so a `joinFully` timeout throws instead of allowing `managedBlock` to keep retrying silently. The current barrier still cannot prove whole-process durability against arbitrary external mods adding writes after its quiet window.
- Added `BlockEntitySerializationGateTest` to prohibit the scope BE remapping/redirect and require the timed-out barrier to throw.
- Added `concurrentDistinctChunkStoresRetainBlockEntityIdentity`: 64 distinguishable BE payloads are stored and read through the concurrent TH region-store path. This exercises concurrent region writes, but **does not exercise the actual Forge `ChunkSerializer.write` mixin**. It would not independently fail on the previous BE scope code; the structural gate does fail on that code. A real Forge boot/integration test is still required for the requested end-to-end regression proof.

## Verification

Command: `export JAVA_HOME=$HOME/jdks/jdk-17; ./gradlew clean test build --no-daemon`

Result: `BUILD SUCCESSFUL in 1m 26s`; JUnit XML: **244 tests, 0 failures, 0 errors, 0 skipped**. The pre-existing 241 tests remained green; three new tests ran. `completionScan` passed. No Forge server boot or Mixin APPLY-time validation was run. The build generated changes under `logs/`; they are excluded from the commit.
