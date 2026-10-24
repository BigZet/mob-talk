package io.github.divineblock.weapon;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** {@code /divineblock weapon <id>}: the weapon into the caller's hand (operators); {@code /divineblock weapon all}: every
 * one, with stones for the sling. */
final class WeaponCommands {
    private WeaponCommands() { }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(CommandManager.literal("divineblock")
                .then(CommandManager.literal("weapon").requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("all").executes(context -> give(context.getSource(), "all")))
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> { for (String id : DivineWeapons.ids()) builder.suggest(id); return builder.buildFuture(); })
                                .executes(context -> give(context.getSource(), StringArgumentType.getString(context, "id")))))));
    }

    private static int give(ServerCommandSource source, String id) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(Text.literal("Только игроку")); return 0; }
        if (id.equals("all")) {
            for (WeaponKind kind : WeaponKind.values()) player.getInventory().insertStack(new ItemStack(DivineWeapons.item(kind)));
            player.getInventory().insertStack(new ItemStack(DivineWeapons.SLING_STONE, 32));
            source.sendFeedback(() -> Text.literal("Всё оружие бога в инвентаре"), true);
            return WeaponKind.values().length;
        }
        WeaponKind kind = WeaponKind.byId(id);
        if (kind == null) { source.sendError(Text.literal("Нет такого оружия: " + id + ". Есть: " + String.join(", ", DivineWeapons.ids()))); return 0; }
        player.getInventory().insertStack(new ItemStack(DivineWeapons.item(kind)));
        if (kind == WeaponKind.DAVID_SLING) player.getInventory().insertStack(new ItemStack(DivineWeapons.SLING_STONE, 16));
        source.sendFeedback(() -> Text.literal(kind.title + " в инвентаре"), true);
        return 1;
    }
}
