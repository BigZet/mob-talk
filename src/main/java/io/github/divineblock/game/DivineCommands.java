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
public final class DivineCommands {
    /** What each {@code act} did, for the self-test's report. */
    public static volatile java.util.function.Consumer<String> acted = line -> { };

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
                // The way back from the eternal ban, for an operator (or in single player with cheats, from a copy of the world).
                .then(CommandManager.literal("unban")
                        .executes(context -> unban(context.getSource(), null))
                        .then(CommandManager.argument("name", StringArgumentType.word()).executes(context -> unban(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(CommandManager.literal("curses").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    long now = player.getServer().getOverworld().getTime();
                    var mine = Curses.of(player.getServer(), player.getUuid());
                    int streak = DivineState.of(player.getServer()).ledger.soul(player.getUuid()).wrathStreak(DeedWatcher.day(player.getServer()));
                    StringBuilder out = new StringBuilder(mine.isEmpty() ? "Проклятий нет" : "Проклятия: ");
                    for (int i = 0; i < mine.size(); i++) out.append(i == 0 ? "" : "; ").append(mine.get(i).describe(now));
                    out.append(". Кар подряд: ").append(streak);
                    context.getSource().sendFeedback(() -> Text.literal(out.toString()), false);
                    return 1;
                }))
                .then(CommandManager.literal("lift").executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    int lifted = Curses.lift(player.getServer(), player.getUuid());
                    DivineState.of(player.getServer()).ledger.soul(player.getUuid()).forgiven();
                    DivineState.of(player.getServer()).markDirty();
                    context.getSource().sendFeedback(() -> Text.literal("Снято проклятий: " + lifted + ", лестница кар сброшена"), false);
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

    private static int unban(ServerCommandSource source, String name) {
        var server = source.getServer();
        java.util.UUID target = null;
        if (name != null) {
            for (var entry : DivineState.of(server).banned.entrySet()) if (entry.getValue().name.equalsIgnoreCase(name)) target = java.util.UUID.fromString(entry.getKey());
            if (target == null) { source.sendError(Text.literal("Изгнанного с именем " + name + " в этом мире нет")); return 0; }
        }
        int lifted = Banishment.unban(server, target, "команда " + source.getName());
        source.sendFeedback(() -> Text.literal(lifted == 0 ? "Изгнанных в этом мире нет" : "Изгнание снято: " + lifted), true);
        return lifted;
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
        acted.accept(miracle.id() + " ×" + strength + " /" + severity + ": " + (done == null ? "не вышло здесь" : done));
        return done == null ? 0 : 1;
    }
}
