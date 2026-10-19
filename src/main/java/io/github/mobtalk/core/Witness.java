package io.github.mobtalk.core;

/** What a bystander can catch the player at, how far away it still notices, and what it costs the player with that bystander:
 * a blow or a killing is seen ({@link MobTalkConfig#witnessSightRange}), an insult or a death threat is heard
 * ({@link MobTalkConfig#witnessHearingRange}); each drop is its own setting. */
public enum Witness {
    HIT("ударил", true), KILL("убил", true), INSULT("оскорбил", false), THREAT("грозил убить", false);

    /** The verb of the memory line: «Видел, как игрок ударил Марфу (корова)». */
    public final String verb;
    private final boolean seen;
    Witness(String verb, boolean seen) { this.verb = verb; this.seen = seen; }

    /** Blocks around the victim within which a mob with a profile notices. */
    public double range(MobTalkConfig config) { return seen ? config.witnessSightRange : config.witnessHearingRange; }
    /** The relationship drop for a witness, as a positive number. */
    public int penalty(MobTalkConfig config) {
        return switch (this) {
            case HIT -> config.witnessHitPenalty;
            case KILL -> config.witnessKillPenalty;
            case INSULT -> config.witnessInsultPenalty;
            case THREAT -> config.witnessThreatPenalty;
        };
    }
    /** The words of a reply that bystanders take personally; null for any other. */
    public static Witness of(SocialIntent social) {
        return social == SocialIntent.THREAT ? THREAT : social == SocialIntent.INSULT ? INSULT : null;
    }
}
