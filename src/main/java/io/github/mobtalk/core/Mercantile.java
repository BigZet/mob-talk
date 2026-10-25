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
            new Wish("minecraft:emerald", "изумруд «на мелкие расходы»"), new Wish("minecraft:cake", "торт, как в кафе"),
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
    static boolean observe(ConversationStore.Profile p, ConversationStore.Relation rel, MobSnapshot snapshot, MobTalkConfig c, long now) {
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
    static GestureIntent gift(GestureIntent base, Rapport r, String item) {
        if (base == null || base.outcome() == Gesture.Outcome.REFUSED && base.hearts() < 0) return base; // Аллергия сильнее жадности.
        int price = price(item), appetite = appetite(r);
        if (price <= 0) return new GestureIntent(Gesture.GIFT, Gesture.Outcome.REFUSED, -1, item,
                "дешёвка, за кого он тебя держит — ты такое не берёшь, ждёшь хотя бы " + wish(r).words());
        boolean wished = item.equals(wish(r).item());
        if (price < appetite) return new GestureIntent(Gesture.GIFT, Gesture.Outcome.GOOD, 1, item,
                "маловато: возьмёшь, но с кислой миной — ты рассчитывала на " + wish(r).words());
        int hearts = Math.max(base.hearts(), 2 + 2 * price / Math.max(1, appetite)) + (wished ? 5 : 0);
        return new GestureIntent(Gesture.GIFT, wished ? Gesture.Outcome.BEST : price >= appetite * 3 ? Gesture.Outcome.BEST : Gesture.Outcome.BETTER,
                Math.min(25, hearts), item, wished ? "ровно то, что ты хотела! ты в восторге" : "дорого — тебе приятно, ты сразу теплеешь");
    }
    /** Подарок принят: аппетит растёт (не выше потолка), часы охлаждения начинаются заново. */
    static void paid(Rapport r, String item, MobTalkConfig c, long now) {
        int price = price(item);
        if (r == null || price <= 0) return;
        if (price >= appetite(r)) r.appetite = Math.min(Math.max(1, c.mercAppetiteCap), appetite(r) + Math.max(1, price / 2));
        r.paidAt = now; r.coolAt = now;
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
        facts.add("Сейчас ты хочешь от него " + wish(r).words() + " — намекни или попроси прямо, капризно и с юмором, но не в каждой реплике.");
        if (neglected) facts.add("Он давно ничего тебе не дарил — ты заметно охладела («ты изменился», «раньше ты был щедрее»).");
        if (courtship == Courtship.MARRIED) facts.add("Ты его жена, и теперь всё его — немного твоё: просишь «на шубу», «на салон», «на отпуск у моря», "
                + "сравниваешь его с мужьями подруг; без подарков дуешься и вздыхаешь, с подарком — снова «котик».");
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
        if (dining(r, now)) facts.add("У вас свидание в ресторане: ты заказала самое дорогое (устрицы, торт, шампанское), счёт — " + r.bill
                + " изумр.; щебечи, хвали заведение, ешь и делай вид, что тебе интересен он, а не меню; ждёшь, что он заплатит — пусть скажет «я плачу». "
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
}
