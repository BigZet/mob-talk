package io.github.mobtalk.core;

import io.github.mobtalk.core.cards.DealerStyle;
import io.github.mobtalk.core.cards.Purse;
import java.util.*;

/** Игра в 21 в памяти мира (docs/cards-21.md): кошельки жителей, залог ставок на столах, долг игрокам после сбоя и дневной итог
 * игрока. Залог пишется в сохранение мира вместе с памятью: если сервер упал посреди раунда, при следующем старте всё, что лежало
 * на столах, становится долгом и возвращается игроку при входе ({@link #recover}, {@link #takeOwed}). Каждый метод под замком
 * {@link ConversationStore}; изумруды двигает игра, правила раунда — {@code core.cards.CardTable}. */
public final class CardLedger {
    /** Сколько записей сессий держит мир для админки. */
    public static final int HISTORY = 200;
    private final ConversationStore store;
    CardLedger(ConversationStore store) { this.store = store; }

    /** Сохраняемая часть: всё, что пережило бы выход из мира. */
    static final class Book {
        /** Житель → его изумруды и игровой день последнего пополнения. */
        Map<String, Wallet> purses = new LinkedHashMap<>();
        /** Игрок → изумруды, которые сейчас лежат на столе (залог). */
        Map<String, Integer> stakes = new LinkedHashMap<>();
        /** Игрок → изумруды, которые вернуть при входе (раунд отменён, когда его не было в сети, или сервер упал). */
        Map<String, Integer> owed = new LinkedHashMap<>();
        /** Игрок → итог игрового дня (выигрыш минус проигрыш). */
        Map<String, Day> days = new LinkedHashMap<>();
        /** Прошедшие сессии, старые первыми. */
        List<Session> history = new ArrayList<>();
        /** Игрок → игровой день, когда житель последний раз сам звал его сыграть. */
        Map<String, Integer> invited = new LinkedHashMap<>();
        /** Игрок → предметы на столе сейчас и предметы, которые вернуть при входе. */
        Map<String, List<ItemStake>> items = new LinkedHashMap<>(), owedItems = new LinkedHashMap<>();
        /** «житель:игрок» → сколько раз за день сыграли на интерес; → день последней скидки на кону. */
        Map<String, Day> interest = new LinkedHashMap<>();
        Map<String, Integer> discounts = new LinkedHashMap<>();

        void sanitize() {
            if (invited == null) invited = new LinkedHashMap<>();
            if (items == null) items = new LinkedHashMap<>();
            if (owedItems == null) owedItems = new LinkedHashMap<>();
            items.values().removeIf(Objects::isNull); owedItems.values().removeIf(Objects::isNull);
            if (interest == null) interest = new LinkedHashMap<>();
            if (discounts == null) discounts = new LinkedHashMap<>();
            if (purses == null) purses = new LinkedHashMap<>();
            purses.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
            purses.values().forEach(w -> w.balance = Math.max(0, w.balance));
            if (stakes == null) stakes = new LinkedHashMap<>();
            stakes.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() <= 0);
            if (owed == null) owed = new LinkedHashMap<>();
            owed.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue() <= 0);
            if (days == null) days = new LinkedHashMap<>();
            days.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
            if (history == null) history = new ArrayList<>();
            history.removeIf(Objects::isNull);
            while (history.size() > HISTORY) history.removeFirst();
        }
        Book copy() {
            Book c = new Book();
            purses.forEach((key, w) -> c.purses.put(key, new Wallet(w.balance, w.day)));
            c.stakes.putAll(stakes); c.owed.putAll(owed);
            days.forEach((key, d) -> c.days.put(key, new Day(d.day, d.net)));
            c.history.addAll(history); // Записи неизменяемы.
            c.invited.putAll(invited);
            items.forEach((k, v) -> c.items.put(k, new ArrayList<>(v)));
            owedItems.forEach((k, v) -> c.owedItems.put(k, new ArrayList<>(v)));
            interest.forEach((k, d) -> c.interest.put(k, new Day(d.day, d.net)));
            c.discounts.putAll(discounts);
            return c;
        }
    }
    static final class Wallet { int balance; long day; Wallet(int balance, long day) { this.balance = balance; this.day = day; } }
    static final class Day { long day; int net; Day(long day, int net) { this.day = day; this.net = net; } }
    /** Одна сессия за столом для «Истории» админки: когда, кто, с кем, сколько раундов и чем кончилось для игрока. */
    public record Session(long time, String player, String playerName, String villager, String villagerName, String variant, int rounds, int net, String note) { }

    private Book book() {
        if (store.state.cards == null) store.state.cards = new Book();
        return store.state.cards;
    }

    // ---- Кошелёк жителя. ----

    /** Кошелёк жителя на сегодня: при первом взгляде — по уровню торговли ({@code base} + 4 за уровень), потом +{@code perDay} в
     * игровой день, пока не дойдёт до старта. */
    public int purse(UUID villager, int level, long day, int base, int perDay) {
        synchronized (store) {
            String key = villager.toString();
            int start = Math.max(0, base) + 4 * (Math.max(1, Math.min(5, level)) - 1);
            Wallet wallet = book().purses.get(key);
            if (wallet == null) { book().purses.put(key, new Wallet(start, day)); store.scheduleSave(); return start; }
            Purse purse = new Purse(wallet.balance, wallet.day);
            purse.regenerate(day, perDay, start);
            if (purse.balance() != wallet.balance || purse.day() != wallet.day) { wallet.balance = purse.balance(); wallet.day = purse.day(); store.scheduleSave(); }
            return wallet.balance;
        }
    }
    /** Кошелёк после раунда или продажи. */
    public void setPurse(UUID villager, int balance) {
        synchronized (store) {
            Wallet wallet = book().purses.get(villager.toString());
            if (wallet == null) book().purses.put(villager.toString(), new Wallet(Math.max(0, balance), 0));
            else wallet.balance = Math.max(0, balance);
            store.scheduleSave();
        }
    }
    /** Сколько у жителя сейчас, −1 — кошелька ещё нет. */
    public int balance(UUID villager) {
        synchronized (store) { Wallet w = villager == null ? null : book().purses.get(villager.toString()); return w == null ? -1 : w.balance; }
    }
    /** Житель продал игроку товар: изумруды идут и в его кошелёк. */
    public void sold(UUID villager, int emeralds) {
        if (villager == null || emeralds <= 0) return;
        synchronized (store) {
            Wallet wallet = book().purses.get(villager.toString());
            if (wallet == null) return; // Кошелёк появится при первой игре — по уровню.
            wallet.balance += emeralds;
            store.scheduleSave();
        }
    }

    /** Характер жителя за столом: по его персоне в памяти, у незнакомца — та же, что дала бы ему память при встрече. */
    public DealerStyle style(UUID villager) {
        synchronized (store) {
            ConversationStore.Profile p = villager == null ? null : store.state.profiles.get(villager.toString());
            Persona persona = p != null && p.social != null ? p.social.persona() : villager == null ? null : SocialProfile.of(villager, false).persona();
            return DealerStyle.of(persona);
        }
    }

    // ---- Залог и долг. ----

    /** Изумруды игрока легли на стол ({@code delta} > 0) или ушли с него (выплата, проигрыш, возврат). */
    public void staked(UUID player, int delta) {
        if (player == null || delta == 0) return;
        synchronized (store) {
            String key = player.toString();
            int now = Math.max(0, book().stakes.getOrDefault(key, 0) + delta);
            if (now == 0) book().stakes.remove(key); else book().stakes.put(key, now);
            store.scheduleSave();
        }
    }
    /** Изумруды игрока на столах сейчас. */
    public int stakedBy(UUID player) { synchronized (store) { return player == null ? 0 : book().stakes.getOrDefault(player.toString(), 0); } }
    /** Вернуть игроку при входе. */
    public void owe(UUID player, int emeralds) {
        if (player == null || emeralds <= 0) return;
        synchronized (store) { book().owed.merge(player.toString(), emeralds, Integer::sum); store.scheduleSave(); }
    }
    /** Долг игроку целиком (и списать его); 0 — ничего. */
    public int takeOwed(UUID player) {
        if (player == null) return 0;
        synchronized (store) {
            Integer owed = book().owed.remove(player.toString());
            if (owed != null) store.scheduleSave();
            return owed == null ? 0 : owed;
        }
    }
    /** Старт мира: столов уже нет, всё, что на них лежало, становится долгом. Возвращает, скольким игрокам. */
    public int recover() {
        synchronized (store) {
            Book book = book();
            int players = 0;
            for (Map.Entry<String, Integer> e : book.stakes.entrySet()) {
                if (e.getValue() <= 0) continue;
                book.owed.merge(e.getKey(), e.getValue(), Integer::sum);
                players++;
            }
            book.stakes.clear();
            for (Map.Entry<String, List<ItemStake>> e : book.items.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                book.owedItems.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
                players++;
            }
            book.items.clear();
            if (players > 0) store.scheduleSave();
            return players;
        }
    }

    // ---- Житель помнит игру: память, отношение, слухи. ----

    /** Отношение за сессию за столом и обида за крупный выигрыш за день: больше половины кошелька — уже не игра, а грабёж. */
    public static final int SESSION_RAPPORT = 1, GREEDY_PENALTY = -5, FALSE_ACCUSE_PENALTY = -8;
    /** Сумма, с которой о раздаче говорит деревня. */
    public static final int RUMOR_AMOUNT = 10;

    /** Характер жителя словами для реплик за столом: персона и черты из памяти. */
    public String persona(UUID villager) {
        synchronized (store) {
            ConversationStore.Profile p = villager == null ? null : store.state.profiles.get(villager.toString());
            SocialProfile social = p != null && p.social != null ? p.social : villager == null ? null : SocialProfile.of(villager, false);
            if (social == null) return "обычный житель";
            String word = switch (social.persona()) {
                case ANXIOUS -> "тревожный"; case SENSITIVE -> "ранимый"; case GLOOMY -> "мрачный"; case PEACEFUL -> "миролюбивый";
                case INTROVERT -> "нелюдимый"; case FRIENDLY -> "дружелюбный"; case UNFLAPPABLE -> "невозмутимый"; case GRUMPY -> "ворчливый";
                case ODD -> "чудной"; case EXTROVERT -> "компанейский"; case CHEERFUL -> "жизнерадостный"; case FLIRTY -> "кокетливый";
                case GREEDY -> "жадный"; case PLAYFUL -> "игривый";
            };
            return p != null && p.personality != null && !p.personality.isBlank() ? word + "; " + TextBounds.characters(p.personality, 300) : word;
        }
    }
    /** Что житель помнит о картах с этим игроком: строки о прошлых партиях из памяти. */
    public String remembered(UUID villager, UUID player) {
        synchronized (store) {
            ConversationStore.Profile p = villager == null ? null : store.state.profiles.get(villager.toString());
            if (p == null) return "";
            List<String> lines = new ArrayList<>();
            for (MemoryFact fact : p.facts) if (fact.text().contains("в 21")) lines.add(fact.text());
            return String.join("; ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
        }
    }

    /** Игрок встал из-за стола: строка в память жителя, +1 к отношению за компанию, −5 и обида, если игрок за день выиграл у него
     * больше половины его кошелька; крупный итог — слух в деревне с именем игрока. */
    public void afterSession(UUID villager, String type, String name, UUID player, String playerName, int rounds, int net, int wonToday, int purseStart, long now) {
        if (villager == null || player == null || rounds <= 0) return;
        synchronized (store) {
            ConversationStore.Profile p = store.ensure(villager, type, name);
            ConversationStore.Relation r = store.relation(p, player);
            String who = playerName == null || playerName.isBlank() ? "игрок" : playerName;
            String result = net > 0 ? "он выиграл у тебя " + net + " изумр." : net < 0 ? "ты выиграл у него " + (-net) + " изумр." : "сыграли вничью";
            int importance = Math.abs(net) >= RUMOR_AMOUNT ? 3 : 2;
            int delta = SESSION_RAPPORT;
            String grudge = "";
            if (wonToday * 2 > Math.max(1, purseStart)) { delta += GREEDY_PENALTY; grudge = "; обидно: за день он обобрал тебя больше чем на полкошелька"; importance = 4; }
            r.score = ConversationStore.clamp(r.score + delta, p.hostile());
            String text = "Играл в 21 с " + who + ": " + rounds + " раздач, " + result + grudge;
            MemoryRanking.remember(p.facts, MemoryFact.of(text, importance, now), Math.max(200, Math.min(4000, store.config().maxMemoryChars)), now);
            p.memory = MemoryRanking.render(p.facts);
            ConversationStore.event(p, now, text, importance);
            store.scheduleSave();
            if (Math.abs(net) >= RUMOR_AMOUNT && p.villageId != null && !p.villageId.isBlank()) {
                String villagerName = p.givenName.isBlank() ? p.name : p.givenName;
                String rumor = net > 0 ? who + " обыграл " + villagerName + " в 21 на " + net + " изумрудов" : villagerName + " обыграл " + who + " в 21 на " + (-net) + " изумрудов";
                store.addRumors(p.villageId, List.of(new VillageRumors.View("", rumor, villagerName, 2, 0, now, 0, who)), now);
            }
        }
    }
    /** «Ловлю!» сработало: жителю стыдно (в память), деревня узнаёт, что он шулер. */
    public void caught(UUID villager, String type, String name, UUID player, String playerName, long now) {
        if (villager == null || player == null) return;
        synchronized (store) {
            ConversationStore.Profile p = store.ensure(villager, type, name);
            String who = playerName == null || playerName.isBlank() ? "игрок" : playerName;
            String text = who + " поймал тебя в 21 на карте из рукава — стыдно";
            MemoryRanking.remember(p.facts, MemoryFact.of(text, 4, now), Math.max(200, Math.min(4000, store.config().maxMemoryChars)), now);
            p.memory = MemoryRanking.render(p.facts);
            ConversationStore.event(p, now, text, 4);
            store.scheduleSave();
            if (p.villageId != null && !p.villageId.isBlank()) {
                String villagerName = p.givenName.isBlank() ? p.name : p.givenName;
                store.addRumors(p.villageId, List.of(new VillageRumors.View("", villagerName + " — шулер: " + who + " поймал его за руку в 21", who, 4, 0, now, 0, "")), now);
            }
        }
    }
    /** «Ловлю!» зря: −8 к отношению и обида в памяти. */
    public void falselyAccused(UUID villager, String type, String name, UUID player, String playerName, long now) {
        if (villager == null || player == null) return;
        synchronized (store) {
            ConversationStore.Profile p = store.ensure(villager, type, name);
            ConversationStore.Relation r = store.relation(p, player);
            r.score = ConversationStore.clamp(r.score + FALSE_ACCUSE_PENALTY, p.hostile());
            String who = playerName == null || playerName.isBlank() ? "игрок" : playerName;
            String text = who + " зря обвинил тебя в шулерстве за игрой в 21 — обидно";
            MemoryRanking.remember(p.facts, MemoryFact.of(text, 3, now), Math.max(200, Math.min(4000, store.config().maxMemoryChars)), now);
            p.memory = MemoryRanking.render(p.facts);
            ConversationStore.event(p, now, text, 3);
            p.revision++;
            store.scheduleSave();
        }
    }
    /** Житель сам звал этого игрока сыграть в этот игровой день: не чаще раза в день. Возвращает true и запоминает, если ещё не звал. */
    public boolean invite(UUID player, long day) {
        if (player == null) return false;
        synchronized (store) {
            Integer last = book().invited.get(player.toString());
            if (last != null && last == (int) day) return false;
            book().invited.put(player.toString(), (int) day);
            store.scheduleSave();
            return true;
        }
    }
    /** Сколько игрок выиграл (плюс) у этого жителя за день: для обиды. Считает по истории сессий за последние сутки реального времени. */
    public int wonToday(UUID villager, UUID player, long now) {
        if (villager == null || player == null) return 0;
        synchronized (store) {
            int sum = 0;
            for (Session s : book().history)
                if (now - s.time() < 20 * 60_000L && s.villager().equals(villager.toString()) && s.player().equals(player.toString())) sum += s.net();
            return Math.max(0, sum);
        }
    }

    // ---- Этап 5: ставки предметами, на интерес, скидка на кону, услуга за проигрыш. ----

    /** Предмет на кону: id и количество (только простые предметы, без зачарований и имён). */
    public record ItemStake(String item, int count) { }
    /** Сколько изумрудов житель даёт за предмет на кону; 0 — такое на кон не берёт. Оценка по редкости, не по рынку. */
    public static int itemValue(String item) {
        if (item == null) return 0;
        return switch (item) {
            case "minecraft:diamond" -> 8;
            case "minecraft:gold_ingot", "minecraft:lapis_lazuli", "minecraft:amethyst_shard" -> 2;
            case "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:redstone", "minecraft:honeycomb", "minecraft:book", "minecraft:paper" -> 1;
            case "minecraft:gold_nugget", "minecraft:wheat", "minecraft:bread", "minecraft:apple", "minecraft:carrot", "minecraft:potato" -> 1;
            case "minecraft:golden_apple", "minecraft:ender_pearl", "minecraft:name_tag", "minecraft:saddle" -> 4;
            case "minecraft:cake", "minecraft:honey_bottle", "minecraft:glow_berries", "minecraft:pumpkin_pie" -> 2;
            case "minecraft:emerald_block" -> 9;
            case "minecraft:netherite_scrap", "minecraft:music_disc_cat", "minecraft:music_disc_13" -> 12;
            default -> 0;
        };
    }
    /** Сколько раз в игровой день игра на интерес греет отношение. */
    public static final int INTEREST_PER_DAY = 5;
    /** Ставка-услуга: сколько изумрудов она стоит на кону; скидка на кону — от и до процентов; отношение, с которого житель её ставит. */
    public static final int FAVOR_VALUE = 4, DISCOUNT_MIN = 10, DISCOUNT_MAX = 30, DISCOUNT_RELATION = 15;

    public void stakeItem(UUID player, ItemStake stake) {
        if (player == null || stake == null) return;
        synchronized (store) { book().items.computeIfAbsent(player.toString(), k -> new ArrayList<>()).add(stake); store.scheduleSave(); }
    }
    /** Предметы игрока с кона (и снять их с залога): после раздачи, отмены или выхода. */
    public List<ItemStake> releaseItems(UUID player) {
        if (player == null) return List.of();
        synchronized (store) { List<ItemStake> items = book().items.remove(player.toString()); if (items != null) store.scheduleSave(); return items == null ? List.of() : items; }
    }
    public void oweItems(UUID player, List<ItemStake> items) {
        if (player == null || items == null || items.isEmpty()) return;
        synchronized (store) { book().owedItems.computeIfAbsent(player.toString(), k -> new ArrayList<>()).addAll(items); store.scheduleSave(); }
    }
    public List<ItemStake> takeOwedItems(UUID player) {
        if (player == null) return List.of();
        synchronized (store) { List<ItemStake> items = book().owedItems.remove(player.toString()); if (items != null) store.scheduleSave(); return items == null ? List.of() : items; }
    }
    /** Игра на интерес: +1 к отношению, не больше {@link #INTEREST_PER_DAY} раз за игровой день с этим жителем. true — засчитано. */
    public boolean interest(UUID villager, String type, String name, UUID player, long day) {
        if (villager == null || player == null) return false;
        synchronized (store) {
            String key = villager + ":" + player;
            Day d = book().interest.get(key);
            if (d == null || d.day != day) { d = new Day(day, 0); book().interest.put(key, d); }
            if (d.net >= INTEREST_PER_DAY) return false;
            d.net++;
            ConversationStore.Profile p = store.ensure(villager, type, name);
            ConversationStore.Relation r = store.relation(p, player);
            r.score = ConversationStore.clamp(r.score + 1, p.hostile());
            store.scheduleSave();
            return true;
        }
    }
    /** Житель сегодня уже ставил скидку на кон этому игроку? Иначе запомнить и разрешить. */
    public boolean discountToday(UUID villager, UUID player, long day) {
        synchronized (store) {
            String key = villager + ":" + player;
            Integer last = book().discounts.get(key);
            if (last != null && last == (int) day) return false;
            book().discounts.put(key, (int) day);
            store.scheduleSave();
            return true;
        }
    }
    /** Игрок женат (замужем) за этим жителем: с супругом — только на интерес. */
    public boolean married(UUID villager, UUID player) {
        if (villager == null || player == null) return false;
        for (JealousyLedger.Partner partner : store.jealousy().partners(player)) if (partner.married() && villager.equals(partner.id())) return true;
        return false;
    }
    /** Супруг игрока, если он есть и это не {@code villager}: ему не нравится, когда игрок спускает изумруды другим. */
    public UUID spouseOtherThan(UUID villager, UUID player) {
        if (player == null) return null;
        for (JealousyLedger.Partner partner : store.jealousy().partners(player)) if (partner.married() && !partner.id().equals(villager)) return partner.id();
        return null;
    }
    /** Супруг узнал, что игрок проиграл в карты другому: строка в память, супруг ворчит в разговоре. */
    public void grumble(UUID spouse, UUID player, String playerName, String villagerName, int lost, long now) {
        if (spouse == null || lost <= 0) return;
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(spouse.toString());
            if (p == null) return;
            String who = playerName == null || playerName.isBlank() ? "Игрок" : playerName;
            String text = who + " проиграл в карты " + lost + " изумр. у " + villagerName + " — тебе это не нравится, деньги-то общие";
            MemoryRanking.remember(p.facts, MemoryFact.of(text, 3, now), Math.max(200, Math.min(4000, store.config().maxMemoryChars)), now);
            p.memory = MemoryRanking.render(p.facts);
            ConversationStore.event(p, now, text, 3);
            store.scheduleSave();
        }
    }
    /** Проиграл ставку-услугу: житель просит принести {@code count} {@code item} («Карточный долг»). false — у них уже есть услуга. */
    public boolean cardDebt(UUID villager, String type, String name, UUID player, String item, int count, long now) {
        if (villager == null || player == null) return false;
        synchronized (store) {
            ConversationStore.Profile p = store.ensure(villager, type, name);
            if (store.favor(villager, player) != null) return false;
            store.startFavor(villager, player, new Favor(item, Math.max(1, Math.min(Favor.MAX_COUNT, count)), "Карточный долг", "", 0, villager, now), now);
            return true;
        }
    }
    /** Можно ли ставить услугу: у игрока с этим жителем нет открытой. */
    public boolean favorOpen(UUID villager, UUID player) { return store.favor(villager, player) != null; }

    // ---- Итог дня и история. ----

    /** Итог игрока за этот игровой день: выигрыш минус проигрыш. */
    public int net(UUID player, long day) {
        synchronized (store) { Day d = player == null ? null : book().days.get(player.toString()); return d == null || d.day != day ? 0 : d.net; }
    }
    public void addNet(UUID player, long day, int delta) {
        if (player == null || delta == 0) return;
        synchronized (store) {
            Day d = book().days.get(player.toString());
            if (d == null || d.day != day) book().days.put(player.toString(), new Day(day, delta));
            else d.net += delta;
            store.scheduleSave();
        }
    }
    public void record(Session session) {
        if (session == null) return;
        synchronized (store) {
            List<Session> history = book().history;
            history.add(session);
            while (history.size() > HISTORY) history.removeFirst();
            store.scheduleSave();
        }
    }
    /** Последние сессии, новые первыми. */
    public List<Session> history(int limit) {
        synchronized (store) {
            List<Session> all = new ArrayList<>(book().history);
            Collections.reverse(all);
            return all.subList(0, Math.min(Math.max(0, limit), all.size()));
        }
    }
}
