package network.vonix.threadedhorizons.common.threading.chunkio;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import network.vonix.threadedhorizons.common.lifecycle.WorkerThreadLocals;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsyncSerializationManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Threaded Horizons Async Serialization Manager");

    private static final ThreadLocal<ArrayDeque<Scope>> scopeHolder = WorkerThreadLocals.create(ArrayDeque::new);

    public static void push(Scope scope) {
        scopeHolder.get().push(scope);
    }

    public static Scope getScope(ChunkPos pos) {
        final Scope scope = scopeHolder.get().peek();
        if (pos == null) return scope;
        if (scope != null) {
            if (scope.pos.equals(pos))
                return scope;
            LOGGER.error("Scope position mismatch! Expected: {} but got {}. This will impact stability. Incompatible mods?", scope.pos, pos, new Throwable());
        }
        return null;
    }

    public static void pop(Scope scope) {
        if (scope != scopeHolder.get().peek()) throw new IllegalArgumentException("Scope mismatch");
        scopeHolder.get().pop();
    }

    public static class Scope {
        public final ChunkPos pos;
        public final Map<LightLayer, LayerLightEventListener> lighting;
        public final Set<BlockPos> blockEntityPositions;
        private final AtomicBoolean isOpen = new AtomicBoolean(false);

        public Scope(ChunkAccess chunk, ServerLevel world) {
            this.pos = chunk.getPos();
            EnumMap<LightLayer, LayerLightEventListener> lightingViews = new EnumMap<>(LightLayer.class);
            LevelLightEngine lightEngine = world.getLightEngine();
            for (LightLayer type : LightLayer.values()) {
                lightingViews.put(type, new CachedLightingView(lightEngine, chunk.getPos(), type));
            }
            this.lighting = lightingViews;
            this.blockEntityPositions = new HashSet<>(chunk.getBlockEntitiesPos());
        }

        public void open() {
            if (!isOpen.compareAndSet(false, true)) throw new IllegalStateException("Cannot use scope twice");
        }

        private static final class CachedLightingView implements LayerLightEventListener {

            private static final DataLayer EMPTY = new DataLayer();

            private final LightLayer lightType;
            private final int chunkX;
            private final int chunkZ;
            private final int minSection;
            private final LayerLightEventListener live;
            private final DataLayer[] cached;
            private final byte[] state;

            CachedLightingView(LevelLightEngine provider, ChunkPos pos, LightLayer type) {
                this.lightType = type;
                this.chunkX = pos.x;
                this.chunkZ = pos.z;
                this.minSection = provider.getMinLightSection();
                int count = Math.max(0, provider.getMaxLightSection() - this.minSection);
                this.live = provider.getLayerListener(type);
                this.cached = new DataLayer[count];
                this.state = new byte[count];
            }

            public LightLayer getLightType() {
                return this.lightType;
            }

            @Override
            public void checkBlock(BlockPos blockPos) {
            }

            @Override
            public void onBlockEmissionIncrease(BlockPos blockPos, int i) {
            }

            @Override
            public boolean hasLightWork() {
                return false;
            }

            @Override
            public int runUpdates(int i, boolean bl, boolean bl2) {
                return i;
            }

            @Override
            public void updateSectionStatus(SectionPos pos, boolean notReady) {
            }

            @Override
            public void enableLightSources(ChunkPos chunkPos, boolean bl) {
            }

            @NotNull
            @Override
            public DataLayer getDataLayerData(SectionPos pos) {
                if (pos.getX() != this.chunkX || pos.getZ() != this.chunkZ) {
                    return EMPTY;
                }
                int index = pos.getY() - this.minSection;
                if (index < 0 || index >= this.cached.length) {
                    return EMPTY;
                }
                byte seen = this.state[index];
                if (seen == 1) {
                    return this.cached[index];
                }
                if (seen == 2) {
                    return EMPTY;
                }
                DataLayer lighting = this.live.getDataLayerData(pos);
                if (lighting == null) {
                    this.state[index] = 2;
                    return EMPTY;
                }
                DataLayer copy = lighting.copy();
                this.cached[index] = copy;
                this.state[index] = 1;
                return copy;
            }

            @Override
            public int getLightValue(BlockPos pos) {
                DataLayer layer = getDataLayerData(SectionPos.of(pos));
                return layer.get(
                        SectionPos.sectionRelative(pos.getX()),
                        SectionPos.sectionRelative(pos.getY()),
                        SectionPos.sectionRelative(pos.getZ())
                );
            }
        }
    }

}
