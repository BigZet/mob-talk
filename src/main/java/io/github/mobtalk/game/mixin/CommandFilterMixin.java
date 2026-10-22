package io.github.mobtalk.game.mixin;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;
import io.github.mobtalk.server.CommandFilter;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;

/** Трек C: команды модов сборки по уровням прав ({@link CommandFilter}) — прячет их из дерева игрока и отказывает при выполнении. */
@Mixin(CommandManager.class)
abstract class CommandFilterMixin {
    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void mobtalk$refuse(ParseResults<ServerCommandSource> parse, String command, CallbackInfo info) {
        ServerCommandSource source = parse.getContext().getSource();
        String refusal = CommandFilter.refusal(source, command);
        if (refusal == null) return;
        source.sendError(Text.literal(refusal));
        info.cancel();
    }

    @Redirect(method = "makeTreeForSource", at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/tree/CommandNode;canUse(Ljava/lang/Object;)Z"))
    private boolean mobtalk$visible(CommandNode<ServerCommandSource> child, Object source, CommandNode<ServerCommandSource> tree,
                                    CommandNode<CommandSource> result, ServerCommandSource owner, Map<CommandNode<ServerCommandSource>, CommandNode<CommandSource>> nodes) {
        return child.canUse(owner) && CommandFilter.visible(owner, tree, child);
    }
}
