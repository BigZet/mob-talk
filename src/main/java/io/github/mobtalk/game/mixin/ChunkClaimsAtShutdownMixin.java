package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.Roads;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.chunk.AbstractChunkHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The game (1.21.1) can lose count of its claims on a chunk: a chunk loader that never runs its last step leaves the holder's
 * reference count above zero, the world's unloading on the way out waits for it to come back to zero, and the game hangs for ever
 * at "Saving worlds" (seen in the roads' self-tests, 2026-10-25, where the roads' chunk reading and the villages held for their
 * views make it likely). While the server stops, a holder still claimed {@link Roads#PATIENCE_NANOS} after it was first found so
 * is let go: its count reads as zero to the unloading, and the chunk is saved and dropped as if the claim had been released. The
 * count itself is left alone, so a release that does come late neither fails nor matters. */
@Mixin(AbstractChunkHolder.class)
abstract class ChunkClaimsAtShutdownMixin {
    @Shadow @Final private AtomicInteger refCount;
    @Unique private long mobtalk$claimedSince;
    @Unique private boolean mobtalk$letGo;

    @Inject(method = "getRefCount", at = @At("HEAD"), cancellable = true)
    private void mobtalk$letGoAtShutdown(CallbackInfoReturnable<Integer> cir) {
        if (Roads.stoppingSince() == 0) { mobtalk$claimedSince = 0; return; }
        int claims = refCount.get();
        if (claims == 0) { mobtalk$claimedSince = 0; return; }
        mobtalk$claimedSince = Roads.firstStuck(mobtalk$claimedSince);
        if (!Roads.patienceOver(mobtalk$claimedSince)) return;
        if (!mobtalk$letGo) {
            mobtalk$letGo = true;
            AbstractChunkHolder self = (AbstractChunkHolder) (Object) this;
            Roads.letGo(self.getPos().x, self.getPos().z, "claims " + claims + " never released");
        }
        cir.setReturnValue(0);
    }
}
