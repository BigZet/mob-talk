package io.github.divineblock.boss;

import java.util.List;
import java.util.Locale;

/** The sacred bosses: who they are, their ultimate and their own affliction, and where they come from. The ids are the
 * public names other parts of the mod pass to {@link DivineBosses#spawn}. */
public enum BossKind {
    BLACK_SCHEMNIK("black_schemnik", "Чёрный Схимник", "Вечная схима", "Обет молчания", Place.CHURCH, Place.GOD),
    BELL_RINGER("bell_ringer", "Звонарь погоста", "Набат", "Звон в ушах", Place.CEMETERY, Place.GOD),
    SERAPH("seraph", "Шестикрылый серафим", "Пламень с небес", "Опаление", Place.CHURCH, Place.GOD),
    FACELESS_ICON("faceless_icon", "Безликий лик", "Иконостас", "Взор лика", Place.CHURCH, Place.GOD),
    WAILER("wailer", "Плакальщица погоста", "Отпевание", "Панихида", Place.CEMETERY, Place.GOD),
    ARCHANGEL("archangel", "Архангел гнева", "Последний суд", "Опаление", Place.FINAL),
    // Biblical bosses.
    LEVIATHAN("leviathan", "Левиафан", "Пучина", "Тяжесть глубин", Place.WATER, Place.GOD),
    BEHEMOTH("behemoth", "Бегемот", "Сила чресл его", "Прах земной", Place.WILD, Place.GOD),
    GOLIATH("goliath", "Голиаф", "Вызов воинству", "Оглушение", Place.GOD),
    HORSEMEN("horsemen", "Четыре всадника", "Снятие печатей", "Голод", Place.FINAL),
    DESTROYING_ANGEL("destroying_angel", "Ангел-губитель", "Десятая казнь", "Печать первенца", Place.NIGHT, Place.GOD),
    CHERUB("cherub", "Херувим с пламенным мечом", "Обращающийся меч", "Изгнание из рая", Place.CHURCH, Place.GOD),
    BEAST("beast", "Зверь из бездны", "Число зверя", "Начертание", Place.FINAL, Place.GOD),
    NIMROD("nimrod", "Нимрод", "Башня до небес", "Смешение языков", Place.GOD);

    /** Where a boss may appear: guarding the church's crypt and altar room, rising on the cemetery at night, sent by the god
     * as a punishment, only from the god's heaviest group, in deep water, in swamps and by rivers, or anywhere at night. */
    public enum Place { CHURCH, CEMETERY, GOD, FINAL, WATER, WILD, NIGHT }

    public final String id, title, ultimate, affliction;
    public final List<Place> places;

    BossKind(String id, String title, String ultimate, String affliction, Place... places) {
        this.id = id; this.title = title; this.ultimate = ultimate; this.affliction = affliction; this.places = List.of(places);
    }

    public static BossKind byId(String id) {
        if (id == null) return null;
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        for (BossKind kind : values()) if (kind.id.equals(wanted) || kind.name().toLowerCase(Locale.ROOT).equals(wanted)) return kind;
        return null;
    }

    public static List<BossKind> at(Place place) {
        return java.util.Arrays.stream(values()).filter(kind -> kind.places.contains(place)).toList();
    }
}
