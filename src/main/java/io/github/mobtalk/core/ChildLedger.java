package io.github.mobtalk.core;

import java.util.*;

/** Ребёнок в семье (трек N) в памяти мира: пара старается, особая ночь, зачатие, срок, роды, и сводка для экрана семьи. Беременность
 * лежит в профиле супруга-жителя ({@link SocialProfile}), даже когда носит игрок: так она переживает перезапуск и не требует
 * профиля игрока. Особый вечер — только в памяти игры: перезапуск его просто заканчивает. Каждый метод под замком
 * {@link ConversationStore}; двигать людей, затемнять экран и рожать малыша — дело игры (ChildController). */
public final class ChildLedger {
    private final ConversationStore store;
    /** Житель → его особый вечер с игроком; не сохраняется. */
    private final Map<UUID, Evening> evenings = new HashMap<>();
    ChildLedger(ConversationStore store) { this.store = store; }

    /** Особый вечер: с кем, что в доме, до какого времени он держится, легли ли уже, проспали ли ночь вместе. */
    public record Evening(UUID player, Pregnancy.Cozy cozy, long untilMillis, boolean dark, boolean slept) { }

    // ---- Пара старается и зачатие. ----

    /** Пара решила завести ребёнка и ещё ждёт его: согласие дано, зачатия не было. */
    public boolean trying(UUID villager, UUID player) {
        synchronized (store) { SocialProfile s = social(villager); return s != null && trying(s, player); }
    }
    static boolean trying(SocialProfile s, UUID player) {
        return s.expectingTick == 0 && s.conceivedTick < 0 && player != null && player.toString().equals(s.expectingWith);
    }
    /** Кто понесёт ребёнка от этого жителя и игрока (по полу жителя и настройке пола игрока). */
    public Pregnancy.Carrier carrier(UUID villager) {
        synchronized (store) {
            SocialProfile s = social(villager);
            if (s == null) return Pregnancy.Carrier.NONE;
            Pregnancy.Carrier set = carrierOf(s);
            return set != null ? set : Pregnancy.carrier(s.gender(), PlayerIdentity.gender(store.config(), expecting(s)));
        }
    }
    /** The player this villager expects a child with, or null. */
    private static UUID expecting(SocialProfile s) {
        try { return s.expectingWith == null || s.expectingWith.isBlank() ? null : UUID.fromString(s.expectingWith); } catch (IllegalArgumentException bad) { return null; }
    }
    private static Pregnancy.Carrier carrierOf(SocialProfile s) {
        try { return s.carrier.isBlank() ? null : Pregnancy.Carrier.valueOf(s.carrier); } catch (IllegalArgumentException bad) { return null; }
    }
    /** Ведёт ли этого малыша беременность (иначе — прежний путь: малыш через игровой день). */
    public boolean handles(UUID villager) {
        synchronized (store) {
            SocialProfile s = social(villager);
            if (s == null) return false;
            if (s.conceivedTick >= 0) return true;
            return store.config().pregnancy && s.expectingTick == 0 && carrier(villager) != Pregnancy.Carrier.NONE;
        }
    }

    /** Ночь вместе (сон из трека J): у старающейся пары {@code roll} (0..1) решает, получилось ли. Особая ночь — больший шанс по
     * уюту. Возвращает носящего, если получилось; null — нет (или пара не старается, или однополая). */
    public Pregnancy.Carrier night(UUID villager, UUID player, long worldTick, double roll) {
        synchronized (store) {
            MobTalkConfig config = store.config();
            SocialProfile s = social(villager);
            if (!config.pregnancy || s == null || !trying(s, player)) return null;
            Pregnancy.Carrier carrier = Pregnancy.carrier(s.gender(), PlayerIdentity.gender(config, player));
            if (carrier == Pregnancy.Carrier.NONE) return null;
            Evening evening = evenings.get(villager);
            boolean special = evening != null && evening.player().equals(player);
            double chance = Pregnancy.chance(special, special ? evening.cozy().score() : 0, config.conceptionChance, config.specialNightChanceMin, config.specialNightChanceMax);
            if (roll >= chance) return null;
            conceive(s, player, carrier, worldTick, config.pregnancyDays);
            return carrier;
        }
    }

    /** Шанс малыша этой ночью ({@link #night}); -1 — пара не старается или носить некому. Для лога. */
    public double chance(UUID villager, UUID player) {
        synchronized (store) {
            MobTalkConfig config = store.config();
            SocialProfile s = social(villager);
            if (!config.pregnancy || s == null || !trying(s, player)
                    || Pregnancy.carrier(s.gender(), PlayerIdentity.gender(config, player)) == Pregnancy.Carrier.NONE) return -1;
            Evening evening = evenings.get(villager);
            boolean special = evening != null && evening.player().equals(player);
            return Pregnancy.chance(special, special ? evening.cozy().score() : 0, config.conceptionChance, config.specialNightChanceMin, config.specialNightChanceMax);
        }
    }

    /** Команда для проверки: беременность у супругов сразу, без броска; {@code days} &gt; 0 — свой срок в днях. Null — не в браке,
     * носить некому или уже ждут малыша. */
    public Pregnancy.Carrier force(UUID villager, UUID player, long worldTick, int days) {
        synchronized (store) {
            MobTalkConfig config = store.config();
            SocialProfile s = social(villager);
            if (s == null || player == null || !player.equals(store.state.family.spouse(villager)) || s.conceivedTick >= 0) return null;
            Pregnancy.Carrier carrier = Pregnancy.carrier(s.gender(), PlayerIdentity.gender(config, player));
            if (carrier == Pregnancy.Carrier.NONE) return null;
            conceive(s, player, carrier, worldTick, days > 0 ? days : config.pregnancyDays);
            return carrier;
        }
    }

    private void conceive(SocialProfile s, UUID player, Pregnancy.Carrier carrier, long worldTick, int days) {
        s.expectingWith = player.toString();
        s.conceivedTick = Math.max(0, worldTick);
        s.pregnancyDays = Math.max(1, days);
        s.expectingTick = Math.max(1, s.conceivedTick + s.pregnancyDays * ChildGrowth.DAY_TICKS);
        s.carrier = carrier.name(); s.pregnancyNews = 0; s.pregnancyTerm = Pregnancy.Term.EARLY.name(); s.pregnancyDay = 1; s.cravingDay = -1;
        store.scheduleSave();
    }

    // ---- Срок. ----

    /** Беременность в паре игрока: житель-супруг, игрок, кто носит, тик зачатия и родов, длина срока, что уже сказано. */
    public record Pregnant(UUID villager, UUID player, String name, Pregnancy.Carrier carrier, long conceived, long due, int days, int news, int cravingDay) {
        public Pregnancy.Term term(long now) { return Pregnancy.term(conceived, now, days); }
        public int day(long now) { return Pregnancy.day(conceived, now, days); }
        public boolean known(long now) { return Pregnancy.known(conceived, now, days); }
    }
    public List<Pregnant> pregnancies() {
        synchronized (store) {
            List<Pregnant> list = new ArrayList<>();
            for (ConversationStore.Profile p : store.state.profiles.values()) {
                SocialProfile s = p.social;
                if (s == null || s.conceivedTick < 0 || s.expectingWith.isBlank() || store.state.family.dead(p.id)) continue;
                Pregnancy.Carrier carrier = carrierOf(s);
                if (carrier == null || carrier == Pregnancy.Carrier.NONE) continue;
                try {
                    list.add(new Pregnant(p.id, UUID.fromString(s.expectingWith), SocialLedger.name(p), carrier, s.conceivedTick, s.expectingTick,
                            Math.max(1, s.pregnancyDays), s.pregnancyNews, s.cravingDay));
                } catch (IllegalArgumentException ignored) { }
            }
            return list;
        }
    }
    /** Игра поставила период и день (для промпта); без повышения ревизии — срок не отменяет реплику. */
    public void progress(UUID villager, Pregnancy.Term term, int day) {
        synchronized (store) {
            SocialProfile s = social(villager);
            if (s == null || s.conceivedTick < 0 || term == null) return;
            if (term.name().equals(s.pregnancyTerm) && s.pregnancyDay == day) return;
            s.pregnancyTerm = term.name(); s.pregnancyDay = day; store.scheduleSave();
        }
    }
    /** Игра сказала новость ступени {@code stage} (1 — узнали, 2 — середина, 3 — поздний, 4 — роды); {@code memory} — запись в память
     * жителя ("" — без записи). false — эта ступень уже сказана. */
    public boolean news(UUID villager, int stage, String memory, long now) {
        synchronized (store) {
            ConversationStore.Profile p = profile(villager);
            if (p == null || p.social == null || p.social.conceivedTick < 0 || p.social.pregnancyNews >= stage) return false;
            p.social.pregnancyNews = stage;
            if (memory != null && !memory.isBlank()) ConversationStore.event(p, now, memory, 4);
            store.scheduleSave();
            return true;
        }
    }
    /** Желание дня {@code day}: true, если сегодня ещё не просила. */
    public boolean craving(UUID villager, int day) {
        synchronized (store) {
            SocialProfile s = social(villager);
            if (s == null || s.conceivedTick < 0 || s.cravingDay >= day) return false;
            s.cravingDay = day; store.scheduleSave(); return true;
        }
    }

    // ---- Особый вечер. ----

    public void startEvening(UUID villager, UUID player, Pregnancy.Cozy cozy, long now) {
        if (villager == null || player == null) return;
        synchronized (store) { evenings.put(villager, new Evening(player, cozy == null ? Pregnancy.Cozy.NONE : cozy, now + SpecialEvening.EVENING_MILLIS, false, false)); }
    }
    public void cozy(UUID villager, Pregnancy.Cozy cozy) {
        synchronized (store) { Evening e = evenings.get(villager); if (e != null && cozy != null) evenings.put(villager, new Evening(e.player(), cozy, e.untilMillis(), e.dark(), e.slept())); }
    }
    /** Легли вместе: свет погас, ночь засчитана. */
    public void dark(UUID villager, long now) {
        synchronized (store) { Evening e = evenings.get(villager); if (e != null) evenings.put(villager, new Evening(e.player(), e.cozy(), now + SpecialEvening.EVENING_MILLIS, true, true)); }
    }
    public Evening evening(UUID villager) { synchronized (store) { return villager == null ? null : evenings.get(villager); } }
    public Map<UUID, Evening> evenings() { synchronized (store) { return Map.copyOf(evenings); } }
    public void endEvening(UUID villager) { synchronized (store) { evenings.remove(villager); } }
    /** Вечера, которые истекли: не дошли до ночи или ночь так и не кончилась утром. */
    public List<UUID> expired(long now) {
        synchronized (store) { return evenings.entrySet().stream().filter(e -> now > e.getValue().untilMillis()).map(Map.Entry::getKey).toList(); }
    }
    /** Утро после особой ночи: сердечки, тёплая память, вечер закончен. false — ночь была не особой. */
    public boolean morning(UUID villager, UUID player, String playerName, long now) {
        synchronized (store) {
            Evening e = evenings.get(villager);
            if (e == null || !e.slept() || !e.player().equals(player)) return false;
            evenings.remove(villager);
            ConversationStore.Profile q = profile(villager);
            ConversationStore.Relation r = q == null ? null : q.relations.get(player.toString());
            if (r == null) return true;
            r.score = ConversationStore.clamp(r.score + store.config().specialNightHearts, q.hostile());
            ConversationStore.feel(q, r, MobState.Event.NIGHT_TOGETHER, now);
            ConversationStore.event(q, now, "Особая ночь с игроком " + TextBounds.characters(playerName == null || playerName.isBlank() ? "неизвестный" : playerName, 60)
                    + ": свечи, разговоры до темноты, вы уснули вместе; отношение " + r.score, 5);
            store.scheduleSave();
            return true;
        }
    }

    /** Факты для промпта супруга: особый вечер, беременность, «мы стараемся». */
    static List<String> facts(ConversationStore.State state, ConversationStore.Profile p, ConversationStore.Relation r, UUID player, MobTalkConfig config,
                              ChildLedger ledger) {
        if (p == null || p.social == null || player == null || r == null || r.rapport == null || r.rapport.courtship() != Courtship.MARRIED) return List.of();
        List<String> facts = new ArrayList<>();
        Evening evening = ledger == null ? null : ledger.evenings.get(p.id);
        SocialProfile s = p.social;
        boolean trying = trying(s, player);
        if (evening != null && evening.player().equals(player)) facts.add(SpecialEvening.fact(evening.cozy().words(), evening.dark(), trying));
        if (s.conceivedTick >= 0 && player.toString().equals(s.expectingWith)) {
            Pregnancy.Carrier carrier = carrierOf(s);
            Pregnancy.Term term = Pregnancy.Term.of(s.pregnancyTerm);
            String fact = Pregnancy.fact(carrier == null ? Pregnancy.Carrier.NONE : carrier, term == null ? Pregnancy.Term.EARLY : term, Math.max(1, s.pregnancyDay),
                    Math.max(1, s.pregnancyDays), s.cravingDay == s.pregnancyDay ? Pregnancy.craving(s.pregnancyDay + s.conceivedTick / ChildGrowth.DAY_TICKS) : "", s.pregnancyNews >= 1);
            if (!fact.isBlank()) facts.add(fact);
        } else if (trying && config.pregnancy && evening == null) facts.add(Pregnancy.TRYING);
        return facts;
    }
    /** Желание дня {@code day} этой беременности (одно и то же весь день). */
    public static String cravingOf(Pregnant pregnant, int day) { return Pregnancy.craving(day + pregnant.conceived() / ChildGrowth.DAY_TICKS); }

    // ---- Экран семьи. ----

    /** Супруг для экрана семьи. {@code pregnancy} — строка срока ("" — не ждёте), {@code craving} — чего хочется сегодня. */
    public record Spouse(UUID id, String name, Gender gender, String persona, String courtship, int hearts, int jealousy, String pregnancy, String craving,
                         Pregnancy.Carrier carrier, boolean trying, int day, int days, List<String> recent) { }
    /** Ребёнок игрока: пол словом («сын», «дочь»), стадия, возраст и сколько до следующей стадии (тики), черты, последние события. */
    public record Child(UUID id, String name, String word, String stage, long ageTicks, long nextTicks, List<String> traits, List<String> recent, boolean dead) { }
    public record Overview(List<Spouse> spouses, List<Child> children, List<String> tree) {
        public boolean empty() { return spouses.isEmpty() && children.isEmpty() && tree.isEmpty(); }
    }
    /** {@code dayTime}: время суток верхнего мира (по нему идёт срок); {@code worldTick}: игровое время (по нему растут дети). */
    public Overview overview(UUID player, long dayTime, long worldTick, long now) {
        if (player == null) return new Overview(List.of(), List.of(), List.of());
        synchronized (store) {
            ConversationStore.State state = store.state;
            MobTalkConfig config = store.config();
            List<Spouse> spouses = new ArrayList<>();
            for (JealousyLedger.Partner partner : JealousyLedger.partners(state, player)) {
                ConversationStore.Profile q = state.profiles.get(partner.id().toString());
                ConversationStore.Relation r = q == null ? null : q.relations.get(player.toString());
                if (q == null || r == null) continue;
                SocialProfile s = q.social;
                String pregnancy = "", craving = "";
                int day = 0, days = 0;
                Pregnancy.Carrier carrier = carrierOf(s);
                if (s.conceivedTick >= 0 && player.toString().equals(s.expectingWith)) {
                    days = Math.max(1, s.pregnancyDays);
                    Pregnancy.Term term = Pregnancy.term(s.conceivedTick, dayTime, days);
                    day = Pregnancy.day(s.conceivedTick, dayTime, days);
                    pregnancy = !Pregnancy.known(s.conceivedTick, dayTime, days) ? "" : term == Pregnancy.Term.DUE ? "роды"
                            : term.word + ", " + day + " из " + days + " дн." + (day >= days ? " — скоро роды" : "");
                    if (pregnancy.isBlank()) days = 0;
                    else if (carrier == Pregnancy.Carrier.VILLAGER) craving = Pregnancy.craving(day + s.conceivedTick / ChildGrowth.DAY_TICKS);
                }
                spouses.add(new Spouse(q.id, SocialLedger.name(q), s.gender(), s.persona().word, r.rapport.courtship().word, r.score,
                        r.rapport.jealousy == null ? 0 : r.rapport.jealousy.level(now), pregnancy, craving, carrier == null ? Pregnancy.Carrier.NONE : carrier,
                        trying(s, player), day, days, ConversationStore.lastEvents(q, 3)));
            }
            List<Child> children = new ArrayList<>();
            long quarter = Math.max(1, Math.max(1, config.childGrowthDays) * ChildGrowth.DAY_TICKS / 4);
            for (UUID id : state.family.children(player)) {
                ConversationStore.Profile c = state.profiles.get(id.toString());
                FamilyTree.Node node = state.family.nodes.get(id.toString());
                boolean dead = state.family.dead(id);
                SocialProfile s = c == null ? null : c.social;
                Gender g = s != null ? s.gender() : node == null ? null : Gender.of(node.gender);
                long age = s == null || s.bornTick < 0 ? -1 : Math.max(0, worldTick - s.bornTick);
                ChildGrowth.Stage stage = s == null ? null : ChildGrowth.Stage.of(s.stage);
                if (stage == null && age >= 0) stage = ChildGrowth.stage(age, config.childGrowthDays);
                long next = age < 0 || stage == ChildGrowth.Stage.ADULT ? -1 : (age / quarter + 1) * quarter - age;
                String name = c != null ? SocialLedger.name(c) : node == null || node.name == null ? "" : node.name;
                children.add(new Child(id, name, g == null ? "ребёнок" : g.offspring, stage == null ? "" : stage.word, age, next,
                        s == null ? List.of() : s.traits().stream().map(t -> t.name().toLowerCase(Locale.ROOT)).toList(),
                        c == null ? List.of() : ConversationStore.lastEvents(c, 3), dead));
            }
            List<String> tree = new ArrayList<>();
            for (FamilyTree.Kin kin : state.family.kin(player))
                tree.add(kin.word() + " " + (kin.name().isBlank() ? "?" : kin.name()) + (kin.dead() ? " †" : ""));
            return new Overview(spouses, children, tree);
        }
    }

    /** Только для игрового самотеста трека N: брак без свадебной службы (её проверяет свой этап трека L). */
    public boolean selfTestMarry(UUID villager, UUID player, String playerName) {
        synchronized (store) {
            ConversationStore.Profile p = profile(villager);
            if (p == null || p.social == null || player == null) return false;
            ConversationStore.Relation r = store.relation(p, player);
            r.score = 95;
            if (r.rapport == null) r.rapport = new Rapport();
            r.rapport.courtship(Courtship.MARRIED);
            store.state.family.marry(player, playerName, PlayerIdentity.gender(store.config(), player), true, p.id, SocialLedger.name(p), p.social.gender(), false);
            store.scheduleSave();
            return true;
        }
    }

    private SocialProfile social(UUID villager) { ConversationStore.Profile p = profile(villager); return p == null ? null : p.social; }
    private ConversationStore.Profile profile(UUID villager) { return villager == null ? null : store.state.profiles.get(villager.toString()); }
}
