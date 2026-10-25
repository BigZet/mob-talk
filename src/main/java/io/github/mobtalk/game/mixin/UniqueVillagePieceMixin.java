package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.UniqueVillagePieces;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolElement;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.util.List;

/** One bar per village: when the jigsaw generator draws the candidates for the next piece, the buildings of
 * {@link UniqueVillagePieces} the village already has drop out. */
@Mixin(targets = "net.minecraft.structure.pool.StructurePoolBasedGenerator$StructurePoolGenerator")
abstract class UniqueVillagePieceMixin {
    @Shadow @Final private List<? super PoolStructurePiece> children;

    @Redirect(method = "generatePiece", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/structure/pool/StructurePool;getElementIndicesInRandomOrder(Lnet/minecraft/util/math/random/Random;)Ljava/util/List;"))
    private List<StructurePoolElement> mobtalk$onePerVillage(StructurePool pool, Random random) {
        return UniqueVillagePieces.without(pool.getElementIndicesInRandomOrder(random), children);
    }
}
