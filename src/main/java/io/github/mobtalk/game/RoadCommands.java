package io.github.mobtalk.game;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.mobtalk.core.RoadPlan;
import java.util.Map;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** {@code /roads} (operators): {@code status}, {@code list}, {@code near} — the nearest road with its first bridge and tunnel,
 * {@code scan [x z]} — look for villages now (around the players or a spot), {@code build x1 z1 x2 z2} — a road between two spots, {@code load id} — build a road now, loading its chunks, {@code relay} — lay the loaded chunks
 * of the roads again. Works in single player and on the server (docs/roads.md). */
final class RoadCommands {
    private RoadCommands() { }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(literal("roads")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(RoadCommands::status)
                .then(literal("status").executes(RoadCommands::status))
                .then(literal("list").executes(context -> {
                    RoadNetwork roads = roads(context);
                    if (roads == null) return 0;
                    var lines = roads.list(context.getSource().getWorld());
                    if (lines.isEmpty()) reply(context, "дорог пока нет");
                    for (String line : lines) reply(context, line);
                    return lines.size();
                }))
                .then(literal("near").executes(RoadCommands::near))
                .then(literal("debug").executes(context -> {
                    RoadNetwork roads = roads(context);
                    if (roads == null) return 0;
                    for (String line : roads.debug(context.getSource().getWorld())) reply(context, line);
                    return 1;
                }))
                .then(literal("scan").executes(context -> {
                    RoadNetwork roads = roads(context);
                    if (roads == null) return 0;
                    roads.scanNow(context.getSource().getWorld());
                    return reply(context, "ищу деревни вокруг игроков");
                }).then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer()).executes(context -> {
                    RoadNetwork roads = roads(context);
                    if (roads == null) return 0;
                    roads.scanAt(context.getSource().getWorld(), IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "z"));
                    return reply(context, "ищу деревни вокруг " + IntegerArgumentType.getInteger(context, "x") + " " + IntegerArgumentType.getInteger(context, "z"));
                }))))
                .then(literal("load").then(argument("id", IntegerArgumentType.integer(1)).executes(context -> {
                    RoadNetwork roads = roads(context);
                    if (roads == null) return 0;
                    RoadNetwork.Road road = roads.road(context.getSource().getWorld(), IntegerArgumentType.getInteger(context, "id"));
                    if (road == null) return reply(context, "нет такой дороги");
                    return reply(context, "дорога #" + road.id + ": держу загруженными " + roads.load(context.getSource().getWorld(), road) + " чанков, пока она не уложена");
                })))
                .then(literal("relay").executes(context -> {
                    RoadNetwork roads = roads(context);
                    return roads == null ? 0 : reply(context, "участков в очереди: " + roads.relay(context.getSource().getWorld()));
                }))
                .then(literal("build").then(argument("x1", IntegerArgumentType.integer()).then(argument("z1", IntegerArgumentType.integer())
                        .then(argument("x2", IntegerArgumentType.integer()).then(argument("z2", IntegerArgumentType.integer()).executes(context -> {
                            RoadNetwork roads = roads(context);
                            if (roads == null) return 0;
                            ServerCommandSource source = context.getSource();
                            roads.build(source.getWorld(), IntegerArgumentType.getInteger(context, "x1"), IntegerArgumentType.getInteger(context, "z1"),
                                    IntegerArgumentType.getInteger(context, "x2"), IntegerArgumentType.getInteger(context, "z2"),
                                    line -> source.sendFeedback(() -> Text.literal("[Дороги] " + line), false));
                            return reply(context, "планирую дорогу…");
                        }))))))));
    }

    private static RoadNetwork roads(CommandContext<ServerCommandSource> context) {
        MobGameBridge bridge = MobGameBridge.active(context.getSource().getServer());
        if (bridge == null) { reply(context, "Mob Talk не запущен в этом мире"); return null; }
        return bridge.roads;
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        RoadNetwork roads = roads(context);
        return roads == null ? 0 : reply(context, roads.status(context.getSource().getWorld()));
    }

    private static int near(CommandContext<ServerCommandSource> context) {
        RoadNetwork roads = roads(context);
        if (roads == null) return 0;
        ServerWorld world = context.getSource().getWorld();
        var pos = context.getSource().getPosition();
        RoadNetwork.Near near = roads.near(world, pos.x, pos.z);
        if (near == null) return reply(context, "дорог пока нет");
        RoadPlan.Point p = near.road().points.get(near.index());
        reply(context, "дорога #" + near.road().id + " в " + Math.round(near.distance()) + " блоках: точка " + at(p) + ", здесь " + kind(p.kind())
                + ", длина " + near.road().points.size() + ", мост " + near.road().bridge + ", тоннель " + near.road().tunnel);
        for (Map.Entry<RoadPlan.Kind, RoadPlan.Point> first : RoadNetwork.firsts(near.road()).entrySet())
            reply(context, "первый " + kind(first.getKey()) + ": " + at(first.getValue()));
        return 1;
    }

    private static String at(RoadPlan.Point p) { return p.blockX() + " " + ((p.h2() + 1) >> 1) + " " + p.blockZ(); }
    private static String kind(RoadPlan.Kind kind) {
        return switch (kind) { case GROUND -> "участок по земле"; case BRIDGE -> "мост"; case TUNNEL -> "тоннель"; };
    }

    private static int reply(CommandContext<ServerCommandSource> context, String text) {
        context.getSource().sendFeedback(() -> Text.literal("[Дороги] " + text), false);
        return 1;
    }
}
