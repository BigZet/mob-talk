package io.github.mobtalk.core;

import java.util.*;

/** Помолвка и свадьба в памяти мира (трек L): с кем игрок помолвлен, кто согласился быть свидетелем, кольцо у жителя, что свадьба
 * сделала с отношениями и памятью пары, ведущего, свидетелей и гостей. Каждый метод работает под замком {@link ConversationStore};
 * людей двигает и эффекты делает игра, порядок службы — {@link Wedding.Ceremony}. */
public final class WeddingLedger {
    /** Сколько даёт сама свадьба: супругу, свидетелю, ведущему, гостю; прибавка за обмен кольцами. Сбежавший из-под венца теряет. */
    public static final int CEREMONY_HEARTS = 10, WITNESS_HEARTS = 5, OFFICIANT_HEARTS = 4, GUEST_HEARTS = 3, RINGS_HEARTS = 3;
    public static final int JILTED_HEARTS = -40, JILTED_GUEST_HEARTS = -10;
    /** Свежая свадьба: столько реального времени (игровой день) супруг помнит её как «сегодняшнюю». */
    public static final long FRESH_MILLIS = 20 * 60_000L;
    private final ConversationStore store;
    WeddingLedger(ConversationStore store) { this.store = store; }

    /** Живой житель, с которым игрок помолвлен, или null. */
    public UUID fiance(UUID player) {
        if (player == null) return null;
        synchronized (store) { return fiance(store.state, player); }
    }
    static UUID fiance(ConversationStore.State state, UUID player) {
        for (ConversationStore.Profile q : state.profiles.values()) {
            ConversationStore.Relation r = q.relations.get(player.toString());
            if (r != null && r.rapport != null && r.rapport.courtship() == Courtship.ENGAGED && !state.family.dead(q.id)) return q.id;
        }
        return null;
    }
    public boolean engaged(UUID villager, UUID player) { return villager != null && villager.equals(fiance(player)); }

    /** Свидетели, которых назвал игрок для свадьбы с невестой (женихом) {@code fiance}: {мужчина, женщина}, null — не назван. */
    public UUID[] witnesses(UUID fiance, UUID player) {
        synchronized (store) {
            Rapport rapport = rapport(fiance, player);
            return rapport == null ? new UUID[2] : new UUID[]{uuid(rapport.witnessMan), uuid(rapport.witnessWoman)};
        }
    }
    private static UUID uuid(String text) { try { return text == null || text.isBlank() ? null : UUID.fromString(text); } catch (IllegalArgumentException bad) { return null; } }
    private Rapport rapport(UUID villager, UUID player) {
        ConversationStore.Profile q = villager == null ? null : store.state.profiles.get(villager.toString());
        ConversationStore.Relation r = q == null || player == null ? null : q.relations.get(player.toString());
        return r == null ? null : r.rapport;
    }

    /** Почему житель не станет свидетелем, или null — станет. Решается до ответа, как исход жеста: модель только озвучивает. */
    static String witnessRefusal(ConversationStore.State state, ConversationStore.Profile p, int score, UUID player, MobSnapshot snapshot) {
        UUID fiance = fiance(state, player);
        if (fiance == null) return "игрок ни с кем не помолвлен — и свадьбы нет";
        if (fiance.equals(p.id)) return "ты сам жених (невеста) на этой свадьбе";
        if (!SocialLedger.adult(p, snapshot)) return "ты ещё ребёнок";
        if (score < Hearts.friend()) return "ты с игроком не настолько близок — свидетелем зовут близких";
        return null;
    }
    /** Игрок попросил жителя быть свидетелем, и ответ доставлен. Согласие (по {@link #witnessRefusal}) записывает его свидетелем
     * своего пола; возвращает строку для чата или "". */
    public String appointWitness(UUID villager, UUID player, String playerName, long now) {
        if (villager == null || player == null) return "";
        synchronized (store) {
            ConversationStore.State state = store.state;
            ConversationStore.Profile p = state.profiles.get(villager.toString());
            if (p == null || p.social == null) return "";
            ConversationStore.Relation own = p.relations.get(player.toString());
            if (witnessRefusal(state, p, own == null ? 0 : own.score, player, null) != null) return "";
            UUID fiance = fiance(state, player);
            Rapport rapport = rapport(fiance, player);
            if (rapport == null) return "";
            String id = villager.toString();
            if (p.social.gender() == Gender.MALE) { rapport.witnessMan = id; if (id.equals(rapport.witnessWoman)) rapport.witnessWoman = ""; }
            else { rapport.witnessWoman = id; if (id.equals(rapport.witnessMan)) rapport.witnessMan = ""; }
            ConversationStore.Profile f = state.profiles.get(fiance.toString());
            String who = p.social.gender() == Gender.MALE ? "свидетелем" : "свидетельницей";
            ConversationStore.event(p, now, "Ты согласился быть " + who + " на свадьбе игрока " + safe(playerName) + " и " + (f == null ? "его невесты" : SocialLedger.name(f)), 3);
            store.scheduleSave();
            return SocialLedger.name(p) + " будет " + who + " на вашей свадьбе";
        }
    }

    /** Все кольца, которые житель носит (от любых игроков): выпадают, если он погибнет. */
    public List<String> rings(UUID villager) {
        synchronized (store) {
            ConversationStore.Profile q = villager == null ? null : store.state.profiles.get(villager.toString());
            if (q == null) return List.of();
            List<String> rings = new ArrayList<>();
            for (ConversationStore.Relation r : q.relations.values()) if (r.rapport != null && r.rapport.ring != null && !r.rapport.ring.isBlank()) rings.add(r.rapport.ring);
            return rings;
        }
    }
    /** Кольцо, которое житель принял при помолвке и на свадьбе (id предмета), "" — нет. */
    public String ring(UUID villager, UUID player) { synchronized (store) { Rapport r = rapport(villager, player); return r == null ? "" : r.ring; } }

    /** Люди для свадьбы из жителей, которых видит игра: профили создаются (свадьба знакомит их с миром памяти), отношение берётся
     * к игроку, а в свадьбе жителей ({@code player} null) — лучшее из отношений к паре. */
    public record Resident(UUID id, String name, String profession, boolean adult) { }
    public List<Wedding.Person> people(List<Resident> residents, UUID spouse, UUID partner, UUID player, long now) {
        synchronized (store) {
            ConversationStore.State state = store.state;
            List<Wedding.Person> people = new ArrayList<>();
            for (Resident resident : residents) {
                if (!resident.adult()) continue;
                ConversationStore.Profile p = store.ensure(resident.id(), "minecraft:villager", resident.name());
                if (p.social == null) continue;
                int bond;
                if (player != null) { ConversationStore.Relation r = p.relations.get(player.toString()); bond = r == null ? 0 : r.score; }
                else bond = state.relations.views(p.id, now).stream().filter(v -> v.other().equals(spouse) || v.other().equals(partner))
                        .mapToInt(MobRelations.View::score).max().orElse(0);
                boolean kin = spouse != null && !resident.id().equals(spouse) && (state.family.related(resident.id(), spouse) || spouse.equals(state.family.spouse(resident.id())))
                        || partner != null && !resident.id().equals(partner) && state.family.related(resident.id(), partner);
                people.add(new Wedding.Person(p.id, SocialLedger.name(p), p.social.gender(), resident.profession(), p.social.persona().word, p.voiceId, bond, kin,
                        state.family.children(p.id).size()));
            }
            store.scheduleSave();
            return people;
        }
    }

    /** Игрок и житель стали супругами у колокола: брак в дереве, сердечки паре, свидетелям, ведущему и гостям, память каждому —
     * свадьба попадает в хронику и слухи. {@code rings}: обменялись кольцами. */
    public void wed(Wedding.Cast cast, UUID player, String playerName, Gender playerGender, boolean rings, String ringItem, long now) {
        if (cast == null || player == null) return;
        synchronized (store) {
            ConversationStore.State state = store.state;
            ConversationStore.Profile p = state.profiles.get(cast.spouse().id().toString());
            if (p == null) return;
            ConversationStore.Relation r = store.relation(p, player);
            if (r.rapport == null) r.rapport = new Rapport();
            UUID former = SocialLedger.partner(state, player);
            if (former != null && !former.equals(p.id)) {
                ConversationStore.Profile q = state.profiles.get(former.toString());
                ConversationStore.Relation fr = q == null ? null : q.relations.get(player.toString());
                if (fr != null && fr.rapport != null) fr.rapport.courtship(Courtship.NONE);
            }
            r.rapport.courtship(Courtship.MARRIED);
            r.rapport.weddingMillis = now;
            if (rings && ringItem != null && !ringItem.isBlank()) r.rapport.ring = ringItem;
            r.score = ConversationStore.clamp(r.score + CEREMONY_HEARTS + (rings ? RINGS_HEARTS : 0), p.hostile());
            state.family.marry(player, safe(playerName), playerGender, true, p.id, SocialLedger.name(p), p.social == null ? null : p.social.gender(), false);
            ConversationStore.feel(p, r, MobState.Event.ROMANCE, now);
            ConversationStore.event(p, now, "Твоя свадьба с игроком " + safe(playerName) + " у колокола: " + who(cast) + (rings ? "; вы обменялись кольцами" : "; колец не было")
                    + "; теперь вы супруги, отношение " + r.score, 5);
            p.revision++;
            for (Wedding.Person guest : cast.everyone()) {
                if (guest.id().equals(p.id)) continue;
                ConversationStore.Profile q = state.profiles.get(guest.id().toString());
                if (q == null) continue;
                Wedding.Role role = cast.role(guest.id());
                int hearts = role == Wedding.Role.OFFICIANT ? OFFICIANT_HEARTS : role == Wedding.Role.WITNESS_MAN || role == Wedding.Role.WITNESS_WOMAN ? WITNESS_HEARTS : GUEST_HEARTS;
                ConversationStore.Relation gr = store.relation(q, player);
                gr.score = ConversationStore.clamp(gr.score + hearts, q.hostile());
                ConversationStore.feel(q, gr, MobState.Event.GESTURE_WARM, now);
                String part = switch (role) {
                    case OFFICIANT -> "Ты вёл свадьбу";
                    case WITNESS_MAN, WITNESS_WOMAN -> "Ты был свидетелем на свадьбе";
                    default -> "Ты был гостем на свадьбе";
                };
                ConversationStore.event(q, now, part + " игрока " + safe(playerName) + " и " + SocialLedger.name(p) + " у колокола; отношение к игроку " + gr.score, 4);
                state.relations.together(q.id, SocialLedger.name(q), q.type, p.id, SocialLedger.name(p), p.type, MobRelations.Kinship.NEIGHBOR, 5, 0, now);
            }
            store.scheduleSave();
        }
    }

    /** Два жителя поженились на глазах у деревни: брак в дереве, память паре и гостям. */
    public void villageWed(Wedding.Cast cast, UUID player, long now) {
        if (cast == null || cast.partner() == null) return;
        synchronized (store) {
            ConversationStore.State state = store.state;
            ConversationStore.Profile a = state.profiles.get(cast.partner().id().toString()), b = state.profiles.get(cast.spouse().id().toString());
            if (a == null || b == null || a.social == null || b.social == null) return;
            state.family.marry(a.id, SocialLedger.name(a), a.social.gender(), false, b.id, SocialLedger.name(b), b.social.gender(), false);
            for (ConversationStore.Profile[] two : List.of(new ConversationStore.Profile[]{a, b}, new ConversationStore.Profile[]{b, a})) {
                ConversationStore.Profile self = two[0], other = two[1];
                if (player != null) ConversationStore.feel(self, store.relation(self, player), MobState.Event.ROMANCE, now);
                ConversationStore.event(self, now, "Твоя свадьба у колокола: " + other.social.gender().spouse + " — " + SocialLedger.name(other) + "; " + who(cast), 5);
                self.revision++;
            }
            state.relations.together(a.id, SocialLedger.name(a), a.type, b.id, SocialLedger.name(b), b.type, MobRelations.Kinship.NEIGHBOR, 30, 0, now);
            for (Wedding.Person guest : cast.everyone()) {
                if (guest.id().equals(a.id) || guest.id().equals(b.id)) continue;
                ConversationStore.Profile q = state.profiles.get(guest.id().toString());
                if (q != null) ConversationStore.event(q, now, "Был на свадьбе " + SocialLedger.name(a) + " и " + SocialLedger.name(b) + " у колокола", 3);
            }
            store.scheduleSave();
        }
    }

    /** Игрок сбежал из-под венца: помолвка расторгнута, у невесты (жениха) сильная обида, у гостей — тоже, деревня запомнит. */
    public void jilted(Wedding.Cast cast, UUID player, String playerName, long now) {
        if (cast == null || player == null) return;
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(cast.spouse().id().toString());
            if (p == null) return;
            ConversationStore.Relation r = store.relation(p, player);
            if (r.rapport == null) r.rapport = new Rapport();
            r.rapport.courtship(Courtship.NONE);
            r.rapport.witnessMan = ""; r.rapport.witnessWoman = "";
            r.score = ConversationStore.clamp(r.score + JILTED_HEARTS, p.hostile());
            ConversationStore.feel(p, r, MobState.Event.DIVORCE, now);
            ConversationStore.event(p, now, "Игрок " + safe(playerName) + " отказался от тебя прямо у колокола, на глазах у всей деревни; помолвки больше нет, отношение " + r.score, 5);
            p.revision++;
            for (Wedding.Person guest : cast.everyone()) {
                if (guest.id().equals(p.id)) continue;
                ConversationStore.Profile q = store.state.profiles.get(guest.id().toString());
                if (q == null) continue;
                ConversationStore.Relation gr = store.relation(q, player);
                gr.score = ConversationStore.clamp(gr.score + JILTED_GUEST_HEARTS, q.hostile());
                ConversationStore.event(q, now, "Игрок " + safe(playerName) + " сбежал из-под венца от " + SocialLedger.name(p) + "; отношение " + gr.score, 4);
            }
            store.scheduleSave();
        }
    }

    /** Свадьбу пришлось отложить: помолвка остаётся, невеста (жених) запоминает почему. */
    public void postponed(UUID spouse, UUID player, String why, long now) {
        synchronized (store) {
            ConversationStore.Profile p = spouse == null ? null : store.state.profiles.get(spouse.toString());
            if (p == null) return;
            ConversationStore.event(p, now, "Свадьбу пришлось отложить: " + TextBounds.characters(why, 200), 2);
            store.scheduleSave();
        }
    }

    /** Строки «Ты и этот игрок» для промпта: помолвка, свадьба впереди или только что сыгранная; для остальных жителей — просьба
     * быть свидетелем, если игрок помолвлен. */
    static List<String> facts(ConversationStore.State state, ConversationStore.Profile p, ConversationStore.Relation r, UUID player, MobSnapshot snapshot,
                              MobTalkConfig config, long now) {
        if (p == null || p.social == null || player == null) return List.of();
        List<String> facts = new ArrayList<>();
        Rapport rapport = r == null ? null : r.rapport;
        Courtship courtship = rapport == null ? Courtship.NONE : rapport.courtship();
        if (courtship == Courtship.ENGAGED) {
            boolean day = snapshot == null || !"night".equals(snapshot.timeOfDay()) && !"dusk".equals(snapshot.timeOfDay());
            facts.add("вы с игроком помолвлены: ты " + (p.social.gender() == Gender.FEMALE ? "приняла" : "принял") + " кольцо, свадьба ещё не сыграна — её играют у деревенского колокола, при всей деревне.");
            if (config.weddingCeremony) facts.add(day ? "если игрок зовёт тебя на свадьбу сейчас («идём на свадьбу», «пора жениться») — радостно соглашайся: вы все идёте к колоколу."
                    : "если игрок зовёт на свадьбу сейчас — скажи, что свадьбы играют днём, сыграете утром.");
            List<String> named = new ArrayList<>();
            for (String id : List.of(rapport.witnessMan, rapport.witnessWoman)) {
                ConversationStore.Profile q = uuid(id) == null ? null : state.profiles.get(id);
                if (q != null) named.add(SocialLedger.name(q));
            }
            if (!named.isEmpty()) facts.add("свидетели на вашей свадьбе: " + String.join(" и ", named) + ".");
        } else if (courtship == Courtship.MARRIED && rapport.weddingMillis >= 0 && now - rapport.weddingMillis < FRESH_MILLIS) {
            facts.add("сегодня у вас была свадьба у колокола — ты ещё под впечатлением.");
        } else if (courtship == Courtship.NONE && config.weddingCeremony) {
            UUID fiance = fiance(state, player);
            if (fiance != null && !fiance.equals(p.id)) {
                ConversationStore.Profile f = state.profiles.get(fiance.toString());
                String refusal = witnessRefusal(state, p, r == null ? 0 : r.score, player, snapshot);
                String who = p.social.gender() == Gender.MALE ? "свидетелем" : "свидетельницей";
                facts.add("игрок помолвлен с " + (f == null ? "жителем вашей деревни" : SocialLedger.name(f)) + "; свадьба впереди. Если он просит тебя быть "
                        + who + " — " + (refusal == null ? "соглашайся с радостью." : "откажи по характеру: " + refusal + "."));
            }
        }
        return facts;
    }
    private static String who(Wedding.Cast cast) {
        List<String> parts = new ArrayList<>();
        parts.add("вёл " + cast.leader().name());
        List<String> witnesses = new ArrayList<>();
        if (cast.witnessMan() != null) witnesses.add(cast.witnessMan().name());
        if (cast.witnessWoman() != null) witnesses.add(cast.witnessWoman().name());
        if (!witnesses.isEmpty()) parts.add("свидетели " + String.join(" и ", witnesses));
        if (!cast.guests().isEmpty()) parts.add("гостей " + cast.guests().size());
        return String.join(", ", parts);
    }
    private static String safe(String name) { return TextBounds.characters(name == null || name.isBlank() ? "неизвестный" : name, 60); }
}
