package io.github.divineblock.game;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.Miracle;
import java.util.Locale;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/** For testing (cheats on): {@code /divineblock act <id> [power] [severity]} performs one act of the catalogue at once, as if
 * the god had chosen it, at the nearest altar or where the player stands; {@code /divineblock list} names them all. */
final class DivineCommands {
    private DivineCommands() { }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(CommandManager.literal("divineblock")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("list").executes(context -> {
                    StringBuilder out = new StringBuilder("Деяния: ");
                    for (Miracle miracle : Miracle.values()) out.append(miracle.id()).append(' ');
                    context.getSource().sendFeedback(() -> Text.literal(out.toString().strip()), false);
                    return 1;
                }))
                .then(CommandManager.literal("act")
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (Miracle miracle : Miracle.values()) if (miracle.id().startsWith(builder.getRemainingLowerCase())) builder.suggest(miracle.id());
                                    return builder.buildFuture();
                                })
                                .executes(context -> act(context.getSource(), StringArgumentType.getString(context, "id"), -1, 1))
                                .then(CommandManager.argument("power", IntegerArgumentType.integer(0, 4))
                                        .executes(context -> act(context.getSource(), StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "power"), 1))
                                        .then(CommandManager.argument("severity", IntegerArgumentType.integer(1, 5))
                                                .executes(context -> act(context.getSource(), StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "power"), IntegerArgumentType.getInteger(context, "severity")))))))));
    }

    private static int act(ServerCommandSource source, String id, int power, int severity) {
        ServerPlayerEntity player = source.getPlayer();
        Miracle miracle = Miracle.byId(id.toLowerCase(Locale.ROOT));
        if (player == null || miracle == null) { source.sendError(Text.literal("Нет такого деяния: " + id + ". Список: /divineblock list")); return 0; }
        BlockPos altar = player.getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(player.getBlockPos(), 12, 6, 12))
            if (player.getServerWorld().getBlockState(pos).getBlock() instanceof AltarBlock) { altar = pos.toImmutable(); break; }
        int strength = power < 0 ? miracle.maxTier : Math.max(miracle.minTier, Math.min(miracle.maxTier, power));
        String done = Miracles.perform(miracle, strength, player, altar, "", DivineBlockMod.config(), severity);
        source.sendFeedback(() -> Text.literal(miracle.id() + " ×" + strength + ": " + (done == null ? "не вышло здесь" : done)), false);
        return done == null ? 0 : 1;
    }
}
