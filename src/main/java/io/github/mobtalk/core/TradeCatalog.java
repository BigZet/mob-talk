package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bundled deals and quests per species from assets/mobtalk/trades.json. A species absent from the file trades nothing. */
public final class TradeCatalog {
    /** Validated contents of one trades.json, keyed by canonical species id in file order. */
    record Loaded(Map<String, List<TradeDeal>> deals, Map<String, List<QuestDefinition>> quests) { }
    private static final Loaded BUNDLED = bundled();
    private static volatile Loaded current = BUNDLED;

    private TradeCatalog() { }

    public static List<TradeDeal> deals(String type) { return current.deals.getOrDefault(normalized(type), List.of()); }
    public static List<QuestDefinition> quests(String type) { return current.quests.getOrDefault(normalized(type), List.of()); }
    public static TradeDeal deal(String type, String id) {
        if (id == null) return null;
        for (TradeDeal deal : deals(type)) if (deal.id().equals(id)) return deal;
        return null;
    }
    public static QuestDefinition quest(String type, String id) {
        if (id == null) return null;
        for (QuestDefinition quest : quests(type)) if (quest.id().equals(id)) return quest;
        return null;
    }
    /** Species that trade or ask for anything, in file order. */
    public static List<String> tradingSpecies() { return List.copyOf(current.deals.keySet()); }

    /** Tests only: serve {@code loaded} instead of the bundled file; null restores the bundle. */
    static void install(Loaded loaded) { current = loaded == null ? BUNDLED : loaded; }

    /** Parses and validates one trades.json document; {@link IllegalStateException} names the first problem. */
    static Loaded parse(String json) { return parse(new StringReader(json)); }
    static Loaded parse(Reader reader) {
        Document data;
        try { data = new Gson().fromJson(reader, Document.class); }
        catch (JsonParseException unreadable) { throw new IllegalStateException("Mob Talk trades: cannot read trades.json", unreadable); }
        if (data == null) throw invalid("empty trades.json");
        Map<String, List<TradeDeal>> deals = new LinkedHashMap<>();
        Map<String, List<QuestDefinition>> quests = new LinkedHashMap<>();
        if (data.species != null) for (var entry : data.species.entrySet()) {
            String type = normalized(entry.getKey());
            SpeciesTrades row = entry.getValue();
            if (row == null) continue;
            var dealIds = new LinkedHashSet<String>();
            var dealList = new ArrayList<TradeDeal>();
            if (row.deals != null) for (DealData deal : row.deals) {
                if (deal == null) throw invalid(type + ": empty deal");
                try {
                    TradeDeal built = new TradeDeal(deal.id, deal.name, item(deal.sell, type), item(deal.price, type), deal.secret,
                            deal.hint, deal.unlockRelationship, deal.unlockQuest, deal.maxPerDay);
                    if (!dealIds.add(built.id())) throw invalid(type + ": duplicate deal " + built.id());
                    dealList.add(built);
                } catch (IllegalArgumentException incomplete) { throw invalid(type + ": incomplete deal " + deal.id); }
            }
            var questIds = new LinkedHashSet<String>();
            var questList = new ArrayList<QuestDefinition>();
            if (row.quests != null) for (QuestData quest : row.quests) {
                if (quest == null) throw invalid(type + ": empty quest");
                try {
                    QuestDefinition.Kind kind = QuestDefinition.Kind.valueOf(String.valueOf(quest.kind).toUpperCase(Locale.ROOT));
                    QuestDefinition.Reward reward = quest.reward == null ? null
                            : new QuestDefinition.Reward(quest.reward.relationship, quest.reward.unlockDeal, quest.reward.discountDeal, quest.reward.discountPercent);
                    QuestDefinition built = new QuestDefinition(quest.id, quest.title, kind, itemId(quest.target, type), quest.count, quest.pitch, reward);
                    if (!questIds.add(built.id())) throw invalid(type + ": duplicate quest " + built.id());
                    questList.add(built);
                } catch (IllegalArgumentException incomplete) { throw invalid(type + ": incomplete quest " + quest.id); }
            }
            for (TradeDeal deal : dealList)
                if (!deal.unlockQuest().isEmpty() && !questIds.contains(deal.unlockQuest())) throw invalid(type + ": unknown unlockQuest of " + deal.id());
            for (QuestDefinition quest : questList) {
                if (!quest.reward().unlockDeal().isEmpty() && !dealIds.contains(quest.reward().unlockDeal())) throw invalid(type + ": unknown unlockDeal of " + quest.id());
                if (!quest.reward().discountDeal().isEmpty() && !dealIds.contains(quest.reward().discountDeal())) throw invalid(type + ": unknown discountDeal of " + quest.id());
            }
            deals.put(type, List.copyOf(dealList));
            quests.put(type, List.copyOf(questList));
        }
        return new Loaded(Collections.unmodifiableMap(deals), Collections.unmodifiableMap(quests));
    }

    private static TradeDeal.Item item(ItemData data, String type) {
        if (data == null) throw invalid(type + ": missing item");
        return new TradeDeal.Item(itemId(data.id, type), data.name, data.count);
    }
    /** Vanilla namespaced ids only; the game layer skips deals whose items do not exist. */
    private static String itemId(String id, String type) {
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw invalid(type + ": bad item id " + id);
        return id;
    }
    private static String normalized(String type) {
        String clean = type == null || type.isBlank() ? "minecraft:unknown" : type.trim().toLowerCase(Locale.ROOT);
        return clean.indexOf(':') >= 0 ? clean : "minecraft:" + clean;
    }
    private static IllegalStateException invalid(String message) { return new IllegalStateException("Mob Talk trades: " + message); }

    private static Loaded bundled() {
        try (var input = TradeCatalog.class.getResourceAsStream("/assets/mobtalk/trades.json")) {
            if (input == null) throw invalid("missing trades.json");
            return parse(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException unreadable) { throw new IllegalStateException("Mob Talk trades: cannot read trades.json", unreadable); }
    }

    private static final class Document { int schemaVersion; Map<String, SpeciesTrades> species; }
    private static final class SpeciesTrades { List<DealData> deals; List<QuestData> quests; }
    private static final class ItemData { String id, name; int count = 1; }
    private static final class DealData {
        String id, name, hint = "", unlockQuest = "";
        ItemData sell, price;
        boolean secret;
        int unlockRelationship, maxPerDay;
    }
    private static final class RewardData { int relationship; String unlockDeal = "", discountDeal = ""; int discountPercent; }
    private static final class QuestData { String id, title, kind, target, pitch = ""; int count = 1; RewardData reward; }
}
