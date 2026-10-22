package io.github.mobtalk.selftestguard.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The self-test never captures the cursor, even if the window gets focus. */
@Mixin(Mouse.class)
abstract class MouseMixin {
    @Inject(method = "lockCursor", at = @At("HEAD"), cancellable = true)
    private void selftestGuard$keepCursor(CallbackInfo ci) { ci.cancel(); }
}
