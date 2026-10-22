package io.github.mobtalk;

import io.github.mobtalk.core.PlayerSettings;
import io.github.mobtalk.core.ServerFeatures;
import io.github.mobtalk.core.SettingsScope;
import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.net.MobSpeechS2C;
import io.github.mobtalk.net.MobTalkNet;
import io.github.mobtalk.net.ServerFeaturesS2C;
import io.github.mobtalk.net.TalkStatusS2C;
import io.github.mobtalk.net.TalkTextC2S;
import io.github.mobtalk.server.PlayerSettingsStore;
import io.github.mobtalk.server.ServerConfig;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Прогон без клиента (раздел 16.3 плана): {@code gradlew runServer -Dmobtalk.selftest.server=true} или
 * {@code scripts/run-local-server.ps1 -SelfTest}. На поднятом мосту проверяет память мира, кодеки пакетов, JSON возможностей и личных
 * настроек, хранилище игроков, затем пишет {@code [mobtalk-server-selftest] ALL CHECKS PASSED} (или FAIL с причинами) и останавливает сервер. */
final class ServerSelfTest {
    private ServerSelfTest() { }
    private static final String TAG = "[mobtalk-server-selftest]";

    static void run(MinecraftServer server, MobGameBridge bridge) {
        List<String> failures = new ArrayList<>();
        check(failures, "мост активен", MobGameBridge.active(server) == bridge);
        check(failures, "память мира открыта", bridge.memory() != null && bridge.memory().cards(null) != null);
        check(failures, "снимок настроек серверный", bridge.config() != null && SettingsScope.serverOnly(bridge.config()).whisperUrl.equals(bridge.config().whisperUrl));
        try {
            TalkTextC2S text = new TalkTextC2S(7, "Привет, Марфа", true, new byte[] {1, 2, 3});
            check(failures, "кодек TalkTextC2S", roundTrip(TalkTextC2S.CODEC, text).equals(text));
            MobSpeechS2C speech = new MobSpeechS2C(7, UUID.randomUUID(), "Марфа", "Му-у", true, 0, 16, MobSpeechS2C.ANSWER, 0, "Привет", "");
            check(failures, "кодек MobSpeechS2C", roundTrip(MobSpeechS2C.CODEC, speech).equals(speech));
            TalkStatusS2C status = new TalkStatusS2C(MobTalkMod.NOT_YET, false, "");
            check(failures, "кодек TalkStatusS2C", roundTrip(TalkStatusS2C.CODEC, status).equals(status));
            ServerFeatures features = new ServerFeatures(MobTalkMod.version(), true, false, false, false, List.of("local", "text"), 15, "ADMIN", "");
            ServerFeaturesS2C packet = new ServerFeaturesS2C(features.toJson());
            check(failures, "ServerFeatures через пакет", ServerFeatures.fromJson(roundTrip(ServerFeaturesS2C.CODEC, packet).json()).equals(features));
        } catch (RuntimeException failure) { failures.add("кодеки: " + failure); }
        try {
            UUID player = UUID.randomUUID();
            PlayerSettings settings = PlayerSettings.fromJson("{\"whisperModel\":\"small\",\"llmModel\":\"hack\"}");
            PlayerSettingsStore.put(player, settings);
            PlayerSettingsStore.flush();
            check(failures, "личные настройки сохранены", PlayerSettingsStore.of(player).equals(settings) && settings.value("llmModel") == null);
            PlayerSettingsStore.remove(player);
            PlayerSettingsStore.flush();
        } catch (RuntimeException failure) { failures.add("хранилище игроков: " + failure); }
        check(failures, "серверные настройки читаются", ServerConfig.get() != null && ServerConfig.get().maxDistance > 0);
        if (failures.isEmpty()) MobTalkMod.LOGGER.info("{} ALL CHECKS PASSED", TAG);
        else for (String failure : failures) MobTalkMod.LOGGER.error("{} FAIL {}", TAG, failure);
        MobTalkMod.LOGGER.info("{} остановка сервера", TAG);
        server.stop(false);
    }

    private static void check(List<String> failures, String what, boolean ok) {
        if (ok) MobTalkMod.LOGGER.info("{} OK {}", TAG, what); else failures.add(what);
    }

    private static <T> T roundTrip(net.minecraft.network.codec.PacketCodec<PacketByteBuf, T> codec, T value) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        codec.encode(buf, value);
        if (buf.readableBytes() > MobTalkNet.MAX_PAYLOAD) throw new IllegalStateException("пакет больше 1 МБ");
        return codec.decode(buf);
    }
}
