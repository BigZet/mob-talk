package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The «Моб и деревня» panel and the village line of /mobtalk state: everything the world memory knows about one mob and its
 * village, in plain lines for a scrolling text. Reads the store under its lock; changes nothing. */
public final class MobInfo {
    private MobInfo() { }
    static final int FACTS = 20, SUMMARIES = 5, RESIDENTS = 40, GIFTS = 8;

    /** One short line for /mobtalk state: «деревня «Ольховка»: гость (35)»; "" outside a known village. */
    public static String standingLine(VillageStanding.View view, String villageName) {
        if (view == null || villageName == null || villageName.isBlank()) return "";
        return "деревня «" + villageName + "»: " + view.rank().word + " (" + view.points() + ")";
    }

    /** The player's standing in a village in detail: rank, points, how many know them, the average, what follows from it. */
    public static List<String> standingLines(VillageStanding.View view, String villageName) {
        List<String> lines = new ArrayList<>();
        if (view == null || villageName == null || villageName.isBlank()) { lines.add("Рядом нет известной деревни."); return lines; }
        lines.add("Ваш статус в «" + villageName + "»: " + view.rank().word);
        lines.add("Очки: " + view.points() + " · знают вас " + view.acquainted() + " из " + view.residents() + " жителей · средний балл " + view.average());
        List<String> effects = new ArrayList<>();
        if (view.refusesTrade()) effects.add("торговать с вами отказываются");
        if (view.golemAlert()) effects.add("голем следит за вами");
        if (view.warns()) effects.add(view.banished() ? "вас гонят из деревни" : "жители предупреждают");
        lines.add("Последствия: " + (effects.isEmpty() ? "нет, торговля открыта" : String.join(", ", effects)));
        String meeting = VillageStanding.describe(view);
        if (!meeting.isBlank()) lines.add("Как встречают: " + meeting);
        return lines;
    }

    /** The whole panel. {@code mob} may be null (only the village where the player stands); {@code duties} are the running
     * STAY/GUARD/SPARE requests in words, {@code place} the place details of a fresh capture (empty when none). Secrets — the
     * mob's own wish and hidden truth — are folded unless {@code secrets}. */
    public static String report(ConversationStore store, UUID mob, UUID player, String dimension, int x, int z, long now,
                                boolean secrets, List<String> duties, Map<String, Object> place) {
        return report(store, mob, player, dimension, x, z, null, now, secrets, duties, place);
    }
    /** As above, with the village where the player stands already found by the game ({@code hereId}: its id, "" for none, null to
     * take the nearest known village within the radius of x, z). */
    public static String report(ConversationStore store, UUID mob, UUID player, String dimension, int x, int z, String hereId, long now,
                                boolean secrets, List<String> duties, Map<String, Object> place) {
        List<String> out = new ArrayList<>();
        synchronized (store) {
            ConversationStore.State state = store.state;
            ConversationStore.Profile p = mob == null ? null : state.profiles.get(mob.toString());
            if (p == null) {
                section(out, "Моб");
                out.add(mob == null ? "Моб не выбран: наведите прицел на моба и нажмите V." : "Этот моб ещё незнаком: заговорите с ним.");
            } else mob(out, store, state, p, mob, player, now, secrets, duties == null ? List.of() : duties, place == null ? Map.of() : place);

            ConversationStore.Village here = hereId == null ? store.villageNear(dimension, x, z) : store.village(hereId);
            section(out, "Деревня, где вы стоите");
            out.addAll(standingLines(here == null ? null : SocialLedger.standing(state, here.id(), player, now), here == null ? "" : here.name()));
            ConversationStore.Village own = p == null || p.villageId == null || p.villageId.isBlank() ? null : store.village(p.villageId);
            ConversationStore.Village shown = own != null ? own : here;
            if (shown != null) village(out, state, shown, own != null && here != null && !own.id().equals(here.id()), player, now);
        }
        return String.join("\n", out);
    }

    private static void mob(List<String> out, ConversationStore store, ConversationStore.State state, ConversationStore.Profile p, UUID mob, UUID player,
                            long now, boolean secrets, List<String> duties, Map<String, Object> place) {
        SpeciesCatalog.Species species = SpeciesCatalog.get(p.type);
        String given = p.givenName == null || p.givenName.isBlank() ? "" : p.givenName;
        section(out, given.isEmpty() ? p.name : given);
        out.add("Вид: " + species.name() + " (" + p.type + ")" + (given.isEmpty() || given.equals(p.name) ? "" : " · в игре: " + p.name));
        String archetype = species.variants().isEmpty() ? "" : species.variants().get(Math.max(0, Math.min(species.variants().size() - 1, p.variantSlot))).name();
        out.add("Характер: " + (archetype.isBlank() ? "—" : archetype) + " · темперамент: спокойствие " + p.calm + ", уверенность " + p.confidence
                + ", любопытство " + p.curiosity + ", общительность " + p.sociability);
        out.add("Дорого: " + text(p.preference) + " · тревожит: " + text(p.fear));
        if (p.customPersonality != null && !p.customPersonality.isBlank()) out.add("Личная настройка: " + p.customPersonality);
        String social = store.social().describe(mob, player);
        if (!social.isBlank()) out.add("Социальный профиль: " + social);
        out.add("Биография: " + (p.backstory == null || p.backstory.isBlank() ? "ещё не написана (появится после первого разговора)" : p.backstory));
        if (secrets) {
            out.add("Желание: " + text(p.wish.isBlank() ? species.desire() : p.wish));
            out.add("Тайна: " + text(p.hidden.isBlank() ? species.secret() : p.hidden));
        } else out.add("Желание и тайна свёрнуты: кнопка «Показать тайны».");

        ConversationStore.Relation r = player == null ? null : p.relations.get(player.toString());
        MobCard card = store.card(mob, player);
        int score = card == null ? 0 : card.relationship();
        section(out, "Отношение к вам");
        boolean villager = "minecraft:villager".equals(p.type);
        String hearts = villager ? Hearts.symbols(score) : "";
        String step = villager ? Hearts.step(score) : "";
        String truce = species.hostile() ? score >= CombatRules.ALLY ? " · свой" : score >= CombatRules.TRUCE ? " · перемирие" : " · враждебен" : "";
        out.add((score >= 0 ? "+" : "") + score + (hearts.isEmpty() ? "" : " " + hearts) + (step.isEmpty() ? "" : " (" + step + ")") + truce
                + " · обращается к вам: " + String.join(", ", PlayerIdentity.forms(PlayerIdentity.configOf(player), "по нику", score)));
        if (r != null && r.hits > 0) out.add("Ударов от вас: " + r.hits);

        section(out, "Состояние");
        MobState.View view = card == null ? MobState.View.NEUTRAL : card.state();
        out.add("Настроение " + view.mood() + word(view, MobState.Axis.MOOD) + " · спокойствие " + view.calm() + word(view, MobState.Axis.CALM)
                + " · интерес " + view.interest() + word(view, MobState.Axis.INTEREST) + " · доверие " + view.trust() + word(view, MobState.Axis.TRUST)
                + " · бодрость " + view.energy() + word(view, MobState.Axis.ENERGY));
        if (view.spell() != null) out.add("Долгое состояние: " + view.spell().word + " (уже " + Math.max(1, view.spellMillis() / 60_000) + " мин)"
                + (view.spell().help.isBlank() ? "" : " · поможет: " + view.spell().help));
        if (!view.reasons().isEmpty()) out.add("Отчего так: " + String.join(", ", view.reasons().stream()
                .map(reason -> (reason.sign() > 0 ? "+ " : reason.sign() < 0 ? "− " : "") + reason.text() + (reason.lasting() ? " (сейчас)" : "")).toList()));
        out.add("Мысль: " + (view.thought().isBlank() ? "—" : "«" + view.thought() + "»"));
        if (view.refusesDeals()) out.add("Слишком встревожен или недоверчив, чтобы торговаться.");

        if (r != null && r.rapport != null) {
            section(out, "Жесты и подарки");
            Rapport rapport = r.rapport;
            String courtship = rapport.courtship().word;
            out.add("Усталость от жестов: " + rapport.fatigue(now) + (rapport.lastGesture.isBlank() ? "" : " · последний жест: " + rapport.lastGesture.toLowerCase(Locale.ROOT))
                    + (courtship.isBlank() ? "" : " · " + courtship));
            List<Rapport.Given> gifts = rapport.gifts;
            out.add("Помнит подарки: " + (gifts.isEmpty() ? "нет" : String.join(", ", gifts.subList(Math.max(0, gifts.size() - GIFTS), gifts.size()).stream().map(Rapport.Given::item).toList())));
        }

        section(out, "Свои");
        List<String> bonds = store.bonds(mob, now).stream().map(MobRelations.View::label).toList();
        out.add(bonds.isEmpty() ? "Друзей и соперников среди мобов нет." : String.join("; ", bonds));
        List<FamilyTree.Kin> kin = state.family.kin(mob);
        if (!kin.isEmpty()) out.add("Семья: " + String.join(", ", kin.stream().map(k -> k.word() + " " + (k.name().isBlank() ? "?" : k.name()) + (k.dead() ? " †" : "")).toList()));

        section(out, "Действия");
        ActionIntent action = card == null ? ActionIntent.none() : card.action();
        out.add("Сейчас: " + (action == null || action.type() == ActionType.NONE ? "ничего по просьбе" : action.type().name().toLowerCase(Locale.ROOT) + " → " + action.target()));
        out.addAll(duties);

        section(out, "Сделки и услуги");
        int before = out.size();
        Map<String, TradeState> deals = store.tradeStates(mob, player);
        deals.forEach((id, s) -> {
            TradeDeal deal = TradeCatalog.deal(p.type, id);
            out.add("«" + (deal == null ? id : deal.name()) + "»: цена " + s.price() + (s.unlocked() ? " · открыта" : "") + (s.boughtToday() > 0 ? " · куплено сегодня " + s.boughtToday() : ""));
        });
        Favor favor = r == null ? null : r.favor;
        if (favor != null) out.add("Ждёт услугу: " + favor.title() + " (" + favor.count() + "× " + favor.item() + ")");
        if (r != null && !r.favorsDone.isEmpty()) out.add("Вы уже сделали: " + String.join(", ", r.favorsDone));
        Bargaining.Session bargain = store.bargain(mob, player);
        if (bargain != null) out.add("Торг: цена " + bargain.price() + ", уступка " + bargain.discount() + ", раундов " + bargain.rounds() + " · " + bargain.status().name().toLowerCase(Locale.ROOT));
        if (out.size() == before) out.add("Нет.");

        section(out, "Память");
        List<MemoryFact> facts = new ArrayList<>(p.facts);
        facts.sort(Comparator.comparingInt(MemoryFact::importance).reversed().thenComparing(Comparator.comparingLong(MemoryFact::time).reversed()));
        if (facts.isEmpty()) out.add("Фактов пока нет.");
        for (MemoryFact fact : facts.stream().limit(FACTS).toList())
            out.add("[" + "★".repeat(MemoryFact.clamp(fact.importance())) + "] " + (fact.reflection() ? "вывод: " : "") + fact.text());
        if (facts.size() > FACTS) out.add("…и ещё " + (facts.size() - FACTS));
        List<ConversationSummary.Summary> summaries = new ArrayList<>(p.summaries);
        if (!summaries.isEmpty()) {
            out.add("Сводки разговоров (новые первыми):");
            for (int i = summaries.size() - 1; i >= Math.max(0, summaries.size() - SUMMARIES); i--) out.add("— " + summaries.get(i).text());
        }

        section(out, "Место");
        if (place.isEmpty()) out.add("Не анализировалось (моб не загружен, далеко или анализ места выключен).");
        else {
            if (place.get("summary") instanceof String summary && !summary.isBlank()) out.add(summary);
            if (place.get("exits") instanceof List<?> exits) out.add("Выходы: " + (exits.isEmpty() ? "нет" : String.join(", ", exits.stream().map(String::valueOf).toList())));
            if (place.get("escape") instanceof String escape && !escape.isBlank()) out.add("Путь наружу: " + escape);
            if (place.get("looksLike") instanceof String looks && !looks.isBlank())
                out.add("Похоже на: " + looks + (place.get("looksLikeConfidence") instanceof Number c ? " (" + Math.round(c.doubleValue() * 100) + "%)" : ""));
            if (place.get("objects") instanceof List<?> objects && !objects.isEmpty()) out.add("Рядом: " + String.join("; ", objects.stream().map(String::valueOf).toList()));
        }
    }

    private static void village(List<String> out, ConversationStore.State state, ConversationStore.Village village, boolean another, UUID player, long now) {
        section(out, (another ? "Деревня моба: «" : "Деревня «") + village.name() + "»");
        out.add("История: " + text(village.story()));
        if (another) out.addAll(standingLines(SocialLedger.standing(state, village.id(), player, now), village.name()));
        List<ConversationStore.Profile> residents = state.profiles.values().stream()
                .filter(q -> village.id().equals(q.villageId) && "minecraft:villager".equals(q.type)).toList();
        int alive = (int) residents.stream().filter(q -> !state.family.dead(q.id)).count();
        out.add("Жители (" + alive + " живых из " + residents.size() + " известных):");
        List<ConversationStore.Profile> sorted = new ArrayList<>(residents);
        sorted.sort(Comparator.comparing((ConversationStore.Profile q) -> state.family.dead(q.id)).thenComparing(q -> -score(q, player)));
        for (ConversationStore.Profile q : sorted.stream().limit(RESIDENTS).toList()) {
            ConversationStore.Relation r = player == null ? null : q.relations.get(player.toString());
            int score = r == null ? 0 : r.score;
            out.add("— " + SocialLedger.name(q) + (state.family.dead(q.id) ? " † погиб" : "") + ": "
                    + (r == null ? "не знаком с вами" : (score >= 0 ? "+" : "") + score + " " + Hearts.symbols(score)).strip());
        }
        if (sorted.size() > RESIDENTS) out.add("…и ещё " + (sorted.size() - RESIDENTS));

        out.add("Слухи:");
        List<VillageRumors.View> rumors = state.rumors.village(village.id(), now);
        if (rumors.isEmpty()) out.add("— никто ни о чём не судачит");
        for (VillageRumors.View rumor : rumors)
            out.add("— " + rumor.label() + String.format(Locale.ROOT, " · вес %.1f", rumor.weight()));

        out.add("Семьи:");
        Set<String> couples = new LinkedHashSet<>();
        for (ConversationStore.Profile q : residents) {
            UUID spouse = state.family.spouse(q.id);
            List<UUID> children = state.family.children(q.id);
            if (spouse == null && children.isEmpty()) continue;
            UUID first = spouse == null || q.id.compareTo(spouse) < 0 ? q.id : spouse;
            String pair = spouse == null ? name(state, q.id) : name(state, first) + " и " + name(state, first.equals(q.id) ? spouse : q.id);
            String kids = children.isEmpty() ? "" : " — дети: " + String.join(", ", children.stream().map(child -> name(state, child) + (state.family.dead(child) ? " †" : "")).toList());
            couples.add("— " + pair + kids);
        }
        if (couples.isEmpty()) out.add("— пар и детей пока нет");
        out.addAll(couples);
    }

    private static int score(ConversationStore.Profile q, UUID player) {
        ConversationStore.Relation r = player == null ? null : q.relations.get(player.toString());
        return r == null ? Integer.MIN_VALUE / 2 : r.score;
    }
    /** A villager's name from its profile, else from the family tree; «?» when neither knows it. */
    private static String name(ConversationStore.State state, UUID id) {
        ConversationStore.Profile p = state.profiles.get(id.toString());
        if (p != null) return SocialLedger.name(p);
        FamilyTree.Node node = state.family.nodes.get(id.toString());
        return node == null || node.name.isBlank() ? "?" : node.name;
    }
    private static String word(MobState.View view, MobState.Axis axis) {
        String word = view.descriptor(axis).word();
        return word == null || word.isBlank() ? "" : " (" + word + ")";
    }
    private static String text(String value) { return value == null || value.isBlank() ? "—" : value.strip(); }
    private static void section(List<String> out, String title) {
        if (!out.isEmpty()) out.add("");
        out.add("■ " + title.toUpperCase(Locale.ROOT));
    }
}
