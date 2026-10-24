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
                // Who is on the stream: the god knows it (one streamer per server), without special favour or wrath.
                .then(CommandManager.literal("streamer")
                        .executes(context -> {
                            String name = Multiplayer.streamerName(context.getSource().getServer());
                            context.getSource().sendFeedback(() -> Text.literal(name.isEmpty() ? "Стример не назначен" : "Стример: " + name), false);
                            return 1;
                        })
                        .then(CommandManager.literal("clear").executes(context -> {
                            Multiplayer.streamer(context.getSource().getServer(), null, null);
                            context.getSource().sendFeedback(() -> Text.literal("Стример снят"), true);
                            return 1;
                        }))
                        .then(CommandManager.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (String name : context.getSource().getServer().getPlayerNames())
                                        if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) builder.suggest(name);
                                    return builder.buildFuture();
                                })
                                .executes(context -> streamer(context.getSource(), StringArgumentType.getString(context, "name")))))
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
                                .executes(context -> act(context.getSource(), StringArgumentType.getString(context, "id"), -1, 1, null))
                                .then(CommandManager.argument("power", IntegerArgumentType.integer(0, 4))
                                        .executes(context -> act(context.getSource(), StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "power"), 1, null))
                                        .then(CommandManager.argument("severity", IntegerArgumentType.integer(1, 5))
                                                .executes(context -> act(context.getSource(), StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "power"), IntegerArgumentType.getInteger(context, "severity"), null))
                                                .then(culprit(context -> IntegerArgumentType.getInteger(context, "power"),
                                                        context -> IntegerArgumentType.getInteger(context, "severity"))))
                                        // After the numbers, so «act id 3» stays a power: the culprit, on whom the act falls and whom Mob Talk's villages blame.
                                        .then(culprit(context -> IntegerArgumentType.getInteger(context, "power"), context -> 1)))
                                .then(culprit(context -> -1, context -> 1))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, ?> culprit(
            java.util.function.ToIntFunction<com.mojang.brigadier.context.CommandContext<ServerCommandSource>> power,
            java.util.function.ToIntFunction<com.mojang.brigadier.context.CommandContext<ServerCommandSource>> severity) {
        return CommandManager.argument("player", net.minecraft.command.argument.EntityArgumentType.player())
                .executes(context -> act(context.getSource(), StringArgumentType.getString(context, "id"), power.applyAsInt(context), severity.applyAsInt(context),
                        net.minecraft.command.argument.EntityArgumentType.getPlayer(context, "player")));
    }

    private static int streamer(ServerCommandSource source, String name) {
        var profile = Multiplayer.profile(source.getServer(), name);
        if (profile.isEmpty()) { source.sendError(Text.literal("Игрок " + name + " не найден: он должен хоть раз зайти на сервер")); return 0; }
        Multiplayer.streamer(source.getServer(), profile.get().getId(), profile.get().getName());
        source.sendFeedback(() -> Text.literal("Стример: " + profile.get().getName()), true);
        return 1;
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

    private static int act(ServerCommandSource source, String id, int power, int severity, ServerPlayerEntity culprit) {
        ServerPlayerEntity player = culprit != null ? culprit : source.getPlayer();
        Miracle miracle = Miracle.byId(id.toLowerCase(Locale.ROOT));
        if (player == null || miracle == null) { source.sendError(Text.literal("Нет такого деяния: " + id + ". Список: /divineblock list")); return 0; }
        BlockPos altar = player.getBlockPos();
        for (BlockPos pos : BlockPos.iterateOutwards(player.getBlockPos(), 12, 6, 12))
            if (player.getServerWorld().getBlockState(pos).getBlock() instanceof AltarBlock) { altar = pos.toImmutable(); break; }
        int strength = power < 0 ? miracle.maxTier : Math.max(miracle.minTier, Math.min(miracle.maxTier, power));
        BlockPos at = altar;
        ServerPlayerEntity target = player;
        String done = VillageEvents.within(new VillageEvents.Context(false, "команда " + source.getName(), java.util.List.of()),
                () -> Zones.perform(miracle, strength, target, at, "", DivineBlockMod.config(), severity));
        source.sendFeedback(() -> Text.literal(miracle.id() + " ×" + strength + ": " + (done == null ? "не вышло здесь" : done)), false);
        acted.accept(miracle.id() + " ×" + strength + " /" + severity + ": " + (done == null ? "не вышло здесь" : done));
        return done == null ? 0 : 1;
    }
}
