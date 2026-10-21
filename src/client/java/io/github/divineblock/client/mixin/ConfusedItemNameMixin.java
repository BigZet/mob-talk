package io.github.divineblock.client.mixin;

import io.github.divineblock.client.boss.NimrodConfusion;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** «Смешение языков»: item names read on the client thread (tooltips, the held item's name) come scrambled while the
 * player is confused. The integrated server runs on its own thread and is never affected. */
@Mixin(ItemStack.class)
abstract class ConfusedItemNameMixin {
    @Inject(method = "getName", at = @At("RETURN"), cancellable = true)
    private void divineblock$confuse(CallbackInfoReturnable<Text> info) {
        if (NimrodConfusion.active()) info.setReturnValue(NimrodConfusion.scramble(info.getReturnValue()));
    }
}
