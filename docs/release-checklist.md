# Release checklist

This checklist records the evidence boundary for the public `1.0.0` release. It is not a promise of production safety or universal compatibility.

- [x] Java 17 clean `./gradlew --no-daemon clean test build` passed from the repository source tree.
- [x] Artifact path is `build/libs/threaded-horizons-mc1.18.2-1.0.0.jar`.
- [x] Completion scan over `src/` returned zero active markers.
- [x] Storage state-machine tests executed rather than reporting `NO-SOURCE`.
- Independent read-only review is required for the exact `1.0.0` artifact before publication.
- Exact packaged-JAR disposable dedicated-server boot reached `Done` and stopped cleanly.
- Matched disposable TH-on/TH-off chunk-generation comparison completed twice.
- Publication read-back is required: the public release page and asset must match the final artifact.

## Not established by this checklist

- Client launch, multiplayer/login, or retained-world reopen.
- Universal modpack compatibility or zero-data-loss behavior.
- Production readiness or a universal performance improvement.
- CurseForge or any downstream platform publication.
