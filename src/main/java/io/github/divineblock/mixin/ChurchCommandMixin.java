package io.github.divineblock.mixin;

import com.mojang.brigadier.ParseResults;
import io.github.divineblock.game.ChurchGuard;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Commands (/setblock, /fill…) may change a church; the god's own (/divineblock act) may not. */
@Mixin(CommandManager.class)
abstract class ChurchCommandMixin {
    @Inject(method = "execute", at = @At("HEAD"))
    private void divineblock$commandBegins(ParseResults<ServerCommandSource> parse, String command, CallbackInfo info) {
        if (!god(command)) ChurchGuard.playerBegins();
    }
    @Inject(method = "execute", at = @At("RETURN"))
    private void divineblock$commandEnds(ParseResults<ServerCommandSource> parse, String command, CallbackInfo info) {
        if (!god(command)) ChurchGuard.playerEnds();
    }
    private static boolean god(String command) { String c = command.startsWith("/") ? command.substring(1) : command; return c.startsWith("divineblock"); }
}
