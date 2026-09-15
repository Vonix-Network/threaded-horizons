package network.vonix.threadedhorizons.common.optimization.worldgen.threadlocal_biome_cache;

import com.mojang.datafixers.util.Function4;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import network.vonix.threadedhorizons.common.lifecycle.WorkerThreadLocals;

import java.util.List;

public class BiomeSourceCachingDelegate extends BiomeSource {

    public static final int CACHE_CAPACITY = BoundedLinkedLongCache.BIOME_CACHE_CAPACITY;

    private final Function4<Integer, Integer, Integer, Climate.Sampler, Holder<Biome>> delegate;
    private final BiomeSource origin;
    private final ThreadLocal<Long2ObjectLinkedOpenHashMap<Holder<Biome>>> cache = WorkerThreadLocals.create(Long2ObjectLinkedOpenHashMap::new);

    public BiomeSourceCachingDelegate(Function4<Integer, Integer, Integer, Climate.Sampler, Holder<Biome>> delegate) {
        this(delegate, null);
    }

    public BiomeSourceCachingDelegate(Function4<Integer, Integer, Integer, Climate.Sampler, Holder<Biome>> delegate, BiomeSource origin) {
        super(origin != null ? List.copyOf(origin.possibleBiomes()) : List.of());
        this.delegate = delegate;
        this.origin = origin;
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return Codec.unit(this);
    }

    @Override
    public BiomeSource withSeed(long seed) {
        if (this.origin != null) {
            BiomeSource reseeds = this.origin.withSeed(seed);
            return new BiomeSourceCachingDelegate(reseeds::getNoiseBiome, reseeds);
        }
        return this;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int biomeX, int biomeY, int biomeZ, Climate.Sampler multiNoiseSampler) {
        final Long2ObjectLinkedOpenHashMap<Holder<Biome>> cache = this.cache.get();
        long l = 0L;
        l |= ((long) biomeX & BlockPos.PACKED_X_MASK) << BlockPos.X_OFFSET;
        l |= ((long) biomeY & BlockPos.PACKED_Y_MASK) << 0;
        final long key = l | ((long) biomeZ & BlockPos.PACKED_Z_MASK) << BlockPos.Z_OFFSET;
        final Holder<Biome> cachedBiome = cache.getAndMoveToLast(key);
        if (cachedBiome != null) return cachedBiome;
        final Holder<Biome> uncachedBiome = delegate.apply(biomeX, biomeY, biomeZ, multiNoiseSampler);
        BoundedLinkedLongCache.evictUntilCapacity(cache, CACHE_CAPACITY);
        cache.put(key, uncachedBiome);
        return uncachedBiome;
    }

    public int cachedSize() {
        return this.cache.get().size();
    }
}
