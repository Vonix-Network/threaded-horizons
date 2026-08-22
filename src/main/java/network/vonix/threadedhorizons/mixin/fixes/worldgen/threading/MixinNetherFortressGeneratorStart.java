package network.vonix.threadedhorizons.mixin.fixes.worldgen.threading;

import network.vonix.threadedhorizons.common.fixes.worldgen.threading.INetherFortressGeneratorPieceData;
import net.minecraft.world.level.levelgen.structure.NetherBridgePieces;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

@Mixin(NetherBridgePieces.StartPiece.class)
public class MixinNetherFortressGeneratorStart {

    @Shadow public List<NetherBridgePieces.PieceWeight> availableBridgePieces;
    @Shadow public List<NetherBridgePieces.PieceWeight> availableCastlePieces;

    @Redirect(method = "<init>(Ljava/util/Random;II)V", at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/levelgen/structure/NetherBridgePieces$PieceWeight;placeCount:I", opcode = Opcodes.PUTFIELD))
    private void redirectSetPieceDataGeneratedCount(NetherBridgePieces.PieceWeight pieceData, int value) {
        // Never mutate the shared static prototype arrays during StartPiece construction.
    }

    @Inject(method = "<init>(Ljava/util/Random;II)V", at = @At("RETURN"))
    private void onInit(CallbackInfo info) {
        this.availableBridgePieces = isolated(this.availableBridgePieces);
        this.availableCastlePieces = isolated(this.availableCastlePieces);
    }

    private static List<NetherBridgePieces.PieceWeight> isolated(List<NetherBridgePieces.PieceWeight> source) {
        List<NetherBridgePieces.PieceWeight> copy = new ArrayList<>(source.size());
        for (NetherBridgePieces.PieceWeight piece : source) {
            copy.add(new NetherBridgePieces.PieceWeight(piece.pieceClass, piece.weight, piece.maxPlaceCount, piece.allowInRow));
        }
        return Collections.synchronizedList(copy);
    }
}
