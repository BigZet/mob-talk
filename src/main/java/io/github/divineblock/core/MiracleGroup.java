package io.github.divineblock.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The god's acts in groups by strength, agreed with the author: Jev (or the rules) picks a group, and the act inside it is
 * drawn at random, never one of the last few done to this mortal. Escalation opens the heavier groups: a first insult opens
 * К1–К3, repeated insults and sins open К4, and К5 — the end of the world — waits for the third blasphemy or a damned soul. */
public enum MiracleGroup {
    K1(Miracle.Kind.WRATH, "знак гнева", "молния в грешника, проклятие голода и слабости, проклятие предмета",
            Miracle.LIGHTNING, Miracle.CURSE_MINOR, Miracle.CURSE_TOOL),
    K2(Miracle.Kind.WRATH, "казни египетские", "рой насекомых и фантомов, ядовитый дождь, дождь наковален, кольцо огня, золото и алмазы в мусор",
            Miracle.SWARM, Miracle.ACID_RAIN, Miracle.ANVIL_RAIN, Miracle.FIRE_RING, Miracle.TRANSMUTE),
    K3(Miracle.Kind.WRATH, "гнев земли и неба", "метеориты, землетрясение, провал земли, горящий динамит с неба, смерч",
            Miracle.METEOR, Miracle.EARTHQUAKE, Miracle.SINKHOLE, Miracle.TNT_RAIN, Miracle.TORNADO),
    K4(Miracle.Kind.WRATH, "воинство тьмы", "орда нежити, разбойники с разорителями, всадники-скелеты, ифриты, Хранитель",
            Miracle.UNDEAD_HORDE, Miracle.RAID, Miracle.SKELETON_RIDERS, Miracle.BLAZE_HORDE, Miracle.WARDEN),
    /** The god's terrible servants: one sacred boss, at random among those not sent lately. */
    KS(Miracle.Kind.WRATH, "святые стражи", "босс: Чёрный Схимник, Звонарь погоста, Шестикрылый серафим, Безликий лик, Плакальщица погоста, "
            + "Левиафан, Бегемот, Голиаф, Ангел-губитель, Херувим с пламенным мечом, Нимрод",
            Miracle.BOSS_SCHEMNIK, Miracle.BOSS_BELL_RINGER, Miracle.BOSS_SERAPH, Miracle.BOSS_FACELESS, Miracle.BOSS_WAILER,
            Miracle.BOSS_LEVIATHAN, Miracle.BOSS_BEHEMOTH, Miracle.BOSS_GOLIATH, Miracle.BOSS_DESTROYING_ANGEL, Miracle.BOSS_CHERUB, Miracle.BOSS_NIMROD),
    K5(Miracle.Kind.WRATH, "конец света", "изгнание в Ад или Край, визеры, великая воронка, драконы, разломы до бездны, вырезание земли, метеоритный дождь, Архангел гнева, Четыре всадника, Зверь из бездны, сам Бог с булавой",
            Miracle.HELL_EXILE, Miracle.END_EXILE, Miracle.WITHER, Miracle.GREAT_CRATER, Miracle.DRAGON, Miracle.RIFTS, Miracle.CHUNK_VOID, Miracle.METEOR_SHOWER,
            Miracle.BOSS_ARCHANGEL, Miracle.BOSS_HORSEMEN, Miracle.BOSS_BEAST, Miracle.GOD_MACE),
    B1(Miracle.Kind.HELP, "малая милость", "исцеление, еда, ясное небо, починка",
            Miracle.HEAL, Miracle.FEED, Miracle.WEATHER_CLEAR, Miracle.REPAIR),
    B2(Miracle.Kind.HELP, "дары", "предмет по просьбе, зачарование, жила руды, мгновенный урожай",
            Miracle.GIFT_ITEM, Miracle.ENCHANT, Miracle.ORE_VEIN, Miracle.GROW),
    B3(Miracle.Kind.HELP, "покровительство", "силы и скорость, страж-волк или голем, молнии по врагам, перенос домой",
            Miracle.BUFF, Miracle.GUARDIAN, Miracle.SMITE_ENEMIES, Miracle.SAFE_HOME),
    B4(Miracle.Kind.HELP, "великое благословение", "тотем бессмертия, благословение на целый день",
            Miracle.TOTEM, Miracle.BLESSING),
    J1(Miracle.Kind.JEST, "лёгкая шутка", "куры или рыба с неба, перемешанный хотбар, лунная гравитация, круговорот дней, радужные овцы, шипение крипера",
            Miracle.CHICKEN_RAIN, Miracle.SWAP_ITEMS, Miracle.MOON_GRAVITY, Miracle.TIME_RUSH, Miracle.SHEEP_PARTY, Miracle.FISH_RAIN, Miracle.CREEPER_PRANK),
    J2(Miracle.Kind.JEST, "злая шутка", "мир вверх ногами, телепорт куда попало, тыква на голову, подъём в воздух, небесный хмель",
            Miracle.FLIP, Miracle.RANDOM_TP, Miracle.PUMPKIN_HEAD, Miracle.LEVITATE, Miracle.DIZZY);

    public final Miracle.Kind kind;
    public final String label, description;
    public final List<Miracle> members;

    MiracleGroup(Miracle.Kind kind, String label, String description, Miracle... members) {
        this.kind = kind; this.label = label; this.description = description; this.members = List.of(members);
    }

    public String id() { return name(); }
    public static MiracleGroup byId(String id) {
        for (MiracleGroup group : values()) if (group.name().equalsIgnoreCase(id == null ? "" : id.strip())) return group;
        return null;
    }

    /** The acts of this group the settings allow at the verdict's budget. */
    public List<Miracle> allowed(Verdict verdict, DivineConfig config) {
        List<Miracle> out = new ArrayList<>();
        for (Miracle miracle : members) if (config.allows(miracle) && miracle.minTier <= Math.max(verdict.budget, miracle.kind == Miracle.Kind.OMEN ? 0 : 1)) out.add(miracle);
        return out;
    }

    /** The groups this verdict has opened, lightest first: the escalation rules, not a choice. */
    public static List<MiracleGroup> open(Verdict verdict, DivineConfig config) {
        int level = verdict.level();
        List<MiracleGroup> out = new ArrayList<>();
        for (MiracleGroup group : values()) {
            if (group.kind != verdict.inclination) continue;
            boolean opened = switch (group) {
                case K1 -> true;
                case K2 -> level >= 2 || verdict.fury;
                case K3 -> level >= 3 || verdict.fury;
                case K4 -> level >= 6 || verdict.fury && verdict.blasphemiesBefore >= 1;
                case KS -> level >= 7 || verdict.fury && verdict.blasphemiesBefore >= 1;
                case K5 -> config.maxTier >= 4 && (verdict.blasphemiesBefore >= 2 || verdict.damned || level >= 9);
                case B1, J1 -> true;
                case B2, J2 -> level >= 2;
                case B3 -> level >= 3;
                case B4 -> level >= 4 && config.maxTier >= 3;
            };
            // Mercy and jests may answer exactly what was asked, whatever the level: "дай алмазов" opens the gifts.
            if (!opened && group.kind != Miracle.Kind.WRATH && group.allowed(verdict, config).stream().anyMatch(m -> m.hintMatches(verdict.prayer) > 0)) opened = true;
            if (opened && !group.allowed(verdict, config).isEmpty()) out.add(group);
        }
        return out;
    }

    /** One act of the group: what the mortal asked for by name if it is here, else at random, never one done to him lately. */
    public Miracle draw(Verdict verdict, DivineConfig config, Random random) {
        List<Miracle> allowed = allowed(verdict, config);
        for (Miracle miracle : allowed) if (miracle.kind != Miracle.Kind.WRATH && miracle.hintMatches(verdict.prayer) > 0) return miracle;
        List<Miracle> fresh = new ArrayList<>(allowed);
        if (verdict.recentActs != null) fresh.removeIf(miracle -> verdict.recentActs.contains(miracle.id()));
        List<Miracle> pool = fresh.isEmpty() ? allowed : fresh;
        return pool.get(random.nextInt(pool.size()));
    }
}
