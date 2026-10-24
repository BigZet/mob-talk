package io.github.divineblock.weapon;

import java.util.Locale;

/** The weapons of the god's world: what each is called, where it comes from, whether it is on by default, and how big its
 * model is drawn in the hand ({@code scale}: how many blocks one model unit is, times sixteen; a sword of 38 units at 0.6 is
 * about a block and a half). The ids are what {@code disabledWeapons} holds and {@code /divineblock weapon} takes. */
public enum WeaponKind {
    DAVID_SLING("david_sling", "Праща Давида", "David's Sling", "Раскрути и отпусти: гладкий камень летит Голиафу в лоб (1 Цар. 17).", true, 1.2f, false),
    CHERUB_SWORD("cherub_sword", "Пламенный меч Херувима", "Cherub's Flaming Sword", "Поджигает; ПКМ — обращающийся меч кружит вокруг (Быт. 3:24). Не чинится: сломанный возвращается к Херувиму.", true, 0.6f, true),
    ARCHISTRATEGOS_SWORD("archistrategos_sword", "Меч архистратига", "Sword of the Archistrategos", "Бьёт нечисть сильнее; тяжёл в руках грешника, лёгок у святого.", true, 0.6f, true),
    GEORGE_SPEAR("george_spear", "Копьё Георгия", "Spear of St George", "Метается и возвращается; змиев (Левиафана, Зверя, дракона) бьёт втрое.", true, 0.42f, false),
    NIMROD_BOW("nimrod_bow", "Лук Нимрода", "Nimrod's Bow", "Натягивается быстрее, стрела ровнее, зверей бьёт вдвое; убитая им скотина — гордыня перед богом (Быт. 10:9).", true, 0.62f, false),
    SAMSON_JAWBONE("samson_jawbone", "Ослиная челюсть Самсона", "Samson's Jawbone", "Чем больше врагов вокруг, тем сильнее удар; отбрасывает всех рядом (Суд. 15:15). Ломается быстро.", true, 0.7f, false),
    GIDEON_JAR("gideon_jar", "Кувшин Гедеона", "Gideon's Pitcher", "Разбей: трубный глас и вспышка, враги вокруг дерутся друг с другом (Суд. 7:16–22).", true, 1.1f, true),
    WAR_SWORD("war_sword", "Большой меч Войны", "Great Sword of War", "Меч второго всадника (Откр. 6:4): страшен, но мобы вокруг злее, жители бегут, торговли нет.", false, 0.55f, false),
    JERICHO_HORN("jericho_horn", "Трубы Иерихона", "Trumpets of Jericho", "Труби три секунды: стена перед тобой рассыпается (Нав. 6:20). Ломает только стеновые блоки и только вне защиты.", true, 0.8f, false),
    ASPEN_STAKE("aspen_stake", "Осиновый кол", "Aspen Stake", "Народное поверье: нечисть бьёт втрое и упокаивает; ночью на погосте вчетверо.", true, 0.9f, false);

    public final String id, title, titleEn, help;
    /** Off by default: the war sword, which wrecks a village's peace. */
    public final boolean defaultOn;
    /** The renderer's scale for the model in the hand. */
    public final float scale;
    /** Whether the model has a glow texture (fire, holy light). */
    public final boolean glow;

    WeaponKind(String id, String title, String titleEn, String help, boolean defaultOn, float scale, boolean glow) {
        this.id = id; this.title = title; this.titleEn = titleEn; this.help = help; this.defaultOn = defaultOn; this.scale = scale; this.glow = glow;
    }

    public static WeaponKind byId(String id) {
        if (id == null) return null;
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        for (WeaponKind kind : values()) if (kind.id.equals(wanted) || kind.name().toLowerCase(Locale.ROOT).equals(wanted)) return kind;
        return null;
    }
}
