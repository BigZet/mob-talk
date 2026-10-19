package io.github.mobtalk.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** What a gift is worth to a villager (docs/mca-social-spec.md §3.4), from the bundled {@code gifts.json}: a base value per item in
 * its category plus the category's bonuses that apply (profession, character, inborn marks, childhood). An item in several categories
 * takes the one that refuses (an allergy weighs more), else the most pleasing. Saturation: the same item given {@code n} times lately
 * loses {@code n × 0.5 × max(U0, 0)^0.85}; the relationship moves by a third of what is left. Pure Java. */
public final class GiftCatalog {
    public static final double SATURATION = 0.5, SATURATION_POWER = 0.85, TO_HEARTS = 0.33;

    record Category(String id, String word, Map<String, Integer> items, Map<String, Integer> bonuses) { }
    /** One gift weighed: the tier, satisfaction before and after saturation, the relationship change, the category word; {@code known}
     * false for a thing the villager does not understand (no category). */
    public record Value(Gesture.Outcome tier, int before, int after, int hearts, String category, boolean known) {
        /** A refusal that also hurts: an allergy, a weapon to the faint-hearted. */
        public boolean offends() { return hearts < 0 && tier == Gesture.Outcome.REFUSED; }
    }
    /** Who receives it. */
    public record Receiver(Persona persona, Set<VillagerTrait> traits, String profession, boolean child) {
        public Receiver { traits = traits == null ? Set.of() : Set.copyOf(traits); profession = profession == null ? "" : profession; }
    }

    private static volatile List<Category> categories;
    private static volatile int good = 10, better = 20;

    /** Weighs {@code item} for this receiver, given {@code times} already among its remembered gifts. */
    public static Value weigh(String item, Receiver receiver, int times) {
        List<Category> all = categories();
        Integer chosen = null; Category chosenCategory = null; boolean refusal = false;
        for (Category category : all) {
            Integer base = item == null ? null : category.items().get(item);
            if (base == null) continue;
            int value = base;
            for (Map.Entry<String, Integer> bonus : category.bonuses().entrySet()) if (applies(bonus.getKey(), receiver)) value += bonus.getValue();
            boolean refuses = value <= 0;
            if (chosen == null || refuses && !refusal || refuses == refusal && (refuses ? value < chosen : value > chosen)) {
                chosen = value; chosenCategory = category; refusal = refuses;
            }
        }
        if (chosen == null) return new Value(Gesture.Outcome.REFUSED, 0, 0, 0, "", false);
        int before = chosen;
        int penalty = (int) (Math.max(0, times) * SATURATION * Math.pow(Math.max(before, 0), SATURATION_POWER));
        int after = before - penalty;
        int hearts = (int) (after * TO_HEARTS);
        Gesture.Outcome tier = before <= 0 ? Gesture.Outcome.REFUSED : after <= 0 ? Gesture.Outcome.ALREADY
                : after <= good ? Gesture.Outcome.GOOD : after <= better ? Gesture.Outcome.BETTER : Gesture.Outcome.BEST;
        return new Value(tier, before, after, hearts, chosenCategory.word(), true);
    }
    /** Whether the item is in any category. */
    public static boolean known(String item) { for (Category category : categories()) if (category.items().containsKey(item)) return true; return false; }

    private static boolean applies(String key, Receiver receiver) {
        if ("child".equals(key)) return receiver.child();
        if (key.startsWith("profession:")) return key.substring("profession:".length()).equals(receiver.profession());
        if (key.startsWith("trait:")) { VillagerTrait trait = VillagerTrait.of(key.substring(6)); return trait != null && receiver.traits().contains(trait); }
        if (key.startsWith("persona:")) return receiver.persona() != null && receiver.persona().name().equals(key.substring(8));
        return false;
    }

    static List<Category> categories() {
        List<Category> loaded = categories;
        if (loaded != null) return loaded;
        synchronized (GiftCatalog.class) {
            if (categories == null) {
                try (var input = GiftCatalog.class.getResourceAsStream("/assets/mobtalk/gifts.json")) {
                    if (input == null) throw new IllegalStateException("Mob Talk gifts: missing gifts.json");
                    categories = parse(new InputStreamReader(input, StandardCharsets.UTF_8));
                } catch (IOException unreadable) { throw new IllegalStateException("Mob Talk gifts: cannot read gifts.json", unreadable); }
            }
            return categories;
        }
    }
    static List<Category> parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        if (root.has("tiers")) {
            JsonObject tiers = root.getAsJsonObject("tiers");
            good = tiers.get("good").getAsInt(); better = tiers.get("better").getAsInt();
        }
        List<Category> list = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("categories")) {
            JsonObject category = element.getAsJsonObject();
            Map<String, Integer> items = new LinkedHashMap<>(), bonuses = new LinkedHashMap<>();
            category.getAsJsonObject("items").entrySet().forEach(e -> items.put(e.getKey(), e.getValue().getAsInt()));
            if (category.has("bonuses")) category.getAsJsonObject("bonuses").entrySet().forEach(e -> bonuses.put(e.getKey(), e.getValue().getAsInt()));
            list.add(new Category(category.get("id").getAsString(), category.get("word").getAsString(), Map.copyOf(items), Map.copyOf(bonuses)));
        }
        return List.copyOf(list);
    }
}
