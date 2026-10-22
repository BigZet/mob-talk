package io.github.mobtalk.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-thread-only deals and favours of the mob's own catalog (trades.json): the model proposes, this class verifies,
 * and items change hands only in the vanilla merchant window ({@link MobMerchant}) — never by the model's words alone. */
final class MobTrading {
    private final MobGameBridge bridge;
    MobTrading(MobGameBridge bridge) { this.bridge = bridge; }

    /** Item id → total count over the main inventory and offhand; worn armour does not count. */
    static Map<String, Integer> inventoryCounts(ServerPlayerEntity player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : stacks(player.getInventory()))
            if (!stack.isEmpty()) counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        return counts;
    }
    /** Localized names of every item this species' deals and favour ideas mention; ids unknown to the registry are skipped. */
    static Map<String, String> itemNames(String type) {
        Map<String, String> names = new LinkedHashMap<>();
        for (TradeDeal deal : TradeCatalog.deals(type)) { name(names, deal.sell().id()); name(names, deal.price().id()); }
        for (TradeCatalog.Idea idea : TradeCatalog.ideas(type)) name(names, idea.item());
        for (String id : FavorItems.ids()) name(names, id); // The favour candidates are drawn from this pool inside Trading.view.
        return names;
    }
    private static void name(Map<String, String> names, String id) {
        Item item = item(id);
        if (item != null) names.put(id, new ItemStack(item).getName().getString());
    }
    static Item item(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null || !Registries.ITEM.containsId(identifier)) return null;
        Item item = Registries.ITEM.get(identifier);
        return item == Items.AIR ? null : item;
    }
    private static String name(TradeDeal.Item item) {
        Item known = item(item.id());
        return known == null ? item.name() : new ItemStack(known).getName().getString();
    }
    private static String amount(TradeDeal.Item item, int count) { return count + "× " + name(item); }
    private static List<ItemStack> stacks(PlayerInventory inventory) {
        List<ItemStack> result = new ArrayList<>(inventory.main);
        result.addAll(inventory.offHand);
        return result;
    }
    static int count(ServerPlayerEntity player, Item item) {
        int total = 0;
        for (ItemStack stack : stacks(player.getInventory())) if (stack.isOf(item)) total += stack.getCount();
        return total;
    }
    /** Takes exactly {@code count} of {@code item} (the caller checked there are enough) and syncs the open container. */
    private static void take(ServerPlayerEntity player, Item item, int count) {
        int left = count;
        for (ItemStack stack : stacks(player.getInventory())) {
            if (left == 0) break;
            if (!stack.isOf(item)) continue;
            int taken = Math.min(stack.getCount(), left);
            stack.decrement(taken);
            left -= taken;
        }
        if (left > 0) throw new IllegalStateException("Предметов в инвентаре стало меньше: повторите");
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
    }
    private int relationship(MobEntity mob, ServerPlayerEntity player) {
        MobCard card = bridge.memory.card(mob.getUuid(), player.getUuid());
        return card == null ? 0 : card.relationship();
    }
    static long worldDay(MobEntity mob) { return Math.floorDiv(mob.getWorld().getTimeOfDay(), 24_000L); }

    /** Applies the model's deal, unlock and quest moves in that order; an unjustified move throws with the reason the player sees. */
    String apply(MobEntity mob, ServerPlayerEntity player, DealIntent deal, String unlock, QuestIntent quest) {
        List<String> messages = new ArrayList<>();
        boolean changed = false;
        String type = MobGameBridge.type(mob);
        UUID mobId = mob.getUuid(), playerId = player.getUuid();
        long now = System.currentTimeMillis();
        if (deal != null && deal.type() == DealIntent.Type.OFFER) {
            TradeDeal found = TradeCatalog.deal(type, deal.dealId());
            if (found == null) throw new IllegalStateException("У этого моба нет сделки " + deal.dealId());
            TradeState state = bridge.memory.tradeState(mobId, playerId, found.id());
            if (!Trading.visible(found, state)) throw new IllegalStateException("Сделка «" + found.name() + "» ещё не открыта");
            if (!Trading.permitted(found, state, relationship(mob, player), deal.price(), now))
                throw new IllegalStateException("Уступка по сделке «" + found.name() + "» выходит за пределы: за раз цена падает не больше чем вдвое, всего не ниже "
                        + found.floorPrice() + ", не больше " + Trading.MAX_ROUNDS + " шагов за сессию");
            bridge.memory.setDealPrice(mobId, playerId, found.id(), deal.price(), now);
            changed = true;
            messages.add("Цена для вас: " + amount(found.price(), deal.price()) + " за " + amount(found.sell(), found.sell().count()) + " (постоянно)");
        } else if (deal != null && deal.type() == DealIntent.Type.DECLINE) messages.add("Торг окончен");
        if (unlock != null && !unlock.isBlank()) {
            TradeDeal found = TradeCatalog.deal(type, unlock);
            if (found == null) throw new IllegalStateException("У этого моба нет сделки " + unlock);
            TradeState state = bridge.memory.tradeState(mobId, playerId, found.id());
            if (!Trading.canUnlock(found, state, relationship(mob, player), bridge.memory.favorsDone(mobId, playerId).size()))
                throw new IllegalStateException("Сделку «" + found.name() + "» ещё рано открывать");
            bridge.memory.unlockDeal(mobId, playerId, found.id(), now);
            changed = true;
            int price = Trading.currentPrice(found, bridge.memory.tradeState(mobId, playerId, found.id()));
            messages.add("Открыта сделка: " + found.name() + " — " + amount(found.sell(), found.sell().count()) + " за " + amount(found.price(), price));
        }
        if (quest != null && quest.type() == QuestIntent.Type.OFFER) {
            if (bridge.memory.favor(mobId, playerId) != null) throw new IllegalStateException("Этот моб уже ждёт от вас услугу «" + bridge.memory.favor(mobId, playerId).title() + "»");
            Item wanted = item(quest.item());
            if (wanted == null) throw new IllegalStateException("Моб просит предмет, которого нет в игре: " + quest.item());
            Favor favor = quest.favor();
            bridge.memory.startFavor(mobId, playerId, favor, now);
            changed = true;
            messages.add("Услуга: " + favor.title() + " — принести " + favor.count() + "× " + new ItemStack(wanted).getName().getString()
                    + rewardPromise(type, favor));
        } else if (quest != null && quest.type() == QuestIntent.Type.COMPLETE) {
            Favor favor = bridge.memory.favor(mobId, playerId);
            if (favor == null) throw new IllegalStateException("Никакой услуги сейчас не ждут");
            Item wanted = item(favor.item());
            int have = wanted == null ? 0 : count(player, wanted);
            if (have < favor.count()) throw new IllegalStateException("У вас нет " + favor.count() + "× " + favorItemName(favor) + " (есть " + have + ")");
            take(player, wanted, favor.count());
            Favor done = bridge.memory.completeFavor(mobId, playerId, MobGameBridge.playerName(player), now);
            bridge.jealousy.favor(mob, player); // Трек J: поручение соперника — повод ревновать.
            changed = true;
            messages.add("Услуга оказана: " + done.title() + " (+" + Favor.RELATIONSHIP + " к отношению" + rewardText(type, done) + ")");
        }
        // The player may be looking at the window right now: a new price, an opened secret or a favour row shows up at once.
        MobMerchant window = changed ? MobMerchant.of(player, mob) : null;
        if (window != null) window.refresh();
        return String.join("; ", messages);
    }
    static String favorItemName(Favor favor) {
        Item known = item(favor.item());
        return known == null ? favor.item() : new ItemStack(known).getName().getString();
    }
    private static String rewardPromise(String type, Favor favor) {
        TradeDeal promised = TradeCatalog.deal(type, favor.rewardDeal());
        if (promised == null) return "";
        return promised.secret() ? "; обещано открыть «" + promised.name() + "»" + (favor.rewardPercent() > 0 ? " и скинуть " + favor.rewardPercent() + "%" : "")
                : favor.rewardPercent() > 0 ? "; обещана скидка " + favor.rewardPercent() + "% на «" + promised.name() + "»" : "";
    }
    private static String rewardText(String type, Favor favor) {
        TradeDeal promised = TradeCatalog.deal(type, favor.rewardDeal());
        if (promised == null) return "";
        return (promised.secret() ? ", открыта сделка «" + promised.name() + "»" : "")
                + (favor.rewardPercent() > 0 ? ", цена на «" + promised.name() + "» снижена на " + favor.rewardPercent() + "%" : "");
    }

    /** The vanilla merchant window over this mob's deals; the player then buys there, and an accepted move refreshes it in place. */
    void open(MobEntity mob, ServerPlayerEntity player) {
        if (mob instanceof net.minecraft.entity.passive.MerchantEntity merchant) {
            // One window per villager: B starts the same vanilla trade a right-click would, so prices and bargaining agree.
            if (merchant.getOffers().isEmpty() || merchant.isBaby()) throw new IllegalStateException("Этот житель ничем не торгует");
            if (merchant.getCustomer() != null && merchant.getCustomer() != player) throw new IllegalStateException("Житель сейчас торгует с кем-то другим");
            if (!merchant.interact(player, net.minecraft.util.Hand.MAIN_HAND).isAccepted()) throw new IllegalStateException("Житель сейчас не может торговать");
            return;
        }
        MobMerchant.open(bridge, mob, player);
    }
}
