package io.github.mobtalk.game;

import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.Mercantile;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;

import java.util.*;

/** Меркантильные горожанки, игровая сторона: раз в пять секунд у жительниц рядом с игроками решается (раз в жизни), меркантильна ли
 * она, и меркантильной ставится флаг наряда ({@link GlamLook}) — по нему клиент одевает её в шубу, леопард или блёстки
 * ({@code clothing/glam}); флаг снимается, когда меркантильных или наряды выключили. Здесь же — сколько при игроке денег для её
 * оценки кошелька. Только серверный поток. */
public final class MercantileController {
    private static final long PERIOD = 100, PHASE = 41;
    private static final double REACH = 48;
    private final MobGameBridge bridge;
    MercantileController(MobGameBridge bridge) { this.bridge = bridge; }

    void tick(ServerWorld world) {
        if (world.getTime() % PERIOD != PHASE || !bridge.config.enabled || !bridge.config.socialLayer) return;
        Set<UUID> seen = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, new Box(player.getBlockPos()).expand(REACH), VillagerEntity::isAlive)) {
                if (!seen.add(villager.getUuid())) continue;
                boolean adult = !villager.isBaby();
                boolean on = Mercantile.on(bridge.config) && adult && bridge.memory.mercantile(villager.getUuid(), villager.getName().getString(), adult, city(world, villager), bridge.config);
                if (villager instanceof GlamLook look) look.mobtalk$setGlam(on && bridge.config.mercOutfit);
            }
        }
    }
    private static boolean city(ServerWorld world, VillagerEntity villager) {
        ConversationStore.Site site = VillageSites.of(world, villager);
        return site != null && site.key().startsWith("mobtalk:city");
    }
    /** Ужин-«тарелочница» после слов игрока: оплата по счёту (изумруды из инвентаря) или приглашение; строка для чата. */
    String dinner(java.util.UUID mobId, ServerPlayerEntity player, String said) {
        String name = MobGameBridge.playerName(player);
        int bill = bridge.memory.dinnerBill(mobId, player.getUuid());
        if (bill > 0 && Mercantile.pays(said)) {
            boolean paid = take(player, bill);
            boolean dash = paid && player.getRandom().nextInt(100) < bridge.config.mercDashPercent;
            String line = bridge.memory.endDinner(mobId, player.getUuid(), name, paid, dash);
            if (dash) bridge.actions.stop(mobId, true); // Ушла: больше не идёт следом.
            return line;
        }
        if (bill == 0 && Mercantile.invites(said)) return bridge.memory.startDinner(mobId, player.getUuid(), name, wealth(player), bridge.config);
        return "";
    }
    /** Забирает {@code count} изумрудов из инвентаря, если их хватает. */
    private static boolean take(PlayerEntity player, int count) {
        var inventory = player.getInventory();
        int have = 0;
        for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(net.minecraft.item.Items.EMERALD)) have += inventory.getStack(i).getCount();
        if (have < count) return false;
        int left = count;
        for (int i = 0; i < inventory.size() && left > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isOf(net.minecraft.item.Items.EMERALD)) continue;
            int used = Math.min(left, stack.getCount());
            stack.decrement(used); left -= used;
        }
        return true;
    }

    /** Богатство игрока в изумрудах (для {@code playerDetails.wealth}): деньги в инвентаре и надетое. */
    static int wealth(PlayerEntity player) {
        Map<String, Integer> counts = new HashMap<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        List<String> worn = new ArrayList<>();
        for (ItemStack stack : player.getArmorItems()) if (!stack.isEmpty()) worn.add(Registries.ITEM.getId(stack.getItem()).toString());
        return Mercantile.wealth(counts, worn);
    }
}
