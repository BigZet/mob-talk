package io.github.divineblock.game;

import io.github.divineblock.core.GodLedger;
import io.github.divineblock.core.Righteousness;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stat;
import net.minecraft.stat.Stats;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** The god sees deeds himself, from server events and the player's own statistics: no polling of the world every tick. */
public final class DeedWatcher {
    /** Animals a player may kill a day for food before it counts as slaughter. */
    static final int FREE_KILLS_PER_DAY = 6;
    private static final int STAT_PERIOD_TICKS = 200;
    private static final List<Item> PLANTS = List.of(Items.OAK_SAPLING, Items.SPRUCE_SAPLING, Items.BIRCH_SAPLING, Items.JUNGLE_SAPLING, Items.ACACIA_SAPLING,
            Items.DARK_OAK_SAPLING, Items.CHERRY_SAPLING, Items.MANGROVE_PROPAGULE, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS, Items.PUMPKIN_SEEDS);
    private static final List<net.minecraft.block.Block> WORKSTATIONS = List.of(Blocks.BELL, Blocks.COMPOSTER, Blocks.LECTERN, Blocks.SMITHING_TABLE,
            Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CARTOGRAPHY_TABLE, Blocks.FLETCHING_TABLE, Blocks.BREWING_STAND, Blocks.BARREL, Blocks.LOOM,
            Blocks.STONECUTTER, Blocks.GRINDSTONE, Blocks.CAULDRON);
    private static final Map<UUID, int[]> killsToday = new HashMap<>(); // {day, kills}
    private static int ticks;

    private DeedWatcher() { }

    static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity player) || !(entity.getWorld() instanceof ServerWorld world)) return;
            onKill(player, world, entity);
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity serverPlayer && world instanceof ServerWorld serverWorld && villageBlock(state) && nearVillagers(serverWorld, pos, 24))
                record(serverPlayer, Righteousness.Deed.VANDALISM, "в деревне");
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (++ticks % STAT_PERIOD_TICKS == 0) pollStats(server); });
    }

    private static void onKill(ServerPlayerEntity player, ServerWorld world, LivingEntity victim) {
        if (victim instanceof VillagerEntity) record(player, Righteousness.Deed.VILLAGER_KILLED, "");
        else if (victim instanceof IronGolemEntity) record(player, Righteousness.Deed.GOLEM_KILLED, "");
        else if (victim instanceof TameableEntity pet && pet.isTamed()) record(player, Righteousness.Deed.PET_KILLED, "");
        else if (victim instanceof AnimalEntity) {
            long day = day(world.getServer());
            int[] counter = killsToday.computeIfAbsent(player.getUuid(), id -> new int[] { (int) day, 0 });
            if (counter[0] != day) { counter[0] = (int) day; counter[1] = 0; }
            if (++counter[1] > FREE_KILLS_PER_DAY) record(player, Righteousness.Deed.SLAUGHTER, "");
        } else if (victim instanceof HostileEntity && nearVillagers(world, victim.getBlockPos(), 32))
            record(player, Righteousness.Deed.DEFENDED_VILLAGE, "");
    }

    private static boolean villageBlock(BlockState state) {
        return state.isIn(BlockTags.BEDS) || WORKSTATIONS.contains(state.getBlock());
    }
    static boolean nearVillagers(ServerWorld world, BlockPos pos, int radius) {
        return !world.getEntitiesByClass(VillagerEntity.class, new Box(pos).expand(radius), LivingEntity::isAlive).isEmpty();
    }

    /** Breeding, trading, raids and planting come from the statistics the game already counts. */
    private static void pollStats(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            GodLedger.Soul soul = DivineState.of(server).ledger.soul(player.getUuid());
            check(player, soul, "bred", Stats.CUSTOM.getOrCreateStat(Stats.ANIMALS_BRED), Righteousness.Deed.ANIMAL_BRED);
            check(player, soul, "traded", Stats.CUSTOM.getOrCreateStat(Stats.TRADED_WITH_VILLAGER), Righteousness.Deed.TRADED);
            check(player, soul, "raids", Stats.CUSTOM.getOrCreateStat(Stats.RAID_WIN), Righteousness.Deed.RAID_WON);
            int planted = 0;
            for (Item plant : PLANTS) planted += player.getStatHandler().getStat(Stats.USED.getOrCreateStat(plant));
            delta(player, soul, "planted", planted, Righteousness.Deed.PLANTED);
        }
        if (!server.getPlayerManager().getPlayerList().isEmpty()) DivineState.of(server).markDirty();
    }
    private static void check(ServerPlayerEntity player, GodLedger.Soul soul, String key, Stat<Identifier> stat, Righteousness.Deed deed) {
        delta(player, soul, key, player.getStatHandler().getStat(stat), deed);
    }
    private static void delta(ServerPlayerEntity player, GodLedger.Soul soul, String key, int value, Righteousness.Deed deed) {
        Integer before = soul.statBaselines.put(key, value);
        if (before == null || value <= before) return; // The first look only sets the baseline.
        for (int i = 0; i < Math.min(20, value - before); i++) record(player, deed, "");
    }

    public static void record(ServerPlayerEntity player, Righteousness.Deed deed, String where) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        DivineState state = DivineState.of(server);
        state.ledger.soul(player.getUuid()).righteousness.record(deed, day(server), where);
        state.markDirty();
    }

    /** The game day by the world's own clock, which sleeping and /time do not rewind. */
    public static long day(MinecraftServer server) { return server.getOverworld().getTime() / 24_000L; }
}
