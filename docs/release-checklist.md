# Release checklist

This checklist records the evidence boundary for the public `1.0.0` release and the later 1.0.7 replacement candidate. It is not a promise of production safety or universal compatibility.

- [x] Java 17 clean `./gradlew --no-daemon clean test build` passed from the repository source tree for the 1.0.0 release.
- [x] Artifact path for 1.0.0 was `build/libs/threaded-horizons-mc1.18.2-1.0.0.jar`.
- [x] 1.0.7 candidate artifact path is `build/libs/threaded-horizons-mc1.18.2-1.0.7.jar` after a fresh Java 17 clean test/build.
- [x] Current successor artifact is 1,073,125 bytes with SHA-256 `149c20b21217bcc9e5363f45fd02325aa2ff09be69748ef20db05f2732b044e1`; it is a development snapshot, not a release asset.
- [x] Ground-up review repairs landed: dimension-aware scheduler pipeline key (`SchedulerPipelineDimensionKeyTest`) and bounded autosave overflow (`AutosaveOverflowBoundTest`); gate 228 tests, 0 failures, completion scan passed.
- [x] Successor artifact identity is rebuild-variant: source tree is pinned and re-gated per rebuild; one verified build produced 1,073,125 bytes / SHA-256 `149c20b21217bcc9e5363f45fd02325aa2ff09be69748ef20db05f2732b044e1`, and the delivered test JAR is 1,073,124 bytes / SHA-256 `0e7c21c44abfde224e5e7ccbf0c8920b30da95a0a53f441bdb669b90973066af`.
- [x] Completion scan over `src/` returned zero active markers.
- [x] Storage state-machine tests executed rather than reporting `NO-SOURCE`.
- Independent read-only review is required for the exact candidate artifact before publication.
- Exact packaged-JAR disposable dedicated-server boot reached `Done` and stopped cleanly.
- Matched disposable TH-on/TH-off chunk-generation comparison completed twice.
- Publication read-back is required: the public release page and asset must match the final artifact.

## Not established by this checklist

- Client launch, multiplayer/login, or retained-world reopen.
- Universal modpack compatibility or zero-data-loss behavior.
- Production readiness or a universal performance improvement.
- CurseForge or any downstream platform publication.
