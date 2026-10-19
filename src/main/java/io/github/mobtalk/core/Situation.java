package io.github.mobtalk.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A sober estimate of who would win a fight, worked out from the snapshot before the model answers: the creature's own
 * strength and health, the player's weapon and armour, allies in sight, and what this player already did to it. The model
 * reads it as fact, so a villager facing a diamond sword stops bluffing and a wolf pack stops cringing. Pure: no Minecraft. */
public final class Situation {
    /** How a fight with the player would go for the creature. */
    public enum Verdict { DOOMED, OUTMATCHED, EVEN, STRONGER }
    /** Species that no gear makes an even match. */
    public static final Set<String> BOSSES = Set.of("minecraft:ender_dragon", "minecraft:wither", "minecraft:warden", "minecraft:elder_guardian");
    /** Allies of the same species count only this close. */
    public static final double ALLY_RANGE = 10;

    /** One side of a possible fight. {@code weapon} and {@code armor} are scores from {@link #weaponScore} and {@link #armorScore}
     * (for a creature, its attack damage halved); a non-fighter has no attack worth the name. */
    public record Combatant(double health, double maxHealth, double weapon, double armor, boolean fighter, boolean boss, int allies) {
        double fraction() { return maxHealth <= 0 ? 1 : Math.max(0, Math.min(1, health / maxHealth)); }
        /** A rough fighting strength: gear and body, scaled by how hurt the side is. */
        double power() {
            if (boss) return 40;
            // A creature that cannot fight back is outmatched by bare fists already; any weapon makes that doom (see verdict).
            double body = fighter ? 1 + Math.min(10, maxHealth / 10) + weapon : 0.5 * (1 + Math.min(10, maxHealth / 10));
            return (body + armor * 0.6) * (0.4 + 0.6 * fraction()) + (fighter ? allies : 0);
        }
    }

    private final Combatant mob, player;
    private final Verdict verdict;
    private final String playerWeapon, playerArmor;
    private final int hits, witnessed, kills, calm, confidence;
    private final boolean trapped;
    private final int relationship;
    private final boolean hostile, threat, creative;

    private Situation(Combatant mob, Combatant player, String playerWeapon, String playerArmor, int hits, int witnessed, int relationship,
                      boolean hostile, boolean threat, boolean creative, int kills, int calm, int confidence, boolean trapped) {
        this.mob = mob; this.player = player; this.verdict = creative ? Verdict.DOOMED : verdict(mob, player);
        this.playerWeapon = playerWeapon; this.playerArmor = playerArmor; this.hits = hits; this.witnessed = witnessed;
        this.relationship = relationship; this.hostile = hostile; this.threat = threat; this.creative = creative;
        this.kills = kills; this.calm = calm; this.confidence = confidence; this.trapped = trapped;
    }

    public Verdict verdict() { return verdict; }

    /** Who would win: a creature that cannot fight at all is doomed against anyone armed, otherwise the powers are compared. */
    public static Verdict verdict(Combatant mob, Combatant player) {
        if (mob.boss()) return Verdict.STRONGER;
        if (!mob.fighter() && player.weapon() > FISTS) return Verdict.DOOMED;
        double ratio = mob.power() / Math.max(0.1, player.power());
        if (ratio < 0.45) return Verdict.DOOMED;
        if (ratio < 0.85) return Verdict.OUTMATCHED;
        if (ratio < 1.5) return Verdict.EVEN;
        return Verdict.STRONGER;
    }

    /** Bare hands still hit: the weapon score of an empty hand. */
    public static final double FISTS = 0.5;
    /** How dangerous a held item is, 0 for anything that is not a weapon. */
    public static double weaponScore(String itemId) {
        String id = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        if (id.endsWith("mace")) return 5;
        if (id.endsWith("trident")) return 4;
        if (id.endsWith("bow")) return id.endsWith("crossbow") ? 3.5 : 3;
        if (!id.endsWith("sword") && !id.endsWith("_axe")) return 0;
        return material(id, 1.5, 2, 2, 3, 4, 5);
    }
    /** How much one worn piece protects, 0 for anything that is not armour. */
    public static double armorScore(String itemId) {
        String id = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        if (id.endsWith("turtle_helmet")) return 0.75;
        if (!id.endsWith("helmet") && !id.endsWith("chestplate") && !id.endsWith("leggings") && !id.endsWith("boots")) return 0;
        return material(id, 0.5, 0.75, 0.75, 1, 1.5, 2);
    }
    private static double material(String id, double wood, double stone, double gold, double iron, double diamond, double netherite) {
        if (id.contains("netherite")) return netherite;
        if (id.contains("diamond")) return diamond;
        if (id.contains("iron") || id.contains("chainmail")) return iron;
        if (id.contains("golden") || id.contains("stone")) return id.contains("golden") ? gold : stone;
        return wood;
    }

    /** The estimate for this snapshot and profile: the player's gear from playerDetails.equipment, the creature's body from
     * mobDetails (fighter, attackDamage), allies from observedEntities, history from the profile. */
    public static Situation of(MobSnapshot snapshot, ConversationStore.Profile profile) {
        Map<String,Object> details = snapshot.mobDetails(), player = snapshot.playerDetails();
        String type = snapshot.type() == null ? "" : snapshot.type();
        boolean fighter = Boolean.TRUE.equals(details.get("fighter"));
        double attack = details.get("attackDamage") instanceof Number damage ? damage.doubleValue() : 0;
        int allies = 0;
        for (ObservedEntity entity : snapshot.observedEntities())
            if (entity.visible() && type.equals(entity.type()) && entity.distance() <= ALLY_RANGE) allies++;
        Combatant mob = new Combatant(snapshot.health(), snapshot.maxHealth(), attack / 2, 0, fighter, BOSSES.contains(type), allies);
        String weaponId = itemId(player, "mainHand"), weaponName = itemName(player, "mainHand");
        double weapon = Math.max(FISTS, Math.max(weaponScore(weaponId), weaponScore(itemId(player, "offHand"))));
        if (weaponScore(itemId(player, "offHand")) > weaponScore(weaponId)) weaponName = itemName(player, "offHand");
        double armor = 0; int pieces = 0; String best = "";
        if (player.get("equipment") instanceof Map<?,?> equipment)
            for (String slot : List.of("head", "chest", "legs", "feet")) {
                if (!(equipment.get(slot) instanceof Map<?,?> item && item.get("id") instanceof String id)) continue;
                double score = armorScore(id);
                if (score <= 0) continue;
                armor += score; pieces++;
                if (score >= armorScore(best)) best = id;
            }
        double health = player.get("health") instanceof Number value ? value.doubleValue() : 20;
        double maxHealth = player.get("maxHealth") instanceof Number value ? value.doubleValue() : 20;
        Combatant human = new Combatant(health, maxHealth, weapon, armor, true, false, 0);
        String weaponText = weapon > FISTS ? "вооружён: " + weaponName : "без оружия в руках";
        String armorText = pieces == 0 ? "без брони" : "броня: " + pieces + " " + plural(pieces, "часть", "части", "частей") + " (" + materialName(best) + ")";
        int witnessed = 0, kills = 0;
        if (profile != null && profile.recentEvents != null) for (String event : profile.recentEvents) if (event != null && event.startsWith("Видел, как игрок")) {
            witnessed++;
            if (event.contains(" убил ")) kills++;
        }
        boolean hostile = profile != null && profile.type != null && SpeciesCatalog.get(profile.type).hostile();
        int hits = profile == null ? 0 : profile.hits, relationship = profile == null ? 0 : profile.relationship;
        // Fear is for a real threat: this player hit or hurt it, is an enemy, or the species is at war with players anyway.
        boolean attacked = snapshot.playerId() != null && snapshot.playerId().toString().equals(String.valueOf(details.get("lastAttacker")));
        boolean threat = hostile || hits > 0 || witnessed > 0 || relationship < 0 || attacked;
        return new Situation(mob, human, weaponText, armorText, hits, witnessed, relationship, hostile, threat, Boolean.TRUE.equals(player.get("creative")),
                // The nerves of the moment, not the temperament alone: a calm creature just beaten still panics.
                kills, profile == null ? 50 : profile.stateView != null ? profile.stateView.calm() : profile.calm,
                profile == null ? 50 : profile.confidence, Boolean.TRUE.equals(details.get("trapped")));
    }

    private static String itemId(Map<String,Object> player, String slot) { return player.get(slot) instanceof Map<?,?> item && item.get("id") instanceof String id ? id : ""; }
    private static String itemName(Map<String,Object> player, String slot) { return player.get(slot) instanceof Map<?,?> item && item.get("name") instanceof String name ? name : "оружие"; }
    private static String materialName(String id) {
        if (id.contains("netherite")) return "незерит"; if (id.contains("diamond")) return "алмаз"; if (id.contains("iron")) return "железо";
        if (id.contains("chainmail")) return "кольчуга"; if (id.contains("golden")) return "золото"; if (id.contains("turtle")) return "панцирь"; return "кожа";
    }
    static String plural(int count, String one, String few, String many) {
        int ten = count % 10, hundred = count % 100;
        if (ten == 1 && hundred != 11) return one;
        if (ten >= 2 && ten <= 4 && (hundred < 12 || hundred > 14)) return few;
        return many;
    }

    /** The block the model reads: the facts in one line each and the verdict it must act on. */
    public String describe() {
        StringBuilder text = new StringBuilder("Трезвая оценка обстановки (факты, считай их верными):");
        text.append("\n— Игрок: ").append(playerWeapon).append(", ").append(playerArmor)
                .append(", здоровье ").append(Math.round(player.health())).append(" из ").append(Math.round(player.maxHealth())).append('.');
        text.append("\n— Ты: здоровье ").append(Math.round(mob.health())).append(" из ").append(Math.round(mob.maxHealth())).append(", ")
                .append(mob.boss() ? "ты сам по себе сила, с которой считаются" : mob.fighter() ? "драться умеешь" : "драться не умеешь: ни оружия, ни зубов, ни когтей")
                .append(mob.allies() > 0 ? ", рядом сородичей: " + mob.allies() : ", сородичей рядом нет").append('.');
        if (hits > 0 || witnessed > 0) {
            text.append("\n— История: ");
            if (hits > 0) text.append("он бил тебя ").append(hits).append(' ').append(plural(hits, "раз", "раза", "раз"));
            if (hits > 0 && witnessed > 0) text.append("; ");
            if (witnessed > 0) text.append("ты видел, как он обходился с другими (см. rememberedEvents), ").append(witnessed).append(' ').append(plural(witnessed, "случай", "случая", "случаев"));
            text.append('.');
        }
        if (creative) text.append("\n— Он в творческом режиме: неуязвим и всемогущ, любая драка с ним проиграна заранее.");
        text.append("\n— Вывод: ").append(switch (verdict) {
            case DOOMED -> hostile
                    ? "в драке с ним тебе не выжить, он сильнее в разы. Ты это знаешь: злись, грози издали, но в лоб не лезь — выжидай, держись на расстоянии; "
                            + "пощады враг не просит, но и хорохориться, будто ты ему ровня, неправдоподобно. Бежать (action FLEE) — только если ты ранен и жизнь дороже злобы."
                    : threat ? "в драке с ним тебе не выжить, он сильнее в разы. Хорохориться, упрямиться и угрожать тут глупо и неправдоподобно: "
                            + "бойся, уступай в споре, откупайся обещаниями, проси пощады, хитри или беги (action FLEE); гордость побереги на потом. "
                            + "О ценах это не говорит: цены только по правилам торга."
                    : "если дойдёт до драки, тебе не выжить, он сильнее в разы — но повода для драки нет: он тебе не враг. Держись ровно, без заискивания "
                            + "и без бравады, просто не нарывайся и не грози ему.";
            case OUTMATCHED -> hostile ? "он заметно сильнее тебя: нападать в лоб глупо, но ты враг и остаёшься им — грози, выжидай, ищи момент; монстры от целого игрока не бегают."
                    : threat ? "он заметно сильнее тебя. Наглость опасна: осторожничай, не нарывайся, ищи выгоду или путь к отступлению."
                    : "он сильнее тебя, но пока не враг: не нарывайся, и всё.";
            case EVEN -> "силы примерно равны: можно дерзить, но драка — риск для обоих.";
            case STRONGER -> "ты сильнее его: можешь позволить себе наглость и угрозы, если характер велит, а он пусть боится.";
        });
        if (!hostile && relationship <= CombatRules.HATRED)
            text.append(" Ты этого игрока ненавидишь: " + (mob.fighter() && (verdict == Verdict.EVEN || verdict == Verdict.STRONGER)
                    ? "при случае он получит по заслугам — можешь напасть (action ATTACK)." : "держись от него подальше или беги, звать на помощь не стыдно."));
        if (kills > 0) text.append("\n— На твоих глазах он убил ").append(kills).append(' ').append(plural(kills, "существо", "существа", "существ"))
                .append(" (кого — в rememberedEvents). Это не строка в отчёте, а горе, ярость или оцепенение — по характеру; не говори об этом ровным голосом и не делай вид, что ничего не было.");
        if (trapped) text.append("\n— Бежать некуда: ты заперт, путь наружу закрыт. Не говори «я ухожу» и не обещай отойти — это невозможно. Остаются слова: торг, мольба, злость, "
                + "обращение к своим, молчание или смирение. action FLEE бессмысленен.");
        if (verdict == Verdict.DOOMED || verdict == Verdict.OUTMATCHED) text.append("\n— Как именно ты это переживаешь — по темпераменту: ")
                .append(calm < 35 ? "паникуешь, сбиваешься, повторяешь обрывки" : calm > 65 ? "держишься ровно и торгуешься холодно" : "нервничаешь, но соображаешь")
                .append(confidence > 65 ? "; даже обречённый не унижаешься — огрызаешься, язвишь, ставишь условия" : confidence < 35 ? "; легко унижаешься, умоляешь, обещаешь что угодно" : "; просишь по-человечески, без театра")
                .append(". Каждая новая реплика — другой оттенок, не копия прошлой.");
        return text.toString();
    }
}
