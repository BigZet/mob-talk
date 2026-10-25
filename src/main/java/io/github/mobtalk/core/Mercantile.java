package io.github.mobtalk.core;

import java.util.*;

/** Меркантильные горожанки: жительница с характером {@link Persona#MERCANTILE} оценивает кошелёк игрока, сама выпрашивает подарки
 * с растущим аппетитом, мелочь не ценит, без подарков быстро остывает, а из ухажёров выбирает богатого. Сатира, без откровенностей:
 * флирт расчётливый и игривый. Решения — здесь, чистой Java; модель их только озвучивает. Реальное время, изменения под замком
 * хранилища. Достаётся взрослым жительницам: чаще в городах (структуры {@code mobtalk:city…}), реже в деревнях. */
public final class Mercantile {
    private Mercantile() { }

    /** Цена в изумрудах того, что меркантильная считает деньгами или дорогой вещью: валюта, драгоценные блоки, редкости. */
    static final Map<String, Integer> PRICES = Map.ofEntries(
            Map.entry("minecraft:emerald", 1), Map.entry("minecraft:emerald_block", 9),
            Map.entry("minecraft:gold_ingot", 1), Map.entry("minecraft:gold_block", 9), Map.entry("minecraft:raw_gold_block", 6),
            Map.entry("minecraft:diamond", 4), Map.entry("minecraft:diamond_block", 36),
            Map.entry("minecraft:netherite_ingot", 32), Map.entry("minecraft:netherite_block", 288),
            Map.entry("minecraft:amethyst_shard", 1), Map.entry("minecraft:lapis_block", 2),
            Map.entry("minecraft:golden_apple", 8), Map.entry("minecraft:enchanted_golden_apple", 64),
            Map.entry("minecraft:cake", 2), Map.entry("minecraft:pumpkin_pie", 1), Map.entry("minecraft:glistering_melon_slice", 2),
            Map.entry("minecraft:golden_carrot", 1), Map.entry("minecraft:honey_bottle", 1),
            Map.entry("minecraft:heart_of_the_sea", 32), Map.entry("minecraft:nether_star", 64), Map.entry("minecraft:totem_of_undying", 24),
            Map.entry("minecraft:nautilus_shell", 3), Map.entry("minecraft:music_disc_cat", 6), Map.entry("minecraft:clock", 6),
            Map.entry("minecraft:spyglass", 3), Map.entry("minecraft:enchanted_book", 6), Map.entry("minecraft:name_tag", 3),
            Map.entry("minecraft:golden_helmet", 5), Map.entry("minecraft:golden_chestplate", 8), Map.entry("minecraft:golden_leggings", 7),
            Map.entry("minecraft:golden_boots", 4), Map.entry("minecraft:golden_horse_armor", 8),
            Map.entry("minecraft:diamond_helmet", 20), Map.entry("minecraft:diamond_chestplate", 32), Map.entry("minecraft:diamond_leggings", 28),
            Map.entry("minecraft:diamond_boots", 16), Map.entry("minecraft:diamond_horse_armor", 24),
            Map.entry("minecraft:netherite_helmet", 40), Map.entry("minecraft:netherite_chestplate", 48), Map.entry("minecraft:netherite_leggings", 44),
            Map.entry("minecraft:netherite_boots", 36), Map.entry("mobtalk:engagement_ring", 12), Map.entry("mobtalk:wedding_ring", 8));
    /** Что считается деньгами при себе (кошелёк): валюта и драгоценные блоки, без вещей. */
    static final Set<String> MONEY = Set.of("minecraft:emerald", "minecraft:emerald_block", "minecraft:gold_ingot", "minecraft:gold_block",
            "minecraft:diamond", "minecraft:diamond_block", "minecraft:netherite_ingot", "minecraft:netherite_block");
    /** Чего она хочет, по возрастанию цены: желание — первое, что не дешевле аппетита; слова для промпта. */
    record Wish(String item, String words) { }
    static final List<Wish> WISHES = List.of(
            new Wish("minecraft:emerald", "изумрудик «на кофе»"), new Wish("minecraft:cake", "торт, как в кафе"),
            new Wish("minecraft:diamond", "алмаз — девушкам нужны бриллианты"), new Wish("minecraft:enchanted_book", "зачарованную книгу «для саморазвития»"),
            new Wish("minecraft:golden_apple", "золотое яблоко"), new Wish("minecraft:emerald_block", "изумрудный блок «на сапожки»"),
            new Wish("minecraft:golden_chestplate", "золотой нагрудник — чтобы блестел"), new Wish("minecraft:diamond_boots", "алмазные сапожки"),
            new Wish("minecraft:heart_of_the_sea", "сердце моря — как у Розы в «Титанике»"), new Wish("minecraft:diamond_chestplate", "алмазную шубу (нагрудник)"),
            new Wish("minecraft:diamond_block", "алмазный блок «на квартиру»"), new Wish("minecraft:nether_star", "звезду Незера с неба"),
            new Wish("minecraft:enchanted_golden_apple", "зачарованное золотое яблоко"));

    /** Как она видит кошелёк игрока. */
    public enum Purse {
        BROKE("голодранец: при нём почти ничего ценного"), MODEST("так себе: кое-что звенит, но не олигарх"), RICH("при деньгах: богатый жених");
        public final String word;
        Purse(String word) { this.word = word; }
    }

    public static boolean on(MobTalkConfig c) { return c != null && c.mercantile; }
    /** Жительница меркантильна и слой включён. */
    public static boolean active(ConversationStore.Profile p) {
        return p != null && p.social != null && p.social.persona() == Persona.MERCANTILE && on(Tuning.get());
    }

    /** Цена вещи в изумрудах; 0 — для неё это мелочь (цветы, хлеб, инструменты). */
    public static int price(String item) { Integer price = item == null ? null : PRICES.get(item); return price == null ? 0 : price; }
    /** Богатство игрока в изумрудах: деньги в инвентаре ({@code counts}: id → сколько) и надетое ({@code worn}). */
    public static int wealth(Map<String, Integer> counts, Collection<String> worn) {
        long sum = 0;
        if (counts != null) for (Map.Entry<String, Integer> e : counts.entrySet())
            if (MONEY.contains(e.getKey()) && e.getValue() != null) sum += (long) price(e.getKey()) * Math.max(0, e.getValue());
        if (worn != null) for (String item : worn) sum += price(item) / 2; // Надетое — видно, но в карман не положишь: вполцены.
        return (int) Math.min(100_000, sum);
    }
    /** Богатство игрока, как его посчитала игра ({@code playerDetails.wealth}); −1 — не видно (слепой моб, старый снимок). */
    static int wealth(MobSnapshot snapshot) {
        return snapshot != null && snapshot.playerDetails().get("wealth") instanceof Number n ? n.intValue() : -1;
    }
    /** При каждом взгляде на неё: запомнить кошелёк игрока (для выбора у соперников) и списать остывание. Под замком хранилища;
     * возвращает, изменилось ли что-то. */
    static boolean observe(ConversationStore.State state, ConversationStore.Profile p, ConversationStore.Relation rel, MobSnapshot snapshot, MobTalkConfig c, long now) {
        if (!active(p) || rel == null) return false;
        if (rel.rapport == null) rel.rapport = new Rapport();
        boolean changed = false;
        int wealth = wealth(snapshot);
        if (wealth >= 0 && wealth != rel.rapport.wallet) { rel.rapport.wallet = wealth; changed = true; }
        if (rel.rapport.paidAt >= 0) changed |= cool(rel, c, now) > 0;
        int expired = expireDinner(rel.rapport, now);
        if (expired != 0) {
            rel.score = ConversationStore.clamp(rel.score + expired, false);
            ConversationStore.event(p, now, "Игрок сводил тебя в ресторан и не заплатил по счёту — позор, ты оскорблена; отношение " + rel.score, 4);
            if (snapshot.playerId() != null) gossip(state, p, snapshot.playerId(), RivalryLedger.playerName(state, p, snapshot.playerId()), Talk.STINGY, "сбежал от счёта в ресторане", now);
            changed = true;
        }
        if (forgotAnniversary(rel, now)) {
            ConversationStore.event(p, now, "Игрок забыл про вашу «годовщину» — ни подарка, ни цветов; ты смертельно обижена; отношение " + rel.score, 4);
            if (snapshot.playerId() != null) gossip(state, p, snapshot.playerId(), RivalryLedger.playerName(state, p, snapshot.playerId()), Talk.STINGY, "забыл про годовщину", now);
            changed = true;
        }
        return changed;
    }
    public static Purse purse(int wealth, MobTalkConfig c) {
        if (wealth < 0) return Purse.MODEST; // Не видно — не судит.
        return wealth < c.mercPoor ? Purse.BROKE : wealth >= c.mercRich ? Purse.RICH : Purse.MODEST;
    }

    /** Станет ли эта жительница меркантильной: решается раз, по UUID; {@code city} — живёт в городе. */
    static boolean chosen(UUID id, boolean city, MobTalkConfig c) {
        int percent = city ? c.mercCityPercent : c.mercVillagePercent;
        if (percent <= 0) return false;
        Random random = new Random(id.getLeastSignificantBits() * 0x9E3779B97F4A7C15L ^ id.getMostSignificantBits());
        return random.nextInt(100) < percent;
    }
    /** Город ли это: место жителя — структура города Mob Talk. */
    static boolean city(MobSnapshot snapshot) {
        return snapshot != null && snapshot.mobDetails().get("villageSite") instanceof Map<?, ?> site && site.get("key") instanceof String key
                && key.startsWith("mobtalk:city");
    }
    /** Раз в жизни жительницы: взрослая женщина в городе ({@code city}) или деревне может стать меркантильной. Возвращает,
     * изменилось ли что-то. */
    static boolean roll(ConversationStore.Profile p, boolean adult, boolean city, MobTalkConfig c) {
        if (!on(c) || p == null || p.social == null || p.social.mercRolled || !adult || !"minecraft:villager".equals(p.type)) return false;
        p.social.mercRolled = true;
        if (p.social.gender() == Gender.FEMALE && chosen(p.id, city, c)) p.social.persona = Persona.MERCANTILE.name();
        return true;
    }

    /** Аппетит сейчас: с какой цены подарок её радует. Растёт от подарков, за часы без них понемногу спадает. */
    public static int appetite(Rapport r) { return r == null ? 1 : Math.max(1, r.appetite); }
    /** Чего она хочет сейчас: первое желание не дешевле аппетита. */
    static Wish wish(Rapport r) {
        int appetite = appetite(r);
        for (Wish w : WISHES) if (price(w.item()) >= appetite) return w;
        return WISHES.getLast();
    }

    /** Подарок глазами меркантильной: дорогое — радость (желанное — вдвойне), дешевле аппетита — «и это всё?», мелочь — не берёт. */
    static GestureIntent gift(GestureIntent base, Rapport r, String item) { return gift(base, r, item, 0); }
    /** {@code repeats} — сколько таких же она получила недавно: то же самое подряд радует всё меньше, и «желанным» оно уже не считается. */
    static GestureIntent gift(GestureIntent base, Rapport r, String item, int repeats) {
        if (base == null || base.outcome() == Gesture.Outcome.REFUSED && base.hearts() < 0) return base; // Аллергия сильнее жадности.
        int price = price(item), appetite = appetite(r);
        if (price <= 0) return new GestureIntent(Gesture.GIFT, Gesture.Outcome.REFUSED, -1, item,
                "дешёвка, за кого он тебя держит — ты такое не берёшь, ждёшь хотя бы " + wish(r).words());
        boolean wished = repeats <= 0 && item.equals(wish(r).item());
        if (price < appetite) return new GestureIntent(Gesture.GIFT, Gesture.Outcome.GOOD, 1, item,
                "маловато: возьмёшь, но с кислой миной — ты рассчитывала на " + wish(r).words());
        int hearts = (Math.max(base.hearts(), 2 + 2 * price / Math.max(1, appetite)) + (wished ? 5 : 0)) / (1 + Math.max(0, repeats));
        return new GestureIntent(Gesture.GIFT, wished ? Gesture.Outcome.BEST : price >= appetite * 3 ? Gesture.Outcome.BEST : Gesture.Outcome.BETTER,
                Math.max(1, Math.min(25, hearts)), item, wished ? "ровно то, что ты хотела! ты в восторге" : "дорого — тебе приятно, ты сразу теплеешь");
    }
    /** С какой цены (изумруды) подарком она хвастается подружкам. */
    static final int BRAG_PRICE = 9;
    /** Вещь по-человечески, для сплетни: желанное — её словами, иначе id. */
    static String thing(String item) {
        for (Wish w : WISHES) if (w.item().equals(item)) return w.words();
        return item;
    }
    /** Подарок принят: аппетит растёт (не выше потолка), часы охлаждения начинаются заново. */
    static void paid(Rapport r, String item, MobTalkConfig c, long now) {
        int price = price(item);
        if (r == null || price <= 0) return;
        if (price >= appetite(r)) r.appetite = Math.min(Math.max(1, c.mercAppetiteCap), appetite(r) + Math.max(1, price / 2));
        r.paidAt = now; r.coolAt = now;
        r.anniversaryAt = -1; // Подарок к «годовщине» успел.
    }
    /** Остывание без подарков: за каждые {@code mercCoolMinutes} после последнего подарка минус {@code mercCoolHearts} сердечек,
     * но не ниже нуля; аппетит за это время спадает на ступень. Возвращает, сколько сердечек ушло. */
    static int cool(ConversationStore.Relation rel, MobTalkConfig c, long now) {
        if (rel == null || rel.rapport == null || c.mercCoolMinutes <= 0) return 0;
        Rapport r = rel.rapport;
        if (r.coolAt < 0) { r.coolAt = now; return 0; }
        long step = c.mercCoolMinutes * 60_000L, steps = Math.max(0, now - r.coolAt) / step;
        if (steps <= 0) return 0;
        r.coolAt += steps * step;
        r.appetite = Math.max(1, appetite(r) - (int) Math.min(100, steps));
        int lost = (int) Math.min(rel.score <= 0 ? 0 : rel.score, steps * c.mercCoolHearts);
        rel.score -= lost;
        return lost;
    }
    /** Давно ли он дарил: дольше двух шагов остывания — «ты изменился». */
    static boolean neglected(Rapport r, MobTalkConfig c, long now) {
        return r != null && r.paidAt >= 0 && c.mercCoolMinutes > 0 && now - r.paidAt > 2L * c.mercCoolMinutes * 60_000L;
    }

    /** Жесты по кошельку: голодранцу не светят объятия, поцелуи и свидания; богатому флирт и комплименты заходят. */
    static void gestures(Map<Gesture, GestureIntent> rolled, Purse purse) {
        for (Map.Entry<Gesture, GestureIntent> e : rolled.entrySet()) {
            Gesture g = e.getKey(); GestureIntent i = e.getValue();
            if (purse == Purse.BROKE && (g == Gesture.HUG || g == Gesture.KISS || g == Gesture.FLIRT))
                e.setValue(new GestureIntent(g, Gesture.Outcome.FAIL, g == Gesture.FLIRT ? -1 : -3, "", "с голодранцем ты не заигрываешь — фыркаешь и отстраняешься"));
            else if (purse == Purse.RICH && (g == Gesture.FLIRT || g == Gesture.COMPLIMENT) && !i.outcome().warm())
                e.setValue(new GestureIntent(g, Gesture.Outcome.SUCCESS, 3, "", "от богатого и комплимент слаще — ты кокетничаешь в ответ"));
            else if (g == Gesture.DATE && purse == Purse.BROKE && i.outcome() == Gesture.Outcome.ACCEPTED)
                e.setValue(new GestureIntent(g, Gesture.Outcome.REFUSED, 0, "", "с голодранцами не встречаешься: сначала пусть разбогатеет"));
            else if (g == Gesture.PROPOSE && i.outcome() == Gesture.Outcome.ACCEPTED && price(i.item()) < 4)
                e.setValue(new GestureIntent(g, Gesture.Outcome.REFUSED, -2, "", "золотое колечко? несерьёзно — замуж только с бриллиантом (алмаз или помолвочное кольцо)"));
        }
    }

    /** Строки «ты и этот игрок» для промпта: кошелёк, желание, остывание и правило тона. */
    static List<String> facts(Rapport r, Purse purse, int wealth, boolean neglected, Courtship courtship) {
        List<String> facts = new ArrayList<>();
        facts.add("ты меркантильная: кошелёк этого игрока на твой взгляд — " + purse.word + (wealth >= 0 ? " (≈" + wealth + " изумр. при себе)" : "") + ".");
        facts.add(switch (purse) {
            case BROKE -> "С бедным ты холодна: отвечаешь коротко, с ленцой, намекаешь, что тебе нужен мужчина посостоятельнее.";
            case MODEST -> "С ним ты приветлива, но расчётлива: улыбаешься и сразу переводишь разговор на подарки.";
            case RICH -> "С богатым ты сама очарование: флиртуешь, хвалишь, смеёшься его шуткам и мило выпрашиваешь подарки.";
        });
        if (courtship != Courtship.MARRIED) facts.add("Сейчас ты хочешь от него " + wish(r).words() + ". Проси об этом редко, примерно раз в три-четыре реплики "
                + "и каждый раз другими словами; в остальных репликах просто отвечай на его слова, щебечи и флиртуй, без просьб.");
        if (neglected) facts.add("Он давно ничего тебе не дарил — ты заметно охладела («ты изменился», «раньше ты был щедрее»).");
        if (r != null && r.anniversaryAt >= 0) facts.add("Сегодня ваша «годовщина» (" + r.anniversaries + "-я): ты ждёшь подарка и напоминаешь об этом; забудет — смертельно обидишься.");
        if (courtship == Courtship.MARRIED) facts.add("Ты его жена, и теперь всё его — немного твоё: мелочи тебя не интересуют, ты просишь денег «на шубу», «на салон», "
                + "«на отпуск у моря» (выбирай одно, не всё сразу) и сравниваешь его с мужьями подруг; без подарков дуешься и вздыхаешь, с подарком — снова «котик». "
                + "Из подарков ты ждёшь хотя бы " + wish(r).words() + ".");
        else if (courtship == Courtship.ENGAGED) facts.add("Вы помолвлены: ты уже обсуждаешь, какое кольцо покажешь подругам и сколько стоит свадьба мечты.");
        facts.add("Тон: игривый расчётливый флирт, сатира над меркантильностью; никакой откровенности и пошлости.");
        return facts;
    }

    // ---- Ужин-«тарелочница». ----

    /** Ужин длится столько реального времени; после побега она помнит его в разговоре столько. */
    public static final long DINNER_MILLIS = 10 * 60_000L, DASH_FRESH_MILLIS = 20 * 60_000L;
    /** Сердечки: оплаченный ужин, оплаченный с побегом, неоплаченный (не хватило или не заплатил вовремя). */
    public static final int DINNER_HEARTS = 8, DASH_HEARTS = 2, STIFFED_HEARTS = -10, EXPIRED_HEARTS = -5;
    private static final java.util.regex.Pattern DINNER = java.util.regex.Pattern.compile(
            "поужина|ужина|на ужин|ресторан|кафе|в кино|свидани|угощ|сводить|посидим|пообедать|в бар\\b|кофе");
    private static final java.util.regex.Pattern PAY = java.util.regex.Pattern.compile(
            "плачу|заплачу|оплачу|оплати|сч[её]т|рассчита|расплачу|угощаю|за мой сч|держи деньги");
    private static String plain(String said) { return said == null ? "" : said.toLowerCase(Locale.ROOT).replace('ё', 'е'); }
    /** Зовёт ли её игрок в ресторан, кафе, на свидание. */
    public static boolean invites(String said) { return DINNER.matcher(plain(said)).find(); }
    /** Платит ли игрок по счёту. */
    public static boolean pays(String said) { return PAY.matcher(plain(said)).find(); }
    /** Счёт: чем больше аппетит, тем дороже меню. */
    static int bill(Rapport r) { return Math.min(64, 3 + 2 * appetite(r)); }
    /** Идёт ли ужин сейчас. */
    static boolean dining(Rapport r, long now) { return r != null && r.dinnerUntil > now; }
    /** Зовёт на ужин: отказ голодранцу (строка-причина) или "" — согласна; счёт записан. */
    static String startDinner(Rapport r, Purse purse, long now) {
        if (purse == Purse.BROKE) return "не идёт: с голодранцем в ресторан — позор";
        if (dining(r, now)) return "";
        r.bill = bill(r); r.dinnerUntil = now + DINNER_MILLIS;
        return "";
    }
    /** Итог ужина: оплачен (и сбежала ли), не оплачен; сердечки и метки. */
    static int endDinner(Rapport r, boolean paid, boolean dash, long now) {
        r.dinnerUntil = -1;
        if (!paid) return STIFFED_HEARTS;
        r.paidAt = now; r.coolAt = now; r.appetite = appetite(r) + 1;
        if (dash) { r.dashedAt = now; return DASH_HEARTS; }
        return DINNER_HEARTS;
    }
    /** Ужин просрочен: игрок так и не заплатил. Возвращает сердечки (0 — нечего списывать). */
    static int expireDinner(Rapport r, long now) {
        if (r == null || r.dinnerUntil < 0 || r.dinnerUntil > now) return 0;
        r.dinnerUntil = -1;
        return EXPIRED_HEARTS;
    }
    /** Строки об ужине для промпта. */
    static List<String> dinnerFacts(Rapport r, long now) {
        List<String> facts = new ArrayList<>();
        if (dining(r, now)) facts.add("Вы сейчас сидите в ресторане, ужин в разгаре: ты только что заказала самое дорогое (устрицы, торт, шампанское), счёт — " + r.bill
                + " изумр.; щебечи, хвали заведение, ешь и делай вид, что тебе интересен он, а не меню; о счёте намекни разве что раз, ждёшь, что он скажет «я плачу». "
                + "Если он зовёт пройтись — action FOLLOW.");
        if (r != null && r.dashedAt >= 0 && now - r.dashedAt < DASH_FRESH_MILLIS)
            facts.add("Ты недавно поела за его счёт и сразу упорхнула («подруга позвонила»); если он об этом — выкручивайся сладко и фальшиво.");
        return facts;
    }

    /** Поправка к счёту выбора у соперников: меркантильной важнее кошелёк и подарки, чем чувства. Шире, чем у других характеров. */
    public static int rivalBonus(int wealth, int giftsRemembered, MobTalkConfig c) {
        int purse = switch (purse(wealth, c)) { case BROKE -> -5; case MODEST -> 2; case RICH -> 8; };
        return purse + Math.min(5, Math.max(0, giftsRemembered));
    }

    // ---- Сплетни подружек. ----

    /** Что подружки говорят об игроке: жмот или щедрый. */
    public enum Talk { STINGY, GENEROUS }
    /** Сплетня в деревне: о каком игроке, кто из меркантильных рассказала и за что; реальные мс. */
    public record Gossip(String talk, String player, String playerName, String by, String byName, String why, long time) {
        Talk kind() { try { return Talk.valueOf(talk); } catch (RuntimeException bad) { return Talk.STINGY; } }
    }
    private static String gossipKey(String village, UUID player) { return village + "|" + player; }
    /** Меркантильная рассказала подружкам, что игрок жмот или щедрый ({@code why} — за что); деревня это тоже слышит. Под замком. */
    static void gossip(ConversationStore.State state, ConversationStore.Profile her, UUID player, String playerName, Talk talk, String why, long now) {
        MobTalkConfig c = Tuning.get();
        if (!c.mercGossip || state == null || her == null || player == null || her.villageId == null || her.villageId.isBlank()) return;
        String name = TextBounds.characters(playerName == null ? "" : playerName, 60), who = SocialLedger.name(her);
        state.mercGossip.values().removeIf(g -> now - g.time() > c.mercGossipMinutes * 60_000L);
        state.mercGossip.put(gossipKey(her.villageId, player), new Gossip(talk.name(), player.toString(), name, her.id.toString(), who,
                TextBounds.characters(why, 120), now));
        RivalryLedger.rumor(state, her, talk == Talk.STINGY ? who + " всем рассказывает, что " + name + " — жмот: " + why
                : who + " хвастается подругам, какой " + name + " щедрый: " + why, talk == Talk.STINGY ? -1 : 1, 2, name, now);
    }
    /** Что она слышала от подружек об этом игроке: свежая сплетня не от неё самой; null — ничего. */
    static Gossip heard(ConversationStore.State state, ConversationStore.Profile her, UUID player, MobTalkConfig c, long now) {
        if (!c.mercGossip || state == null || her == null || player == null || her.villageId == null) return null;
        Gossip g = state.mercGossip.get(gossipKey(her.villageId, player));
        return g == null || g.by().equals(her.id.toString()) || now - g.time() > c.mercGossipMinutes * 60_000L ? null : g;
    }
    /** Кошелёк глазами подружек: про жмота думают на ступень хуже, про щедрого — на ступень лучше. */
    static Purse rumoured(Purse purse, Gossip g) {
        if (g == null) return purse;
        return g.kind() == Talk.STINGY ? purse == Purse.RICH ? Purse.MODEST : Purse.BROKE : purse == Purse.BROKE ? Purse.MODEST : Purse.RICH;
    }
    static String gossipFact(Gossip g) {
        return g.kind() == Talk.STINGY
                ? "Подружка " + g.byName() + " рассказала тебе, что " + g.playerName() + " — жмот (" + g.why() + "); ты настороже и колешь его этим."
                : "Подружка " + g.byName() + " хвасталась, что " + g.playerName() + " щедрый (" + g.why() + "); ты с ним особенно мила — вдруг и тебе перепадёт.";
    }

    // ---- Сама клянчит; «годовщины». ----

    /** С какого отношения она клянчит у того, с кем не встречается. */
    static final int BEG_SCORE = 30;
    /** Сколько минут после напоминания о «годовщине» у него есть на подарок; сколько сердечек стоит забыть. */
    public static final long ANNIVERSARY_WAIT_MILLIS = 10 * 60_000L;
    public static final int ANNIVERSARY_HEARTS = -6;
    /** Повод самой заговорить с ухажёром: «годовщина» (ждёт подарка) или давно не дарил; null — молчит. Отмечает, что клянчила. */
    static String beg(ConversationStore.Relation rel, MobTalkConfig c, long now) {
        if (!c.mercBeg || rel == null || rel.rapport == null) return null;
        Rapport r = rel.rapport;
        boolean partner = r.courtship().partners();
        if (!partner && rel.score < BEG_SCORE) return null;
        if (r.begAt >= 0 && now - r.begAt < c.mercBegMinutes * 60_000L) return null;
        if (partner && r.datingAt < 0) r.datingAt = now; // Пары до этой версии: годовщины — с сегодняшнего дня.
        if (partner && r.anniversaryAt < 0 && c.mercAnniversaryMinutes > 0) {
            int due = (int) Math.min(1000, (now - r.datingAt) / (c.mercAnniversaryMinutes * 60_000L));
            if (due > r.anniversaries) {
                r.anniversaries = due; r.anniversaryAt = now; r.begAt = now;
                return "у вас «годовщина» (" + due * c.mercAnniversaryMinutes + " минут вместе): ты сама напоминаешь о ней, капризно и сладко, и ждёшь подарка — "
                        + wish(r).words() + "; намекни, что подружкам на годовщину дарят больше";
            }
        }
        if (r.paidAt >= 0 && now - r.paidAt <= c.mercCoolMinutes * 60_000L) return null;
        r.begAt = now;
        return "ты меркантильная и давно ничего от него не получала: сама подойди, покапризничай, назови его «котиком» и выпроси " + wish(r).words();
    }
    /** Он не подарил ничего к «годовщине» за отведённое время: обида. Возвращает, случилось ли. */
    static boolean forgotAnniversary(ConversationStore.Relation rel, long now) {
        Rapport r = rel == null ? null : rel.rapport;
        if (r == null || r.anniversaryAt < 0 || now - r.anniversaryAt < ANNIVERSARY_WAIT_MILLIS) return false;
        r.anniversaryAt = -1;
        rel.score = ConversationStore.clamp(rel.score + ANNIVERSARY_HEARTS, false);
        return true;
    }

    // ---- Уходит к богатому жителю. ----

    /** Бросит ли она этого ухажёра: встречаетесь или помолвлены, а подарков нет дольше {@code mercLeaveMinutes}. */
    static boolean tired(Rapport r, MobTalkConfig c, long now) {
        if (r == null || !c.mercLeave || c.mercLeaveMinutes <= 0) return false;
        Courtship courtship = r.courtship();
        if (courtship != Courtship.DATING && courtship != Courtship.ENGAGED) return false;
        long since = r.paidAt >= 0 ? r.paidAt : r.datingAt;
        return since >= 0 && now - since > c.mercLeaveMinutes * 60_000L;
    }
    /** Сердечки, которые теряет брошенный ради богатого. */
    public static final int LEFT_HEARTS = -15;
    /** Она ушла от тех, кто не дарит, к богатому жителю {@code rich} и вышла за него. Под замком хранилища; возвращает уведомления
     * брошенным игрокам (UUID → текст), пусто — не ушла. */
    static Map<UUID, String> leave(ConversationStore.State state, ConversationStore.Profile her, ConversationStore.Profile rich, String richWork,
                                   MobTalkConfig c, long now) {
        if (!active(her) || rich == null || her.id.equals(rich.id) || state.family.married(her.id) || state.family.married(rich.id)
                || state.family.related(her.id, rich.id)) return Map.of();
        List<UUID> tiredOf = new ArrayList<>();
        boolean keeps = false;
        for (Map.Entry<String, ConversationStore.Relation> e : her.relations.entrySet()) {
            Rapport r = e.getValue().rapport;
            if (r == null || !r.courtship().partners()) continue;
            if (tired(r, c, now)) { try { tiredOf.add(UUID.fromString(e.getKey())); } catch (IllegalArgumentException ignored) { } }
            else keeps = true; // Есть щедрый — от него не уходит.
        }
        if (tiredOf.isEmpty() || keeps) return Map.of();
        String she = SocialLedger.name(her), he = SocialLedger.name(rich), work = richWork == null || richWork.isBlank() ? "богатый житель" : richWork;
        Map<UUID, String> notices = new LinkedHashMap<>();
        for (UUID player : tiredOf) {
            ConversationStore.Relation rel = her.relations.get(player.toString());
            String name = RivalryLedger.playerName(state, her, player);
            boolean ring = !rel.rapport.ring.isBlank();
            rel.rapport.courtship(Courtship.NONE);
            rel.rapport.ring = ""; rel.rapport.witnessMan = ""; rel.rapport.witnessWoman = "";
            rel.rapport.anniversaryAt = -1;
            rel.score = ConversationStore.clamp(rel.score + LEFT_HEARTS, false);
            ConversationStore.event(her, now, "Ты бросила игрока " + name + " — он давно ничего не дарил — и ушла к богатому: это " + he + " (" + work + ")"
                    + (ring ? "; кольцо оставила себе" : "") + "; отношение " + rel.score, 5);
            gossip(state, her, player, name, Talk.STINGY, "жалел денег, и " + she + " ушла к богатому", now);
            notices.put(player, she + " ушла от вас к богатому — это " + he + " (" + work + "): «он хотя бы дарит подарки»" + (ring ? ". Кольцо она оставила себе." : "."));
        }
        state.family.marry(her.id, she, her.social.gender(), false, rich.id, he, rich.social == null ? Gender.MALE : rich.social.gender(), false);
        ConversationStore.event(her, now, "Ты вышла замуж; твой муж — " + he + " (" + work + "), наконец-то при деньгах", 5);
        ConversationStore.event(rich, now, "На тебе женилась " + she + " — красавица, но любит дорогие подарки", 5);
        state.relations.together(her.id, she, her.type, rich.id, he, rich.type, MobRelations.Kinship.NEIGHBOR, 30, 0, now);
        RivalryLedger.rumor(state, her, she + " бросила ухажёра и вышла замуж; муж — " + he + ", «он хотя бы при деньгах»", 0, 3, "", now);
        her.revision++; rich.revision++;
        return notices;
    }
}
