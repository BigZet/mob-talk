package io.github.mobtalk.client.game;

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

/** Server-thread-only deals and quests of the mob's own catalog (trades.json): the model proposes, this class verifies,
 * and items change hands only here — never by the model's words alone. */
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
    /** Localized names of every item this species' deals and BRING quests mention; ids unknown to the registry are skipped. */
    static Map<String, String> itemNames(String type) {
        Map<String, String> names = new LinkedHashMap<>();
        for (TradeDeal deal : TradeCatalog.deals(type)) { name(names, deal.sell().id()); name(names, deal.price().id()); }
        for (QuestDefinition quest : TradeCatalog.quests(type)) if (quest.kind() == QuestDefinition.Kind.BRING) name(names, quest.target());
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
    private static String targetName(QuestDefinition quest) {
        Item known = item(quest.target());
        return known == null ? quest.target() : new ItemStack(known).getName().getString();
    }
    private static List<ItemStack> stacks(PlayerInventory inventory) {
        List<ItemStack> result = new ArrayList<>(inventory.main);
        result.addAll(inventory.offHand);
        return result;
    }
    private static int count(ServerPlayerEntity player, Item item) {
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
    private static long worldDay(MobEntity mob) { return Math.floorDiv(mob.getWorld().getTimeOfDay(), 24_000L); }

    /** Applies the model's deal, unlock and quest moves in that order; an unjustified move throws with the reason the player sees. */
    String apply(MobEntity mob, ServerPlayerEntity player, DealIntent deal, String unlock, QuestIntent quest) {
        List<String> messages = new ArrayList<>();
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
            messages.add("Цена для вас: " + amount(found.price(), deal.price()) + " за " + amount(found.sell(), found.sell().count()) + " (постоянно)");
        } else if (deal != null && deal.type() == DealIntent.Type.DECLINE) messages.add("Торг окончен");
        if (unlock != null && !unlock.isBlank()) {
            TradeDeal found = TradeCatalog.deal(type, unlock);
            if (found == null) throw new IllegalStateException("У этого моба нет сделки " + unlock);
            TradeState state = bridge.memory.tradeState(mobId, playerId, found.id());
            if (!Trading.canUnlock(found, state, relationship(mob, player), bridge.memory.quests(mobId, playerId)))
                throw new IllegalStateException("Сделку «" + found.name() + "» ещё рано открывать");
            bridge.memory.unlockDeal(mobId, playerId, found.id(), now);
            int price = Trading.currentPrice(found, bridge.memory.tradeState(mobId, playerId, found.id()));
            messages.add("Открыта сделка: " + found.name() + " — " + amount(found.sell(), found.sell().count()) + " за " + amount(found.price(), price));
        }
        if (quest != null && quest.type() == QuestIntent.Type.OFFER) {
            QuestDefinition found = TradeCatalog.quest(type, quest.questId());
            if (found == null) throw new IllegalStateException("У этого моба нет задания " + quest.questId());
            Map<String, QuestState> quests = bridge.memory.quests(mobId, playerId);
            QuestState known = quests.get(found.id());
            if (known != null && known.status() == QuestState.Status.DONE) throw new IllegalStateException("Задание «" + found.title() + "» уже выполнено");
            if (quests.values().stream().anyMatch(state -> state.status() == QuestState.Status.ACTIVE))
                throw new IllegalStateException("У вас уже есть задание от этого моба");
            bridge.memory.startQuest(mobId, playerId, found.id(), now);
            messages.add("Задание принято: " + found.title() + " (" + progress(found, player, 0) + " из " + found.count() + ")");
        } else if (quest != null && quest.type() == QuestIntent.Type.COMPLETE) {
            QuestDefinition found = TradeCatalog.quest(type, quest.questId());
            if (found == null) throw new IllegalStateException("У этого моба нет задания " + quest.questId());
            QuestState state = bridge.memory.quests(mobId, playerId).get(found.id());
            if (state == null || state.status() != QuestState.Status.ACTIVE) throw new IllegalStateException("Задание «" + found.title() + "» не активно");
            Item target = found.kind() == QuestDefinition.Kind.BRING ? item(found.target()) : null;
            if (found.kind() == QuestDefinition.Kind.BRING) {
                int have = target == null ? 0 : count(player, target);
                if (have < found.count()) throw new IllegalStateException("У вас нет " + found.count() + "× " + targetName(found));
            } else if (state.progress() < found.count())
                throw new IllegalStateException("Задание «" + found.title() + "» ещё не выполнено: " + state.progress() + " из " + found.count());
            QuestDefinition.Reward reward = bridge.memory.completeQuest(mobId, playerId, found.id(), player.getName().getString(), now);
            if (target != null) take(player, target, found.count());
            messages.add("Задание выполнено: " + found.title() + rewardText(type, reward));
        }
        return String.join("; ", messages);
    }
    private static int progress(QuestDefinition quest, ServerPlayerEntity player, int kills) {
        if (quest.kind() != QuestDefinition.Kind.BRING) return Math.min(quest.count(), kills);
        Item target = item(quest.target());
        return target == null ? 0 : Math.min(quest.count(), count(player, target));
    }
    private static String rewardText(String type, QuestDefinition.Reward reward) {
        List<String> parts = new ArrayList<>();
        if (reward == null) return "";
        if (reward.relationship() != 0) parts.add((reward.relationship() > 0 ? "+" : "") + reward.relationship() + " к отношению");
        TradeDeal unlocked = TradeCatalog.deal(type, reward.unlockDeal());
        if (unlocked != null) parts.add("открыта сделка «" + unlocked.name() + "»");
        TradeDeal cheaper = TradeCatalog.deal(type, reward.discountDeal());
        if (cheaper != null && reward.discountPercent() > 0) parts.add("цена на «" + cheaper.name() + "» снижена на " + reward.discountPercent() + "%");
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    /** One exchange of a visible deal at the player's current price; the only way the mob's goods reach the inventory. */
    String buy(MobEntity mob, ServerPlayerEntity player, String dealId) {
        String type = MobGameBridge.type(mob);
        TradeDeal deal = TradeCatalog.deal(type, dealId);
        if (deal == null) throw new IllegalStateException("У этого моба нет такой сделки");
        TradeState state = bridge.memory.tradeState(mob.getUuid(), player.getUuid(), deal.id());
        if (!Trading.visible(deal, state)) throw new IllegalStateException("Сделка «" + deal.name() + "» ещё не открыта");
        long day = worldDay(mob);
        if (Trading.stockLeft(deal, state, day) == 0) throw new IllegalStateException("На сегодня у моба больше нет: " + deal.name());
        Item priceItem = item(deal.price().id()), goods = item(deal.sell().id());
        if (priceItem == null || goods == null) throw new IllegalStateException("Предметы сделки «" + deal.name() + "» неизвестны игре");
        int price = Trading.currentPrice(deal, state);
        int have = count(player, priceItem);
        if (have < price) throw new IllegalStateException("Нужно " + amount(deal.price(), price) + ", у вас " + have);
        take(player, priceItem, price);
        for (int left = deal.sell().count(); left > 0; ) {
            ItemStack stack = new ItemStack(goods, Math.min(left, goods.getMaxCount()));
            left -= stack.getCount();
            player.getInventory().offerOrDrop(stack);
        }
        player.currentScreenHandler.sendContentUpdates();
        bridge.memory.recordPurchase(mob.getUuid(), player.getUuid(), deal.id(), player.getName().getString(), day, System.currentTimeMillis());
        return "Куплено: " + amount(deal.sell(), deal.sell().count()) + " за " + amount(deal.price(), price);
    }

    /** What the trade screen shows: visible deals with the player's price, teasers of locked secret deals, the active quest. */
    MobGameBridge.TradeOffers offers(MobEntity mob, ServerPlayerEntity player) {
        String type = MobGameBridge.type(mob);
        Map<String, TradeState> states = bridge.memory.tradeStates(mob.getUuid(), player.getUuid());
        Map<String, Integer> inventory = inventoryCounts(player);
        List<MobGameBridge.TradeRow> rows = new ArrayList<>();
        for (Trading.Priced priced : Trading.priced(type, states, worldDay(mob)))
            if (item(priced.deal().sell().id()) != null && item(priced.deal().price().id()) != null)
                rows.add(new MobGameBridge.TradeRow(priced.deal(), priced.currentPrice(), priced.stockLeft(),
                        inventory.getOrDefault(priced.deal().price().id(), 0)));
        List<String> hints = new ArrayList<>();
        for (TradeDeal deal : TradeCatalog.deals(type))
            if (deal.secret() && !Trading.visible(deal, states.getOrDefault(deal.id(), TradeState.initial()))) hints.add(deal.hint());
        MobGameBridge.ActiveQuest active = null;
        for (var entry : bridge.memory.quests(mob.getUuid(), player.getUuid()).entrySet()) {
            QuestDefinition quest = TradeCatalog.quest(type, entry.getKey());
            if (quest == null || entry.getValue().status() != QuestState.Status.ACTIVE) continue;
            active = new MobGameBridge.ActiveQuest(quest.title(), progress(quest, player, entry.getValue().progress()), quest.count());
        }
        return new MobGameBridge.TradeOffers(mob.getName().getString(), List.copyOf(rows), List.copyOf(hints), active);
    }
}
