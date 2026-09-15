package network.vonix.threadedhorizons.common.config.updater;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Consumer;

public class Updaters {

    static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Config Updater");
    private static final Long2ObjectOpenHashMap<Consumer<Config>> updaters = new Long2ObjectOpenHashMap<>();

    static {
        updaters.put(0, source -> {
            Queue<Runnable> pendingActions = new LinkedList<>();
            Queue<Config> pendingConfigs = new LinkedList<>();
            pendingConfigs.add(source);
            while (!pendingConfigs.isEmpty()) {
                final Config config = pendingConfigs.poll();
                for (Config.Entry entry : config.entrySet()) {
                    if (entry.getValue() instanceof Config) pendingConfigs.add(entry.getValue());
                    else pendingActions.add(() -> entry.setValue("default"));
                }
            }
            pendingActions.forEach(Runnable::run);
        });
        updaters.put(1, source -> {
            final Object _ioSystem = source.get("asyncIO");
            if (_ioSystem instanceof CommentedConfig ioSystem) {
                // rename asyncIO to ioSystem
                source.remove("asyncIO");
                source.add("ioSystem", ioSystem);

                final Object _async = ioSystem.get("enabled");
                if (_async instanceof Boolean async) {
                    // rename enabled to async
                    ioSystem.remove("enabled");
                    ioSystem.add("async", async);
                }
            }
        });
        updaters.put(2, source -> {
            final Object generalOptimizations = source.get("generalOptimizations");
            if (generalOptimizations instanceof Config generalConfig) {
                generalConfig.set("optimizeAsyncChunkRequest", false);
            }
            final Object threadedWorldGen = source.get("threadedWorldGen");
            if (threadedWorldGen instanceof Config worldGenConfig) {
                worldGenConfig.set("reduceLockRadius", false);
            }
        });
        updaters.put(3, source -> {
            final Object threadedWorldGen = source.get("threadedWorldGen");
            if (threadedWorldGen instanceof Config worldGenConfig) {
                worldGenConfig.set("reduceLockRadius", true);
            }
        });
        updaters.put(4, source -> {
            final Object ioSystem = source.get("ioSystem");
            if (ioSystem instanceof Config ioConfig) {
                clampIoCacheLimit(ioConfig, "chunkDataCacheSoftLimit", 4096);
                clampIoCacheLimit(ioConfig, "chunkDataCacheLimit", 8192);
            }
        });
    }

    static void clampIoCacheLimit(Config ioConfig, String key, int max) {
        final Object value = ioConfig.get(key);
        if (!(value instanceof Number number)) {
            return;
        }
        int configured = number.intValue();
        if (configured > max || configured < 1) {
            int clamped = Math.min(Math.max(1, configured), max);
            LOGGER.warn("Migrating {} from {} to {} to prevent unbounded IO NBT retention", key, configured, clamped);
            ioConfig.set(key, clamped);
        }
    }

    public static void update(Config config) {
        while (true) {
            final long version = config.getLongOrElse("version", 0);
            final Consumer<Config> updater = updaters.get(version);
            if (updater != null) {
                LOGGER.info("Updating config from {} to {}", version, version + 1);
                updater.accept(config);
                config.set("version", version + 1);
            } else {
                break;
            }
        }
    }

}
