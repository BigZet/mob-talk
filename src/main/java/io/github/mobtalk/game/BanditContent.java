package io.github.mobtalk.game;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** The city bandit's registrations, on both sides before any world loads: the entity type, its attributes, a spawn egg; and the
 * operator's command {@code /bandits spawn [n] | night | status | clear}. */
public final class BanditContent {
    private BanditContent() { }
    public static final Identifier BANDIT_ID = Identifier.of("mobtalk", "bandit");
    public static EntityType<BanditEntity> BANDIT;
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        BANDIT = Registry.register(Registries.ENTITY_TYPE, BANDIT_ID,
                EntityType.Builder.create(BanditEntity::new, SpawnGroup.MONSTER).dimensions(0.6f, 1.8f).eyeHeight(1.62f).maxTrackingRange(8).build("bandit"));
        FabricDefaultAttributeRegistry.register(BANDIT, BanditEntity.attributes());
        Item egg = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "bandit_spawn_egg"), new SpawnEggItem(BANDIT, 0x2B2B30, 0x8A1F1F, new Item.Settings()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> entries.add(egg));
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(literal("bandits")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("spawn").executes(c -> spawn(c, 3)).then(argument("count", IntegerArgumentType.integer(1, 8)).executes(c -> spawn(c, IntegerArgumentType.getInteger(c, "count")))))
                .then(literal("night").executes(BanditContent::night))
                .then(literal("status").executes(BanditContent::status))
                .then(literal("clear").executes(BanditContent::clear))));
    }

    private static BanditController controller(ServerCommandSource source) {
        BanditController controller = BanditController.of(source.getServer());
        if (controller == null) source.sendError(Text.literal("Mob Talk не запущен в этом мире"));
        return controller;
    }

    private static int spawn(CommandContext<ServerCommandSource> context, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        BanditController controller = controller(context.getSource());
        if (controller == null) return 0;
        List<BanditEntity> gang = controller.spawnGang((ServerWorld) player.getWorld(), player, count, false);
        context.getSource().sendFeedback(() -> Text.literal(gang.isEmpty() ? "Не нашлось места для банды рядом" : "Банда из " + gang.size() + " вышла из темноты"), false);
        return gang.size();
    }

    private static int night(CommandContext<ServerCommandSource> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        BanditController controller = controller(context.getSource());
        if (controller == null) return 0;
        controller.callTonight((ServerWorld) player.getWorld(), player);
        context.getSource().sendFeedback(() -> Text.literal("Банда придёт этой ночью, как только вы будете в городе"), false);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        BanditController controller = controller(context.getSource());
        if (controller == null) return 0;
        ServerWorld world = (ServerWorld) player.getWorld();
        List<BanditEntity> all = BanditController.bandits(world);
        StringBuilder text = new StringBuilder("Бандиты: ").append(controller.territory(world, player.getBlockPos()) ? "вы в городе" : "вы не в городе")
                .append(BanditController.night(world) ? ", ночь" : ", день").append(", эта ночь: ").append(controller.tonight(world, player))
                .append(". Банды о вас: ").append(controller.memory(world, player)).append(". В мире: ").append(all.size());
        for (BanditEntity bandit : all)
            text.append("\n").append(bandit.getName().getString()).append(" — ").append(bandit.mode()).append(", ").append(bandit.getBlockPos().toShortString())
                    .append(bandit.demand() > 0 ? ", просит " + bandit.demand() : "").append(bandit.lootCount() > 0 ? ", добыча " + bandit.lootCount() : "");
        context.getSource().sendFeedback(() -> Text.literal(text.toString()), false);
        return all.size();
    }

    private static int clear(CommandContext<ServerCommandSource> context) {
        int n = 0;
        for (ServerWorld world : context.getSource().getServer().getWorlds())
            for (BanditEntity bandit : BanditController.bandits(world)) { bandit.discard(); n++; }
        int count = n;
        context.getSource().sendFeedback(() -> Text.literal("Убрано бандитов: " + count), false);
        return n;
    }
}
