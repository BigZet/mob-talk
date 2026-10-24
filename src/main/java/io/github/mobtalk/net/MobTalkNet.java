package io.github.mobtalk.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.nio.charset.StandardCharsets;

/** Пакеты Mob Talk (раздел 12.1 плана): канал {@code mobtalk:<имя>}, кодеки вручную, каждый пакет — record в своём файле.
 * Типы регистрирует один раз на обеих сторонах {@code MobTalkMod.onInitialize}; приёмники C2S вешает сервер, S2C — клиент. */
public final class MobTalkNet {
    private MobTalkNet() { }
    /** Предел полезной нагрузки одного пакета у Minecraft; всё крупнее режется на чанки ({@link SpeechClipS2C}). */
    public static final int MAX_PAYLOAD = 1024 * 1024;
    /** Снимок к вопросу: JPEG не больше 200 КБ. */
    public static final int MAX_PICTURE = 200 * 1024;
    /** Аудио на распознавание: не больше 900 КБ, чтобы с заголовком уложиться в {@link #MAX_PAYLOAD}. */
    public static final int MAX_AUDIO = 900 * 1024;
    /** Чанк озвучки. */
    public static final int MAX_CHUNK = 512 * 1024;
    /** Длинные строки (JSON настроек, экранов, намерений): предел в символах. */
    public static final int MAX_JSON = 256 * 1024;
    /** Обычные строки (реплика, статус, подпись). */
    public static final int MAX_TEXT = 8 * 1024;

    public static Identifier id(String name) { return Identifier.of("mobtalk", name); }

    public static <T extends CustomPayload> CustomPayload.Id<T> payloadId(String name) { return new CustomPayload.Id<>(id(name)); }

    /** Кодек из пары функций; {@code PacketByteBuf} годится и как {@code RegistryByteBuf} у Fabric. */
    public static <T extends CustomPayload> PacketCodec<PacketByteBuf, T> codec(java.util.function.BiConsumer<T, PacketByteBuf> write,
            java.util.function.Function<PacketByteBuf, T> read) {
        return PacketCodec.of((value, buf) -> write.accept(value, buf), buf -> read.apply(buf));
    }

    static void writeText(PacketByteBuf buf, String text) { buf.writeString(text == null ? "" : text, MAX_TEXT); }
    static String readText(PacketByteBuf buf) { return buf.readString(MAX_TEXT); }
    static void writeJson(PacketByteBuf buf, String json) { buf.writeString(json == null ? "" : json, MAX_JSON); }
    static String readJson(PacketByteBuf buf) { return buf.readString(MAX_JSON); }
    static void writeBytes(PacketByteBuf buf, byte[] data, int max, String what) {
        byte[] bytes = data == null ? new byte[0] : data;
        if (bytes.length > max) throw new IllegalArgumentException(what + " больше " + max / 1024 + " КБ: " + bytes.length);
        buf.writeByteArray(bytes);
    }
    static byte[] readBytes(PacketByteBuf buf, int max) { return buf.readByteArray(max); }
    /** Сколько байт займёт строка в UTF-8: для проверки предела пакета у вызывающего. */
    public static int utf8Length(String text) { return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length; }

    /** Клиент → сервер. */
    public static void registerC2S() {
        PayloadTypeRegistry.playC2S().register(TalkStartC2S.ID, TalkStartC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(TalkTextC2S.ID, TalkTextC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(TalkAudioC2S.ID, TalkAudioC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(TalkCancelC2S.ID, TalkCancelC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(GiftC2S.ID, GiftC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MobActionC2S.ID, MobActionC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(PlayerSettingsC2S.ID, PlayerSettingsC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(ScreenRequestC2S.ID, ScreenRequestC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(SelfTestLine.ID, SelfTestLine.CODEC);
        PayloadTypeRegistry.playC2S().register(MomentKeyC2S.ID, MomentKeyC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(RecStateC2S.ID, RecStateC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(MomentReplayC2S.ID, MomentReplayC2S.CODEC);
    }
    /** Сервер → клиент. */
    public static void registerS2C() {
        PayloadTypeRegistry.playS2C().register(TalkStatusS2C.ID, TalkStatusS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MobSpeechS2C.ID, MobSpeechS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(SpeechClipS2C.ID, SpeechClipS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MobStateS2C.ID, MobStateS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(MobLabelS2C.ID, MobLabelS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerFeaturesS2C.ID, ServerFeaturesS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ScreenDataS2C.ID, ScreenDataS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(NoticeS2C.ID, NoticeS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(SelfTestLine.ID, SelfTestLine.CODEC);
        PayloadTypeRegistry.playS2C().register(MomentS2C.ID, MomentS2C.CODEC);
    }
    /** Обе стороны: вызывается из {@code main}-входа, который работает и у клиента, и у сервера. */
    public static void registerPayloads() { registerC2S(); registerS2C(); }
}
