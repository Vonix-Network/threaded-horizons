package network.vonix.threadedhorizons.common.optimization.worldgen.global_biome_cache;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.mojang.datafixers.util.Function4;
import net.minecraft.core.IdMap;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Registry;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;

/**
 * Per-sampler section biome cache. Outer keys are sampler <em>object identity</em>
 * (not Climate.Sampler structural equality) so two equal-but-distinct sampler
 * instances never share entries. The outer sampler cache is bounded; eviction
 * may drop cached data but never merges sampler namespaces.
 *
 * <p>This type is retained for isolation tests and for a possible later enable,
 * but {@code useGlobalBiomeCache} stays false on this 1.18.2 Forge target.
 * Upstream C2ME 1.18.2 declared the same key unsafe. The unused biome-preload
 * executor from that tree is not started here.
 */
public class MultiBiomeCache {

    /** Bounded number of distinct Climate.Sampler identity caches retained at once. */
    public static final int MAX_SAMPLER_CACHES = 64;
    public static final int PER_SAMPLER_SECTION_MAXIMUM = 8192;

    private final IdMap<Holder<Biome>> registry;

    private final Function4<Integer, Integer, Integer, Climate.Sampler, Holder<Biome>> delegate;

    public MultiBiomeCache(Function4<Integer, Integer, Integer, Climate.Sampler, Holder<Biome>> delegate, IdMap<Holder<Biome>> registry) {
        this.registry = registry;
        this.delegate = delegate;
    }

    /**
     * Guava LoadingCache uses equals/hashCode. Climate.Sampler is a record, so
     * structurally equal instances would share a cache under a raw Sampler key.
     * {@link SamplerIdentity} forces object-identity partitioning.
     */
    private final LoadingCache<SamplerIdentity, LoadingCache<SectionPos, Holder<Biome>[][][]>> biomeCaches =
            CacheBuilder.newBuilder()
                    .maximumSize(MAX_SAMPLER_CACHES)
                    .build(new CacheLoader<>() {
                        @Override
                        public LoadingCache<SectionPos, Holder<Biome>[][][]> load(SamplerIdentity identity) {
                            return createCache(identity.sampler);
                        }
                    });

    private LoadingCache<SectionPos, Holder<Biome>[][][]> createCache(Climate.Sampler multiNoiseSampler) {
        Preconditions.checkNotNull(multiNoiseSampler);
        return CacheBuilder.newBuilder()
                .softValues()
                .maximumSize(PER_SAMPLER_SECTION_MAXIMUM)
                .build(new CacheLoader<>() {
                    @Override
                    public Holder<Biome>[][][] load(SectionPos key) {
                        int startX = QuartPos.fromBlock(key.minBlockX());
                        int startY = QuartPos.fromBlock(key.minBlockY());
                        int startZ = QuartPos.fromBlock(key.minBlockZ());
                        final Holder<Biome>[][][] result = new Holder[4][4][4];
                        for (int x = startX; x < startX + 4; x++) {
                            for (int y = startY; y < startY + 4; y++) {
                                for (int z = startZ; z < startZ + 4; z++) {
                                    result[x - startX][y - startY][z - startZ] = delegate.apply(x, y, z, multiNoiseSampler);
                                    Preconditions.checkNotNull(result[x - startX][y - startY][z - startZ]);
                                }
                            }
                        }
                        return result;
                    }
                });
    }

    private LoadingCache<SectionPos, Holder<Biome>[][][]> getCache(Climate.Sampler multiNoiseSampler) {
        return this.biomeCaches.getUnchecked(new SamplerIdentity(multiNoiseSampler));
    }

    private Holder[][][] getCachedBiome(SectionPos chunkSectionPos, Climate.Sampler multiNoiseSampler) {
        return getCache(multiNoiseSampler).getUnchecked(chunkSectionPos);
    }

    public Holder<Biome> getBiomeForNoiseGen(int biomeX, int biomeY, int biomeZ, Climate.Sampler multiNoiseSampler, boolean fast) {
        final SectionPos chunkPos = SectionPos.of(QuartPos.toSection(biomeX), QuartPos.toSection(biomeY), QuartPos.toSection(biomeZ));
        final int offsetX = biomeX - QuartPos.fromBlock(chunkPos.minBlockX());
        final int offsetY = biomeY - QuartPos.fromBlock(chunkPos.minBlockY());
        final int offsetZ = biomeZ - QuartPos.fromBlock(chunkPos.minBlockZ());
        return getCachedBiome(chunkPos, multiNoiseSampler)[offsetX][offsetY][offsetZ];
    }

    public Holder<Biome>[][][] preloadBiomes(SectionPos pos, Holder<Biome>[][][] def, Climate.Sampler multiNoiseSampler) {
        if (def != null) {
            getCache(multiNoiseSampler).put(pos, def);
            return def;
        } else {
            return getCachedBiome(pos, multiNoiseSampler);
        }
    }

    /** Test/diagnostic: number of sampler-identity caches currently retained. */
    public long samplerCacheCount() {
        this.biomeCaches.cleanUp();
        return this.biomeCaches.size();
    }

    /**
     * Identity-only key. equals/hashCode use System.identityHashCode / == so
     * Climate.Sampler record equality cannot merge distinct instances.
     */
    private static final class SamplerIdentity {
        private final Climate.Sampler sampler;

        private SamplerIdentity(Climate.Sampler sampler) {
            this.sampler = Preconditions.checkNotNull(sampler);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SamplerIdentity identity && this.sampler == identity.sampler;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this.sampler);
        }
    }

    public interface BiomeProvider {
        Biome sample(Registry<Biome> biomeRegistry, int x, int y, int z);
    }

}
