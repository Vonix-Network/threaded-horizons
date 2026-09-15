# Installation

## Runtime

- Java 17
- Minecraft 1.18.2
- Forge 40.3.12 installer/userdev coordinate `1.18.2-40.3.12`
- Declared Forge range `[40.3.12,41)`

Place the locally built `threaded-horizons-mc1.18.2-1.0.7.jar` in `mods/` when testing the `candidate/1.0.7` development branch. This is not a published release asset. Do not install this candidate into a live production world without a backup. Do not install it alongside a C2ME-branded artifact.

## Source build

From the repository root, with Java 17:

```shell
./gradlew clean build --no-daemon
```

Use only the reobfuscated JAR from `build/libs/`.

## First launch

1. Accept the Minecraft EULA on dedicated servers.
2. Confirm the log loads `threadedhorizons` and mixin configs `threadedhorizons.mixins.json`, `threadedhorizons-asm.mixins.json`, `threadedhorizons-compat.mixins.json`.
3. Dedicated servers do not load `threadedhorizons.client.mixins.json` client-only classes through a dedicated-server run configuration; the packaged client mixin config is still listed in the JAR manifest for client use.
4. Edit `config/threadedhorizons.toml` after the first successful start.
