package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.IStrongholdGenerator;
import net.minecraft.world.level.levelgen.structure.StrongholdPieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

@Mixin(StrongholdPieces.class)
public class MixinStrongholdGenerator implements IStrongholdGenerator {

    @Shadow @Final private static StrongholdPieces.PieceWeight[] STRONGHOLD_PIECE_WEIGHTS;
    @Shadow private static List<StrongholdPieces.PieceWeight> currentPieces;
    private static final ThreadLocal<Integer> totalWeightThreadLocal = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<List<StrongholdPieces.PieceWeight>> currentPiecesThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<Class<? extends StrongholdPieces.StrongholdPiece>> activePieceTypeThreadLocal = new ThreadLocal<>();

    @Redirect(method = "resetPieces", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;currentPieces:Ljava/util/List;", opcode = Opcodes.PUTSTATIC))
    private static void redirectAssignList(List<StrongholdPieces.PieceWeight> value) {
        final List<StrongholdPieces.PieceWeight> pieceDataList = new ArrayList<>(STRONGHOLD_PIECE_WEIGHTS.length);
        for (StrongholdPieces.PieceWeight pieceData : STRONGHOLD_PIECE_WEIGHTS) {
            pieceDataList.add(copyPieceWeight(pieceData));
        }
        final List<StrongholdPieces.PieceWeight> isolated = Collections.synchronizedList(pieceDataList);
        currentPiecesThreadLocal.set(isolated);
        activePieceTypeThreadLocal.remove();

    }

    private static StrongholdPieces.PieceWeight copyPieceWeight(StrongholdPieces.PieceWeight prototype) {
        final boolean depthFour = prototype.getClass().getName().endsWith("$1");
        final boolean depthFive = prototype.getClass().getName().endsWith("$2");
        return new StrongholdPieces.PieceWeight(prototype.pieceClass, prototype.weight, prototype.maxPlaceCount) {
            @Override
            public boolean doPlace(int depth) {
                if (!super.doPlace(depth)) return false;
                return (!depthFour || depth > 4) && (!depthFive || depth > 5);
            }
        };
    }

    @Redirect(method = "resetPieces", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces$PieceWeight;placeCount:I", opcode = Opcodes.PUTFIELD))
    private static void redirectSetGeneratedCount(StrongholdPieces.PieceWeight pieceData, int value) {
        // no-op
    }

    @Redirect(method = "resetPieces", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"))
    private static <E> boolean redirectListAdd(List<E> list, E e) {
        return false; // no-op
    }

    @Redirect(method = {"updatePieceWeight", "generatePieceFromSmallDoor"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;currentPieces:Ljava/util/List;", opcode = Opcodes.GETSTATIC))
    private static List<StrongholdPieces.PieceWeight> redirectGetCurrentPieces() {
        List<StrongholdPieces.PieceWeight> isolated = currentPiecesThreadLocal.get();
        if (isolated == null) throw new IllegalStateException("Stronghold generation state was not initialized on this thread");
        return isolated;
    }

    @Redirect(method = "updatePieceWeight", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;totalWeight:I", opcode = Opcodes.PUTSTATIC))
    private static void redirectSetTotalWeight(int value) {
        totalWeightThreadLocal.set(value);
    }

    @Redirect(method = {"generatePieceFromSmallDoor", "updatePieceWeight"}, at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;totalWeight:I", opcode = Opcodes.GETSTATIC))
    private static int redirectGetTotalWeight() {
        return totalWeightThreadLocal.get();
    }

    @Redirect(method = "generatePieceFromSmallDoor", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;imposedPiece:Ljava/lang/Class;", opcode = Opcodes.PUTSTATIC))
    private static void redirectSetActivePieceType(Class<? extends StrongholdPieces.StrongholdPiece> value) {
        activePieceTypeThreadLocal.set(value);
    }

    @Redirect(method = "generatePieceFromSmallDoor", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/StrongholdPieces;imposedPiece:Ljava/lang/Class;", opcode = Opcodes.GETSTATIC))
    private static Class<? extends StrongholdPieces.StrongholdPiece> redirectGetActivePieceType() {
        return activePieceTypeThreadLocal.get();
    }

    @Override
    public ThreadLocal<Class<? extends StrongholdPieces.StrongholdPiece>> getActivePieceTypeThreadLocal() {
        return activePieceTypeThreadLocal;
    }
}
