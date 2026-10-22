package io.github.mobtalk.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Раздел 16.2 плана: каждый пакет раздела 12.1 кодируется и раскодируется в равную запись; границы и отказ на слишком большом. */
class NetCodecTest {
    private static <T extends CustomPayload> T roundTrip(PacketCodec<PacketByteBuf, T> codec, T value) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        codec.encode(buf, value);
        assertTrue(buf.readableBytes() <= MobTalkNet.MAX_PAYLOAD, "в пределах 1 МБ");
        T decoded = codec.decode(buf);
        assertEquals(0, buf.readableBytes(), "прочитано целиком");
        return decoded;
    }
    private static <T extends CustomPayload> void same(PacketCodec<PacketByteBuf, T> codec, T value) {
        assertEquals(value, roundTrip(codec, value), value.getClass().getSimpleName());
        assertEquals("mobtalk", value.getId().id().getNamespace());
    }
    private static byte[] bytes(int length) { byte[] data = new byte[length]; for (int i = 0; i < length; i++) data[i] = (byte) (i * 31); return data; }

    @Test void clientToServer() {
        same(TalkStartC2S.CODEC, new TalkStartC2S(42));
        same(TalkTextC2S.CODEC, new TalkTextC2S(42, "Привет, Марфа! Как дела?", true, bytes(1000)));
        same(TalkTextC2S.CODEC, new TalkTextC2S(42, "", false, new byte[0]));
        same(TalkAudioC2S.CODEC, new TalkAudioC2S(42, "wav16k", 15, bytes(MobTalkNet.MAX_AUDIO)));
        same(TalkCancelC2S.CODEC, new TalkCancelC2S("world changed"));
        same(GiftC2S.CODEC, new GiftC2S(7));
        same(MobActionC2S.CODEC, new MobActionC2S(7, "{\"type\":\"STAY\",\"minutes\":5}"));
        same(PlayerSettingsC2S.CODEC, new PlayerSettingsC2S("{\"whisperModel\":\"large-v3\",\"playerGender\":\"male\"}"));
        same(ScreenRequestC2S.CODEC, new ScreenRequestC2S("family", 0));
    }

    @Test void serverToClient() {
        same(TalkStatusS2C.CODEC, new TalkStatusS2C("Моб думает…", true, ""));
        same(TalkStatusS2C.CODEC, new TalkStatusS2C("", false, "Лимит на сегодня исчерпан"));
        same(MobSpeechS2C.CODEC, new MobSpeechS2C(42, UUID.randomUUID(), "Марфа", "Му-у, чего надо?", true, 0, 16.5));
        same(MobSpeechS2C.CODEC, new MobSpeechS2C(42, UUID.randomUUID(), "Марфа", "Му-у", false, 9, 48));
        same(SpeechClipS2C.CODEC, new SpeechClipS2C(9, 0, 2, "mp3", bytes(MobTalkNet.MAX_CHUNK)));
        same(SpeechClipS2C.CODEC, new SpeechClipS2C(9, 1, 2, "mp3", bytes(17)));
        same(MobStateS2C.CODEC, new MobStateS2C(42, 50, 60, 70, 80, "в духе", 35, "подруга", 2));
        same(MobLabelS2C.CODEC, new MobLabelS2C(42, "Марфа", "ждёт ребёнка"));
        same(ServerFeaturesS2C.CODEC, new ServerFeaturesS2C("{\"version\":\"0.7.0\",\"sttPaths\":[\"local\",\"text\"]}"));
        same(ScreenDataS2C.CODEC, new ScreenDataS2C("info", "{\"mob\":\"Марфа\"}"));
        same(NoticeS2C.CODEC, new NoticeS2C("Настройки приняты", true));
    }

    @Test void nullBytesBecomeEmpty() {
        assertEquals(0, new TalkTextC2S(1, "x", false, null).picture().length);
        assertEquals(0, new TalkAudioC2S(1, "mp3", 1, null).audio().length);
        assertEquals(0, new SpeechClipS2C(1, 0, 1, "mp3", null).chunk().length);
    }

    @Test void oversizedPayloadsAreRefused() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        assertThrows(IllegalArgumentException.class, () -> TalkAudioC2S.CODEC.encode(buf, new TalkAudioC2S(1, "mp3", 20, bytes(MobTalkNet.MAX_AUDIO + 1))));
        assertThrows(IllegalArgumentException.class, () -> TalkTextC2S.CODEC.encode(buf, new TalkTextC2S(1, "", false, bytes(MobTalkNet.MAX_PICTURE + 1))));
        assertThrows(IllegalArgumentException.class, () -> SpeechClipS2C.CODEC.encode(buf, new SpeechClipS2C(1, 0, 1, "mp3", bytes(MobTalkNet.MAX_CHUNK + 1))));
        // Чужой пакет с байтами сверх предела отвергается и при чтении, не доходя до логики.
        PacketByteBuf hostile = new PacketByteBuf(Unpooled.buffer());
        hostile.writeVarInt(1); hostile.writeString("mp3"); hostile.writeVarInt(20); hostile.writeByteArray(bytes(MobTalkNet.MAX_AUDIO + 1));
        assertThrows(RuntimeException.class, () -> TalkAudioC2S.CODEC.decode(hostile));
    }

    @Test void everyPacketHasItsOwnChannel() {
        List<CustomPayload.Id<?>> ids = List.of(TalkStartC2S.ID, TalkTextC2S.ID, TalkAudioC2S.ID, TalkCancelC2S.ID, GiftC2S.ID, MobActionC2S.ID,
                PlayerSettingsC2S.ID, ScreenRequestC2S.ID, TalkStatusS2C.ID, MobSpeechS2C.ID, SpeechClipS2C.ID, MobStateS2C.ID, MobLabelS2C.ID,
                ServerFeaturesS2C.ID, ScreenDataS2C.ID, NoticeS2C.ID);
        assertEquals(16, ids.stream().map(id -> id.id().toString()).distinct().count());
    }
}
