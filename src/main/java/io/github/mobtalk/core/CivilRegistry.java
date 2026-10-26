package io.github.mobtalk.core;

import java.util.*;
import java.util.function.Function;

/** The civil registry (ЗАГС, track «чиновники», docs/officials.md): the book of acts and the papers issued from it, as in Russia —
 * the acts of the law on civil status (birth, marriage, divorce, death) and the certificates and the forms of the Ministry of
 * Justice's order № 200. The acts are not written by hand: {@link #sync} reads the world's {@link FamilyTree} and enters every new
 * birth, marriage, divorce and death with the next act number and today's game day. The clerk's window ({@code CivilOffice}) offers
 * a player the papers about his own acts and his family's ({@link #papers}); the first certificate is free, a repeated one costs the
 * state fee. Pure Java; it lives in {@link Officialdom}, under the store lock. */
public final class CivilRegistry {
    /** An act of civil status. */
    public enum Kind { BIRTH, MARRIAGE, DIVORCE, DEATH }

    /** One entry of the book: kind, act number, game day, village, and who (birth: child, mother, father; marriage and divorce: the
     * two; death: the dead), ids with the names they had then. */
    public static final class Act {
        String kind = "";
        int number; long day;
        String village = "";
        /** The sex of the first one (the child, the dead), as {@link Gender} names it; "" when unknown. */
        String gender = "";
        List<String> ids = new ArrayList<>(), names = new ArrayList<>();
        Act copy() { Act c = new Act(); c.kind = kind; c.number = number; c.day = day; c.village = village; c.gender = gender; c.ids = new ArrayList<>(ids); c.names = new ArrayList<>(names); return c; }
        public Kind kind() { try { return Kind.valueOf(kind); } catch (IllegalArgumentException e) { return null; } }
        public int number() { return number; }
        public long day() { return day; }
        public String village() { return village; }
        public UUID id(int i) { return i < ids.size() && !ids.get(i).isBlank() ? UUID.fromString(ids.get(i)) : null; }
        public String name(int i) { return i < names.size() ? names.get(i) : ""; }
        boolean has(UUID id) { return id != null && ids.contains(id.toString()); }
        /** «родился» / «родилась» by the first one's sex. */
        String verb(String male, String female, String unknown) { return gender.equals("FEMALE") ? female : gender.equals("MALE") ? male : unknown; }
    }

    /** A paper an official issues: its title as on the form, the office that issues it, its look (a certificate on a strict-account
     * blank — a repeated one costs the state fee — or a справка), and the act it is made from (null for the papers not from the book). */
    public enum Paper {
        BIRTH_CERTIFICATE("свидетельство о рождении", Officialdom.Rank.CLERK, true, Kind.BIRTH),
        MARRIAGE_CERTIFICATE("свидетельство о заключении брака", Officialdom.Rank.CLERK, true, Kind.MARRIAGE),
        DIVORCE_CERTIFICATE("свидетельство о расторжении брака", Officialdom.Rank.CLERK, true, Kind.DIVORCE),
        DEATH_CERTIFICATE("свидетельство о смерти", Officialdom.Rank.CLERK, true, Kind.DEATH),
        MARRIAGE_NOTE("справка о заключении брака (форма № 5)", Officialdom.Rank.CLERK, false, Kind.MARRIAGE),
        DEATH_NOTE("справка о смерти (форма № 11)", Officialdom.Rank.CLERK, false, Kind.DEATH),
        NO_RECORD("извещение об отсутствии записи акта гражданского состояния (форма № 13)", Officialdom.Rank.CLERK, false, null),
        NOT_MARRIED("справка об отсутствии факта государственной регистрации заключения брака (форма № 15)", Officialdom.Rank.CLERK, false, null),
        REGISTRATION("справка о регистрации по месту жительства (форма № 9)", Officialdom.Rank.CLERK, false, null),
        FAMILY("справка о составе семьи", Officialdom.Rank.CLERK, false, null),
        REFERENCE("характеристика с места жительства", Officialdom.Rank.HEAD, false, null),
        HOUSEHOLD("выписка из похозяйственной книги", Officialdom.Rank.HEAD, false, null),
        ALIVE("справка о подтверждении факта нахождения в живых", Officialdom.Rank.HEAD, false, null),
        BUILD_PERMIT("разрешение на строительство", Officialdom.Rank.INSPECTOR, false, null),
        NO_ORDERS("справка об отсутствии неисполненных предписаний", Officialdom.Rank.INSPECTOR, false, null),
        HOUSING("акт обследования жилищных условий", Officialdom.Rank.INSPECTOR, false, null);
        public final String title; public final Officialdom.Rank office; public final boolean certificate; public final Kind act;
        Paper(String title, Officialdom.Rank office, boolean certificate, Kind act) { this.title = title; this.office = office; this.certificate = certificate; this.act = act; }
        public static Paper of(String name) { for (Paper p : values()) if (p.name().equals(name)) return p; return null; }
        /** The state fee in emeralds: a repeated certificate, a building permit; the rest is free, as in the law. */
        public int fee(boolean repeated, int stateFee) { return this == BUILD_PERMIT || repeated && certificate ? Math.max(0, stateFee) : 0; }
    }

    /** One line of the window: the paper, the act it is made from (0 for none), and a short «о ком» for the menu. */
    public record Offer(Paper paper, int act, String about) { }

    /** What the game knows of the holder for the papers not from the book: his name, his village, its title, the village's opinion
     * of him (−100..100), the family lines («супруга — Аксинья»), the household lines («коров — 2») and whether he has an open order. */
    public record Facts(String holder, String place, long day, int reputation, List<String> family, List<String> household, boolean openOrder) { }

    List<Act> acts = new ArrayList<>();
    int nextAct = 101;
    /** Certificates already issued, as {@code act|player}: the next one is «повторное», for the state fee. */
    Set<String> issued = new LinkedHashSet<>();
    /** Series numbers of the certificate blanks («бланки строгой отчётности»). */
    int nextBlank = 512001;

    /** Enters what the tree knows and the book does not yet: births with a parent, marriages, divorces (a married pair no longer
     * together, both alive), deaths. {@code names} fills a name the tree lacks (a newborn named later). Returns the new acts. */
    public List<Act> sync(FamilyTree tree, long day, String village, Function<UUID, String> names) {
        List<Act> added = new ArrayList<>();
        if (tree == null) return added;
        for (FamilyTree.Node node : tree.nodes.values()) {
            UUID id = UUID.fromString(node.id);
            if ((!node.mother.isBlank() || !node.father.isBlank()) && find(Kind.BIRTH, id, null) == null)
                added.add(enter(Kind.BIRTH, day, village, tree, names, node.id, node.mother, node.father));
            if (!node.spouse.isBlank() && node.id.compareTo(node.spouse) < 0) {
                FamilyTree.Node other = tree.nodes.get(node.spouse);
                if (other != null && other.spouse.equals(node.id) && !current(id, UUID.fromString(node.spouse)))
                    added.add(enter(Kind.MARRIAGE, day, village, tree, names, node.id, node.spouse));
            }
            if (node.dead && find(Kind.DEATH, id, null) == null) added.add(enter(Kind.DEATH, day, village, tree, names, node.id));
        }
        // A marriage in the book whose pair is no longer together while both live: a divorce.
        for (Act marriage : List.copyOf(acts)) {
            if (marriage.kind() != Kind.MARRIAGE) continue;
            UUID a = marriage.id(0), b = marriage.id(1);
            if (a == null || b == null || !current(a, b)) continue;
            FamilyTree.Node x = tree.nodes.get(a.toString()), y = tree.nodes.get(b.toString());
            if (x == null || y == null || x.dead || y.dead || x.spouse.equals(y.id)) continue;
            added.add(enter(Kind.DIVORCE, day, village, tree, names, x.id, y.id));
        }
        return added;
    }
    private Act enter(Kind kind, long day, String village, FamilyTree tree, Function<UUID, String> names, String... ids) {
        Act act = new Act();
        act.kind = kind.name(); act.number = nextAct++; act.day = day; act.village = village == null ? "" : village;
        FamilyTree.Node first = ids.length == 0 || ids[0] == null ? null : tree.nodes.get(ids[0]);
        act.gender = first == null || first.gender == null ? "" : first.gender;
        for (String id : ids) {
            act.ids.add(id == null ? "" : id);
            FamilyTree.Node n = id == null || id.isBlank() ? null : tree.nodes.get(id);
            String name = n == null ? "" : n.name;
            if (name.isBlank() && id != null && !id.isBlank() && names != null) { String found = names.apply(UUID.fromString(id)); if (found != null) name = found; }
            act.names.add(name == null || name.isBlank() ? "неизвестно" : name);
        }
        acts.add(act);
        return act;
    }
    /** The last act of this kind about {@code a} (and {@code b}, if given), or null. */
    Act find(Kind kind, UUID a, UUID b) {
        for (int i = acts.size() - 1; i >= 0; i--) {
            Act act = acts.get(i);
            if (act.kind() == kind && act.has(a) && (b == null || act.has(b))) return act;
        }
        return null;
    }
    /** Whether the book has these two married now: a marriage after their last divorce. */
    boolean current(UUID a, UUID b) {
        Act married = find(Kind.MARRIAGE, a, b), divorced = find(Kind.DIVORCE, a, b);
        return married != null && (divorced == null || divorced.number < married.number);
    }
    public Act act(int number) { for (Act a : acts) if (a.number == number) return a.copy(); return null; }
    public List<Act> acts() { return acts.stream().map(Act::copy).toList(); }

    /** What {@code office}'s window can give {@code player}. The clerk (ЗАГС and the passport desk): certificates and справки about his acts, his children's births and his relatives'
     * deaths, the notice that his own birth is not on record, the справка that he is not married (when he is not) and the family
     * справки (registration, family). The head: the character reference, the household book, the справка that he is alive. The
     * inspector: the building permit, the справка of no open orders (when he has none), the housing inspection. */
    public List<Offer> papers(UUID player, FamilyTree tree, Officialdom.Rank office, boolean openOrder) {
        List<Offer> out = new ArrayList<>();
        if (player == null) return out;
        if (office == Officialdom.Rank.HEAD) return List.of(new Offer(Paper.REFERENCE, 0, ""), new Offer(Paper.HOUSEHOLD, 0, ""), new Offer(Paper.ALIVE, 0, ""));
        if (office == Officialdom.Rank.INSPECTOR) {
            List<Offer> inspector = new ArrayList<>(List.of(new Offer(Paper.BUILD_PERMIT, 0, ""), new Offer(Paper.HOUSING, 0, "")));
            if (!openOrder) inspector.add(1, new Offer(Paper.NO_ORDERS, 0, ""));
            return inspector;
        }
        Set<String> family = new HashSet<>();
        family.add(player.toString());
        if (tree != null) tree.links(player).keySet().forEach(id -> family.add(id.toString()));
        boolean ownBirth = false, married = false;
        for (Act act : acts) {
            Kind kind = act.kind();
            if (kind == null) continue;
            boolean mine = act.has(player);
            switch (kind) {
                case BIRTH -> {
                    if (player.equals(act.id(0))) ownBirth = true;
                    if (mine) out.add(new Offer(Paper.BIRTH_CERTIFICATE, act.number, act.name(0)));
                }
                case MARRIAGE -> {
                    if (!mine) continue;
                    UUID other = player.equals(act.id(0)) ? act.id(1) : act.id(0);
                    String with = player.equals(act.id(0)) ? act.name(1) : act.name(0);
                    if (current(player, other)) married = true;
                    out.add(new Offer(Paper.MARRIAGE_CERTIFICATE, act.number, with));
                    out.add(new Offer(Paper.MARRIAGE_NOTE, act.number, with));
                }
                case DIVORCE -> { if (mine) out.add(new Offer(Paper.DIVORCE_CERTIFICATE, act.number, player.equals(act.id(0)) ? act.name(1) : act.name(0))); }
                case DEATH -> {
                    if (act.ids.isEmpty() || !family.contains(act.ids.get(0))) continue;
                    out.add(new Offer(Paper.DEATH_CERTIFICATE, act.number, act.name(0)));
                    out.add(new Offer(Paper.DEATH_NOTE, act.number, act.name(0)));
                }
            }
        }
        if (!ownBirth) out.add(new Offer(Paper.NO_RECORD, 0, "о вашем рождении"));
        if (!married) out.add(new Offer(Paper.NOT_MARRIED, 0, ""));
        out.add(new Offer(Paper.REGISTRATION, 0, ""));
        out.add(new Offer(Paper.FAMILY, 0, ""));
        return out;
    }

    /** Whether this certificate was already issued to this player (then it is a repeated one, for the fee). */
    public boolean repeated(Paper paper, int act, UUID player) { return paper.certificate && issued.contains(paper.name() + "|" + act + "|" + player); }
    /** Marks the paper issued and returns the blank's series and number: «II-ЛЖ № 512001» for a certificate, «№ 42» for a справка. */
    public String issue(Paper paper, int act, UUID player) {
        if (paper.certificate) issued.add(paper.name() + "|" + act + "|" + player);
        int n = nextBlank++;
        if (!paper.certificate) return "№ " + (n % 1000 + 1);
        String[] roman = { "I", "II", "III", "IV", "V", "VI", "VII", "VIII" };
        String letters = "АБВГДЕЖИКЛМНОПРСТУФХЦЧШЭЮЯ";
        return roman[n % roman.length] + "-" + letters.charAt(n / 7 % letters.length()) + letters.charAt(n / 13 % letters.length()) + " № " + n;
    }

    /** The lines of the paper as it is printed: the official wording of the form, filled from the act ({@code act} null for the
     * papers not from the book) and the facts. */
    public static List<String> lines(Paper paper, Act act, Facts facts) {
        List<String> out = new ArrayList<>();
        String holder = facts == null || facts.holder() == null ? "" : facts.holder(), place = facts == null || facts.place() == null || facts.place().isBlank() ? "деревня" : facts.place();
        long day = facts == null ? 0 : facts.day();
        if (act == null && paper.act != null) return out;
        switch (paper) {
            case BIRTH_CERTIFICATE -> {
                out.add(act.name(0) + " " + act.verb("родился", "родилась", "родился(лась)") + " в " + act.day + "-й день");
                out.add("Мать: " + act.name(1));
                out.add("Отец: " + (act.name(2).equals("неизвестно") || act.name(2).isBlank() ? "— (прочерк)" : act.name(2)));
            }
            case MARRIAGE_CERTIFICATE -> { out.add(act.name(0) + " и " + act.name(1)); out.add("заключили брак в " + act.day + "-й день"); }
            case DIVORCE_CERTIFICATE -> { out.add("Брак между " + act.name(0) + " и " + act.name(1)); out.add("прекращён в " + act.day + "-й день"); }
            case DEATH_CERTIFICATE -> out.add(act.name(0) + " " + act.verb("умер", "умерла", "умер(ла)") + " в " + act.day + "-й день");
            case MARRIAGE_NOTE -> {
                out.add("Имеется запись акта о заключении брака");
                out.add(act.name(0) + " и " + act.name(1) + ", " + act.day + "-й день");
                out.add("Выдана для предъявления по месту требования");
            }
            case DEATH_NOTE -> { out.add("Имеется запись акта о смерти: " + act.name(0)); out.add("Выдана для получения социального пособия на погребение"); }
            case NO_RECORD -> { out.add("Запись акта о рождении гражданина " + holder); out.add("не обнаружена: по документам гражданин не рождался"); }
            case NOT_MARRIED -> { out.add("Запись акта о заключении брака"); out.add("гражданина " + holder + " не обнаружена"); }
            case REGISTRATION -> { out.add("Гражданин " + holder + " зарегистрирован"); out.add("по месту жительства: " + place); out.add("Выдана для предъявления по месту требования"); }
            case FAMILY -> {
                out.add("Дана " + holder + " в том, что состав семьи:");
                if (facts == null || facts.family() == null || facts.family().isEmpty()) out.add("проживает один(одна)");
                else out.addAll(facts.family());
            }
            case REFERENCE -> {
                int r = facts == null ? 0 : facts.reputation();
                out.add("Гражданин " + holder + " по месту жительства (" + place + ")");
                out.add(r >= 40 ? "характеризуется исключительно положительно, в быту скромен" : r >= 10 ? "характеризуется положительно, жалоб от соседей не поступало"
                        : r > -10 ? "характеризуется удовлетворительно" : r > -40 ? "характеризуется неудовлетворительно, на замечания реагирует слабо"
                        : "характеризуется отрицательно, неоднократно доставлял беспокойство жителям");
                out.add("Характеристика дана для предъявления по месту требования");
            }
            case HOUSEHOLD -> {
                out.add("Личное подсобное хозяйство гражданина " + holder + ":");
                if (facts == null || facts.household() == null || facts.household().isEmpty()) out.add("скота и птицы не числится");
                else out.addAll(facts.household());
            }
            case ALIVE -> { out.add("Подтверждается, что гражданин " + holder); out.add("на " + day + "-й день жив"); out.add("Справка действительна в течение одного дня"); }
            case BUILD_PERMIT -> { out.add("Разрешается строительство: " + holder); out.add("Населённый пункт: " + place); out.add("Срок действия: до " + (day + PERMIT_DAYS) + "-го дня"); }
            case NO_ORDERS -> { out.add("Неисполненных предписаний за гражданином " + holder); out.add("не числится (по состоянию на " + day + "-й день)"); }
            case HOUSING -> { out.add("Жилищные условия гражданина " + holder + " обследованы"); out.add("и признаны удовлетворительными"); out.add("Замечание: отсутствует согласование перепланировки"); }
        }
        return out;
    }
    /** How long a building permit keeps the inspector away, in game days. */
    public static final int PERMIT_DAYS = 7;

    void sanitize() {
        if (acts == null) acts = new ArrayList<>();
        acts.removeIf(a -> a == null || a.kind() == null || a.ids == null);
        for (Act a : acts) {
            if (a.names == null) a.names = new ArrayList<>();
            if (a.village == null) a.village = "";
            if (a.gender == null) a.gender = "";
            a.ids.replaceAll(id -> id == null || !id.isBlank() && !valid(id) ? "" : id);
            while (a.names.size() < a.ids.size()) a.names.add("неизвестно");
        }
        if (issued == null) issued = new LinkedHashSet<>();
        issued.removeIf(Objects::isNull);
    }
    private static boolean valid(String id) { try { UUID.fromString(id); return true; } catch (IllegalArgumentException e) { return false; } }
    CivilRegistry copy() {
        CivilRegistry c = new CivilRegistry();
        acts.forEach(a -> c.acts.add(a.copy())); c.nextAct = nextAct; c.issued = new LinkedHashSet<>(issued); c.nextBlank = nextBlank;
        return c;
    }
}
