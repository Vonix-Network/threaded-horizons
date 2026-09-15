package network.vonix.threadedhorizons.platform;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Forge stand-in for FabricLoader queries used by the 1.18.2 reference.
 */
public final class LoaderHooks {
    private LoaderHooks() {
    }

    public static Path getConfigDir() {
        String override = System.getProperty("network.vonix.threadedhorizons.configDir");
        if (override != null && !override.isBlank()) {
            Path directory = Path.of(override);
            try {
                java.nio.file.Files.createDirectories(directory);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not create override config directory", exception);
            }
            return directory;
        }
        try {
            Path directory = FMLPaths.CONFIGDIR.get();
            if (directory != null) {
                return directory;
            }
        } catch (Throwable throwable) {
            // unit tests and early class load have no FML path context
        }
        Path fallback = Path.of(System.getProperty("java.io.tmpdir"), "threadedhorizons-config");
        try {
            java.nio.file.Files.createDirectories(fallback);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create config directory", exception);
        }
        return fallback;
    }

    public static boolean isModLoaded(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        try {
            if (ModList.get() != null && ModList.get().isLoaded(id)) {
                return true;
            }
        } catch (Throwable ignored) {
            // Mixin apply happens before ModList is populated.
        }
        try {
            var loading = FMLLoader.getLoadingModList();
            if (loading != null && loading.getModFileById(id) != null) {
                return true;
            }
        } catch (Throwable ignored) {
            // unit tests and very early class load have no FML context
        }
        return false;
    }

    /**
     * Lithium-family ports redirect CompoundTag HashMap allocation and may inject
     * blocking chunk-access work. Radium is a Forge Lithium fork with the same
     * CompoundTag redirect ownership as Lithium and Canary.
     */
    public static boolean isLithiumFamilyId(String id) {
        return "lithium".equals(id) || "canary".equals(id) || "radium".equals(id);
    }

    public static boolean isLithiumFamilyLoaded() {
        return isModLoaded("lithium") || isModLoaded("canary") || isModLoaded("radium");
    }

    /**
     * C2ME-branded artifacts use mod ID {@code c2me}. Threaded Horizons is a
     * standalone replacement with overlapping mixins, so that combination is
     * not supported.
     */
    public static boolean isC2meBrandedLoaded() {
        return isModLoaded("c2me");
    }

    public static void rejectUnsupportedC2meCoexistence() {
        rejectUnsupportedC2meCoexistence(isC2meBrandedLoaded());
    }

    public static void rejectUnsupportedC2meCoexistence(boolean c2meBrandedLoaded) {
        if (c2meBrandedLoaded) {
            throw new IllegalStateException(
                    "Threaded Horizons is a standalone Forge C2ME replacement (mod ID threadedhorizons) "
                            + "and must not be installed alongside a C2ME-branded artifact. "
                            + "Overlapping mixins are not a supported combination.");
        }
    }

    public static boolean isClient() {
        try {
            return FMLEnvironment.dist == Dist.CLIENT;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    public static Optional<String> getModVersion(String id) {
        return resolveModVersion(
                id,
                () -> {
                    try {
                        ModList modList = ModList.get();
                        if (modList == null) {
                            return Optional.empty();
                        }
                        return modList.getModContainerById(id)
                                .map(container -> container.getModInfo().getVersion().toString());
                    } catch (Throwable ignored) {
                        return Optional.empty();
                    }
                },
                () -> {
                    try {
                        var loading = FMLLoader.getLoadingModList();
                        if (loading == null || loading.getModFileById(id) == null) {
                            return Optional.empty();
                        }
                        return loading.getModFileById(id).getMods().stream()
                                .filter(mod -> id.equals(mod.getModId()))
                                .findFirst()
                                .map(mod -> mod.getVersion().toString());
                    } catch (Throwable ignored) {
                        return Optional.empty();
                    }
                });
    }

    static Optional<String> resolveModVersion(
            String id,
            Supplier<Optional<String>> populatedLookup,
            Supplier<Optional<String>> earlyLookup) {
        return firstNonBlank(safeLookup(populatedLookup), safeLookup(earlyLookup));
    }

    private static Optional<String> safeLookup(Supplier<Optional<String>> lookup) {
        try {
            return lookup.get();
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static Optional<String> firstNonBlank(Optional<String> first, Optional<String> second) {
        if (first != null && first.filter(value -> !value.isBlank()).isPresent()) {
            return first;
        }
        if (second != null) {
            return second.filter(value -> !value.isBlank());
        }
        return Optional.empty();
    }
}
