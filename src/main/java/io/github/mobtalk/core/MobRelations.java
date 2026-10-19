package io.github.mobtalk.core;

import java.util.*;

/** What the creatures of one world think of each other: for every mob with a profile, up to {@link #MAX_BONDS} directed bonds
 * to other mobs, each a score of -100..100, the last meeting, and a few facts (one village, one species, grief for the dead).
 * Bonds grow out of the game alone — standing together, blows, a death seen — never out of the model. A score fades
 * towards nothing while the two do not meet (half in {@link #BOND_HALF_LIFE}); grief lasts {@link #GRIEF_DURATION}. Pure
 * Java, persisted inside the world memory; every mutation runs under the store lock. The directed edges are the ground
 * for village rumours and mob-to-mob talk later: who is whose friend, who mourns whom, who keeps away from whom. */
public final class MobRelations {
    /** Bonds per mob; the weakest makes room for a stronger newcomer. */
    public static final int MAX_BONDS = 12;
    /** From this score the other is a friend; at or below {@link #RIVAL} an enemy. */
    public static final int FRIEND = 40, RIVAL = -40;
    /** Simply being together never takes a bond above this: friendship beyond it would need more than proximity. */
    public static final int SYMPATHY_CAP = 60;
    /** Where a bond starts with a neighbour of the same village, and with one of the same species. */
    public static final int NEIGHBOR_START = 10, KIN_START = 5;
    /** A blow between two creatures: what the victim loses towards the attacker, and the attacker towards the victim. */
    public static final int ATTACKED_DROP = -45, ATTACKER_DROP = -15;
    /** Standing within reach: one point per {@link #TOGETHER_INTERVAL}; being around a conversation: two per {@link #MET_INTERVAL}. */
    public static final int TOGETHER_GAIN = 1, MET_GAIN = 2;
    public static final long TOGETHER_INTERVAL = 30_000L, MET_INTERVAL = 60_000L;
    /** Half of a score is gone after this long without a meeting (real time). */
    public static final long BOND_HALF_LIFE = 6 * 60 * 60_000L;
    /** Grief for the dead: three Minecraft days of real time. */
    public static final long GRIEF_DURATION = 3 * 20 * 60_000L;

    /** Why two creatures belong together without having met: one village (neighbours: villagers and golems), one species, or nothing. */
    public enum Kinship { NONE, KIN, NEIGHBOR }
    /** The tags a bond may carry: FRIEND and RIVAL follow the score, the others are facts stored with the bond. */
    public enum Tag {
        FRIEND("друг"), RIVAL("соперник"), KIN("сородич"), NEIGHBOR("сосед"), MOURNS("скорбит");
        public final String word;
        Tag(String word) { this.word = word; }
    }

    /** One village makes neighbours; one species makes kin; anything else is nothing. Blood and marriage live in {@link FamilyTree}. */
    public static Kinship kinship(String typeA, String villageA, String typeB, String villageB) {
        if (villageA != null && !villageA.isBlank() && villageA.equals(villageB)) return Kinship.NEIGHBOR;
        return typeA != null && typeA.equals(typeB) ? Kinship.KIN : Kinship.NONE;
    }

    /** A detached picture of one bond at some moment. */
    public record View(UUID other, String name, String type, int score, long lastMet, Set<Tag> tags, boolean consoled) {
        public boolean friend() { return tags.contains(Tag.FRIEND); }
        public boolean rival() { return tags.contains(Tag.RIVAL); }
        public boolean mourns() { return tags.contains(Tag.MOURNS); }
        /** How much this bond matters: grief and family hold on, a faded acquaintance goes first. */
        public int strength() { return Math.abs(score) + (mourns() ? 50 : 0) + (tags.contains(Tag.NEIGHBOR) ? 5 : 0); }
        /** « Аксинья +52 [семья, друг]» for the debug line. */
        public String label() {
            List<String> words = tags.stream().sorted().map(tag -> tag.word).toList();
            return name + " " + (score >= 0 ? "+" : "") + score + (words.isEmpty() ? "" : " [" + String.join(", ", words) + "]");
        }
        /** The line the model reads: who the other is to this creature, in words, never numbers. */
        public String describe(boolean present) {
            String species = SpeciesCatalog.get(type == null ? "" : type).name().toLowerCase(Locale.ROOT);
            String who = name + " (" + species + ")";
            String family = tags.contains(Tag.NEIGHBOR) ? "сосед по деревне" : tags.contains(Tag.KIN) ? "сородич" : "";
            String text;
            if (mourns()) text = who + " погиб на твоих глазах; ты скорбишь по нему" + (consoled ? ", друг приходил посидеть с тобой" : "");
            else if (score >= 70) text = who + " — близкий друг" + (family.isEmpty() ? "" : ", " + family);
            else if (score >= FRIEND) text = who + " — друг" + (family.isEmpty() ? "" : ", " + family);
            else if (score >= 15) text = who + " — " + (family.isEmpty() ? "приятель, ладите" : family + ", ладите");
            else if (score > -15) text = who + " — " + (family.isEmpty() ? "знакомый" : family);
            else if (score > RIVAL) text = who + " — недолюбливаешь, держишься настороже";
            else text = who + " — враг, ты его сторонишься";
            return text + (present ? " (сейчас рядом)" : "");
        }
    }

    /** One directed bond on disk. */
    static final class Bond {
        String other, name = "", type = "";
        int score; long touched, lastMet, since;
        /** When the grief began and when a friend came, -1 for never. */
        long mourningSince = -1, consoledAt = -1;
        Set<String> tags = new LinkedHashSet<>();
        /** The score now: it halves every {@link #BOND_HALF_LIFE} since it was last touched. */
        int scoreAt(long now) {
            long elapsed = Math.max(0, now - touched);
            return (int) Math.round(score * Math.pow(0.5, elapsed / (double) (Tuning.get().bondHalfLifeHours * 60 * 60_000L)));
        }
        void settle(long now) { score = scoreAt(now); touched = now; }
        boolean mourning(long now) { return mourningSince >= 0 && now >= mourningSince && now - mourningSince < Tuning.get().griefMinutes * 60_000L; }
        View view(long now) {
            Set<Tag> all = EnumSet.noneOf(Tag.class);
            int current = scoreAt(now);
            if (current >= FRIEND) all.add(Tag.FRIEND);
            if (current <= RIVAL) all.add(Tag.RIVAL);
            if (tags.contains(Tag.KIN.name())) all.add(Tag.KIN);
            if (tags.contains(Tag.NEIGHBOR.name())) all.add(Tag.NEIGHBOR);
            if (mourning(now)) all.add(Tag.MOURNS);
            UUID id;
            try { id = UUID.fromString(other); } catch (IllegalArgumentException invalid) { id = new UUID(0, 0); }
            return new View(id, name, type, current, lastMet, Collections.unmodifiableSet(all), consoledAt >= 0);
        }
        Bond copy() {
            Bond c = new Bond(); c.other = other; c.name = name; c.type = type; c.score = score; c.touched = touched; c.lastMet = lastMet;
            c.since = since; c.mourningSince = mourningSince; c.consoledAt = consoledAt; c.tags = new LinkedHashSet<>(tags); return c;
        }
    }

    Map<String, List<Bond>> bonds = new LinkedHashMap<>();

    /** The bonds of one mob, strongest first; empty for a mob that knows nobody. */
    public List<View> views(UUID mob, long now) {
        List<Bond> own = mob == null ? null : bonds.get(mob.toString());
        if (own == null || own.isEmpty()) return List.of();
        return own.stream().map(bond -> bond.view(now)).sorted(Comparator.comparingInt(View::strength).reversed()).toList();
    }
    /** The block the model reads: at most {@code limit} strongest bonds in words; {@code present} marks who stands nearby. */
    public static List<String> lines(List<View> views, Set<UUID> present, int limit) {
        if (views == null || views.isEmpty()) return List.of();
        return views.stream().limit(Math.max(0, limit)).map(view -> view.describe(present != null && present.contains(view.other()))).toList();
    }

    /** Two creatures side by side: both warm to each other by {@code gain}, at most once per {@code interval} per pair and never
     * past {@link #SYMPATHY_CAP}; a new bond starts where the kinship says. Returns whether anything changed. */
    public boolean together(UUID a, String nameA, String typeA, UUID b, String nameB, String typeB, Kinship kinship, int gain, long interval, long now) {
        if (a == null || b == null || a.equals(b)) return false;
        // Enemies do not warm up by standing next to each other, on either side; only time wears a feud down.
        Bond ab = find(a, b), ba = find(b, a);
        if (ab != null && ab.scoreAt(now) <= RIVAL || ba != null && ba.scoreAt(now) <= RIVAL) return false;
        boolean changed = warm(a, b, nameB, typeB, kinship, gain, interval, now);
        changed |= warm(b, a, nameA, typeA, kinship, gain, interval, now);
        return changed;
    }
    private boolean warm(UUID self, UUID other, String otherName, String otherType, Kinship kinship, int gain, long interval, long now) {
        Bond bond = find(self, other);
        boolean fresh = bond == null;
        if (fresh) {
            bond = create(self, other, otherName, otherType, now, kinship == Kinship.NEIGHBOR ? NEIGHBOR_START : kinship == Kinship.KIN ? KIN_START : 0);
            if (bond == null) return false;
        } else {
            if (now >= bond.lastMet && now - bond.lastMet < interval) return false;
            bond.settle(now);
            if (bond.score < SYMPATHY_CAP) bond.score = Math.min(SYMPATHY_CAP, bond.score + gain);
            name(bond, otherName, otherType);
        }
        if (kinship == Kinship.NEIGHBOR) bond.tags.add(Tag.NEIGHBOR.name());
        if (kinship == Kinship.KIN) bond.tags.add(Tag.KIN.name());
        bond.lastMet = now;
        return true;
    }

    /** One creature struck another: the victim's bond to the attacker drops hard, the attacker's to the victim a little. */
    public boolean attacked(UUID attacker, String attackerName, String attackerType, UUID victim, String victimName, String victimType, long now) {
        if (attacker == null || victim == null || attacker.equals(victim)) return false;
        boolean changed = drop(victim, attacker, attackerName, attackerType, ATTACKED_DROP, now);
        changed |= drop(attacker, victim, victimName, victimType, ATTACKER_DROP, now);
        return changed;
    }
    private boolean drop(UUID self, UUID other, String otherName, String otherType, int delta, long now) {
        Bond bond = find(self, other);
        if (bond == null) bond = create(self, other, otherName, otherType, now, delta);
        else { bond.settle(now); name(bond, otherName, otherType); bond.score = Math.max(-100, Math.min(bond.score, 0) + delta); }
        if (bond == null) return false;
        bond.lastMet = now;
        return true;
    }

    /** A creature saw {@code dead} die. It mourns only someone it knew: a bond it did not hold against them, or a creature with a
     * life story of its own. The name is kept with the bond, so the grief survives the dead one's profile. */
    public boolean mourn(UUID witness, UUID dead, String deadName, String deadType, boolean acquainted, long now) {
        if (witness == null || dead == null || witness.equals(dead)) return false;
        Bond bond = find(witness, dead);
        if (bond == null) {
            if (!acquainted) return false;
            bond = create(witness, dead, deadName, deadType, now, 0);
            if (bond == null) return false;
        } else if (bond.scoreAt(now) < 0 && !acquainted) return false;
        else name(bond, deadName, deadType);
        if (bond.mourning(now)) return false;
        bond.mourningSince = now; bond.consoledAt = -1; bond.lastMet = now;
        return true;
    }
    /** A friend came and sat with the mourner: once per loss. */
    public boolean consoled(UUID mourner, UUID dead, long now) {
        Bond bond = find(mourner, dead);
        if (bond == null || !bond.mourning(now) || bond.consoledAt >= 0) return false;
        bond.consoledAt = now;
        return true;
    }
    /** The loss this creature still grieves and nobody has come to share yet, or null. */
    public View unconsoledGrief(UUID mob, long now) {
        for (View view : views(mob, now)) if (view.mourns() && !view.consoled()) return view;
        return null;
    }

    /** Drops everything this mob thought of others; what others thought of it stays (a name to mourn). */
    public void remove(UUID mob) { if (mob != null) bonds.remove(mob.toString()); }

    private Bond find(UUID self, UUID other) {
        List<Bond> own = bonds.get(self.toString());
        if (own == null) return null;
        String key = other.toString();
        for (Bond bond : own) if (key.equals(bond.other)) return bond;
        return null;
    }
    /** A new bond, evicting the weakest old one when the list is full; null when the newcomer would be the weakest of all. */
    private Bond create(UUID self, UUID other, String otherName, String otherType, long now, int score) {
        List<Bond> own = bonds.computeIfAbsent(self.toString(), ignored -> new ArrayList<>());
        if (own.size() >= MAX_BONDS) {
            Bond weakest = null; int least = Integer.MAX_VALUE;
            for (Bond bond : own) { int strength = bond.view(now).strength(); if (strength < least) { least = strength; weakest = bond; } }
            if (weakest == null || least > Math.max(3, Math.abs(score))) return null;
            own.remove(weakest);
        }
        Bond bond = new Bond(); bond.other = other.toString(); bond.score = score; bond.touched = now; bond.since = now;
        name(bond, otherName, otherType);
        own.add(bond);
        return bond;
    }
    private static void name(Bond bond, String name, String type) {
        if (name != null && !name.isBlank()) bond.name = TextBounds.characters(name, 60);
        if (type != null && !type.isBlank()) bond.type = type;
    }

    void sanitize() {
        if (bonds == null) bonds = new LinkedHashMap<>();
        bonds.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        for (List<Bond> own : bonds.values()) {
            own.removeIf(bond -> bond == null || bond.other == null || bond.other.isBlank());
            for (Bond bond : own) {
                bond.score = Math.max(-100, Math.min(100, bond.score));
                if (bond.name == null) bond.name = ""; if (bond.type == null) bond.type = "";
                if (bond.mourningSince < 0) bond.mourningSince = -1; if (bond.consoledAt < 0) bond.consoledAt = -1;
                if (bond.tags == null) bond.tags = new LinkedHashSet<>();
                // "FAMILY" meant "one village" before real families existed: such bonds are neighbours.
                if (bond.tags.remove("FAMILY")) bond.tags.add(Tag.NEIGHBOR.name());
                bond.tags.removeIf(tag -> tag == null || !(tag.equals(Tag.KIN.name()) || tag.equals(Tag.NEIGHBOR.name())));
            }
            while (own.size() > MAX_BONDS) own.removeFirst();
        }
        bonds.values().removeIf(List::isEmpty);
    }
    MobRelations copy() {
        MobRelations c = new MobRelations();
        bonds.forEach((key, own) -> { List<Bond> list = new ArrayList<>(own.size()); for (Bond bond : own) list.add(bond.copy()); c.bonds.put(key, list); });
        return c;
    }
}
