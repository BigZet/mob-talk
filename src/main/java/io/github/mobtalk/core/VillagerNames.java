package io.github.mobtalk.core;

import java.util.List;
import java.util.UUID;

/** A villager's given name before anyone has spoken to it: the social layer marries, buries and gossips about villagers the
 * player never met, and a wedding of «Крестьянин и Крестьянин» is no wedding. The name follows the UUID and the sex, so the same
 * villager is called the same on every visit; the model keeps it when it writes the life story. */
public final class VillagerNames {
    private VillagerNames() { }

    static final List<String> MALE = List.of("Пахом", "Ладко", "Прохор", "Ефим", "Фома", "Лука", "Тихон", "Макар", "Савва", "Кузьма", "Демид", "Елисей",
            "Захар", "Матвей", "Нестор", "Остап", "Платон", "Родион", "Семён", "Трофим", "Устин", "Фрол", "Харитон", "Яков", "Гордей", "Емеля",
            "Игнат", "Клим", "Мирон", "Назар", "Онисим", "Пантелей", "Спиридон", "Терентий", "Фадей", "Вавила", "Герасим", "Данила", "Ермолай", "Зосима");
    static final List<String> FEMALE = List.of("Аксинья", "Марфа", "Пелагея", "Устинья", "Фёкла", "Агафья", "Варвара", "Глафира", "Дарья", "Евдокия",
            "Зинаида", "Ирина", "Лукерья", "Матрёна", "Настасья", "Прасковья", "Серафима", "Таисия", "Ульяна", "Феодора", "Акулина", "Василиса",
            "Груня", "Домна", "Ефросинья", "Зоя", "Калерия", "Любава", "Мавра", "Олимпиада", "Параша", "Степанида", "Фелицата", "Харитина", "Ярослава",
            "Анфиса", "Всеслава", "Гликерия", "Ефимия", "Злата");

    /** Deterministic for the villager: the same UUID and sex give the same name. */
    public static String pick(UUID id, Gender gender) {
        List<String> pool = gender == Gender.FEMALE ? FEMALE : MALE;
        long hash = id == null ? 0 : id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17);
        return pool.get((int) Math.floorMod(hash, (long) pool.size()));
    }
}
