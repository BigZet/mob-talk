package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The social layer's short prompt blocks (who the villager is, the outcomes of the player's gestures decided in advance) and the
 * words for memory lines. Every block carries the rule to use it, not recite it. */
final class SocialPrompt {
    private SocialPrompt() { }

    static final String CHILD_ROMANCE = "Флирт, поцелуи и ухаживания с тобой невозможны: ты ещё ребёнок. Если игрок заигрывает — не понимаешь, о чём он, "
            + "или отшиваешь по-детски; gesture тогда NONE.";
    static final String GESTURE_HEADER = "Жесты игрока: исход уже решила игра. Если игрок этой репликой сделает такой жест, это свершившийся факт — "
            + "отыграй ровно этот исход, своими словами и по характеру, не споря с ним и не объявляя его вслух; список не пересказывай:";
    static final String GESTURE_FIELD_HEAD = "gesture — что игрок делает именно ЭТОЙ своей репликой: ";
    static final String GESTURE_FIELD_TAIL = "; иначе NONE. Жест должен быть в его словах, а не в твоих; только значения из схемы. "
            + "Вещь из руки (подарок, цветы, кольцо) забирает игра, когда ты её принимаешь: не говори «оставь себе», если берёшь.";

    /** «Нрав» and sex of a villager, its inborn marks as facts; "" for anyone outside the layer. */
    static String persona(ConversationStore.Profile p) {
        if (p == null || p.social == null) return "";
        Persona persona = p.social.persona();
        StringBuilder text = new StringBuilder("\nНрав: ").append(persona.word).append(" — ").append(persona.speech).append(". ")
                .append("Ты ").append(SocialLedger.adult(p, null) ? p.social.gender().adult : p.social.gender().child).append('.');
        ChildGrowth.Stage stage = ChildGrowth.Stage.of(p.social.stage);
        if (stage != null && stage != ChildGrowth.Stage.ADULT) text.append(" Возраст: ").append(stage.speech).append('.');
        List<String> facts = new ArrayList<>();
        for (VillagerTrait trait : p.social.traits()) if (trait.spoken) facts.add(trait.fact);
        if (!facts.isEmpty()) text.append(" О себе (факт, к слову, не в каждой реплике): ").append(String.join(", ", facts)).append('.');
        return text.toString();
    }

    /** The outcomes block and the gesture field rule; "" when the villager has none on offer. */
    static String gestures(ConversationStore.Profile p) {
        SocialTurn turn = p == null ? null : p.socialTurn;
        if (turn == null || turn.rolled().isEmpty() && turn.closed().isEmpty() && turn.facts().isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        if (!turn.facts().isEmpty()) text.append("\n\nТы и этот игрок (факт, живи с ним, не объявляй в каждой реплике): ").append(String.join(" ", turn.facts()));
        if (turn.rolled().isEmpty() && turn.closed().isEmpty()) return text.toString();
        text.append("\n\n").append(GESTURE_HEADER);
        for (Map.Entry<Gesture, GestureIntent> entry : turn.rolled().entrySet())
            text.append("\n— ").append(entry.getKey().word).append(": ").append(words(entry.getValue())).append(';');
        for (String line : turn.closed()) text.append("\n").append(line);
        List<String> cues = new ArrayList<>();
        for (Gesture gesture : turn.rolled().keySet()) cues.add(gesture.name() + " — " + cue(gesture));
        return text.append("\n").append(GESTURE_FIELD_HEAD).append(String.join(", ", cues)).append(GESTURE_FIELD_TAIL).toString();
    }
    /** How the player's words show the gesture. */
    static String cue(Gesture gesture) {
        return switch (gesture) {
            case JOKE -> "шутит"; case COMPLIMENT -> "хвалит тебя или твоё"; case HUG -> "обнимает"; case FLIRT -> "заигрывает"; case KISS -> "целует";
            case GIFT -> "протягивает вещь из руки («держи», «это тебе»)"; case DATE -> "предлагает встречаться, ухаживает с цветами";
            case PROPOSE -> "делает предложение («выходи за меня», с кольцом)"; case DIVORCE -> "хочет расстаться или развестись";
            case CHILD -> "зовёт завести ребёнка";
            default -> "";
        };
    }
    static final String KIN_ROMANCE = "Флирт, поцелуи и ухаживания между вами невозможны: вы родня. Если игрок заигрывает — оборви его, как родного; gesture тогда NONE.";

    /** What the villager and this player are to each other: a couple, a parent and a child. */
    static List<String> playerFacts(Courtship courtship, FamilyTree tree, java.util.UUID mob, java.util.UUID player, MobTalkConfig config) {
        List<String> facts = new ArrayList<>();
        Gender playerGender = Gender.ofPlayer(config.playerGender);
        if (courtship == Courtship.MARRIED) facts.add("этот игрок — " + (playerGender == null ? "твой супруг" : playerGender == Gender.MALE ? "твой муж" : "твоя жена") + ", вы в браке.");
        else if (courtship == Courtship.DATING) facts.add("вы с этим игроком встречаетесь — вы пара, но ещё не женаты.");
        if (tree.parents(mob).contains(player)) facts.add("этот игрок — " + (playerGender == null ? "твой родитель" : playerGender == Gender.MALE ? "твой отец" : "твоя мать") + ", ты его ребёнок.");
        if (tree.children(mob).contains(player)) facts.add("этот игрок — твой ребёнок.");
        if (tree.widowed(player) && tree.links(player).containsKey(mob)) facts.add("он твой вдовец.");
        return facts;
    }
    /** «Свои» from the family tree: «Марфа — твоя жена (сейчас рядом)», «Пётр — твой сын, его уже нет в живых». */
    static List<String> familyLines(List<FamilyTree.Kin> family, java.util.Set<java.util.UUID> present) {
        if (family == null || family.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        for (FamilyTree.Kin kin : family) {
            String name = kin.name().isBlank() ? "без имени" : kin.name();
            lines.add(name + " — " + (feminine(kin.word()) ? "твоя " : "твой ") + kin.word()
                    + (kin.dead() ? ", его уже нет в живых — ты горюешь" : present != null && present.contains(kin.id()) ? " (сейчас рядом)" : ""));
        }
        return lines;
    }
    /** How the village sees this player, one line; "" for a stranger or outside the layer. */
    static String standing(ConversationStore.Profile p) {
        String line = p == null ? "" : VillageStanding.describe(p.standing);
        String god = p == null || p.divine == null ? "" : p.divine;
        return (line.isBlank() ? "" : "\nДеревня об этом игроке (факт, не пересказывай): " + line + ".")
                + (god.isBlank() ? "" : "\nБожий суд (факт, говори с трепетом, крестись на словах, без насмешки над верой): " + god + ".");
    }
    /** Б1: what the village knows of the god's deeds about this player now — the curse over it and whose it is, a blessing, the bell
     * assembly or the exile it decided; "" for nothing (or when the setting is off). */
    static String divine(ConversationStore.State state, String villageId, java.util.UUID player, long now) {
        if (!Tuning.get().divineTidings || state.divine == null || villageId == null || villageId.isBlank() || player == null) return "";
        List<String> parts = new ArrayList<>();
        DivineTidings.CurseView curse = state.divine.curseOn(villageId, now);
        if (curse != null) {
            if (curse.blames(player)) parts.add("на деревне сейчас божья кара «" + curse.title() + "» за ЕГО грехи — ты боишься и винишь его, просишь покаяться у алтаря");
            else if (curse.known()) parts.add("на деревне сейчас божья кара «" + curse.title() + "» за грехи игрока " + curse.culpritName() + ", не этого");
            else parts.add("на деревне сейчас божья кара «" + curse.title() + "», за чьи грехи — не знаешь");
        }
        String blessing = state.divine.blessing(player, now);
        if (!blessing.isBlank()) parts.add("бог явил ему милость «" + blessing + "» — ты зовёшь его благословенным и рад ему");
        if (state.divine.exiled(villageId, player)) parts.add("сход у колокола изгнал его за навлечённый гнев божий — прощение только через покаяние");
        else for (DivineTidings.AssemblyView a : state.divine.assemblies())
            if (a.village().equals(villageId) && a.player().equals(player)) { parts.add("староста созвал сход у колокола: если он не покается до заката, деревня его изгонит"); break; }
        return String.join("; ", parts);
    }
    /** Why the spouse will not have a child now, or null when it agrees. */
    static String childRefusal(int score, boolean infertile, boolean expecting, boolean tooSoon) { return childRefusal(score, infertile, expecting, false, tooSoon); }
    /** {@code trying}: the couple already agreed and waits for a night together; {@code expecting}: she is with child. */
    static String childRefusal(int score, boolean infertile, boolean expecting, boolean trying, boolean tooSoon) {
        if (expecting) return "ребёнок у вас уже на подходе";
        if (trying) return "вы уже договорились и стараетесь: скажи прямо и тепло, что согласна давно, а малыш бывает от ночи вместе в одной кровати "
                + "(лучше особый вечер: свечи, цветы, уют) — позови спать вместе, не говори «пусть случится в своё время» и не намекай, будто ребёнок уже есть";
        if (tooSoon) return "рано: с прошлого раза прошло меньше трёх игровых дней";
        if (infertile) return "у тебя не может быть детей — это твоя боль";
        if (score < Hearts.marriage()) return "сейчас между вами не та близость";
        return null;
    }
    /** The correction when the reply says yes to a courtship the game turned down. */
    static String consentRejection(String reason) {
        return "Игра решила, что ты отказываешь" + (reason == null || reason.isBlank() ? "" : " (" + reason + ")")
                + ", а в реплике ты соглашаешься. Откажи по характеру — мягко или резко, — не говори «да» и не обещай пару или брак.";
    }
    private static boolean feminine(String word) { return List.of("жена", "мать", "дочь", "сестра").contains(word); }
    /** For the biography of the first reply: the sex the name and story must agree with, and the family as given facts. */
    static String lifeFacts(ConversationStore.Profile p) {
        if (p == null || p.social == null) return "";
        boolean adult = SocialLedger.adult(p, null);
        StringBuilder text = new StringBuilder("Ты — ").append(adult ? p.social.gender().adult : p.social.gender().child)
                .append(": имя, история и слова о себе — в этом роде.");
        if (p.family != null && !p.family.isEmpty()) {
            List<String> kin = new ArrayList<>();
            for (FamilyTree.Kin k : p.family) kin.add(k.word() + " " + (k.name().isBlank() ? "(имя придумай)" : k.name()) + (k.dead() ? " (умер)" : ""));
            text.append(" Твоя семья — факт, история не должна ему противоречить, имена бери отсюда: ").append(String.join(", ", kin)).append('.');
        }
        return text.append('\n').toString();
    }
    /** The gesture enum for the schema; empty when the villager has none (no gesture field then). */
    static List<String> schemaGestures(ConversationStore.Profile p) { return p == null || p.socialTurn == null ? List.of() : p.socialTurn.schemaValues(); }

    /** What happens, in words, from the villager's side. */
    static String words(GestureIntent intent) {
        Gesture.Outcome o = intent.outcome();
        return switch (intent.gesture()) {
            case JOKE -> o == Gesture.Outcome.SUCCESS ? "шутка зайдёт — тебе правда смешно" : "шутка не зайдёт — не смешно, скривишься или огрызнёшься";
            case COMPLIMENT -> o == Gesture.Outcome.SUCCESS ? "похвала приятна — оттаешь, смутишься или расцветёшь" : "похвала не тронет — примешь за лесть или подвох";
            case HUG -> o == Gesture.Outcome.SUCCESS ? "обнимешь в ответ" : "отстранишься — обниматься не хочешь";
            case FLIRT -> o == Gesture.Outcome.SUCCESS ? "флирт зайдёт — ответишь кокетством" : o == Gesture.Outcome.SLAP ? "оскорбишься и влепишь пощёчину"
                    : "смутишься и неловко отшутишься, ответа нет";
            case KISS -> o == Gesture.Outcome.SUCCESS ? "ответишь на поцелуй" : o == Gesture.Outcome.SLAP ? "влепишь пощёчину" : "увернёшься — целовать не дашь";
            case GIFT -> giftWords(intent);
            case DATE -> o == Gesture.Outcome.ACCEPTED ? "согласишься встречаться и возьмёшь цветы — с этой минуты вы пара" : "откажешь: " + intent.note();
            case PROPOSE -> o == Gesture.Outcome.ACCEPTED ? (Tuning.get().weddingCeremony
                    ? "согласишься и примешь кольцо — с этой минуты вы помолвлены, а свадьбу сыграете у колокола при всей деревне, когда игрок позовёт"
                    : "согласишься на брак и примешь кольцо — с этой минуты вы супруги") : "откажешь: " + intent.note();
            case CHILD -> o == Gesture.Outcome.ACCEPTED ? "согласишься завести ребёнка — теперь вы его ждёте вместе" : "откажешь: " + intent.note();
            case DIVORCE -> Courtship.ENGAGED.name().equals(intent.note()) ? "помолвка расторгнута — это решено, свадьбы не будет; кольцо ты вернёшь"
                    : Courtship.MARRIED.name().equals(intent.note()) ? "брак расторгнут — это решено, вы больше не супруги; переживи это по характеру"
                    : "вы расстаётесь — это решено, вы больше не пара";
            default -> "";
        };
    }
    /** The occasion for words about a gift thrown at the villager's feet: the outcome is a fact, the line reacts to it. */
    static String thrownGift(GestureIntent intent) {
        String what = intent.item() + (intent.note().isBlank() ? "" : " (" + intent.note() + ")");
        String how = switch (intent.outcome()) {
            case BEST -> "ты его подобрал и в восторге: лучший подарок";
            case BETTER -> "ты его подобрал и рад: хороший подарок";
            case GOOD -> "ты его подобрал: мелочь, но приятно";
            case ALREADY -> "ты его не взял: он тебе это уже дарил, надоело";
            default -> intent.hearts() < 0 ? "ты его не взял и обиделся: тебе такое нельзя или противно" : "ты его не взял: не нужно";
        };
        return "игрок только что бросил тебе под ноги подарок — " + what + "; " + how
                + ". Это уже случилось: отреагируй одной репликой по характеру, обращаясь к игроку";
    }
    /** A gift in words: what it is (the item id, the model names it in Russian) and how it is taken. */
    static String giftWords(GestureIntent intent) {
        String what = "у него в руке " + intent.item() + (intent.note().isBlank() ? "" : " (" + intent.note() + ")") + " — ";
        return what + switch (intent.outcome()) {
            case BEST -> "примешь с восторгом: лучший подарок, о каком ты мечтал";
            case BETTER -> "обрадуешься: хороший подарок, возьмёшь";
            case GOOD -> "возьмёшь с благодарностью: мелочь, но приятно";
            case ALREADY -> "не возьмёшь: он тебе это уже дарил, надоело — скажи об этом";
            default -> intent.hearts() < 0 ? "не возьмёшь и обидишься: тебе такое нельзя или противно"
                    : intent.note().isBlank() ? "не возьмёшь: не поймёшь, что это и зачем оно тебе" : "не возьмёшь: тебе это не нужно";
        };
    }

    /** The memory line the gesture leaves. */
    static String memoryLine(GestureIntent intent, String playerName, int score) {
        String who = "Игрок " + (playerName == null || playerName.isBlank() ? "неизвестный" : TextBounds.characters(playerName, 60));
        boolean warm = intent.outcome().warm();
        String what = switch (intent.gesture()) {
            case JOKE -> warm ? " пошутил — было смешно" : " пошутил — не смешно";
            case COMPLIMENT -> warm ? " сделал комплимент — было приятно" : " сделал комплимент — не тронуло";
            case HUG -> warm ? " обнял, ты обнял в ответ" : " полез обниматься, ты отстранился";
            case FLIRT -> warm ? " флиртовал с тобой, тебе понравилось" : intent.outcome() == Gesture.Outcome.SLAP ? " флиртовал, получил пощёчину" : " флиртовал, вышло неловко";
            case KISS -> warm ? " поцеловал тебя" : intent.outcome() == Gesture.Outcome.SLAP ? " полез целоваться, получил пощёчину" : " полез целоваться, ты увернулся";
            case GIFT -> " подарил тебе " + intent.item() + (intent.takesItem() ? (intent.outcome() == Gesture.Outcome.BEST ? " — лучший подарок" : " — ты взял")
                    : intent.outcome() == Gesture.Outcome.ALREADY ? " — уже дарил, ты не взял" : " — ты отказался");
            case DATE -> warm ? " предложил встречаться и подарил цветы — вы теперь пара" : " предложил встречаться — ты отказал (" + intent.note() + ")";
            case PROPOSE -> warm ? (Tuning.get().weddingCeremony ? " сделал тебе предложение с кольцом — вы помолвлены, свадьба впереди"
                    : " сделал тебе предложение с кольцом — вы теперь в браке") : " сделал тебе предложение — ты отказал (" + intent.note() + ")";
            case DIVORCE -> Courtship.MARRIED.name().equals(intent.note()) ? " развёлся с тобой" : Courtship.ENGAGED.name().equals(intent.note()) ? " расторг помолвку с тобой" : " расстался с тобой";
            case CHILD -> warm ? " — вы решили завести ребёнка" : " звал завести ребёнка — ты отказал (" + intent.note() + ")";
            default -> " сделал жест «" + intent.gesture().word + "»";
        };
        return who + what + "; отношение " + score;
    }
    /** Ordinary for small talk, more for a slap; gifts and family steps matter more. */
    static int importance(GestureIntent intent) {
        if (intent.outcome() == Gesture.Outcome.SLAP) return 3;
        return switch (intent.gesture()) {
            case GIFT -> 4;
            case DATE, PROPOSE, CHILD -> intent.outcome() == Gesture.Outcome.ACCEPTED ? 5 : 3;
            case DIVORCE -> 5;
            default -> MemoryFact.ORDINARY;
        };
    }
}
