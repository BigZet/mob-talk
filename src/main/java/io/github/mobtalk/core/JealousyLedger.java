package io.github.mobtalk.core;

import java.util.*;

/** Ревность и ночи вместе в памяти мира (трек J): кто из жителей встречается с игроком или в браке с ним, кто из них узнал о поводе
 * (видел сам — по расстоянию, которое передаёт игра, или по слуху — если живёт в деревне соперника), что это сделало с отношением,
 * доверием и памятью. Каждый метод работает под замком {@link ConversationStore}; игровой стороне остаётся найти очевидцев. */
public final class JealousyLedger {
    private final ConversationStore store;
    /** How much jealousy one cause must add to hurt the mood too. */
    static final int STING = 5;
    JealousyLedger(ConversationStore store) { this.store = store; }

    /** Житель-пара игрока: пол, в браке ли, деревня. */
    public record Partner(UUID id, Gender gender, boolean married, String villageId) { }
    /** Кто и насколько заревновал после одного повода; {@code rival} и {@code deed} — для реплики ревнующего («подарил ей …»). */
    public record Notice(UUID jealous, int amount, Jealousy.Source source, String rival, String deed) { }

    /** Живые жители, которые встречаются с игроком или женаты на нём. */
    public List<Partner> partners(UUID player) { synchronized (store) { return partners(store.state, player); } }
    static List<Partner> partners(ConversationStore.State state, UUID player) {
        if (player == null) return List.of();
        List<Partner> list = new ArrayList<>();
        for (ConversationStore.Profile q : state.profiles.values()) {
            if (q.social == null || !"minecraft:villager".equals(q.type) || state.family.dead(q.id)) continue;
            ConversationStore.Relation r = q.relations.get(player.toString());
            if (r == null || r.rapport == null || !r.rapport.courtship().partners()) continue;
            list.add(new Partner(q.id, q.social.gender(), r.rapport.courtship() == Courtship.MARRIED, q.villageId == null ? "" : q.villageId));
        }
        return list;
    }
    public boolean partner(UUID villager, UUID player) {
        if (villager == null) return false;
        return partners(player).stream().anyMatch(p -> p.id().equals(villager));
    }

    /** Игрок сделал что-то с жителем {@code rival}: каждая пара игрока того же пола, что и соперник, ревнует — если видела
     * ({@code seen}: пара → расстояние до соперника) или живёт с соперником в одной деревне (по слуху). {@code giftHearts}: сколько
     * сердечек принёс подарок; {@code detail}: что подарено. Возвращает тех, кто заревновал. */
    public List<Notice> noticed(UUID rival, UUID player, String playerName, Jealousy.Cause cause, int giftHearts, String detail,
                                Map<UUID, Double> seen, long now) {
        if (rival == null || player == null || cause == null) return List.of();
        synchronized (store) {
            MobTalkConfig config = store.config();
            if (!config.socialLayer) return List.of();
            ConversationStore.State state = store.state;
            ConversationStore.Profile rp = state.profiles.get(rival.toString());
            if (rp != null && (!"minecraft:villager".equals(rp.type) || !SocialLedger.adult(rp, null))) return List.of();
            Gender rivalGender = rp != null && rp.social != null ? rp.social.gender() : SocialProfile.genderOf(rival);
            String rivalName = rp != null ? SocialLedger.name(rp) : VillagerNames.pick(rival, rivalGender);
            String rivalVillage = rp == null || rp.villageId == null ? "" : rp.villageId;
            List<Notice> notices = new ArrayList<>();
            for (Partner partner : partners(state, player)) {
                if (partner.id().equals(rival) || partner.gender() != rivalGender) continue;
                Double distance = seen == null ? null : seen.get(partner.id());
                Jealousy.Source source;
                if (distance != null) source = Jealousy.Source.SAW;
                else if (!rivalVillage.isBlank() && rivalVillage.equals(partner.villageId())) source = Jealousy.Source.RUMOR;
                else continue;
                int amount = Jealousy.amount(cause, giftHearts, source, distance == null ? 0 : distance, config.witnessSightRange, partner.married(), config);
                ConversationStore.Profile q = state.profiles.get(partner.id().toString());
                ConversationStore.Relation r = store.relation(q, player);
                if (r.rapport == null) r.rapport = new Rapport();
                Jealousy jealousy = r.rapport.jealousy();
                int applied = jealousy.feel(cause, rival.toString(), rivalName, rivalGender, detail, amount, source, now);
                if (applied <= 0) continue;
                int hearts = Math.round(applied * config.jealousyHeartsPercent / 100f);
                r.score = ConversationStore.clamp(r.score - hearts, q.hostile());
                // A chat with a neighbour heard by rumour is a pinch, not an injury: only a real cause (a gift, a favour, a night, a
                // talk seen close up) stings the mood, so a player who talks to the village does not make the partner embittered.
                boolean stings = applied >= STING;
                if (stings) ConversationStore.feel(q, r, MobState.Event.JEALOUSY, now);
                // Нет повышения ревизии: что игрок делал с другим, не отменяет реплику, которую пара говорит сейчас.
                if (stings) ConversationStore.event(q, now, memoryLine(playerName, cause, rivalName, rivalGender, PlayerIdentity.gender(config, player), detail, source,
                        jealousy.level(now), r.score), importance(cause));
                notices.add(new Notice(partner.id(), applied, source, rivalName, Jealousy.deed(cause, rivalGender, PlayerIdentity.gender(config, player), detail)));
            }
            if (!notices.isEmpty()) store.scheduleSave();
            return notices;
        }
    }
    static int importance(Jealousy.Cause cause) {
        return switch (cause) { case TALK -> 2; case FAVOR -> 3; case SLEEP, GIFT -> 4; };
    }
    static String memoryLine(String playerName, Jealousy.Cause cause, String rivalName, Gender rivalGender, Gender playerGender, String detail,
                             Jealousy.Source source, int level, int score) {
        String who = "Игрок " + (playerName == null || playerName.isBlank() ? "неизвестный" : TextBounds.characters(playerName, 60));
        return "Ревность: " + who + " " + Jealousy.deed(cause, rivalGender, playerGender, detail) + " (" + (rivalGender == Gender.MALE ? "соперник" : "соперница")
                + " — " + rivalName + "); " + (source == Jealousy.Source.SAW ? "ты видел это сам" : "тебе донесли в деревне")
                + "; ревность " + level + ", отношение " + score;
    }

    /** Внимание игрока к своей паре (разговор, подарок, услуга) гасит её ревность на {@code amount}; возвращает, на сколько. */
    public int attention(UUID villager, UUID player, int amount, long now) {
        if (villager == null || player == null || amount <= 0) return 0;
        synchronized (store) {
            ConversationStore.Relation r = partnerRelation(villager, player);
            if (r == null || r.rapport.jealousy == null) return 0;
            int soothed = r.rapport.jealousy.soothe(amount, now);
            if (soothed > 0) store.scheduleSave();
            return soothed;
        }
    }
    private ConversationStore.Relation partnerRelation(UUID villager, UUID player) {
        ConversationStore.Profile q = store.state.profiles.get(villager.toString());
        ConversationStore.Relation r = q == null ? null : q.relations.get(player.toString());
        return r == null || r.rapport == null || !r.rapport.courtship().partners() || store.state.family.dead(villager) ? null : r;
    }

    /** Пара проспала ночь рядом с игроком: отношение +{@link MobTalkConfig#coSleepHearts}, доверие вверх, ревность гаснет сильно,
     * память и флаг утренней реплики. Раз за ночь; false — не пара или ночь уже засчитана. */
    public boolean sleptTogether(UUID villager, UUID player, String playerName, long now) {
        if (villager == null || player == null) return false;
        synchronized (store) {
            ConversationStore.Relation r = partnerRelation(villager, player);
            if (r == null) return false;
            ConversationStore.Profile q = store.state.profiles.get(villager.toString());
            MobTalkConfig config = store.config();
            Jealousy jealousy = r.rapport.jealousy();
            if (!jealousy.night(now)) return false;
            jealousy.soothe(Math.max(config.jealousyAttention, 2 * config.jealousySleep), now);
            r.score = ConversationStore.clamp(r.score + config.coSleepHearts, q.hostile());
            ConversationStore.feel(q, r, MobState.Event.NIGHT_TOGETHER, now);
            ConversationStore.event(q, now, "Этой ночью ты спал рядом с игроком " + TextBounds.characters(playerName == null || playerName.isBlank() ? "неизвестный" : playerName, 60)
                    + "; отношение " + r.score, 4);
            store.scheduleSave();
            return true;
        }
    }
    /** Утренняя реплика после ночи вместе: true один раз. */
    public boolean morning(UUID villager, UUID player) {
        if (villager == null || player == null) return false;
        synchronized (store) {
            ConversationStore.Relation r = partnerRelation(villager, player);
            if (r == null || r.rapport.jealousy == null || !r.rapport.jealousy.takeMorning()) return false;
            store.scheduleSave();
            return true;
        }
    }
    /** Текущая ревность пары к игроку; 0 для не пары. */
    public int level(UUID villager, UUID player, long now) {
        synchronized (store) {
            ConversationStore.Relation r = villager == null || player == null ? null : partnerRelation(villager, player);
            return r == null || r.rapport.jealousy == null ? 0 : r.rapport.jealousy.level(now);
        }
    }

    /** Строки «Ты и этот игрок» для промпта пары: ревность с соперником и недавняя ночь вместе; пусто для остальных. */
    static List<String> facts(ConversationStore.Profile p, Rapport rapport, MobTalkConfig config, long now) {
        if (p == null || p.social == null || rapport == null || rapport.jealousy == null || !rapport.courtship().partners()) return List.of();
        List<String> facts = new ArrayList<>();
        String jealous = rapport.jealousy.hint(p.social.gender(), Gender.ofPlayer(config.playerGender), rapport.courtship() == Courtship.MARRIED, now);
        if (!jealous.isBlank()) facts.add(jealous);
        String night = rapport.jealousy.nightHint(now);
        if (!night.isBlank()) facts.add(night);
        return facts;
    }
}
