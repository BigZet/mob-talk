package io.github.mobtalk.core;

import java.util.*;

/** Соперники за одно сердце в памяти мира (идея П3, docs/rivals.md): кто ухаживает за жителем, можно ли отбить у другого игрока
 * пару до помолвки, кого житель выберет, когда кольцо приносит один, а рядом сильный соперник, и что об этом скажет деревня.
 * До помолвки сердце открыто; после неё — закрыто, пока пара не расстанется. Каждый метод работает под замком
 * {@link ConversationStore}; что показать игрокам, игра забирает через {@link #drain()}. */
public final class RivalryLedger {
    private final ConversationStore store;
    /** Что сказать игрокам строкой над хотбаром: «У тебя соперник: Петя», «Аксинья ушла к Диме». */
    private final List<Notice> notices = new ArrayList<>();
    RivalryLedger(ConversationStore store) { this.store = store; }

    /** Строка для одного игрока. {@code villager} — о каком жителе речь; {@code item} — предмет, чьё имя игра подставит вместо
     * {@link #ITEM} на языке игрока ("" — нет). */
    public record Notice(UUID player, UUID villager, String text, String item) {
        public Notice(UUID player, UUID villager, String text) { this(player, villager, text, ""); }
    }
    /** Место имени предмета в строке {@link Notice}. */
    public static final String ITEM = "{item}";
    /** Начало отказа на кольцо, когда житель отвечает у колокола (по нему {@link #after} назначает выбор). */
    public static final String BELL_NOTE ="ты не отвечаешь на кольцо сразу";
    /** Один ухажёр в силе: сердечки, счёт выбора, где пара стоит сейчас. */
    public record Suitor(UUID player, String name, int hearts, int score, Courtship courtship) { }
    /** Ответ на кольцо при соперниках: отказ (null — решать не соперникам) и сколько сердечек он стоит. */
    public record Verdict(String refusal, int hearts) { }

    static boolean on(MobTalkConfig c) { return c != null && c.socialLayer && c.rivals; }
    static long window(MobTalkConfig c) { return Math.max(1, c.rivalWindowHours) * 3_600_000L; }

    /** Забирает накопленные строки для игроков. */
    public List<Notice> drain() {
        synchronized (store) {
            if (notices.isEmpty()) return List.of();
            List<Notice> out = List.copyOf(notices);
            notices.clear();
            return out;
        }
    }

    // ---- Кто ухаживает ----

    /** Ухажёры жителя сейчас, по счёту выбора: игроки с романтическим жестом в окне интереса, сердечками от дружбы, свободные (или
     * пара этого жителя) и того пола, к которому житель тянется; пара жителя считается всегда. Не больше {@link MobTalkConfig#rivalMaxSuitors}. */
    static List<Suitor> suitors(ConversationStore.State state, ConversationStore.Profile p, MobTalkConfig c, long now) {
        if (!on(c) || p == null || p.social == null || state.family.dead(p.id) || !SocialLedger.adult(p, null)) return List.of();
        Map<String, Suitor> found = new LinkedHashMap<>();
        for (Map.Entry<String, ConversationStore.Relation> e : p.relations.entrySet()) {
            ConversationStore.Relation r = e.getValue();
            UUID player = uuid(e.getKey());
            if (player == null || r == null || state.profiles.containsKey(e.getKey())) continue;
            Courtship courtship = r.rapport == null ? Courtship.NONE : r.rapport.courtship();
            boolean partner = courtship.partners();
            boolean courting = p.rivalry != null && p.rivalry.courting(player, now, window(c));
            if (!partner && !courting) continue;
            if (!partner) {
                if (r.score < Hearts.friend()) continue;
                UUID elsewhere = SocialLedger.partner(state, player);
                if (elsewhere != null && !elsewhere.equals(p.id)) continue;
                if (!Romance.drawn(p.social.gender(), p.social.traits(), PlayerIdentity.gender(c, player))) continue;
            }
            found.put(e.getKey(), new Suitor(player, playerName(state, p, player), r.score, score(p, r, player, now), courtship));
        }
        List<Suitor> order = new ArrayList<>(found.values());
        order.sort(Comparator.comparing((Suitor s) -> !s.courtship().partners()).thenComparing(Comparator.comparingInt(Suitor::score).reversed()));
        return order.size() > c.rivalMaxSuitors ? List.copyOf(order.subList(0, Math.max(1, c.rivalMaxSuitors))) : order;
    }
    /** Счёт выбора одного игрока у этого жителя. */
    static int score(ConversationStore.Profile p, ConversationStore.Relation r, UUID player, long now) {
        int hearts = r == null ? 0 : r.score;
        Rivalry.Suitor s = p.rivalry == null ? null : p.rivalry.suitor(player);
        int jealousy = r == null || r.rapport == null || r.rapport.jealousy == null ? 0 : r.rapport.jealousy.level(now);
        int gifts = r == null || r.rapport == null ? 0 : r.rapport.gifts(now).size();
        Persona persona = p.social == null ? null : p.social.persona();
        return Rivalry.score(hearts, s == null ? 0 : s.trialWins, jealousy, Rivalry.personaBonus(persona, gifts, s == null ? 0 : s.romances));
    }
    /** Имя игрока: как его знает мир, иначе как записал ухажёр. */
    static String playerName(ConversationStore.State state, ConversationStore.Profile p, UUID player) {
        String known = state.players.get(player.toString());
        if (known != null && !known.isBlank()) return known;
        Rivalry.Suitor s = p.rivalry == null ? null : p.rivalry.suitor(player);
        return s != null && !s.name.isBlank() ? s.name : "другой игрок";
    }
    /** Другой игрок, с которым житель встречается (DATING), или null. */
    static UUID dating(ConversationStore.Profile p, UUID except) {
        for (Map.Entry<String, ConversationStore.Relation> e : p.relations.entrySet()) {
            if (except != null && e.getKey().equals(except.toString())) continue;
            ConversationStore.Relation r = e.getValue();
            if (r != null && r.rapport != null && r.rapport.courtship() == Courtship.DATING) { UUID id = uuid(e.getKey()); if (id != null) return id; }
        }
        return null;
    }

    // ---- Ответы на букет и кольцо (до запроса, вместе с остальными исходами жестов) ----

    /** С соперниками сердце закрыто только помолвкой и браком: житель помолвлен или в браке с другим игроком. null — открыто. */
    static String closedBy(ConversationStore.State state, ConversationStore.Profile p, UUID player) {
        UUID spouse = state.family.spouse(p.id);
        UUID other = spouse != null && !spouse.equals(player) && state.players.containsKey(spouse.toString()) ? spouse : null;
        if (other == null) for (Map.Entry<String, ConversationStore.Relation> e : p.relations.entrySet()) {
            ConversationStore.Relation r = e.getValue();
            if (e.getKey().equals(player.toString()) || r == null || r.rapport == null) continue;
            Courtship c = r.rapport.courtship();
            if (c == Courtship.ENGAGED || c == Courtship.MARRIED) { other = uuid(e.getKey()); if (other != null) break; }
        }
        if (other == null) return null;
        String name = state.players.getOrDefault(other.toString(), "");
        return "сердце занято: ты " + (married(state, p, other) ? "в браке" : "помолвлен" + female(p, "а")) + " с другим игроком"
                + (name.isBlank() ? "" : " (" + name + ")") + ", и это не ты";
    }
    private static boolean married(ConversationStore.State state, ConversationStore.Profile p, UUID other) {
        ConversationStore.Relation r = p.relations.get(other.toString());
        return other.equals(state.family.spouse(p.id)) || r != null && r.rapport != null && r.rapport.courtship() == Courtship.MARRIED;
    }

    /** Почему житель не возьмёт букет из-за соперников; null — соперники не мешают. Отбить пару можно, если этот игрок милее её
     * пары на {@link Rivalry#stealMargin}. */
    static String dateRefusal(ConversationStore.State state, ConversationStore.Profile p, UUID player, int score, MobTalkConfig c, long now) {
        if (!on(c) || p.social == null) return null;
        if (p.rivalry != null && p.rivalry.paused(player, now)) return paused(p);
        return stealRefusal(state, p, player, score, c);
    }
    private static String paused(ConversationStore.Profile p) {
        return "ты недавно выбрал" + female(p, "а") + " другого — этому игроку пока рано снова за тобой ухаживать; дружить можно";
    }
    private static String stealRefusal(ConversationStore.State state, ConversationStore.Profile p, UUID player, int score, MobTalkConfig c) {
        UUID other = dating(p, player);
        if (other == null) return null;
        String name = playerName(state, p, other);
        if (!c.rivalSteal) return "у тебя уже есть пара — " + name + ", и это не он";
        ConversationStore.Relation r = p.relations.get(other.toString());
        int need = (r == null ? 0 : r.score) + Rivalry.stealMargin(p.social.persona(), c.rivalStealMargin);
        if (score >= need) return null;
        return "у тебя пара — " + name + "; уйти к этому игроку ты готов" + female(p, "а") + " только тогда, когда он станет тебе заметно милее, а пока " + name + " ближе";
    }

    /** Ответ на кольцо с оглядкой на соперников: null — соперники не мешают (решают обычные правила); иначе отказ и его цена.
     * Если рядом сильный соперник ({@link MobTalkConfig#rivalStrong}), житель выбирает по счёту: проигравший кольцо не отдаёт и
     * теряет {@link MobTalkConfig#rivalLoserPenalty}, при ничьей — отказ без штрафа. */
    static Verdict proposeVerdict(ConversationStore.State state, ConversationStore.Profile p, ConversationStore.Relation own, UUID player, MobTalkConfig c, long now) {
        if (!on(c) || p.social == null) return null;
        if (p.rivalry != null && p.rivalry.paused(player, now)) return new Verdict(paused(p), 0);
        int hearts = own == null ? 0 : own.score;
        String steal = stealRefusal(state, p, player, hearts, c);
        if (steal != null) return new Verdict(steal, 0);
        if (p.rivalry != null && p.rivalry.sceneAt >= 0)
            return new Verdict("ты уже позвал" + female(p, "а") + " ухажёров к колоколу — ответишь там, при всех", 0);
        Rivalry.Choice choice = choice(state, p, own, player, c, now);
        if (choice == null) return null;
        // Сцена у колокола: при сильном сопернике житель отвечает не сразу, а при всех.
        if (c.rivalBellScene) return new Verdict(BELL_NOTE + ": позовёшь всех, кто за тобой ухаживает, к колоколу через "
                + Math.max(1, c.rivalGatherMinutes) + " мин. и там при всех скажешь, кого выбираешь; кольцо пока не бери", 0);
        if (!choice.tie() && choice.winner().player().equals(player)) return null;
        if (choice.tie()) {
            String names = String.join(" и ", choice.tied().stream().filter(t -> !t.player().equals(player)).map(Rivalry.Candidate::name).toList());
            return new Verdict("ты не можешь выбрать между этим игроком и " + names + " — оба тебе дороги; пусть проявят себя, потом решишь", 0);
        }
        return new Verdict("ты выбираешь не его, а " + choice.winner().name() + " — тот тебе милее; скажи это прямо, но по характеру", -Math.max(0, c.rivalLoserPenalty));
    }
    /** Выбор между игроком с кольцом и сильными соперниками; null — сильных соперников нет. */
    static Rivalry.Choice choice(ConversationStore.State state, ConversationStore.Profile p, ConversationStore.Relation own, UUID player, MobTalkConfig c, long now) {
        List<Rivalry.Candidate> candidates = new ArrayList<>();
        for (Suitor s : suitors(state, p, c, now))
            if (!s.player().equals(player) && s.hearts() >= c.rivalStrong) candidates.add(new Rivalry.Candidate(s.player(), s.name(), s.score()));
        if (candidates.isEmpty()) return null;
        candidates.add(new Rivalry.Candidate(player, playerName(state, p, player), score(p, own, player, now)));
        return Rivalry.choose(candidates);
    }

    // ---- После ответа: что жест изменил ----

    /** После доставленного ответа (под замком): романтический жест делает игрока ухажёром (и, если ухажёров стало двое, деревня
     * об этом заговорит); принятый букет или кольцо при чужой паре — житель уходит от неё; кольцо при сильных соперниках — выбор
     * сделан, проигравшие получают штраф и паузу. */
    void after(ConversationStore.State state, ConversationStore.Profile p, ConversationStore.Relation r, UUID player, String playerName,
               GestureIntent intent, MobTalkConfig c, long now) {
        if (!on(c) || p == null || p.social == null || player == null || intent == null) return;
        Gesture g = intent.gesture();
        boolean bouquet = g == Gesture.GIFT && intent.takesItem() && Romance.bouquet(intent.item());
        boolean romantic = g == Gesture.FLIRT || g == Gesture.KISS || g == Gesture.DATE || g == Gesture.PROPOSE || bouquet;
        if (!romantic) return;
        boolean accepted = intent.outcome() == Gesture.Outcome.ACCEPTED;
        Set<UUID> before = ids(suitors(state, p, c, now));
        // Жест сделан до решения о паре: кольцо проигравшего тоже считается ухаживанием, но пауза ниже его выключит.
        if (p.rivalry == null) p.rivalry = new Rivalry();
        p.rivalry.romance(player, playerName, deed(intent), now, window(c));
        if ((g == Gesture.DATE || g == Gesture.PROPOSE) && accepted) {
            UUID other = dating(p, player);
            if (other != null) leave(state, p, other, player, playerName, now);
        }
        boolean bell = g == Gesture.PROPOSE && !accepted && intent.note().startsWith(BELL_NOTE);
        if (bell) schedule(state, p, player, playerName, c, now);
        else if (g == Gesture.PROPOSE) decided(state, p, r, player, playerName, accepted, c, now);
        List<Suitor> after = suitors(state, p, c, now);
        if (!before.contains(player) && ids(after).contains(player) && after.size() >= 2) started(state, p, player, after, now);
        if (after.size() >= 2 && p.rivalry.sceneAt < 0) startTrial(state, p, after, c, now);
    }
    private static Set<UUID> ids(List<Suitor> list) { Set<UUID> out = new HashSet<>(); for (Suitor s : list) out.add(s.player()); return out; }

    /** Что этот жест значит для сравнения, словами жителя; "" — не стоит упоминать. */
    static String deed(GestureIntent intent) {
        if (!intent.outcome().warm()) return "";
        return switch (intent.gesture()) {
            case FLIRT -> "флиртовал с тобой";
            case KISS -> "поцеловал тебя";
            case GIFT -> "подарил тебе " + intent.item();
            case DATE -> "подарил тебе цветы и позвал встречаться";
            case PROPOSE -> "сделал тебе предложение с кольцом";
            default -> "";
        };
    }

    /** Новый ухажёр при уже имеющемся: житель это запоминает, деревня об этом говорит, оба игрока узнают о сопернике. */
    private void started(ConversationStore.State state, ConversationStore.Profile p, UUID newcomer, List<Suitor> suitors, long now) {
        String villager = SocialLedger.name(p);
        List<String> names = suitors.stream().map(Suitor::name).toList();
        ConversationStore.event(p, now, "За тобой ухаживают сразу несколько игроков: " + String.join(", ", names), 3);
        String newcomerName = playerName(state, p, newcomer);
        rumor(state, p, "за " + villager + " ухаживают " + String.join(" и ", names), 0, 2, newcomerName, now);
        for (Suitor s : suitors) {
            List<String> others = suitors.stream().filter(o -> !o.player().equals(s.player())).map(Suitor::name).toList();
            notices.add(new Notice(s.player(), p.id, villager + ": у тебя " + (others.size() > 1 ? "соперники" : "соперник") + " — " + String.join(", ", others)));
        }
    }
    /** Житель ушёл от пары {@code from} к игроку {@code to}: прежняя пара больше не пара, житель скажет ей об этом при встрече. */
    private void leave(ConversationStore.State state, ConversationStore.Profile p, UUID from, UUID to, String toName, long now) {
        ConversationStore.Relation old = p.relations.get(from.toString());
        if (old != null && old.rapport != null) old.rapport.courtship(Courtship.NONE);
        String fromName = playerName(state, p, from);
        Rivalry.Suitor s = p.rivalry.ensure(from, fromName);
        s.leftFor = TextBounds.characters(toName == null ? "" : toName, 60); s.leftAt = now;
        String villager = SocialLedger.name(p);
        ConversationStore.event(p, now, "Ты " + went(p) + " от игрока " + fromName + " к игроку " + toName + "; с " + fromName + " вы больше не пара", 5);
        rumor(state, p, villager + " " + went(p) + " от " + fromName + " к " + toName, -1, 3, toName, now);
        notices.add(new Notice(from, p.id, villager + " " + went(p) + " к другому: " + toName));
        notices.add(new Notice(to, p.id, villager + " теперь встречается с тобой; прежняя пара — " + fromName));
    }
    /** Кольцо при соперниках: принято — проигравшие сильные соперники теряют сердечки и ухаживание на время; отвергнуто, потому что
     * житель выбрал другого, — пауза у этого игрока (штраф уже в исходе жеста). */
    private void decided(ConversationStore.State state, ConversationStore.Profile p, ConversationStore.Relation own, UUID player, String playerName,
                         boolean accepted, MobTalkConfig c, long now) {
        long pause = now + Math.max(0, c.rivalLoserPauseHours) * 3_600_000L;
        String villager = SocialLedger.name(p);
        if (accepted) {
            List<Suitor> losers = suitors(state, p, c, now).stream().filter(s -> !s.player().equals(player) && s.hearts() >= c.rivalStrong).toList();
            if (losers.isEmpty()) return;
            for (Suitor loser : losers) {
                ConversationStore.Relation lr = p.relations.get(loser.player().toString());
                if (lr != null) lr.score = ConversationStore.clamp(lr.score - Math.max(0, c.rivalLoserPenalty), p.hostile());
                p.rivalry.ensure(loser.player(), loser.name()).pausedUntil = pause;
                notices.add(new Notice(loser.player(), p.id, villager + " выбрал" + female(p, "а") + " другого: " + playerName));
            }
            String lost = String.join(" и ", losers.stream().map(Suitor::name).toList());
            ConversationStore.event(p, now, "Ты выбрал" + female(p, "а") + " игрока " + playerName + ", а не " + lost, 5);
            rumor(state, p, villager + " выбрал" + female(p, "а") + " " + playerName + ", а " + lost + " остал" + (losers.size() > 1 ? "ись" : "ся") + " ни с чем", 1, 3, playerName, now);
            return;
        }
        Rivalry.Choice choice = choice(state, p, own, player, c, now);
        if (choice != null && choice.tie()) { startTrial(state, p, suitors(state, p, c, now), c, now); return; } // Пусть проявят себя.
        if (choice == null || choice.winner().player().equals(player)) return;
        p.rivalry.ensure(player, playerName).pausedUntil = pause;
        ConversationStore.event(p, now, "Игрок " + playerName + " звал тебя замуж, но ты выбрал" + female(p, "а") + " " + choice.winner().name(), 4);
    }

    // ---- Что житель знает о соперниках, для промпта ----

    /** Строки «Ты и этот игрок» о соперничестве; пусто без соперников. */
    static List<String> facts(ConversationStore.State state, ConversationStore.Profile p, ConversationStore.Relation own, UUID player, MobTalkConfig c, long now) {
        if (!on(c) || p == null || p.social == null || p.rivalry == null || player == null) return List.of();
        List<String> facts = new ArrayList<>();
        Rivalry.Suitor self = p.rivalry.suitor(player);
        if (self != null && !self.leftFor.isBlank() && self.leftAt >= 0 && now - self.leftAt < Rivalry.LEFT_FRESH_MILLIS)
            facts.add("ты " + went(p) + " от этого игрока к " + self.leftFor + " — вы больше не пара; если он заговорит, скажи ему это прямо, по характеру");
        if (p.rivalry.paused(player, now))
            facts.add("ты недавно выбрал" + female(p, "а") + " не этого игрока, а другого; он может остаться тебе другом, но ухаживания пока ни к чему");
        List<Suitor> suitors = suitors(state, p, c, now);
        Suitor me = suitors.stream().filter(s -> s.player().equals(player)).findFirst().orElse(null);
        if (me == null) return facts;
        if (p.rivalry.sceneAt >= 0)
            facts.add("ты позвал" + female(p, "а") + " ухажёров к колоколу и там при всех скажешь, кого выбираешь; до этого никому не отвечай «да»");
        Rivalry.Trial trial = p.rivalry.trial;
        if (trial != null && now < trial.endsAt) {
            List<String> done = new ArrayList<>();
            for (Map.Entry<String, Integer> e : trial.progress.entrySet()) {
                UUID id = uuid(e.getKey());
                if (id != null && e.getValue() > 0) done.add((id.equals(player) ? "этот игрок" : playerName(state, p, id)) + " — " + e.getValue());
            }
            facts.add("ты устроил" + female(p, "а") + " ухажёрам испытание: " + trialTask(trial) + "; осталось минут: " + Math.max(1, (trial.endsAt - now + 59_999) / 60_000)
                    + (done.isEmpty() ? "; пока никто ничего не принёс" : "; уже принесли: " + String.join(", ", done))
                    + ". Подзадоривай, но кто победил — решает игра");
        }
        List<String> others = new ArrayList<>();
        for (Suitor s : suitors) {
            if (s.player().equals(player)) continue;
            Rivalry.Standing standing = Rivalry.Standing.of(s.score(), me.score());
            String how = switch (standing) {
                case AHEAD -> s.name() + " тебе пока милее";
                case EVEN -> "с " + s.name() + " они для тебя почти наравне";
                case BEHIND -> "этот игрок тебе милее, чем " + s.name();
            };
            Rivalry.Suitor rs = p.rivalry.suitor(s.player());
            String deed = rs != null && !rs.deed.isBlank() && rs.deedAt >= 0 && now - rs.deedAt < window(c) ? "; " + s.name() + " недавно " + rs.deed : "";
            others.add(how + (s.courtship() == Courtship.DATING ? " (вы с " + s.name() + " встречаетесь)" : "") + deed);
        }
        if (others.isEmpty()) return facts;
        facts.add("за тобой ухаживает не только этот игрок: " + String.join("; ", others) + ". Можешь сравнивать их вслух, дразнить одного другим, "
                + "ставить условие («кто первым принесёт мне…») — по характеру и не в каждой реплике; чисел не называй, кого ты выберешь — решает игра");
        UUID other = dating(p, player);
        if (other != null && c.rivalSteal) {
            ConversationStore.Relation or = p.relations.get(other.toString());
            int need = (or == null ? 0 : or.score) + Rivalry.stealMargin(p.social.persona(), c.rivalStealMargin);
            if (own != null && own.score >= need)
                facts.add("если этот игрок сейчас предложит встречаться (с цветами в руке), ты уйдёшь к нему от " + playerName(state, p, other));
        }
        return facts;
    }

    // ---- Испытания (часть 2) ----

    /** Испытание для HUD: чьё, что принести, сколько, до какого времени и кто сколько принёс. */
    public record TrialView(UUID villager, String villagerName, String kind, String item, int count, long startedAt, long endsAt, List<Entry> entries) { }
    public record Entry(UUID player, String name, int progress) { }
    /** Сдача для испытания: сколько предметов житель взял, выиграно ли испытание, повод для реплики жителя. */
    public record Delivery(int taken, boolean won, String cue) { }

    static boolean trials(MobTalkConfig c) { return on(c) && c.rivalTrials; }
    private static String trialTask(Rivalry.Trial t) {
        return Rivalry.Trial.FAVORITE.equals(t.kind) ? "кто первым подарит тебе " + t.item + " (это твой любимый подарок)"
                : "кто первым принесёт тебе " + t.count + " шт. " + t.item;
    }
    /** Новое испытание, если ухажёров двое и больше, испытания нет, а прошлое кончилось больше игровых суток назад. */
    private void startTrial(ConversationStore.State state, ConversationStore.Profile p, List<Suitor> suitors, MobTalkConfig c, long now) {
        if (!trials(c) || suitors.size() < 2 || p.rivalry == null || p.rivalry.trial != null) return;
        if (p.rivalry.lastTrialAt >= 0 && now - p.rivalry.lastTrialAt < Rivalry.TRIAL_GAP_MILLIS) return;
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        Rivalry.Trial t = new Rivalry.Trial();
        if (random.nextBoolean()) {
            t.kind = Rivalry.Trial.FAVORITE; t.item = Rivalry.FAVORITES.get(random.nextInt(Rivalry.FAVORITES.size())); t.count = 1;
        } else {
            FavorItems.Candidate pick = FavorItems.draw(12, random).stream().filter(x -> x.count() >= 3).findFirst().orElse(new FavorItems.Candidate("minecraft:wheat", 16));
            t.kind = Rivalry.Trial.BRING; t.item = pick.item(); t.count = Math.min(16, pick.count());
        }
        t.startedAt = now; t.endsAt = now + Rivalry.TRIAL_MILLIS;
        for (Suitor s : suitors) t.progress.put(s.player().toString(), 0);
        p.rivalry.trial = t;
        String villager = SocialLedger.name(p);
        String task = Rivalry.Trial.FAVORITE.equals(t.kind) ? "кто первым подарит " + ITEM : "кто первым принесёт " + ITEM + " ×" + t.count;
        for (Suitor s : suitors) notices.add(new Notice(s.player(), p.id, villager + ": испытание — " + task + ", 10 минут", t.item));
        ConversationStore.event(p, now, "Ты устроил" + female(p, "а") + " ухажёрам (" + String.join(", ", suitors.stream().map(Suitor::name).toList())
                + ") испытание: " + trialTask(t), 3);
    }
    /** Игрок отдаёт жителю {@code available} шт. {@code item}: если это его испытание и игрок в нём участвует, житель берёт сколько
     * не хватает; null — не испытание (обычный подарок). Первый, кто набрал нужное, выигрывает. */
    public Delivery deliver(UUID villager, UUID player, String playerName, String item, int available, long now) {
        if (villager == null || player == null || item == null || available <= 0) return null;
        synchronized (store) {
            MobTalkConfig c = store.config();
            ConversationStore.Profile p = store.state.profiles.get(villager.toString());
            if (!trials(c) || p == null || p.rivalry == null || p.rivalry.trial == null) return null;
            Rivalry.Trial t = p.rivalry.trial;
            if (!t.item.equals(item) || now >= t.endsAt) return null;
            String key = player.toString();
            if (!t.progress.containsKey(key) && !ids(suitors(store.state, p, c, now)).contains(player)) return null;
            if (p.rivalry.paused(player, now)) return null;
            int have = t.progress.getOrDefault(key, 0), taken = Math.min(available, t.count - have);
            if (taken <= 0) return null;
            t.progress.put(key, have + taken);
            p.rivalry.ensure(player, playerName);
            boolean won = have + taken >= t.count;
            String cue = won ? "игрок первым выполнил твоё испытание ухажёров (" + trialTask(t) + ") — он победил; порадуйся вслух, соперников можно поддеть"
                    : "игрок принёс тебе для испытания ухажёров " + taken + " шт. " + item + ", у него уже " + (have + taken) + " из " + t.count + "; подбодри его";
            if (won) finish(store.state, p, player, c, now);
            p.revision++; store.scheduleSave();
            return new Delivery(taken, won, cue);
        }
    }
    /** Конец испытания: победителю {@link MobTalkConfig#rivalTrialWin} и победа в счёт выбора, остальным, кто что-то принёс, —
     * {@link MobTalkConfig#rivalTrialTry}. {@code winner} null — никто не выиграл. */
    private void finish(ConversationStore.State state, ConversationStore.Profile p, UUID winner, MobTalkConfig c, long now) {
        Rivalry.Trial t = p.rivalry.trial;
        p.rivalry.trial = null; p.rivalry.lastTrialAt = now;
        if (t == null) return;
        String villager = SocialLedger.name(p), winnerName = winner == null ? "" : playerName(state, p, winner);
        for (Map.Entry<String, Integer> e : t.progress.entrySet()) {
            UUID id = uuid(e.getKey());
            ConversationStore.Relation r = id == null ? null : p.relations.get(e.getKey());
            if (r == null) continue;
            if (id.equals(winner)) {
                r.score = ConversationStore.clamp(r.score + Math.max(0, c.rivalTrialWin), p.hostile());
                p.rivalry.ensure(id, winnerName).trialWins++;
                notices.add(new Notice(id, p.id, villager + ": испытание твоё! +" + Math.max(0, c.rivalTrialWin)));
            } else {
                if (e.getValue() > 0) r.score = ConversationStore.clamp(r.score + Math.max(0, c.rivalTrialTry), p.hostile());
                notices.add(new Notice(id, p.id, winner == null ? villager + ": испытание кончилось, никто не успел" : villager + ": испытание выиграл " + winnerName));
            }
        }
        if (winner == null) return;
        ConversationStore.event(p, now, "Испытание ухажёров (" + trialTask(t) + ") выиграл игрок " + winnerName, 4);
        rumor(state, p, winnerName + " выиграл испытание за сердце " + villager, 1, 2, winnerName, now);
    }
    /** Испытания, чьё время вышло: побеждает тот, кто принёс больше (при равенстве — никто). Под замком, раз в секунду. */
    public void expire(long now) {
        synchronized (store) {
            boolean changed = false;
            for (ConversationStore.Profile p : store.state.profiles.values()) {
                if (p.rivalry == null || p.rivalry.trial == null || now < p.rivalry.trial.endsAt) continue;
                UUID best = null; int top = 0; boolean tie = false;
                for (Map.Entry<String, Integer> e : p.rivalry.trial.progress.entrySet()) {
                    if (e.getValue() > top) { top = e.getValue(); best = uuid(e.getKey()); tie = false; }
                    else if (e.getValue() == top && top > 0) tie = true;
                }
                finish(store.state, p, tie ? null : best, store.config(), now);
                p.revision++; changed = true;
            }
            if (changed) store.scheduleSave();
        }
    }
    /** Идущие испытания для HUD (полоса с таймером у участников). */
    public List<TrialView> trials(long now) {
        synchronized (store) {
            List<TrialView> out = new ArrayList<>();
            for (ConversationStore.Profile p : store.state.profiles.values()) {
                Rivalry.Trial t = p.rivalry == null ? null : p.rivalry.trial;
                if (t == null || now >= t.endsAt) continue;
                List<Entry> entries = new ArrayList<>();
                for (Map.Entry<String, Integer> e : t.progress.entrySet()) {
                    UUID id = uuid(e.getKey());
                    if (id != null) entries.add(new Entry(id, playerName(store.state, p, id), e.getValue()));
                }
                out.add(new TrialView(p.id, SocialLedger.name(p), t.kind, t.item, t.count, t.startedAt, t.endsAt, entries));
            }
            return out;
        }
    }
    /** Админ закончил испытание досрочно: итог как по времени. */
    public boolean endTrial(UUID villager, long now) {
        if (villager == null) return false;
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(villager.toString());
            if (p == null || p.rivalry == null || p.rivalry.trial == null) return false;
            p.rivalry.trial.endsAt = now;
        }
        expire(now);
        return true;
    }

    // ---- Выбор у колокола (часть 3) ----

    /** Назначенный выбор: житель, когда, кто звал кольцом (null — позвал админ), кого ждут. */
    public record Scene(UUID villager, String villagerName, long at, UUID proposer, List<UUID> suitors) { }
    /** Итог выбора: победитель (null — ничья или некого выбирать), проигравшие, повод для торжественной реплики жителя. */
    public record SceneResult(UUID villager, String villagerName, UUID winner, String winnerName, List<UUID> losers, boolean tie, String cue) { }

    private void schedule(ConversationStore.State state, ConversationStore.Profile p, UUID proposer, String proposerName, MobTalkConfig c, long now) {
        if (p.rivalry.sceneAt >= 0) return;
        int minutes = Math.max(1, c.rivalGatherMinutes);
        p.rivalry.sceneAt = now + minutes * 60_000L; p.rivalry.sceneBy = proposer == null ? "" : proposer.toString();
        String villager = SocialLedger.name(p);
        for (Suitor s : suitors(state, p, c, now))
            notices.add(new Notice(s.player(), p.id, villager + " выберет у колокола через " + minutes + " мин. — приходи"));
        if (proposer != null) {
            ConversationStore.event(p, now, "Игрок " + proposerName + " протянул тебе кольцо; ты ответишь у колокола, при всех ухажёрах", 4);
            rumor(state, p, proposerName + " позвал замуж " + villager + ", а " + villager + " выберет жениха у колокола", 0, 3, proposerName, now);
        }
    }
    /** Админ позвал ухажёров к колоколу: false — ухажёров меньше двух или выбор уже назначен. */
    public boolean callScene(UUID villager, long now) {
        if (villager == null) return false;
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(villager.toString());
            MobTalkConfig c = store.config();
            if (p == null || suitors(store.state, p, c, now).size() < 2) return false;
            if (p.rivalry == null) p.rivalry = new Rivalry();
            if (p.rivalry.sceneAt >= 0) return false;
            schedule(store.state, p, null, "", c, now);
            p.revision++; store.scheduleSave();
            return true;
        }
    }
    /** Назначенные выборы. */
    public List<Scene> scenes(long now) {
        synchronized (store) {
            List<Scene> out = new ArrayList<>();
            for (ConversationStore.Profile p : store.state.profiles.values()) {
                if (p.rivalry == null || p.rivalry.sceneAt < 0) continue;
                out.add(new Scene(p.id, SocialLedger.name(p), p.rivalry.sceneAt, uuid(p.rivalry.sceneBy),
                        suitors(store.state, p, store.config(), now).stream().map(Suitor::player).toList()));
            }
            return out;
        }
    }
    /** Житель объявляет выбор: среди сильных ухажёров, своей пары и того, кто звал кольцом, побеждает лучший по счёту. Победитель
     * становится парой (кольцо, протянутое снова, будет принято), проигравшие теряют {@link MobTalkConfig#rivalLoserPenalty} и
     * ухаживание на {@link MobTalkConfig#rivalLoserPauseHours}; ничья — новое испытание. null — выбора не было. */
    public SceneResult resolveScene(UUID villager, long now) {
        if (villager == null) return null;
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(villager.toString());
            if (p == null || p.rivalry == null || p.rivalry.sceneAt < 0) return null;
            MobTalkConfig c = store.config();
            UUID proposer = uuid(p.rivalry.sceneBy);
            p.rivalry.sceneAt = -1; p.rivalry.sceneBy = "";
            p.revision++; store.scheduleSave();
            String name = SocialLedger.name(p);
            List<Suitor> suitors = suitors(store.state, p, c, now);
            List<Rivalry.Candidate> candidates = new ArrayList<>();
            for (Suitor s : suitors)
                if (s.hearts() >= c.rivalStrong || s.courtship().partners() || s.player().equals(proposer)) candidates.add(new Rivalry.Candidate(s.player(), s.name(), s.score()));
            if (candidates.isEmpty()) return new SceneResult(p.id, name, null, "", List.of(), false, "");
            Rivalry.Choice choice = Rivalry.choose(candidates);
            if (choice.tie()) {
                startTrial(store.state, p, suitors, c, now);
                List<String> tied = choice.tied().stream().map(Rivalry.Candidate::name).toList();
                for (Rivalry.Candidate t : candidates) notices.add(new Notice(t.player(), p.id, name + " не может выбрать между " + String.join(" и ", tied)));
                ConversationStore.event(p, now, "У колокола ты не смог" + female(p, "ла") + " выбрать между " + String.join(" и ", tied), 4);
                return new SceneResult(p.id, name, null, "", choice.tied().stream().map(Rivalry.Candidate::player).toList(), true,
                        "ты стоишь у колокола перед ухажёрами (" + String.join(", ", tied) + ") и честно говоришь, что не можешь выбрать: они тебе одинаково дороги."
                                + (trials(c) && p.rivalry.trial != null ? " Объяви им испытание: " + trialTask(p.rivalry.trial) : " Пусть ещё проявят себя"));
            }
            Rivalry.Candidate won = choice.winner();
            ConversationStore.Relation wr = store.relation(p, won.player());
            if (wr.rapport == null) wr.rapport = new Rapport();
            UUID other = dating(p, won.player());
            if (other != null) { ConversationStore.Relation or = p.relations.get(other.toString()); if (or != null && or.rapport != null) or.rapport.courtship(Courtship.NONE); }
            if (!wr.rapport.courtship().partners()) wr.rapport.courtship(Courtship.DATING);
            long pause = now + Math.max(0, c.rivalLoserPauseHours) * 3_600_000L;
            List<UUID> losers = new ArrayList<>();
            for (Rivalry.Candidate loser : choice.losers()) {
                ConversationStore.Relation lr = p.relations.get(loser.player().toString());
                if (lr != null) lr.score = ConversationStore.clamp(lr.score - Math.max(0, c.rivalLoserPenalty), p.hostile());
                p.rivalry.ensure(loser.player(), loser.name()).pausedUntil = pause;
                losers.add(loser.player());
                notices.add(new Notice(loser.player(), p.id, name + " выбрал" + female(p, "а") + " другого: " + won.name()));
            }
            notices.add(new Notice(won.player(), p.id, name + " выбрал" + female(p, "а") + " тебя!" + (won.player().equals(proposer) ? " Протяни кольцо снова — ответ будет «да»" : "")));
            String lost = String.join(" и ", choice.losers().stream().map(Rivalry.Candidate::name).toList());
            ConversationStore.event(p, now, "У колокола ты при всех выбрал" + female(p, "а") + " игрока " + won.name() + (lost.isEmpty() ? "" : ", а не " + lost), 5);
            rumor(store.state, p, name + " при всех выбрал" + female(p, "а") + " " + won.name() + (lost.isEmpty() ? "" : ", а " + lost + " остал" + (losers.size() > 1 ? "ись" : "ся") + " ни с чем"), 1, 3, won.name(), now);
            return new SceneResult(p.id, name, won.player(), won.name(), List.copyOf(losers), false,
                    "ты стоишь у колокола перед ухажёрами и при всех объявляешь выбор: ты выбираешь " + won.name()
                            + (lost.isEmpty() ? "" : ", а не " + lost) + ". Скажи это торжественно и по характеру, проигравшим — пару слов утешения или колкости"
                            + (won.player().equals(proposer) ? "; кольцо примешь, когда он протянет его снова" : ""));
        }
    }

    // ---- Для HUD и админки ----

    /** Соперник, которого игрок видит в HUD над жителем: имя и насколько он впереди; null — соперников нет. */
    public record Rival(String name, Rivalry.Standing standing) { }
    public Rival rival(UUID villager, UUID player, long now) {
        if (villager == null || player == null) return null;
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(villager.toString());
            List<Suitor> suitors = suitors(store.state, p, store.config(), now);
            Suitor me = suitors.stream().filter(s -> s.player().equals(player)).findFirst().orElse(null);
            if (me == null) return null;
            Suitor best = null;
            for (Suitor s : suitors) if (!s.player().equals(player) && (best == null || s.score() > best.score())) best = s;
            return best == null ? null : new Rival(best.name(), Rivalry.Standing.of(best.score(), me.score()));
        }
    }
    /** Все ухажёры жителя для админки: имя, сердечки, счёт выбора, пара ли. */
    public List<Suitor> suitors(UUID villager, long now) {
        if (villager == null) return List.of();
        synchronized (store) { return suitors(store.state, store.state.profiles.get(villager.toString()), store.config(), now); }
    }
    /** Админ сбросил соперничество: ухажёры, паузы и испытания забыты, пары остаются. */
    public boolean reset(UUID villager) {
        if (villager == null) return false;
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(villager.toString());
            if (p == null || p.rivalry == null) return false;
            p.rivalry = null; p.revision++; store.scheduleSave(); // Испытание и назначенный выбор уходят вместе с ухажёрами.
            return true;
        }
    }

    // ---- Мелочи ----

    /** Слух в деревне жителя; рассказывает сам житель. */
    static void rumor(ConversationStore.State state, ConversationStore.Profile p, String text, int sign, int importance, String subject, long now) {
        if (p.villageId == null || p.villageId.isBlank()) return;
        Map<String, String> players = new LinkedHashMap<>();
        state.players.forEach((id, name) -> players.put(name, id));
        String source = SocialLedger.name(p);
        state.rumors.add(p.villageId, List.of(new VillageRumors.View("", text, source, importance, sign, 0, importance, subject)),
                Map.of(source, p.id.toString()), players, now);
    }
    /** Окончание глагола для жителя-женщины: {@code ending} — для неё, "" — для мужчины. */
    static String female(ConversationStore.Profile p, String ending) { return p.social != null && p.social.gender() == Gender.FEMALE ? ending : ""; }
    /** «ушёл» или «ушла». */
    static String went(ConversationStore.Profile p) { return female(p, "а").isEmpty() ? "ушёл" : "ушла"; }
    private static UUID uuid(String text) { try { return UUID.fromString(text); } catch (IllegalArgumentException | NullPointerException bad) { return null; } }
}
