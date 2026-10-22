package io.github.mobtalk.core;

import java.util.*;

/** A villager's given name before anyone has spoken to it: the social layer marries, buries and gossips about villagers the
 * player never met, and a wedding of «Крестьянин и Крестьянин» is no wedding. Names are ordinary Russian ones; a new villager takes
 * the name fewest villagers of the world already carry (none in its own village, while the pool lasts), so a village has no two
 * Irinas. The choice is stored in the profile; the order of the pool follows the UUID, so ties break the same way every time. */
public final class VillagerNames {
    private VillagerNames() { }

    static final List<String> MALE = List.of("Александр", "Алексей", "Анатолий", "Андрей", "Антон", "Аркадий", "Арсений", "Артём", "Богдан",
            "Борис", "Вадим", "Валентин", "Валерий", "Василий", "Вениамин", "Виктор", "Виталий", "Владимир", "Владислав", "Всеволод", "Вячеслав",
            "Гавриил", "Геннадий", "Георгий", "Герман", "Глеб", "Григорий", "Даниил", "Демьян", "Денис", "Дмитрий", "Евгений", "Егор", "Елисей",
            "Ефим", "Захар", "Иван", "Игнат", "Игорь", "Илья", "Кирилл", "Константин", "Лев", "Леонид", "Лука", "Макар", "Максим", "Марк",
            "Матвей", "Мирон", "Михаил", "Назар", "Никита", "Николай", "Олег", "Павел", "Пётр", "Платон", "Прохор", "Родион", "Роман",
            "Ростислав", "Руслан", "Савелий", "Святослав", "Семён", "Сергей", "Станислав", "Степан", "Тимофей", "Тимур", "Тихон", "Фёдор",
            "Филипп", "Эдуард", "Юрий", "Яков", "Ярослав");
    static final List<String> FEMALE = List.of("Агата", "Александра", "Алёна", "Алина", "Алла", "Анастасия", "Ангелина", "Анна", "Антонина",
            "Арина", "Валентина", "Валерия", "Варвара", "Василиса", "Вера", "Вероника", "Виктория", "Галина", "Дарья", "Диана", "Ева",
            "Евгения", "Екатерина", "Елена", "Елизавета", "Жанна", "Злата", "Зинаида", "Зоя", "Инга", "Инна", "Ирина", "Карина", "Кира",
            "Клавдия", "Кристина", "Ксения", "Лада", "Лариса", "Лидия", "Лилия", "Любовь", "Людмила", "Майя", "Маргарита", "Марина",
            "Мария", "Милана", "Надежда", "Наталья", "Нина", "Оксана", "Олеся", "Ольга", "Полина", "Раиса", "Регина", "Римма", "Светлана",
            "Серафима", "Снежана", "Софья", "Таисия", "Тамара", "Татьяна", "Ульяна", "Фаина", "Юлия", "Яна");

    /** Weight of one villager of the same village carrying a name, against one elsewhere in the world. */
    static final int SAME_VILLAGE = 1000;

    /** The UUID's own choice, ignoring everyone else: for a villager the store has no profile of (a name only shown in passing). */
    public static String pick(UUID id, Gender gender) { return order(id, gender).getFirst(); }
    /** Чьё это имя по спискам: мужское, женское или null (не из списков). */
    static Gender sexOf(String name) { return name == null ? null : MALE.contains(name.strip()) ? Gender.MALE : FEMALE.contains(name.strip()) ? Gender.FEMALE : null; }

    /** The name for a new villager: the least worn in {@code wear} (name → weight, see {@link #wear}); ties go by the UUID's order. */
    public static String pick(UUID id, Gender gender, Map<String, Integer> wear) {
        String best = null; int least = Integer.MAX_VALUE;
        for (String name : order(id, gender)) {
            int worn = wear == null ? 0 : wear.getOrDefault(name, 0);
            if (worn < least) { best = name; least = worn; if (worn == 0) break; }
        }
        return best;
    }

    /** How worn each name is among {@code others}: one per villager carrying it, {@link #SAME_VILLAGE} when it lives in {@code villageId}. */
    static Map<String, Integer> wear(Collection<ConversationStore.Profile> others, UUID self, String villageId) {
        Map<String, Integer> wear = new HashMap<>();
        boolean placed = villageId != null && !villageId.isBlank();
        for (ConversationStore.Profile q : others) {
            if (q.id == null || q.id.equals(self) || !"minecraft:villager".equals(q.type) || q.givenName == null || q.givenName.isBlank()) continue;
            wear.merge(q.givenName.strip(), placed && villageId.equals(q.villageId) ? SAME_VILLAGE : 1, Integer::sum);
        }
        return wear;
    }

    /** The pool for the sex, shuffled by the UUID: the same villager always looks through it in the same order. */
    private static List<String> order(UUID id, Gender gender) {
        List<String> pool = new ArrayList<>(gender == Gender.FEMALE ? FEMALE : MALE);
        long hash = id == null ? 0 : id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17);
        Collections.shuffle(pool, new Random(hash));
        return pool;
    }
}
