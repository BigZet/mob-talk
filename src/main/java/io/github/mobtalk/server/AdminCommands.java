package io.github.mobtalk.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.mobtalk.MobTalkMod;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.WhitelistEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Серверная команда {@code /mobtalk admin …} (бриф C, шаг 4): стример, админы, бюджет, модель, функции, любое серверное поле,
 * адрес админки, перечитать настройки, whitelist по никам, игроки онлайн. Право — ADMIN ({@link CommandFilter}); логика —
 * {@link AdminActions}. Ник в UUID: игрок онлайн, иначе офлайн-UUID на сервере без проверки аккаунтов, иначе кэш профилей
 * (запрос к Mojang идёт не на потоке сервера). Клиентский {@code /mobtalk} пересылает сюда {@code admin …} как есть. */
public final class AdminCommands {
    private AdminCommands() { }

    private static volatile Supplier<String> webAddress = () -> "";
    /** Точка подключения трека W1: адрес веб-админки для {@code /mobtalk admin web}. */
    public static void setWebAddress(Supplier<String> address) { webAddress = address == null ? () -> "" : address; }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> register(dispatcher));
    }

    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        SuggestionProvider<ServerCommandSource> online = (context, builder) -> CommandSource.suggestMatching(context.getSource().getPlayerNames(), builder);
        SuggestionProvider<ServerCommandSource> fields = (context, builder) -> CommandSource.suggestMatching(AdminActions.settable(), builder);
        SuggestionProvider<ServerCommandSource> features = (context, builder) -> CommandSource.suggestMatching(AdminActions.settable().stream()
                .filter(name -> { var f = AdminActions.field(name); return f != null && f.getType() == boolean.class; }).toList(), builder);
        SuggestionProvider<ServerCommandSource> kinds = (context, builder) -> CommandSource.suggestMatching(
                Arrays.stream(PlayerBudget.Kind.values()).map(PlayerBudgetImpl::kindName).map(name -> name.contains(" ") ? "\"" + name + "\"" : name).toList(), builder);

        LiteralArgumentBuilder<ServerCommandSource> admin = literal("admin")
                .requires(source -> CommandFilter.refusal(source, "mobtalk admin") == null)
                .executes(context -> reply(context, help()))
                .then(literal("streamer")
                        .executes(context -> reply(context, AdminActions.showStreamer(id -> name(context.getSource().getServer(), id))))
                        .then(literal("clear").executes(context -> reply(context, AdminActions.clearStreamer())))
                        .then(argument("nick", StringArgumentType.greedyString()).suggests(online)
                                .executes(context -> withPlayer(context, StringArgumentType.getString(context, "nick"), AdminActions::streamer))))
                .then(literal("admin")
                        .then(literal("list").executes(context -> reply(context, AdminActions.listAdmins(id -> name(context.getSource().getServer(), id)))))
                        .then(literal("add").then(argument("nick", StringArgumentType.greedyString()).suggests(online)
                                .executes(context -> withPlayer(context, StringArgumentType.getString(context, "nick"), AdminActions::addAdmin))))
                        .then(literal("remove").then(argument("nick", StringArgumentType.greedyString()).suggests(online)
                                .executes(context -> withPlayer(context, StringArgumentType.getString(context, "nick"), AdminActions::removeAdmin)))))
                .then(literal("budget")
                        .executes(context -> reply(context, MobTalkMod.budget().report()))
                        .then(literal("show").executes(context -> reply(context, MobTalkMod.budget().report())))
                        .then(literal("set").then(argument("who", StringArgumentType.string()).suggests((context, builder) -> {
                                    builder.suggest("all");
                                    return CommandSource.suggestMatching(context.getSource().getPlayerNames(), builder);
                                })
                                .then(argument("kind", StringArgumentType.string()).suggests(kinds)
                                        .then(argument("perMinute", IntegerArgumentType.integer(0, 10_000))
                                                .then(argument("perDay", DoubleArgumentType.doubleArg(0, 10_000)).executes(AdminCommands::budgetSet)))))))
                .then(literal("model").then(argument("id", StringArgumentType.greedyString())
                        .executes(context -> reply(context, AdminActions.model(StringArgumentType.getString(context, "id"))))))
                .then(literal("feature").then(argument("name", StringArgumentType.word()).suggests(features)
                        .then(literal("on").executes(context -> reply(context, AdminActions.feature(StringArgumentType.getString(context, "name"), true))))
                        .then(literal("off").executes(context -> reply(context, AdminActions.feature(StringArgumentType.getString(context, "name"), false))))))
                .then(literal("set").then(argument("field", StringArgumentType.word()).suggests(fields)
                        .then(argument("value", StringArgumentType.greedyString())
                                .executes(context -> reply(context, AdminActions.set(StringArgumentType.getString(context, "field"), StringArgumentType.getString(context, "value")))))))
                .then(literal("get").then(argument("field", StringArgumentType.word()).suggests(fields)
                        .executes(context -> reply(context, AdminActions.get(StringArgumentType.getString(context, "field"))))))
                .then(literal("web").executes(context -> {
                    String address = webAddress.get();
                    return reply(context, address == null || address.isBlank() ? "Веб-админка не запущена" : "Веб-админка: " + address);
                }))
                .then(literal("reload").executes(AdminCommands::reload))
                .then(literal("players").executes(context -> reply(context, players(context.getSource().getServer()))))
                .then(literal("whitelist")
                        .then(literal("list").executes(context -> reply(context, whitelistList(context.getSource().getServer()))))
                        .then(literal("on").executes(context -> whitelistToggle(context, true)))
                        .then(literal("off").executes(context -> whitelistToggle(context, false)))
                        .then(literal("add").then(argument("nick", StringArgumentType.greedyString()).suggests(online)
                                .executes(context -> whitelist(context, StringArgumentType.getString(context, "nick"), true))))
                        .then(literal("remove").then(argument("nick", StringArgumentType.greedyString()).suggests(online)
                                .executes(context -> whitelist(context, StringArgumentType.getString(context, "nick"), false)))));
        dispatcher.register(literal("mobtalk").then(admin));
    }

    static String help() {
        return "/mobtalk admin: streamer <ник>|clear, admin add|remove|list, budget show|set <ник|all> <вид> <в минуту> <$ в день>, "
                + "model <id>, feature <имя> on|off, set <поле> <значение>, get <поле>, web, reload, players, whitelist add|remove|list|on|off";
    }

    private static int reply(CommandContext<ServerCommandSource> context, String text) {
        context.getSource().sendFeedback(() -> Text.literal("[Mob Talk] " + text), false);
        return 1;
    }

    // ---- Ник → профиль. ----

    /** Профиль игрока по нику: онлайн, офлайн-UUID (сервер без проверки аккаунтов), иначе кэш профилей Mojang. Готов на потоке сервера. */
    static CompletableFuture<Optional<GameProfile>> profile(MinecraftServer server, String nick) {
        String wanted = nick == null ? "" : nick.strip();
        if (wanted.startsWith("\"") && wanted.endsWith("\"") && wanted.length() > 1) wanted = wanted.substring(1, wanted.length() - 1);
        if (wanted.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(wanted);
        if (player != null) return CompletableFuture.completedFuture(Optional.of(player.getGameProfile()));
        if (!server.isOnlineMode()) return CompletableFuture.completedFuture(Optional.of(Uuids.getOfflinePlayerProfile(wanted)));
        if (server.getUserCache() == null) return CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<GameProfile>> done = new CompletableFuture<>();
        server.getUserCache().findByNameAsync(wanted).whenComplete((found, error) -> server.execute(() -> done.complete(error == null && found != null ? found : Optional.empty())));
        return done;
    }

    private static int withPlayer(CommandContext<ServerCommandSource> context, String nick, BiFunction<UUID, String, String> action) {
        ServerCommandSource source = context.getSource();
        profile(source.getServer(), nick).thenAccept(found -> {
            String text = found.map(p -> action.apply(p.getId(), p.getName())).orElse("Игрок «" + nick + "» не найден");
            source.sendFeedback(() -> Text.literal("[Mob Talk] " + text), false);
        });
        return 1;
    }

    private static int budgetSet(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String who = StringArgumentType.getString(context, "who"), kind = StringArgumentType.getString(context, "kind");
        int perMinute = IntegerArgumentType.getInteger(context, "perMinute");
        double perDay = DoubleArgumentType.getDouble(context, "perDay");
        if ("all".equalsIgnoreCase(who) || "все".equalsIgnoreCase(who))
            return reply(context, AdminActions.budgetSet(MobTalkMod.budget(), "all", "все", kind, perMinute, perDay));
        withPlayer(context, who, (id, name) -> AdminActions.budgetSet(MobTalkMod.budget(), id.toString(), name, kind, perMinute, perDay));
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        if (!ServerConfig.persistent()) return reply(context, "В одиночной игре настройки сервера берутся из F7");
        CompletableFuture.runAsync(() -> {
            String text;
            try { ServerConfig.init(FabricLoader.getInstance().getConfigDir()); text = "Настройки сервера перечитаны из " + ServerConfig.FILE; }
            catch (Exception failure) { text = "Не удалось перечитать " + ServerConfig.FILE + ": " + failure.getClass().getSimpleName(); }
            String result = text;
            source.getServer().execute(() -> source.sendFeedback(() -> Text.literal("[Mob Talk] " + result), false));
        });
        return 1;
    }

    // ---- Игроки и whitelist. ----

    static String players(MinecraftServer server) {
        List<ServerPlayerEntity> online = server.getPlayerManager().getPlayerList();
        if (online.isEmpty()) return "Игроков нет";
        StringBuilder out = new StringBuilder("Игроки онлайн:");
        for (ServerPlayerEntity player : online) {
            String level = switch (MobTalkMod.permissions().level(player)) { case ADMIN -> "админ"; case STREAMER -> "стример"; case PLAYER -> "игрок"; };
            String spent = MobTalkMod.budget().spentToday(player.getUuid());
            out.append("\n  ").append(player.getGameProfile().getName()).append(" — ").append(level).append(spent.isBlank() ? "" : ", сегодня " + spent);
        }
        return out.toString();
    }

    private static String whitelistList(MinecraftServer server) {
        PlayerManager manager = server.getPlayerManager();
        String[] names = manager.getWhitelistedNames();
        return "Whitelist " + (manager.isWhitelistEnabled() ? "включён" : "выключен") + ": " + (names.length == 0 ? "пусто" : String.join(", ", names));
    }

    private static int whitelistToggle(CommandContext<ServerCommandSource> context, boolean on) {
        MinecraftServer server = context.getSource().getServer();
        server.getPlayerManager().setWhitelistEnabled(on);
        if (on) server.kickNonWhitelistedPlayers(context.getSource());
        return reply(context, on ? "Whitelist включён: войти могут только игроки из списка" : "Whitelist выключен");
    }

    private static int whitelist(CommandContext<ServerCommandSource> context, String nick, boolean add) {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();
        profile(server, nick).thenAccept(found -> {
            String text;
            if (found.isEmpty()) text = "Игрок «" + nick + "» не найден";
            else {
                GameProfile profile = found.get();
                var list = server.getPlayerManager().getWhitelist();
                if (add) {
                    if (list.isAllowed(profile)) text = profile.getName() + " уже в whitelist";
                    else { list.add(new WhitelistEntry(profile)); text = profile.getName() + " добавлен в whitelist"; }
                } else {
                    if (!list.isAllowed(profile)) text = profile.getName() + " нет в whitelist";
                    else { list.remove(profile); server.kickNonWhitelistedPlayers(source); text = profile.getName() + " убран из whitelist"; }
                }
            }
            source.sendFeedback(() -> Text.literal("[Mob Talk] " + text), false);
        });
        return 1;
    }

    /** Ник игрока по UUID для ответов: онлайн, кэш профилей, иначе начало UUID. */
    static String name(MinecraftServer server, UUID id) {
        if (id == null) return "";
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
        if (player != null) return player.getGameProfile().getName();
        if (MobTalkMod.budget() instanceof PlayerBudgetImpl budget) return budget.name(id);
        return server.getUserCache() == null ? id.toString() : server.getUserCache().getByUuid(id).map(GameProfile::getName).orElse(id.toString());
    }

}
