package io.github.mobtalk.game;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/** /alarm — what the alarm of the village where you stand is about; /alarm clear — end it at once; /alarm clear all — end every
 * village's alarm in this world. Operators only (cheats on in a single-player world). */
public final class DefenseCommands {
    private DefenseCommands() { }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(CommandManager.literal("alarm")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> show(context.getSource()))
                .then(CommandManager.literal("clear").executes(context -> clear(context.getSource()))
                        .then(CommandManager.literal("all").executes(context -> clearAll(context.getSource()))))));
    }

    private static VillageDefenseController defense(ServerCommandSource source) {
        MobGameBridge bridge = MobGameBridge.active(source.getServer());
        return bridge == null ? null : bridge.defense;
    }

    private static int show(ServerCommandSource source) {
        VillageDefenseController defense = defense(source);
        String line = defense == null ? null : defense.describeAt(source.getWorld(), BlockPos.ofFloored(source.getPosition()));
        source.sendFeedback(() -> Text.literal(line == null ? "Здесь тревоги нет" : "Тревога в деревне " + line), false);
        return line == null ? 0 : 1;
    }

    private static int clear(ServerCommandSource source) {
        VillageDefenseController defense = defense(source);
        Text village = defense == null ? null : defense.clearAt(source.getWorld(), BlockPos.ofFloored(source.getPosition()));
        if (village == null) { source.sendFeedback(() -> Text.literal("Здесь тревоги нет"), false); return 0; }
        source.sendFeedback(() -> Text.literal("Тревога в деревне " + village.getString() + " снята"), true);
        return 1;
    }

    private static int clearAll(ServerCommandSource source) {
        VillageDefenseController defense = defense(source);
        int count = defense == null ? 0 : defense.clearAll(source.getWorld());
        source.sendFeedback(() -> Text.literal("Снято тревог: " + count), true);
        return count;
    }
}
