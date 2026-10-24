package io.github.mobtalk.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.server.web.AdminWeb;
import io.github.mobtalk.server.web.ApiException;
import io.github.mobtalk.server.web.Json;
import io.github.mobtalk.server.web.Moments;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** {@code /clip} — метки для монтажа (идея З4): {@code /clip [текст]} отмечает момент, {@code start}/{@code stop} открывают и закрывают
 * сессию записи без OBS, {@code shift <с>} сдвигает её таймкоды, {@code list} и {@code export} показывают моменты и главы YouTube
 * (строки копируются кликом). Право — STREAMER по умолчанию ({@link PermissionsImpl}, корень {@code clip}; игрокам открывает
 * {@code commandLevels} «clip»: «PLAYER»). Работает на выделенном сервере с админкой; чтение истории — не на потоке сервера. */
public final class ClipCommands {
    private ClipCommands() { }
    static final String PREFIX = "[Моменты] ";

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> register(dispatcher));
    }

    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("clip")
                .executes(context -> mark(context, null))
                .then(literal("start").executes(context -> start(context, 0))
                        .then(argument("секунд_назад", IntegerArgumentType.integer(0, 86_400)).executes(context -> start(context, IntegerArgumentType.getInteger(context, "секунд_назад")))))
                .then(literal("stop").executes(ClipCommands::stop))
                .then(literal("shift").then(argument("секунды", IntegerArgumentType.integer(-3600, 3600)).executes(context -> shift(context, IntegerArgumentType.getInteger(context, "секунды")))))
                .then(literal("list").executes(ClipCommands::list))
                .then(literal("export").executes(ClipCommands::export))
                .then(argument("текст", StringArgumentType.greedyString()).executes(context -> mark(context, StringArgumentType.getString(context, "текст")))));
    }

    /** The key «Отметить момент» comes here too (MomentKeyC2S). */
    public static void markFromKey(ServerPlayerEntity player) {
        Moments moments = moments();
        if (moments == null || !allowed(player)) return;
        JsonObject record = moments.mark(Json.player(player.getUuid(), player.getGameProfile().getName()), null, -1, player.getGameProfile().getName());
        MomentLink.confirm(player, record);
    }

    static boolean allowed(ServerPlayerEntity player) {
        Permissions permissions = MobTalkMod.permissions();
        if (permissions instanceof PermissionsImpl impl) return impl.allows(impl.level(player), "clip", null);
        return permissions != null && permissions.level(player) != Permissions.Level.PLAYER;
    }

    private static Moments moments() { AdminWeb web = AdminLink.web(); return web == null ? null : web.moments(); }

    private static int mark(CommandContext<ServerCommandSource> context, String title) {
        return withMoments(context, (player, moments) -> {
            JsonObject record = moments.mark(Json.player(player.getUuid(), player.getGameProfile().getName()), title, -1, player.getGameProfile().getName());
            MomentLink.confirm(player, record);
            return null;
        });
    }

    private static int start(CommandContext<ServerCommandSource> context, int back) {
        return withMoments(context, (player, moments) -> {
            moments.startSession(Json.player(player.getUuid(), player.getGameProfile().getName()), "command", back);
            return "Сессия записи началась" + (back > 0 ? " " + back + " с назад" : "") + ". Таймкоды моментов считаются от этого времени.";
        });
    }

    private static int stop(CommandContext<ServerCommandSource> context) {
        return withMoments(context, (player, moments) -> moments.stopSession(player.getUuidAsString(), "command") == null
                ? "Открытой сессии записи нет." : "Сессия записи закончена. Моменты — во вкладке «Моменты» админки или /clip export.");
    }

    private static int shift(CommandContext<ServerCommandSource> context, int seconds) {
        return withMoments(context, (player, moments) -> {
            String session = moments.latestSession(player.getUuidAsString());
            if (session == null) return "Сессий записи ещё нет: /clip start.";
            long total = moments.shiftOf(session) + seconds * 1000L;
            moments.shift(session, total);
            return "Все таймкоды сессии сдвинуты: теперь " + (total >= 0 ? "+" : "") + total / 1000 + " с.";
        });
    }

    /** The last ten moments of the newest session, read off the server thread. */
    private static int list(CommandContext<ServerCommandSource> context) {
        return later(context, (player, moments) -> {
            String session = moments.latestSession(player.getUuidAsString());
            if (session == null) return Text.literal(PREFIX + "Сессий записи ещё нет: /clip start или подключите OBS в F7.");
            JsonArray items = moments.moments(session, 1, false, false, 24).getAsJsonArray("items");
            MutableText text = Text.literal(PREFIX + "Моментов в последней сессии: " + items.size());
            for (int i = Math.max(0, items.size() - 10); i < items.size(); i++) {
                JsonObject item = items.get(i).getAsJsonObject();
                String line = Moments.clockOf(Json.number(item, "timecode", 0)) + " " + "★".repeat((int) Json.number(item, "weight", 1)) + " " + Json.string(item, "title");
                text.append(Text.literal("\n" + line).styled(style -> copy(style, line)));
            }
            return text;
        });
    }

    /** YouTube chapters of the newest session, each line and the whole block copyable. */
    private static int export(CommandContext<ServerCommandSource> context) {
        return later(context, (player, moments) -> {
            String session = moments.latestSession(player.getUuidAsString());
            if (session == null) return Text.literal(PREFIX + "Сессий записи ещё нет: /clip start или подключите OBS в F7.");
            String chapters = new String(moments.export(session, "youtube", false, 1, false).body(), StandardCharsets.UTF_8).strip();
            if (chapters.isEmpty()) return Text.literal(PREFIX + "В последней сессии моментов нет.");
            MutableText text = Text.literal(PREFIX + "Главы для YouTube ").append(Text.literal("[скопировать всё]").formatted(Formatting.AQUA).styled(style -> copy(style, chapters)));
            for (String line : chapters.split("\n")) text.append(Text.literal("\n" + line).styled(style -> copy(style, line)));
            if (chapters.split("\n").length < 3) text.append(Text.literal("\nYouTube показывает главы, только если их три и больше.").formatted(Formatting.GRAY));
            return text;
        });
    }

    private static net.minecraft.text.Style copy(net.minecraft.text.Style style, String value) {
        return style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Нажмите, чтобы скопировать")));
    }

    private interface Action<T> { T run(ServerPlayerEntity player, Moments moments); }

    private static int withMoments(CommandContext<ServerCommandSource> context, Action<String> action) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(Text.literal(PREFIX + "Команду даёт игрок: моменты привязаны к его записи.")); return 0; }
        Moments moments = moments();
        if (moments == null) { source.sendError(Text.literal(PREFIX + "Метки для монтажа работают на сервере с веб-админкой.")); return 0; }
        try {
            String answer = action.run(player, moments);
            if (answer != null) source.sendFeedback(() -> Text.literal(PREFIX + answer), false);
            return 1;
        } catch (ApiException failure) { source.sendError(Text.literal(PREFIX + failure.getMessage())); return 0; }
    }

    private static int later(CommandContext<ServerCommandSource> context, Action<Text> action) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(Text.literal(PREFIX + "Команду даёт игрок: моменты привязаны к его записи.")); return 0; }
        Moments moments = moments();
        if (moments == null) { source.sendError(Text.literal(PREFIX + "Метки для монтажа работают на сервере с веб-админкой.")); return 0; }
        CompletableFuture.supplyAsync(() -> action.run(player, moments)).whenComplete((text, failure) -> source.getServer().execute(() -> {
            if (failure != null) source.sendError(Text.literal(PREFIX + "Не получилось: " + (failure.getCause() == null ? failure : failure.getCause()).getMessage()));
            else source.sendFeedback(() -> text, false);
        }));
        return 1;
    }
}
