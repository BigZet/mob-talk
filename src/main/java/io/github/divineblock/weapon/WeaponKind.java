package io.github.divineblock.weapon;

import java.util.Locale;

/** The weapons of the god's world: what each is called, where it comes from and whether it is on by default. The ids are
 * what {@code disabledWeapons} holds, {@code /divineblock weapon} takes and the sprites in textures/item are named by. */
public enum WeaponKind {
    DAVID_SLING("david_sling", "Праща Давида", "David's Sling", "Раскрути и отпусти: гладкий камень летит Голиафу в лоб (1 Цар. 17).", true),
    CHERUB_SWORD("cherub_sword", "Пламенный меч Херувима", "Cherub's Flaming Sword", "Поджигает; ПКМ — обращающийся меч кружит вокруг (Быт. 3:24). Не чинится: сломанный возвращается к Херувиму.", true),
    ARCHISTRATEGOS_SWORD("archistrategos_sword", "Меч архистратига", "Sword of the Archistrategos", "Бьёт нечисть сильнее; тяжёл в руках грешника, лёгок у святого.", true),
    GEORGE_SPEAR("george_spear", "Копьё Георгия", "Spear of St George", "Метается и возвращается; змиев (Левиафана, Зверя, дракона) бьёт втрое.", true),
    NIMROD_BOW("nimrod_bow", "Лук Нимрода", "Nimrod's Bow", "Натягивается быстрее, стрела ровнее, зверей бьёт вдвое; убитая им скотина — гордыня перед богом (Быт. 10:9).", true),
    SAMSON_JAWBONE("samson_jawbone", "Ослиная челюсть Самсона", "Samson's Jawbone", "Чем больше врагов вокруг, тем сильнее удар; отбрасывает всех рядом (Суд. 15:15). Ломается быстро.", true),
    GIDEON_JAR("gideon_jar", "Кувшин Гедеона", "Gideon's Pitcher", "Разбей: трубный глас и вспышка, враги вокруг дерутся друг с другом (Суд. 7:16–22).", true),
    WAR_SWORD("war_sword", "Большой меч Войны", "Great Sword of War", "Меч второго всадника (Откр. 6:4): страшен, но мобы вокруг злее, жители бегут, торговли нет.", false),
    JERICHO_HORN("jericho_horn", "Трубы Иерихона", "Trumpets of Jericho", "Труби три секунды: стена перед тобой рассыпается (Нав. 6:20). Ломает только стеновые блоки и только вне защиты.", true),
    ASPEN_STAKE("aspen_stake", "Осиновый кол", "Aspen Stake", "Народное поверье: нечисть бьёт втрое и упокаивает; ночью на погосте вчетверо.", true);

    public final String id, title, titleEn, help;
    /** Off by default: the war sword, which wrecks a village's peace. */
    public final boolean defaultOn;

    WeaponKind(String id, String title, String titleEn, String help, boolean defaultOn) {
        this.id = id; this.title = title; this.titleEn = titleEn; this.help = help; this.defaultOn = defaultOn;
    }

    public static WeaponKind byId(String id) {
        if (id == null) return null;
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        for (WeaponKind kind : values()) if (kind.id.equals(wanted) || kind.name().toLowerCase(Locale.ROOT).equals(wanted)) return kind;
        return null;
    }
}
