package io.github.mobtalk.core;

import java.util.*;

/** What the social layer adds to a villager's profile: sex, character and inborn marks, and — for a child born into the family tree —
 * the world tick of its birth and its growth stage as the game last set it. A villager met in the world gets them derived from its
 * UUID, so the same villager has the same sex and character before and after anyone talked to it. */
public final class SocialProfile {
    String gender = "", persona = "";
    List<String> traits = new ArrayList<>();
    /** World tick of birth for a child of a couple, −1 for a villager met grown (or a vanilla baby). */
    long bornTick = -1;
    /** {@link ChildGrowth.Stage} by name, "" until the game set one. */
    String stage = "";
    /** A child on the way with the player: −1 for none, 0 when conceived and the game has not set the day yet, else the birth tick. */
    long expectingTick = -1;
    /** The other parent of the child on the way (the player's UUID). */
    String expectingWith = "";
    /** Трек N: беременность. Тик зачатия (−1 — нет; при {@code expectingTick == 0} пара ещё старается), длина срока на момент
     * зачатия (игровые дни), кто носит ({@link Pregnancy.Carrier}), что игра уже сказала (0 — ничего, 1 — узнали, 2 — середина,
     * 3 — поздний срок, 4 — роды начались), период и день, как игра их последний раз поставила, и день срока последнего желания. */
    long conceivedTick = -1;
    int pregnancyDays, pregnancyNews, pregnancyDay;
    String carrier = "", pregnancyTerm = "";
    int cravingDay = -1;
    /** {@link VoiceAge} by name, "" until the villager's voice was first cast; villagers never age. */
    String age = "";
    /** Решено ли, меркантильна ли она ({@link Mercantile#roll}): раз в жизни, при первой встрече после включения. */
    boolean mercRolled;

    /** The deterministic sex, character and marks of a villager met in the world. */
    static SocialProfile of(UUID id, boolean child) {
        Random random = new Random(id.getMostSignificantBits() * 31 + id.getLeastSignificantBits());
        SocialProfile social = new SocialProfile();
        social.gender = (random.nextBoolean() ? Gender.MALE : Gender.FEMALE).name();
        social.persona = Persona.pick(random, child).name();
        for (VillagerTrait trait : VillagerTrait.roll(random)) social.traits.add(trait.name());
        return social;
    }
    /** The sex of a villager without a profile yet: the same answer {@link #of} would give. */
    public static Gender genderOf(UUID id) { return of(id, false).gender(); }
    /** Потянет ли жителя без профиля к игроку этого пола ({@link Romance#drawn}); для игрового самотеста свадьбы. */
    public static boolean drawnTo(UUID id, Gender player) { SocialProfile s = of(id, false); return Romance.drawn(s.gender(), s.traits(), player); }

    public Gender gender() { Gender g = Gender.of(gender); return g == null ? Gender.MALE : g; }
    public Persona persona() { Persona p = Persona.of(persona); return p == null ? Persona.FRIENDLY : p; }
    public Set<VillagerTrait> traits() {
        Set<VillagerTrait> set = EnumSet.noneOf(VillagerTrait.class);
        for (String name : traits) { VillagerTrait trait = VillagerTrait.of(name); if (trait != null) set.add(trait); }
        return set;
    }
    public boolean has(VillagerTrait trait) { return traits.contains(trait.name()); }
    /** How old the villager sounds, null before its voice was first cast. */
    public VoiceAge age() { return VoiceAge.of(age); }

    /** Малыш родился: ни ожидания, ни беременности. */
    void clearExpecting() {
        expectingTick = -1; expectingWith = ""; conceivedTick = -1; pregnancyDays = 0; pregnancyNews = 0; pregnancyDay = 0;
        carrier = ""; pregnancyTerm = ""; cravingDay = -1;
    }
    void sanitize() {
        if (Gender.of(gender) == null) gender = Gender.MALE.name();
        if (Persona.of(persona) == null) persona = Persona.FRIENDLY.name();
        if (traits == null) traits = new ArrayList<>();
        traits.removeIf(name -> VillagerTrait.of(name) == null);
        if (stage == null) stage = "";
        if (expectingWith == null) expectingWith = "";
        if (expectingTick < -1) expectingTick = -1;
        if (age == null || VoiceAge.of(age) == null) age = "";
        if (carrier == null) carrier = "";
        if (pregnancyTerm == null) pregnancyTerm = "";
        if (conceivedTick < -1) conceivedTick = -1;
    }
    SocialProfile copy() {
        SocialProfile c = new SocialProfile(); c.gender = gender; c.persona = persona; c.traits = new ArrayList<>(traits); c.bornTick = bornTick; c.stage = stage;
        c.expectingTick = expectingTick; c.expectingWith = expectingWith; c.age = age;
        c.conceivedTick = conceivedTick; c.pregnancyDays = pregnancyDays; c.pregnancyNews = pregnancyNews; c.pregnancyDay = pregnancyDay;
        c.carrier = carrier; c.pregnancyTerm = pregnancyTerm; c.cravingDay = cravingDay; c.mercRolled = mercRolled;
        return c;
    }
}
