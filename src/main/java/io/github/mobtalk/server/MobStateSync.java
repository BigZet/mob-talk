package io.github.mobtalk.server;

import io.github.mobtalk.core.BargainMeter;
import io.github.mobtalk.core.ChildLedger;
import io.github.mobtalk.core.Favor;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobState;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.Pregnancy;
import io.github.mobtalk.game.GameBridge;
import io.github.mobtalk.game.GameBridge.MobView;
import io.github.mobtalk.game.GameBridge.PlayerView;
import io.github.mobtalk.net.MobLabelS2C;
import io.github.mobtalk.net.MobStateS2C;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** HUD и подписи решает сервер (бриф A, шаг 3): раз в секунду каждому игроку уходит {@link MobStateS2C} и {@link MobLabelS2C} для
 * знакомых мобов в {@link #LABEL_RANGE} блоков и для его собеседника, но только когда что-то изменилось (или прошло
 * {@link #REFRESH_MILLIS}): состояние, отношение, услуга, торг, имя, строка под именем. Моб, ушедший из круга, получает пустую
 * подпись, чтобы клиент снял её. */
final class MobStateSync {
    static final double LABEL_RANGE = 24;
    static final long REFRESH_MILLIS = 5_000;
    private final GameBridge bridge;
    private final DialogueService.Outbox out;

    /** Что игроку уже отправлено, чтобы не слать то же самое. */
    static final class Tracker {
        final Map<UUID, MobStateS2C> states = new HashMap<>();
        final Map<UUID, MobLabelS2C> labels = new HashMap<>();
        final Map<UUID, Long> sentMillis = new HashMap<>();
    }

    MobStateSync(GameBridge bridge, DialogueService.Outbox out) { this.bridge = bridge; this.out = out; }

    void tick(UUID player, UUID target, Tracker tracker, Map<UUID, BargainMeter.Move> bargains) {
        if (!bridge.config().enabled) return;
        bridge.player(player).thenCombine(bridge.knownMobsNear(player, LABEL_RANGE), (view, known) -> {
            Set<UUID> wanted = new HashSet<>(known);
            if (target != null) wanted.add(target);
            return new Object[] { view, wanted };
        }).whenComplete((pair, error) -> bridge.onServer(() -> {
            if (error != null) return null;
            PlayerView view = (PlayerView) pair[0];
            @SuppressWarnings("unchecked") Set<UUID> wanted = (Set<UUID>) pair[1];
            long now = System.currentTimeMillis();
            for (UUID gone : new HashSet<>(tracker.labels.keySet())) {
                if (wanted.contains(gone)) continue;
                MobLabelS2C old = tracker.labels.remove(gone);
                tracker.states.remove(gone); tracker.sentMillis.remove(gone);
                if (old != null && !old.name().isEmpty()) out.label(player, new MobLabelS2C(old.mobId(), gone, "", ""));
            }
            for (UUID id : wanted) bridge.mob(id, view.dimension()).whenComplete((mob, missing) -> bridge.onServer(() -> {
                if (missing == null) sync(player, view, mob, tracker, bargains.get(id), now);
                return null;
            }));
            return null;
        }));
    }

    private void sync(UUID player, PlayerView view, MobView mob, Tracker tracker, BargainMeter.Move move, long now) {
        MobTalkConfig config = bridge.config();
        MobCard card = bridge.memory().card(mob.id(), player);
        boolean acquainted = bridge.memory().acquainted(mob.id());
        String name = acquainted ? bridge.memory().displayName(mob.id()) : "";
        String line = pregnancyLine(mob.id(), view.timeOfDay(), config);
        MobLabelS2C label = new MobLabelS2C(mob.entityId(), mob.id(), card == null ? "" : name, card == null ? "" : line);
        Long sent = tracker.sentMillis.get(mob.id());
        boolean stale = sent == null || now - sent > REFRESH_MILLIS;
        if (stale || !label.equals(tracker.labels.get(mob.id()))) { tracker.labels.put(mob.id(), label); out.label(player, label); }
        // Время повтора сдвигается только когда повтор ушёл: иначе подпись уходила один раз, клиент снимал её через 15 с без
        // обновления (или при смене мира на входе) и таблички над головами пропадали (живая проверка 23 октября).
        if (stale) tracker.sentMillis.put(mob.id(), now);
        if (card == null) return;
        Favor favor = bridge.memory().favor(mob.id(), player);
        if (favor == null) send(player, mob, card, tracker, move, "", stale, now);
        else bridge.countInHand(player, favor.item()).whenComplete((have, error) -> bridge.onServer(() -> {
            int count = error == null && have != null ? have : 0;
            send(player, mob, card, tracker, move, "услуга: " + favor.title() + " " + Math.min(count, favor.count()) + "/" + favor.count(), stale, now);
            return null;
        }));
    }

    private void send(UUID player, MobView mob, MobCard card, Tracker tracker, BargainMeter.Move move, String favor, boolean stale, long now) {
        MobState.View state = card.state();
        MobState.Descriptor marker = state == null ? null : state.marker();
        String bargain = ""; int bargainSign = 0;
        if (move != null && state != null) {
            BargainMeter.Verdict verdict = BargainMeter.read(move, state, card.relationship()).verdict();
            bargain = verdict.key; bargainSign = verdict.sign;
        }
        MobStateS2C packet = new MobStateS2C(mob.entityId(), mob.id(), state == null ? 50 : state.calm(), state == null ? 50 : state.trust(),
                state == null ? 50 : state.interest(), state == null ? 50 : state.mood(), marker == null ? "" : marker.key(), marker == null ? 0 : marker.sign(),
                card.relationship(), ScreenReports.relationshipLabel(card), hearts(card), favor, bargain, bargainSign,
                state == null ? 60 : state.energy(), state == null ? -1 : state.face(), state == null || state.spell() == null ? "" : state.spell().key,
                state == null ? 0 : (int) (state.spellMillis() / 60_000L), reasons(state), state != null && state.female());
        if (stale || !packet.equals(tracker.states.get(mob.id()))) { tracker.states.put(mob.id(), packet); out.state(player, packet); }
    }

    /** Причины для карточки над головой: «+друг рядом», «-гроза», по строке, не больше трёх. */
    static String reasons(MobState.View state) {
        if (state == null) return "";
        StringBuilder out = new StringBuilder();
        for (MobState.Reason reason : state.reasons().stream().limit(3).toList()) {
            if (!out.isEmpty()) out.append('\n');
            out.append(reason.sign() > 0 ? '+' : reason.sign() < 0 ? '-' : '~').append(reason.text());
        }
        return out.toString();
    }

    private static int hearts(MobCard card) {
        if (!"minecraft:villager".equals(card.type())) return 0;
        int value = card.relationship();
        return value >= io.github.mobtalk.core.Hearts.marriage() ? 3 : value >= io.github.mobtalk.core.Hearts.dating() ? 2 : value >= io.github.mobtalk.core.Hearts.friend() ? 1 : 0;
    }

    /** «ждёт малыша · 3 из 5 дн.» — строка трека N под именем беременной жительницы. */
    private String pregnancyLine(UUID mob, long tick, MobTalkConfig config) {
        if (!config.pregnancy || !config.pregnancyStatus) return "";
        for (ChildLedger.Pregnant p : bridge.memory().children().pregnancies()) {
            if (!p.known(tick) || !mob.equals(p.villager())) continue;
            Pregnancy.Term term = p.term(tick);
            return Pregnancy.status(term, p.day(tick), p.days());
        }
        return "";
    }

    /** Для тестов: сколько мобов у игрока с подписью. */
    static List<UUID> labelled(Tracker tracker) { return tracker.labels.keySet().stream().toList(); }
}
