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
    static final String GESTURE_FIELD = "gesture — что игрок делает именно ЭТОЙ своей репликой: JOKE — шутит, COMPLIMENT — хвалит тебя или твоё, HUG — обнимает, "
            + "FLIRT — заигрывает, KISS — целует, GIFT — протягивает тебе вещь из руки («держи», «это тебе»); иначе NONE. Жест должен быть в его словах, а не в твоих; "
            + "только значения из схемы. Подарок забирает игра, когда ты его принимаешь: не говори «оставь себе», если берёшь.";

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
        if (turn == null || turn.rolled().isEmpty() && turn.closed().isEmpty()) return "";
        StringBuilder text = new StringBuilder("\n\n").append(GESTURE_HEADER);
        for (Map.Entry<Gesture, GestureIntent> entry : turn.rolled().entrySet())
            text.append("\n— ").append(entry.getKey().word).append(": ").append(words(entry.getValue())).append(';');
        for (String line : turn.closed()) text.append("\n").append(line);
        return text.append("\n").append(GESTURE_FIELD).toString();
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
            default -> " сделал жест «" + intent.gesture().word + "»";
        };
        return who + what + "; отношение " + score;
    }
    /** Ordinary for small talk, more for a slap; gifts and family steps matter more. */
    static int importance(GestureIntent intent) {
        if (intent.outcome() == Gesture.Outcome.SLAP) return 3;
        return intent.gesture() == Gesture.GIFT ? 4 : MemoryFact.ORDINARY;
    }
}
