package network.vonix.threadedhorizons.common.optimization.worldgen.global_biome_cache;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import network.vonix.threadedhorizons.common.config.ThreadedHorizonsConfig;
import network.vonix.threadedhorizons.common.util.ListIndexedIterable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class MultiBiomeCacheTest {

    @Test
    void distinctSamplerIdentitiesDoNotShareSectionEntries() {
        List<Holder<Biome>> holders = List.of(marker(1), marker(2));
        Map<Climate.Sampler, Holder<Biome>> bySampler = new IdentityHashMap<>();
        MultiBiomeCache cache = new MultiBiomeCache((x, y, z, sampler) -> {
            Holder<Biome> holder = bySampler.get(sampler);
            if (holder == null) {
                throw new IllegalStateException("unknown sampler");
            }
            return holder;
        }, new ListIndexedIterable<>(holders));

        Climate.Sampler samplerA = samplerIdentity(1);
        Climate.Sampler samplerB = samplerIdentity(2);
        bySampler.put(samplerA, holders.get(0));
        bySampler.put(samplerB, holders.get(1));

        Holder<Biome> fromA = cache.getBiomeForNoiseGen(0, 0, 0, samplerA, false);
        Holder<Biome> fromB = cache.getBiomeForNoiseGen(0, 0, 0, samplerB, false);
        assertSame(holders.get(0), fromA);
        assertSame(holders.get(1), fromB);
        assertNotSame(fromA, fromB);
        assertEquals(2L, cache.samplerCacheCount());
    }

    @Test
    void structurallyEqualButDistinctSamplersDoNotShareCaches() {
        List<Holder<Biome>> holders = List.of(marker(10), marker(20));
        Map<Climate.Sampler, Holder<Biome>> bySampler = new IdentityHashMap<>();
        MultiBiomeCache cache = new MultiBiomeCache((x, y, z, sampler) -> {
            Holder<Biome> holder = bySampler.get(sampler);
            if (holder == null) {
                throw new IllegalStateException("unknown sampler identity");
            }
            return holder;
        }, new ListIndexedIterable<>(holders));

        // Same structural components (null density functions + empty spawn list) so
        // Climate.Sampler.equals would be true, but object identity differs.
        Climate.Sampler first = samplerStructurallyEmpty();
        Climate.Sampler second = samplerStructurallyEmpty();
        assertNotSame(first, second);
        assertEquals(first, second, "precondition: samplers are structurally equal records");

        bySampler.put(first, holders.get(0));
        bySampler.put(second, holders.get(1));

        Holder<Biome> fromFirst = cache.getBiomeForNoiseGen(0, 0, 0, first, false);
        Holder<Biome> fromSecond = cache.getBiomeForNoiseGen(0, 0, 0, second, false);
        assertSame(holders.get(0), fromFirst);
        assertSame(holders.get(1), fromSecond);
        assertNotSame(fromFirst, fromSecond);
        assertEquals(2L, cache.samplerCacheCount(), "identity keys must retain both outer caches");
    }

    @Test
    void outerSamplerCacheNeverExceedsConfiguredMaximum() {
        List<Holder<Biome>> holders = new ArrayList<>();
        for (int index = 0; index < MultiBiomeCache.MAX_SAMPLER_CACHES + 16; index++) {
            holders.add(marker(index));
        }
        AtomicInteger calls = new AtomicInteger();
        MultiBiomeCache cache = new MultiBiomeCache((x, y, z, sampler) -> {
            calls.incrementAndGet();
            int id = Math.floorMod(System.identityHashCode(sampler), holders.size());
            return holders.get(id);
        }, new ListIndexedIterable<>(holders));

        long peak = 0L;
        for (int index = 0; index < MultiBiomeCache.MAX_SAMPLER_CACHES + 16; index++) {
            Climate.Sampler sampler = samplerIdentity(index);
            cache.getBiomeForNoiseGen(0, 0, 0, sampler, false);
            peak = Math.max(peak, cache.samplerCacheCount());
            assertTrue(cache.samplerCacheCount() <= MultiBiomeCache.MAX_SAMPLER_CACHES,
                    "retained=" + cache.samplerCacheCount());
        }
        assertTrue(peak <= MultiBiomeCache.MAX_SAMPLER_CACHES);
        assertTrue(cache.samplerCacheCount() <= MultiBiomeCache.MAX_SAMPLER_CACHES);
    }

    @Test
    void afterEvictionResampleStillMatchesDelegate() {
        List<Holder<Biome>> holders = new ArrayList<>();
        for (int index = 0; index < MultiBiomeCache.MAX_SAMPLER_CACHES + 8; index++) {
            holders.add(marker(index));
        }
        Map<Climate.Sampler, Holder<Biome>> bySampler = new IdentityHashMap<>();
        MultiBiomeCache cache = new MultiBiomeCache((x, y, z, sampler) -> bySampler.get(sampler),
                new ListIndexedIterable<>(holders));

        Climate.Sampler first = samplerIdentity(10);
        bySampler.put(first, holders.get(0));
        Holder<Biome> original = cache.getBiomeForNoiseGen(4, 4, 4, first, false);
        assertSame(holders.get(0), original);

        for (int index = 1; index <= MultiBiomeCache.MAX_SAMPLER_CACHES + 2; index++) {
            Climate.Sampler extra = samplerIdentity(100 + index);
            bySampler.put(extra, holders.get(index % holders.size()));
            cache.getBiomeForNoiseGen(0, 0, 0, extra, false);
        }
        assertTrue(cache.samplerCacheCount() <= MultiBiomeCache.MAX_SAMPLER_CACHES);

        Holder<Biome> resampled = cache.getBiomeForNoiseGen(4, 4, 4, first, false);
        assertSame(holders.get(0), resampled);
        assertFalse(ThreadedHorizonsConfig.threadedWorldGenConfig.useGlobalBiomeCache,
                "isolation tests do not enable the excluded family");
        SectionPos section = SectionPos.of(QuartPos.toSection(4), QuartPos.toSection(4), QuartPos.toSection(4));
        Holder<Biome> direct = bySampler.get(first);
        assertSame(direct, cache.getBiomeForNoiseGen(
                QuartPos.fromBlock(section.minBlockX()),
                QuartPos.fromBlock(section.minBlockY()),
                QuartPos.fromBlock(section.minBlockZ()),
                first,
                false));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Holder<Biome> marker(int id) {
        return (Holder) Holder.direct(Integer.valueOf(id));
    }

    /**
     * Build distinct Climate.Sampler identities without touching DensityFunctions
     * static init (requires full Minecraft bootstrap).
     */
    private static Climate.Sampler samplerIdentity(int seed) {
        try {
            Constructor<Climate.Sampler> ctor = Climate.Sampler.class.getDeclaredConstructor(
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    List.class
            );
            ctor.setAccessible(true);
            // Distinct object graph so identity/equals differ without DensityFunctions.constant.
            return ctor.newInstance(null, null, null, null, null, null, List.of(Integer.valueOf(seed)));
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }

    /** Two calls produce equal records with distinct object identity. */
    private static Climate.Sampler samplerStructurallyEmpty() {
        try {
            Constructor<Climate.Sampler> ctor = Climate.Sampler.class.getDeclaredConstructor(
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    net.minecraft.world.level.levelgen.DensityFunction.class,
                    List.class
            );
            ctor.setAccessible(true);
            return ctor.newInstance(null, null, null, null, null, null, List.of());
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException(exception);
        }
    }
}
