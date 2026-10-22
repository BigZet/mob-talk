package io.github.mobtalk.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.ActionType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import java.util.List;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** The {@code /mobtalk} command tree; every branch is a thin call into {@link MobTalkClient}. */
final class MobTalkCommands {
    private MobTalkCommands() { }

    static void register(MobTalkClient client) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("mobtalk")
                .executes(context -> { client.requestSettings(); return 1; })
                .then(literal("settings").executes(context -> { client.requestSettings(); return 1; }))
                .then(literal("say").then(argument("text", StringArgumentType.greedyString()).executes(context ->
                        client.say(StringArgumentType.getString(context, "text")) ? 1 : 0)))
                .then(literal("status").executes(context -> { client.notice(client.statusLine()); return 1; }))
                .then(literal("info").executes(context -> { client.requestInfo(); return 1; }))
                .then(literal("family").executes(context -> { client.requestFamily(); return 1; }))
                .then(literal("state").executes(context -> { client.chat("[Mob Talk] " + client.stateLine()); return 1; }))
                .then(literal("village").then(literal("forgive").executes(context -> { client.forgiveVillage(); return 1; })))
                .then(literal("clear").executes(context -> { client.clearSelection("Собеседник снят"); return 1; }))
                .then(literal("trade").executes(context -> { client.openTrade(); return 1; }))
                .then(literal("follow").executes(context -> { action(client, new ActionIntent(ActionType.FOLLOW, "player")); return 1; }))
                .then(literal("flee").executes(context -> { action(client, new ActionIntent(ActionType.FLEE, "player")); return 1; }))
                .then(literal("stop").executes(context -> { client.stopSelectedAction(); return 1; }))
                .then(literal("stop_all").executes(context -> { client.stopAllFollowers(); return 1; }))
                .then(literal("attack").then(argument("target", StringArgumentType.word()).executes(context -> {
                    action(client, new ActionIntent(ActionType.ATTACK, StringArgumentType.getString(context, "target"))); return 1;
                })))
                .then(literal("reload").executes(context -> { client.reload(); return 1; }))
                .then(literal("mic").then(literal("list").executes(context -> { listMicrophones(client); return 1; })))
                // Трек C: /mobtalk admin — серверная команда; у клиента тот же корень, поэтому пересылаем её серверу как есть.
                .then(literal("admin").executes(context -> admin(client, ""))
                        .then(argument("args", StringArgumentType.greedyString()).executes(context -> admin(client, StringArgumentType.getString(context, "args")))))));
    }

    private static int admin(MobTalkClient client, String args) {
        if (!io.github.mobtalk.client.settings.ServerSettingsView.sendToServer("mobtalk admin " + args)) client.notice("Команда не ушла серверу");
        return 1;
    }

    private static void action(MobTalkClient client, ActionIntent action) {
        client.requestMobAction(action).whenComplete((result, error) -> client.client.execute(() -> {
            if (error == null) client.notice(result); else client.notice("Не удалось применить действие: " + error.getClass().getSimpleName());
        }));
    }

    private static void listMicrophones(MobTalkClient client) {
        client.maintenance.execute(() -> {
            try {
                List<String> names = MicrophoneRecorder.devices();
                client.client.execute(() -> {
                    client.chat("[Mob Talk] microphoneName в config/mobtalk.json (пусто = микрофон по умолчанию):");
                    if (names.isEmpty()) client.chat("[Mob Talk] Подходящие устройства не найдены: проверьте доступ Windows");
                    for (String name : names) client.chat("[Mob Talk] " + name);
                });
            } catch (RuntimeException unavailable) {
                client.client.execute(() -> client.notice("Не удалось получить микрофоны: проверьте доступ Windows"));
            }
        });
    }
}
