package io.github.mobtalk.server;

import io.github.mobtalk.core.ActionType;
import io.github.mobtalk.core.CombatRules;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.Decider;
import io.github.mobtalk.core.FriendActions;
import io.github.mobtalk.core.Hearts;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobInfo;
import io.github.mobtalk.core.MobRelations;
import io.github.mobtalk.core.MobState;
import io.github.mobtalk.core.SpeciesCatalog;
import io.github.mobtalk.core.VillageRumors;
import io.github.mobtalk.game.GameBridge;
import io.github.mobtalk.game.GameBridge.PlayerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Тексты для команд и экранов, которые раньше собирал клиент из памяти встроенного сервера: /mobtalk state, положение в деревне
 * (F7), подпись отношения. Только ядро: без Minecraft и без перевода (слова состояния — ключи, клиент их не переводит здесь, поэтому
 * берём русские слова из {@link MobState.Descriptor#word()}). */
final class ScreenReports {
    private ScreenReports() { }

    /** «отношение +N (свой) ♥♥» для HUD и заголовка окна сделок; без карточки — незнакомец. */
    static String relationshipLabel(MobCard card) {
        int value = card == null ? 0 : card.relationship();
        boolean hostile = card != null && SpeciesCatalog.get(card.type()).hostile();
        String mark = hostile && value >= CombatRules.ALLY ? " (свой)" : hostile && value >= CombatRules.TRUCE ? " (перемирие)" : "";
        String hearts = card != null && "minecraft:villager".equals(card.type()) ? Hearts.symbols(value) : "";
        return "отношение " + (value >= 0 ? "+" : "") + value + mark + (hearts.isEmpty() ? "" : " " + hearts);
    }

    /** /mobtalk state: все оси состояния собеседника словами, его мысль, аренды, связи, слухи, нрав и деревня. */
    static String stateLine(GameBridge bridge, UUID selected, UUID player, PlayerView view, Decider decider) {
        MobCard card = selected == null ? null : bridge.memory().card(selected, player);
        if (card == null) return "Состояние: собеседник не выбран или ещё незнаком";
        MobState.View state = card.state();
        StringBuilder text = new StringBuilder("Состояние ").append(card.name().isBlank() ? card.type() : card.name()).append(": ");
        List<String> words = new ArrayList<>();
        for (MobState.Descriptor word : state.words()) words.add(word.word());
        text.append(words.isEmpty() ? "ровно" : String.join(", ", words))
                .append(" (настроение ").append(state.mood()).append(", спокойствие ").append(state.calm())
                .append(", интерес ").append(state.interest()).append(", доверие ").append(state.trust()).append(')');
        if (!state.thought().isEmpty()) text.append(" · мысль: «").append(state.thought()).append('»');
        long now = view.timeOfDay();
        for (FriendActions.Status active : bridge.friendStatus(selected))
            text.append(" · ").append(active.type().name().toLowerCase(Locale.ROOT)).append(active.type() == ActionType.SPARE ? " " + active.target() : "")
                    .append(" ещё ~").append(Math.max(1, (active.expires() - now + 1199) / 1200)).append(" мин");
        List<MobRelations.View> bonds = bridge.memory().bonds(selected, System.currentTimeMillis());
        if (!bonds.isEmpty()) text.append(" · свои: ").append(String.join(", ", bonds.stream().limit(6).map(MobRelations.View::label).toList()));
        List<VillageRumors.View> rumors = bridge.memory().rumorsOf(selected, System.currentTimeMillis());
        if (!rumors.isEmpty()) text.append(" · слухи: ").append(String.join("; ", rumors.stream().limit(3).map(VillageRumors.View::label).toList()));
        String social = bridge.memory().social().describe(selected, player);
        if (!social.isBlank()) text.append(" · нрав: ").append(social);
        ConversationStore.Village village = bridge.villageAt(player, view.dimension(), (int) view.x(), (int) view.z());
        if (village != null) text.append(" · ").append(MobInfo.standingLine(bridge.memory().social().standing(village.id(), player, System.currentTimeMillis()), village.name()));
        text.append(" · решатель: ").append(decider.name());
        return text.toString();
    }

    /** Положение игрока в деревне, где он стоит, для F7. */
    static List<String> villageLines(GameBridge bridge, UUID player, PlayerView view) {
        ConversationStore.Village village = bridge.villageAt(player, view.dimension(), (int) view.x(), (int) view.z());
        return MobInfo.standingLines(village == null ? null : bridge.memory().social().standing(village.id(), player, System.currentTimeMillis()),
                village == null ? "" : village.name());
    }
}
