package io.github.divineblock.boss;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/** For testing (cheats on): {@code /divineblock boss <id> [power]} raises a boss eight blocks ahead of the player,
 * {@code /divineblock boss ult} makes the nearest one cast its ultimate now, {@code /divineblock boss clear} removes them. */
final class BossCommands {
    private BossCommands() { }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> dispatcher.register(CommandManager.literal("divineblock")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("boss")
                        .then(CommandManager.literal("ult").executes(context -> ult(context.getSource())))
                        .then(CommandManager.literal("clear").executes(context -> clear(context.getSource())))
                        .then(CommandManager.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (String id : DivineBosses.ids()) if (id.startsWith(builder.getRemainingLowerCase())) builder.suggest(id);
                                    return builder.buildFuture();
                                })
                                .executes(context -> summon(context.getSource(), StringArgumentType.getString(context, "id"), 3))
                                .then(CommandManager.argument("power", IntegerArgumentType.integer(1, 5))
                                        .executes(context -> summon(context.getSource(), StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "power"))))))));
    }

    private static int summon(ServerCommandSource source, String id, int power) {
        ServerPlayerEntity player = source.getPlayer();
        BossKind kind = BossKind.byId(id);
        if (player == null || kind == null) { source.sendError(Text.literal("Нет такого босса: " + id + ". Есть: " + String.join(", ", DivineBosses.ids()))); return 0; }
        Vec3d ahead = player.getPos().add(player.getRotationVector().multiply(1, 0, 1).normalize().multiply(8));
        BlockPos at = BlockPos.ofFloored(ahead);
        BlockPos ground = player.getServerWorld().getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, at);
        if (Math.abs(ground.getY() - player.getBlockY()) > 6) ground = at;
        LivingEntity boss = DivineBosses.spawn(kind, player.getServerWorld(), ground, power, null);
        if (boss == null) { source.sendError(Text.literal("Негде встать: " + kind.title)); return 0; }
        source.sendFeedback(() -> Text.literal(kind.title + " ×" + power + (boss instanceof SacredBoss ? "" : " (замена: " + boss.getType().getName().getString() + ")")), false);
        return 1;
    }

    private static int ult(ServerCommandSource source) {
        var bosses = DivineBosses.near(source.getWorld(), BlockPos.ofFloored(source.getPosition()), 64);
        if (bosses.isEmpty()) { source.sendError(Text.literal("Рядом нет босса")); return 0; }
        for (SacredBoss boss : bosses) boss.forceUltimate();
        source.sendFeedback(() -> Text.literal("Ульта: " + bosses.size()), false);
        return bosses.size();
    }

    private static int clear(ServerCommandSource source) {
        var bosses = DivineBosses.near(source.getWorld(), BlockPos.ofFloored(source.getPosition()), 128);
        for (SacredBoss boss : bosses) boss.discard();
        source.sendFeedback(() -> Text.literal("Убрано боссов: " + bosses.size()), false);
        return bosses.size();
    }
}
