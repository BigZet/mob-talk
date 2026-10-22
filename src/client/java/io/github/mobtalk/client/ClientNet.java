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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Клиентская сторона пакетов: приём S2C (трек A: реплики, звук, состояние, экраны) и личные настройки серверу при входе.
 * Приёмники Fabric уже на потоке клиента. */
final class ClientNet {
    private ClientNet() { }
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-client-net");
    private static volatile ServerFeatures features = ServerFeatures.none();

    /** Что сообщил сервер при входе; {@link ServerFeatures#none()} до ответа и на сервере без Mob Talk. */
    static ServerFeatures features() { return features; }

    static void register(MobTalkClient owner) {
        ClientPlayNetworking.registerGlobalReceiver(ServerFeaturesS2C.ID, (payload, context) -> {
            features = ServerFeatures.fromJson(payload.json());
            LOGGER.info("Mob Talk: сервер {} (уровень {}, диалог {}, пути распознавания {})", features.version(), features.level(), features.dialogue(), features.sttPaths());
            SelfTestServer.features(features);
        });
        ClientPlayNetworking.registerGlobalReceiver(TalkStatusS2C.ID, (payload, context) -> {
            SelfTestServer.status(payload.status(), payload.busy(), payload.error());
            owner.onStatus(payload.status(), payload.busy(), payload.error());
        });
        ClientPlayNetworking.registerGlobalReceiver(NoticeS2C.ID, (payload, context) -> {
            if (payload.toast()) SystemToast.add(context.client().getToastManager(), SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal("Mob Talk"), Text.literal(payload.text()));
            else owner.chat(payload.text());
            SelfTestServer.notice(payload.text());
        });
        ClientPlayNetworking.registerGlobalReceiver(MobSpeechS2C.ID, (payload, context) -> { SelfTestServer.speech(payload); owner.onSpeech(payload); });
        ClientPlayNetworking.registerGlobalReceiver(SpeechClipS2C.ID, (payload, context) -> owner.onClip(payload));
        ClientPlayNetworking.registerGlobalReceiver(MobStateS2C.ID, (payload, context) -> { SelfTestServer.state(payload); owner.onState(payload); });
        ClientPlayNetworking.registerGlobalReceiver(MobLabelS2C.ID, (payload, context) -> { SelfTestServer.label(payload); owner.onLabel(payload); });
        ClientPlayNetworking.registerGlobalReceiver(ScreenDataS2C.ID, (payload, context) -> {
            if (VoiceInput.screenData(payload)) return; // Трек B: ответ проверки микрофона через сервер.
            if (io.github.mobtalk.client.settings.ServerSettingsView.SCREEN.equals(payload.screen())) { io.github.mobtalk.client.settings.ServerSettingsView.accept(payload.json()); return; } // Трек C: серверные настройки для F7.
            owner.onScreen(payload.screen(), payload.json());
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            features = ServerFeatures.none();
            io.github.mobtalk.client.settings.ServerSettingsView.reset();
            sendSettings(owner);
        });
    }

    /** Личные настройки серверу: при входе и после сохранения в F7. */
    static void sendSettings(MobTalkClient owner) {
        if (owner.config() == null || !owner.connected() || !ClientPlayNetworking.canSend(PlayerSettingsC2S.ID)) return;
        ClientPlayNetworking.send(new PlayerSettingsC2S(PlayerSettings.of(owner.config()).toJson()));
    }
}
