package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.Roads;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.chunk.AbstractChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The other way a chunk can keep the world from ever unloading on the way out (see {@link ChunkClaimsAtShutdownMixin}): its
 * saving future, combined from the generation steps' futures, never done. While the server stops, a holder whose saving has not
 * been done {@link Roads#PATIENCE_NANOS} after it was first asked about is reported savable as it stands. */
@Mixin(ChunkHolder.class)
abstract class ChunkSavingAtShutdownMixin {
    @Shadow private CompletableFuture<?> savingFuture;
    @Unique private long mobtalk$savingSince;
    @Unique private boolean mobtalk$letGo;

    @Inject(method = "isSavable", at = @At("HEAD"), cancellable = true)
    private void mobtalk$savableAtShutdown(CallbackInfoReturnable<Boolean> cir) {
        if (Roads.stoppingSince() == 0 || savingFuture.isDone()) { mobtalk$savingSince = 0; return; }
        mobtalk$savingSince = Roads.firstStuck(mobtalk$savingSince);
        if (!Roads.patienceOver(mobtalk$savingSince)) return;
        if (!mobtalk$letGo) {
            mobtalk$letGo = true;
            AbstractChunkHolder self = (AbstractChunkHolder) (Object) this;
            Roads.letGo(self.getPos().x, self.getPos().z, "saving never done");
        }
        cir.setReturnValue(true);
    }
}
