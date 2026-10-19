package io.github.mobtalk.core;

import java.util.*;

/** A creature speaking first: the occasions the game can notice without a model (the player came back after a long absence,
 * the creature has something on its mind or an open deal, two friends stand together, the player built or dug a lot nearby,
 * night or rain just set in), the budgets that keep it rare, and the words of the occasion for the prompt. Pure Java: the game
 * layer gathers the {@link Facts}, a {@link Decider} says whether and how the creature speaks, the client voices it. */
public final class Initiative {
    private Initiative() { }

    /** The player has not met this creature for this long: a return worth a word. */
    public static final long RETURN_MILLIS = 20 * 60_000L;
    /** One creature speaks first at most once in this time. */
    public static final long MOB_COOLDOWN_MILLIS = 5 * 60_000L;
    /** How close the player must be: to be spoken to, and to overhear two creatures. */
    public static final double SPEAK_RANGE = 6, HEAR_RANGE = 12;
    /** How close a friend must stand for a word between the two, and how many blocks changed nearby make a building worth a remark. */
    public static final double FRIEND_RANGE = 8;
    public static final int BUILDING_BLOCKS = 8;
    /** How long after night or rain set in the change is still fresh. */
    public static final long WEATHER_FRESH_MILLIS = 30_000L;
    /** A scene between creatures: this many lines, the first on the occasion, the rest answers. */
    public static final int SCENE_MIN_LINES = 2, SCENE_MAX_LINES = 4;

    /** Why a creature speaks first, strongest first. */
    public enum Trigger {
        RETURN("игрок вернулся после долгого отсутствия"), BUILDING("игрок только что много строил или копал рядом"),
        CONCERN("у тебя есть незакрытое дело с игроком или мысль, которая не даёт покоя"), WEATHER("погода или время суток только что переменились"),
        FRIENDS("рядом стоит твой друг, и никто с вами не говорит"),
        /** Raised by the game itself, not detected: a gift thrown at the villager's feet, a family event. Always voiced. */
        GIFT("игрок только что сделал тебе подарок или в твоей семье случилось важное");
        public final String reason;
        Trigger(String reason) { this.reason = reason; }
    }
    /** How the creature speaks up; {@link #SILENT} means it does not. */
    public enum Reaction {
        GREET("поздороваться, отметить встречу"), REMARK("заметить вслух то, что видишь или думаешь"), ASK("спросить или напомнить о деле"),
        COMPLAIN("пожаловаться, поворчать"), SILENT("промолчать");
        public final String manner;
        Reaction(String manner) { this.manner = manner; }
    }

    /** Another creature in reach, as a possible partner or next speaker: {@code bond} is its score towards the one considering it. */
    public record Candidate(UUID id, String name, int bond, double distance) { }

    /** What the game knows about one creature at one moment. {@code lastMetMillis}: when the player last was near it or talked to
     * it, -1 for never; {@code weatherChange}: "" or what just changed ("ночь", "дождь", "гроза", "рассвет"). */
    public record Facts(UUID mob, String name, boolean acquainted, boolean hostile, boolean busy, int relationship, double playerDistance,
                        long lastMetMillis, boolean hasThought, boolean openDeal, List<Candidate> friendsNearby, int blocksChanged,
                        String weatherChange, long weatherChangedMillis) {
        public Facts { friendsNearby = friendsNearby == null ? List.of() : List.copyOf(friendsNearby); weatherChange = weatherChange == null ? "" : weatherChange; }
    }

    /** The occasion, handed from the game to the client: who speaks, why, and who may answer. */
    public record Cue(UUID mob, String mobName, Trigger trigger, String reason, List<Candidate> partners, long createdMillis) {
        public Cue { partners = partners == null ? List.of() : List.copyOf(partners); }
        /** The friend this creature speaks to, when the occasion is a friend. */
        public Candidate partner() { return partners.isEmpty() ? null : partners.getFirst(); }
    }
    /** A decider's answer: whether the creature speaks, in what manner, and how much (0 — a word, 1 — a line, 2 — two lines). */
    public record Verdict(boolean speak, Reaction reaction, int level) {
        public static final Verdict QUIET = new Verdict(false, Reaction.SILENT, 0);
        public Verdict { reaction = reaction == null ? Reaction.SILENT : reaction; level = Math.max(0, Math.min(2, level)); }
    }

    /** The strongest occasion the facts give, or null: never for a stranger, a creature hostile by nature, a busy one, or one the
     * player cannot hear. */
    public static Trigger detect(Facts f, long now) {
        MobTalkConfig tuning = Tuning.get();
        if (f == null || !f.acquainted() || f.hostile() || f.busy() || f.playerDistance() > tuning.initiativeHearRange) return null;
        boolean close = f.playerDistance() <= tuning.initiativeSpeakRange;
        if (close && f.relationship() >= 0 && f.lastMetMillis() >= 0 && now - f.lastMetMillis() >= tuning.initiativeReturnMinutes * 60_000L) return Trigger.RETURN;
        if (f.blocksChanged() >= BUILDING_BLOCKS && f.relationship() > CombatRules.HATRED) return Trigger.BUILDING;
        if (close && (f.hasThought() || f.openDeal())) return Trigger.CONCERN;
        if (close && !f.weatherChange().isEmpty() && now - f.weatherChangedMillis() <= WEATHER_FRESH_MILLIS) return Trigger.WEATHER;
        if (!f.friendsNearby().isEmpty() && f.friendsNearby().stream().anyMatch(c -> c.distance() <= FRIEND_RANGE)) return Trigger.FRIENDS;
        return null;
    }

    /** The occasion in words for the prompt. */
    public static String reason(Trigger trigger, Facts f) {
        return switch (trigger) {
            case RETURN -> "игрок вернулся к тебе после долгого отсутствия" + (f.lastMetMillis() >= 0 ? " (его не было около " + Math.max(20, (System.currentTimeMillis() - f.lastMetMillis()) / 60_000) + " минут)" : "");
            case BUILDING -> "игрок только что поставил или сломал много блоков рядом с тобой (" + f.blocksChanged() + "): строит, копает или ломает — посмотри, что вышло (блок «Место»), и скажи, что об этом думаешь";
            case CONCERN -> f.openDeal() ? "у тебя с игроком незакрытое дело (услуга, сделка или обещание — см. данные о торге), и он рядом" : "тебя не отпускает твоя последняя мысль, а игрок рядом";
            case WEATHER -> "только что " + (f.weatherChange().isEmpty() ? "переменилась погода" : switch (f.weatherChange()) {
                case "ночь" -> "настала ночь"; case "дождь" -> "пошёл дождь"; case "гроза" -> "началась гроза"; case "рассвет" -> "рассвело"; default -> f.weatherChange(); })
                    + ", и игрок рядом";
            case FRIENDS -> "рядом стоит " + (f.friendsNearby().isEmpty() ? "твой друг" : f.friendsNearby().getFirst().name() + ", твой друг") + "; игрок поблизости, но с вами не говорит";
            case GIFT -> trigger.reason;
        };
    }

    /** The whole occasion for the prompt: why, in what manner, how long. */
    public static String cueText(Cue cue, Verdict verdict) {
        String manner = verdict.reaction() == Reaction.SILENT ? Reaction.REMARK.manner : verdict.reaction().manner;
        String length = verdict.level() <= 0 ? "одно-два слова или одна короткая фраза" : verdict.level() == 1 ? "одна короткая фраза" : "одна-две короткие фразы";
        return "Повод: " + cue.reason() + ". Настрой: " + manner + ". Длина: " + length + "."
                + (cue.trigger() == Trigger.FRIENDS && cue.partner() != null ? " Обращаешься к " + cue.partner().name() + ", не к игроку; игрок лишь слышит." : "");
    }

    /** The budgets: one occasion a minute in a world at most (per the config), one in {@link #MOB_COOLDOWN_MILLIS} per creature. */
    public static final class Budget {
        private long worldLast = -1;
        private final Map<UUID, Long> mobLast = new HashMap<>();
        /** Whether this creature may speak first now, given {@code perMinute} occasions per minute in the world. */
        public boolean allows(UUID mob, int perMinute, long now) {
            long worldInterval = 60_000L / Math.max(1, Math.min(60, perMinute));
            if (worldLast >= 0 && now - worldLast < worldInterval) return false;
            Long last = mobLast.get(mob);
            return last == null || now - last >= Tuning.get().initiativeMobCooldownMinutes * 60_000L;
        }
        /** Spends the budget on this creature. */
        public void spent(UUID mob, long now) { worldLast = now; mobLast.put(mob, now); }
        /** Returns an unused occasion to the world, so a creature that chose silence does not block the next for a minute. */
        public void refund(long now) { if (worldLast == now) worldLast = -1; }
        public void forget(UUID mob) { mobLast.remove(mob); }
        public void clear() { worldLast = -1; mobLast.clear(); }
    }

    /** A scene between creatures: who takes part, how many lines are left, who spoke last and what was said. */
    public static final class Scene {
        public final List<Candidate> participants;
        public int linesLeft;
        public UUID lastSpeaker; public String lastSpeakerName = "", lastLine = "";
        public Scene(List<Candidate> participants, int lines) { this.participants = List.copyOf(participants); this.linesLeft = Math.max(0, lines); }
        /** Everyone but the last speaker, nearest first. */
        public List<Candidate> others() {
            return participants.stream().filter(c -> !c.id().equals(lastSpeaker)).sorted(Comparator.comparingDouble(Candidate::distance)).toList();
        }
        public boolean over() { return linesLeft <= 0; }
    }
}
