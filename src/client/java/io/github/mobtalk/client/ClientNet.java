package io.github.mobtalk.client;

import io.github.mobtalk.core.PlayerSettings;
import io.github.mobtalk.core.ServerFeatures;
import io.github.mobtalk.net.MobLabelS2C;
import io.github.mobtalk.net.MobSpeechS2C;
import io.github.mobtalk.net.MobStateS2C;
import io.github.mobtalk.net.NoticeS2C;
import io.github.mobtalk.net.PlayerSettingsC2S;
import io.github.mobtalk.net.ScreenDataS2C;
import io.github.mobtalk.net.ServerFeaturesS2C;
import io.github.mobtalk.net.SpeechClipS2C;
import io.github.mobtalk.net.TalkStatusS2C;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Клиентская сторона пакетов (трек S1): приём S2C, личные настройки при входе и подсказка на выделенном сервере, где диалог
 * появится в треке A. Трек A заменяет приёмники речи и состояния своими, трек B — приём звука. */
final class ClientNet {
    private ClientNet() { }
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-client-net");
    static final String NOT_YET = "На сервере эта часть появится в треке A";
    private static volatile ServerFeatures features = ServerFeatures.none();
    private static boolean hinted;

    /** Что сообщил сервер при входе; {@link ServerFeatures#none()} в одиночке до ответа и на сервере без Mob Talk. */
    static ServerFeatures features() { return features; }

    static void register(MobTalkClient owner) {
        ClientPlayNetworking.registerGlobalReceiver(ServerFeaturesS2C.ID, (payload, context) -> {
            features = ServerFeatures.fromJson(payload.json());
            LOGGER.info("Mob Talk: сервер {} (уровень {}, пути распознавания {})", features.version(), features.level(), features.sttPaths());
        });
        ClientPlayNetworking.registerGlobalReceiver(TalkStatusS2C.ID, (payload, context) ->
                owner.notice(payload.error().isEmpty() ? payload.status() : payload.error()));
        ClientPlayNetworking.registerGlobalReceiver(NoticeS2C.ID, (payload, context) -> {
            if (payload.toast()) SystemToast.add(context.client().getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal("Mob Talk"), Text.literal(payload.text()));
            else owner.notice(payload.text());
        });
        ClientPlayNetworking.registerGlobalReceiver(MobSpeechS2C.ID, (payload, context) -> LOGGER.info("Mob Talk: MobSpeechS2C {} «{}» (трек A)", payload.speaker(), payload.text()));
        ClientPlayNetworking.registerGlobalReceiver(SpeechClipS2C.ID, (payload, context) -> LOGGER.debug("Mob Talk: SpeechClipS2C {} {}/{} (трек B)", payload.clipId(), payload.index(), payload.count()));
        ClientPlayNetworking.registerGlobalReceiver(MobStateS2C.ID, (payload, context) -> LOGGER.debug("Mob Talk: MobStateS2C {} (трек A)", payload.mobId()));
        ClientPlayNetworking.registerGlobalReceiver(MobLabelS2C.ID, (payload, context) -> LOGGER.debug("Mob Talk: MobLabelS2C {} (трек A)", payload.mobId()));
        ClientPlayNetworking.registerGlobalReceiver(ScreenDataS2C.ID, (payload, context) -> {
            // Трек C: серверные настройки для F7; трек A оставляет эту ветку, когда заменит приёмник экранов своим.
            if (io.github.mobtalk.client.settings.ServerSettingsView.SCREEN.equals(payload.screen())) io.github.mobtalk.client.settings.ServerSettingsView.accept(payload.json());
            else LOGGER.debug("Mob Talk: ScreenDataS2C {} (трек A)", payload.screen());
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            features = ServerFeatures.none();
            hinted = false;
            io.github.mobtalk.client.settings.ServerSettingsView.reset();
            sendSettings(owner);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> hint(owner, client));
    }

    /** Личные настройки серверу: при входе и после сохранения в F7. */
    static void sendSettings(MobTalkClient owner) {
        if (owner.config() == null || !ClientPlayNetworking.canSend(PlayerSettingsC2S.ID)) return;
        ClientPlayNetworking.send(new PlayerSettingsC2S(PlayerSettings.of(owner.config()).toJson()));
    }

    /** На выделенном сервере клавиши мода пока ничего не делают: одна подсказка в чат за вход, вместо тишины или NPE. */
    private static void hint(MobTalkClient owner, MinecraftClient client) {
        if (client.world == null || client.getServer() != null || client.player == null) return;
        boolean pressed = false;
        if (owner.talkKey != null) while (owner.talkKey.wasPressed()) pressed = true;
        if (owner.tradeKey != null) while (owner.tradeKey.wasPressed()) pressed = true;
        if (owner.giftKey != null) while (owner.giftKey.wasPressed()) pressed = true;
        if (pressed && !hinted) {
            hinted = true;
            client.player.sendMessage(Text.literal("[Mob Talk] " + NOT_YET + (features.version().isEmpty() ? " (на сервере нет Mob Talk)" : "")), false);
        }
    }
}
