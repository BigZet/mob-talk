package io.github.mobtalk.core;

import java.util.List;

/** What a village thinks of the player (docs/mca-social-spec.md §6.2–6.3, adapted): the sum of its residents' relationships with the
 * player plus what its rumours say ({@link VillageRumors.Reputation}), and the rank that follows — from exile to the village head.
 * Ranks need people, not buildings: a guard needs several residents who know the player, the head a village of some size. The rank
 * has consequences: an exile is refused trade, warned and watched by the golem, and told to leave; the prompt colours how villagers
 * meet the player. Pure Java. */
public final class VillageStanding {
    private VillageStanding() { }
    /** Rumours weigh like this many relationship points per unit of their balance × weight (capped at ±{@link #RUMOR_CAP}). */
    public static final int RUMOR_POINTS = 10, RUMOR_CAP = 100;
    public static final int EXILE_POINTS = -150, EXILE_AVERAGE = -40, SUSPECT_POINTS = -40, SUSPECT_AVERAGE = -15;
    public static final int FRIEND_POINTS = 120, GUARD_POINTS = 300, ELDER_POINTS = 600;
    public static final int FRIEND_PEOPLE = 3, GUARD_PEOPLE = 5, ELDER_PEOPLE = 8, ELDER_RESIDENTS = 10;

    public enum Rank {
        EXILE("изгнанник"), SUSPECT("на подозрении"), STRANGER("чужак"), GUEST("гость"), FRIEND("свой"), GUARD("защитник деревни"), ELDER("глава деревни");
        public final String word;
        Rank(String word) { this.word = word; }
    }
    /** The standing: rank, points, how many residents the village has (known to the world memory) and how many know the player. */
    public record View(Rank rank, int points, int residents, int acquainted, int average) {
        public static final View NONE = new View(Rank.STRANGER, 0, 0, 0, 0);
        /** An exile gets no trade. */
        public boolean refusesTrade() { return rank == Rank.EXILE; }
        /** The golem keeps an eye on a suspect and an exile. */
        public boolean golemAlert() { return rank == Rank.EXILE || rank == Rank.SUSPECT; }
        /** Villagers warn a suspect; an exile is told to leave. */
        public boolean warns() { return rank == Rank.SUSPECT || rank == Rank.EXILE; }
        public boolean banished() { return rank == Rank.EXILE; }
        /** The same standing with another rank (the god's part and the bell assembly decide it, see {@link DivineTidings}). */
        public View as(Rank other) { return new View(other, points, residents, acquainted, average); }
    }

    /** {@code scores}: each resident's relationship with the player, for those who know them; {@code residents}: all known residents. */
    public static View of(List<Integer> scores, int residents, VillageRumors.Reputation rumors) {
        int sum = 0;
        for (int score : scores) sum += score;
        int acquainted = scores.size();
        int rumor = rumors == null || !rumors.known() ? 0 : (int) Math.round(Math.max(-RUMOR_CAP, Math.min(RUMOR_CAP, rumors.balance() * Math.min(rumors.weight(), 10) * RUMOR_POINTS)));
        int points = sum + rumor;
        int average = acquainted == 0 ? 0 : Math.round(sum / (float) acquainted);
        Rank rank;
        MobTalkConfig tuning = Tuning.get(); // The point thresholds are settings; the constants above are their defaults.
        if (points <= tuning.villageExilePoints || acquainted >= 2 && average <= EXILE_AVERAGE) rank = Rank.EXILE;
        else if (points < tuning.villageSuspectPoints || acquainted >= 1 && average < SUSPECT_AVERAGE) rank = Rank.SUSPECT;
        else if (points >= tuning.villageElderPoints && acquainted >= ELDER_PEOPLE && residents >= ELDER_RESIDENTS) rank = Rank.ELDER;
        else if (points >= tuning.villageGuardPoints && acquainted >= GUARD_PEOPLE) rank = Rank.GUARD;
        else if (points >= tuning.villageFriendPoints && acquainted >= FRIEND_PEOPLE) rank = Rank.FRIEND;
        else if (points >= 0 && acquainted >= 1) rank = Rank.GUEST;
        else rank = Rank.STRANGER;
        return new View(rank, points, residents, acquainted, average);
    }

    /** The prompt line: how the village sees the player and how to meet them; "" for a stranger. */
    public static String describe(View view) {
        if (view == null) return "";
        return switch (view.rank()) {
            case EXILE -> "деревня его изгнала: ты с ним не торгуешь и гонишь его словами — пусть убирается, пока голем не взялся";
            case SUSPECT -> "он на подозрении у деревни: держишься настороже и можешь предупредить, что за ним следят";
            case GUEST -> "он здесь гость: встречаешь как знакомого";
            case FRIEND -> "он в деревне свой: встречаешь тепло";
            case GUARD -> "он защитник деревни: рядом с ним спокойнее, говоришь с уважением";
            case ELDER -> "он глава деревни: к его слову прислушиваются, можешь звать его старостой";
            case STRANGER -> "";
        };
    }
}
