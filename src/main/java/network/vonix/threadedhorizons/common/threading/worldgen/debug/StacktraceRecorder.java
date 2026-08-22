package network.vonix.threadedhorizons.common.threading.worldgen.debug;


import com.google.common.collect.Sets;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import com.mojang.logging.LogUtils;
import network.vonix.threadedhorizons.platform.LoaderHooks;
import network.vonix.threadedhorizons.platform.MappingHooks;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

// Used to examine getChunk calls with reduced lock radius
public class StacktraceRecorder {

    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Reduced-radius stack recording is explicitly opt-in. reduceLockRadius=true
     * alone must not allocate diagnostics on hot paths.
     */
    private static final boolean CONFIGURED_DO_RECORD = Boolean.getBoolean("network.vonix.threadedhorizons.common.threading.worldgen.debug.DebugReducedLockRadius")
            && ThreadedHorizonsConfig.threadedWorldGenConfig.reduceLockRadius;
    private static final boolean warnAtWarningLevel = !Boolean.getBoolean("network.vonix.threadedhorizons.common.threading.worldgen.debug.DebugReducedLockRadiusAtWarningLevel");
    private static final int recordFrequency = Mth.clamp(Integer.getInteger("network.vonix.threadedhorizons.common.threading.worldgen.debug.DebugReducedLockRadiusFrequency", 4), 1, 16);
    private static final int maxRecordedStacktraces = Mth.clamp(Integer.getInteger("network.vonix.threadedhorizons.common.threading.worldgen.debug.DebugReducedLockRadiusMaxUnique", 256), 16, 4096);
    private static final long frequencyBitMask = (1L << recordFrequency) - 1;

    // Diagnostics must never become an unbounded heap owner on a live server.
    private static final Set<StacktraceHolder> recordedStacktraces = Sets.newConcurrentHashSet();
    private static final AtomicLong sampledCount = new AtomicLong();
    private static final AtomicInteger throwableCreations = new AtomicInteger();
    private static volatile Supplier<Throwable> throwableFactory = Throwable::new;
    private static volatile Boolean recordingOverrideForTest = null;
    private static volatile Integer maxUniqueOverrideForTest = null;

    private static boolean doRecord() {
        Boolean override = recordingOverrideForTest;
        return override != null ? override : CONFIGURED_DO_RECORD;
    }

    private static int maxUnique() {
        Integer override = maxUniqueOverrideForTest;
        return override != null ? override : maxRecordedStacktraces;
    }

    public static void record() {
        if (!doRecord()) return;
        if ((sampledCount.incrementAndGet() & frequencyBitMask) != 0) return;
        doRecordSample(Thread.currentThread().getStackTrace(), true);
    }

    private static void doRecordSample(StackTraceElement[] stackTrace, boolean logNew) {
        // Capture stack without constructing Throwable; dedupe first.
        final StacktraceHolder probe = new StacktraceHolder(stackTrace, null);
        synchronized (recordedStacktraces) {
            if (recordedStacktraces.size() >= maxUnique()) return;
            if (recordedStacktraces.contains(probe)) return;
            // Only construct a Throwable for a genuinely new trace that will be retained/logged.
            final Throwable throwable = throwableFactory.get();
            throwableCreations.incrementAndGet();
            if (throwable.getStackTrace() == null || throwable.getStackTrace().length == 0) {
                throwable.setStackTrace(stackTrace);
            }
            final StacktraceHolder stacktraceHolder = new StacktraceHolder(stackTrace, throwable);
            if (recordedStacktraces.add(stacktraceHolder) && logNew && stacktraceHolder.needPrint()) {
                LOGGER.warn("Potential dangerous call with reducedLockRadius", stacktraceHolder.throwable);
            }
        }
    }

    // --- test hooks ---

    public static boolean isRecordingEnabled() {
        return doRecord();
    }

    public static boolean isConfiguredRecordingEnabled() {
        return CONFIGURED_DO_RECORD;
    }

    public static int throwableCreationCount() {
        return throwableCreations.get();
    }

    public static int retainedTraceCount() {
        return recordedStacktraces.size();
    }

    public static int maxUniqueTraces() {
        return maxUnique();
    }

    public static void resetForTest() {
        synchronized (recordedStacktraces) {
            recordedStacktraces.clear();
        }
        sampledCount.set(0L);
        throwableCreations.set(0);
        throwableFactory = Throwable::new;
        recordingOverrideForTest = null;
        maxUniqueOverrideForTest = null;
    }

    public static void setRecordingEnabledForTest(Boolean enabled) {
        recordingOverrideForTest = enabled;
    }

    public static void setMaxUniqueForTest(Integer maxUnique) {
        maxUniqueOverrideForTest = maxUnique;
    }

    public static void setThrowableFactoryForTest(Supplier<Throwable> factory) {
        throwableFactory = factory == null ? Throwable::new : factory;
    }

    /**
     * Force a sampled record attempt that bypasses the frequency mask (tests only).
     */
    public static void recordForcedForTest() {
        if (!doRecord()) return;
        doRecordSample(Thread.currentThread().getStackTrace(), false);
    }

    public static void recordForcedWithTraceForTest(StackTraceElement[] stackTrace) {
        if (!doRecord()) return;
        doRecordSample(stackTrace, false);
    }


    public static class StacktraceHolder {

        private static final String StructureProcessor$process = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor", "processBlock", "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;)Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate$StructureBlockInfo;");
        private static final String BlendingData$getBlendingData = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.levelgen.blending.BlendingData", "getOrUpdateBlendingData", "(Lnet/minecraft/server/level/WorldGenRegion;II)Lnet/minecraft/world/level/levelgen/blending/BlendingData;");
        private static final String ChunkGenerator$carve = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.chunk.ChunkGenerator", "applyCarvers", "(Lnet/minecraft/server/level/WorldGenRegion;JLnet/minecraft/world/level/biome/BiomeManager;Lnet/minecraft/world/level/StructureFeatureManager;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/GenerationStep$Carving;)V");
        private static final String NaturalSpawner$populateEntities = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.NaturalSpawner", "spawnMobsForChunkGeneration", "(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/level/biome/Biome;Lnet/minecraft/world/level/ChunkPos;Ljava/util/Random;)V");
        private static final String StructureFeatureManager$method_41032 = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.StructureFeatureManager", "method_41032", "(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/feature/ConfiguredStructureFeature;)Ljava/util/List;");
        private static final String BiomeManager$Storage$getBiomeForNoiseGen = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.biome.BiomeManager$NoiseBiomeSource", "getNoiseBiome", "(III)Lnet/minecraft/core/Holder;");
        private static final String BlockCollisions$getChunk = MappingHooks.resolver()
                .mapMethodName("intermediary", "net.minecraft.world.level.BlockCollisions", "getChunk", "(II)Lnet/minecraft/world/level/BlockGetter;");

        @NotNull
        private final StackTraceElement[] stackTrace;
        private final Throwable throwable;

        public StacktraceHolder() {
            // Legacy path retained for binary compatibility; prefer the lazy factory path.
            this.throwable = throwableFactory.get();
            throwableCreations.incrementAndGet();
            this.stackTrace = this.throwable.getStackTrace();
        }

        StacktraceHolder(StackTraceElement[] stackTrace, Throwable throwable) {
            this.stackTrace = stackTrace;
            this.throwable = throwable;
        }

        public boolean needPrint() {
            for (StackTraceElement stackTraceElement : stackTrace) {
                if (stackTraceElement.getMethodName().equals("method_26971"))
                    return false;
                if (stackTraceElement.getClassName().equals(RuleProcessor.class.getName()) &&
                        stackTraceElement.getMethodName().equals(StructureProcessor$process))
                    return false;
                if (stackTraceElement.getClassName().equals(BlendingData.class.getName()) &&
                        stackTraceElement.getMethodName().equals(BlendingData$getBlendingData))
                    return false;
                if (stackTraceElement.getClassName().equals(NoiseBasedChunkGenerator.class.getName()) &&
                        stackTraceElement.getMethodName().equals(ChunkGenerator$carve))
                    return false;
                if (stackTraceElement.getClassName().equals(NaturalSpawner.class.getName()) &&
                        stackTraceElement.getMethodName().equals(NaturalSpawner$populateEntities))
                    return false;
                if (stackTraceElement.getClassName().equals(StructureFeatureManager.class.getName()) &&
                        stackTraceElement.getMethodName().equals(StructureFeatureManager$method_41032))
                    return false;
                if (stackTraceElement.getClassName().equals(LevelReader.class.getName()) &&
                        stackTraceElement.getMethodName().equals(BiomeManager$Storage$getBiomeForNoiseGen))
                    return false;
                if (stackTraceElement.getClassName().equals(BlockCollisions.class.getName()) &&
                        stackTraceElement.getMethodName().equals(BlockCollisions$getChunk))
                    return false;

                // lithium
                if (stackTraceElement.getClassName().equals("me.jellysquid.mods.lithium.common.entity.movement.ChunkAwareBlockCollisionSweeper"))
                    return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "StacktraceHolder{" +
                    "stackTrace=" + Arrays.toString(stackTrace) +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StacktraceHolder that = (StacktraceHolder) o;
            return Arrays.equals(stackTrace, that.stackTrace);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(stackTrace);
        }
    }

}
