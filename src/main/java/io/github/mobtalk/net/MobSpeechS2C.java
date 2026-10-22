package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.UUID;

/** Реплика моба всем в радиусе. {@code toYou} — адресована этому игроку; {@code clipId} 0 — без звука, иначе следом придут чанки
 * {@link SpeechClipS2C} с этим номером; {@code kind} — {@link #ANSWER}, {@link #WITNESS}, {@link #INITIATIVE}, {@link #VILLAGE},
 * {@link #NOTICE} или {@link #FALLBACK}; {@code millis} — длительность звука (сколько клиент держит «говорит…»); {@code heard} — что
 * сказал игрок (пусто у посторонних реплик); {@code asker} — ник спросившего (пусто, если спросил сам получатель или никто).
 * Трек A расширил пакет раздела 12.1 полями {@code kind, millis, heard, asker}. */
public record MobSpeechS2C(int mobId, UUID mob, String speaker, String text, boolean toYou, int clipId, double range,
                           String kind, int millis, String heard, String asker) implements CustomPayload {
    public static final Id<MobSpeechS2C> ID = MobTalkNet.payloadId("mob_speech");
    public static final PacketCodec<PacketByteBuf, MobSpeechS2C> CODEC = MobTalkNet.codec(MobSpeechS2C::write, MobSpeechS2C::read);
    public static final String ANSWER = "answer", WITNESS = "witness", INITIATIVE = "initiative", VILLAGE = "village", NOTICE = "notice", FALLBACK = "fallback";

    public MobSpeechS2C {
        speaker = speaker == null ? "" : speaker; text = text == null ? "" : text; kind = kind == null || kind.isEmpty() ? ANSWER : kind;
        heard = heard == null ? "" : heard; asker = asker == null ? "" : asker;
    }
    /** Старая форма раздела 12.1: ответ собеседнику без звука и без слов игрока. */
    public MobSpeechS2C(int mobId, UUID mob, String speaker, String text, boolean toYou, int clipId, double range) {
        this(mobId, mob, speaker, text, toYou, clipId, range, ANSWER, 0, "", "");
    }

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId); buf.writeUuid(mob); MobTalkNet.writeText(buf, speaker); MobTalkNet.writeText(buf, text); buf.writeBoolean(toYou);
        buf.writeVarInt(clipId); buf.writeDouble(range); MobTalkNet.writeText(buf, kind); buf.writeVarInt(millis); MobTalkNet.writeText(buf, heard); MobTalkNet.writeText(buf, asker);
    }
    private static MobSpeechS2C read(PacketByteBuf buf) {
        return new MobSpeechS2C(buf.readVarInt(), buf.readUuid(), MobTalkNet.readText(buf), MobTalkNet.readText(buf), buf.readBoolean(),
                buf.readVarInt(), buf.readDouble(), MobTalkNet.readText(buf), buf.readVarInt(), MobTalkNet.readText(buf), MobTalkNet.readText(buf));
    }
    @Override public Id<MobSpeechS2C> getId() { return ID; }
    /** Реплика без текста: моб решил промолчать (клиент только отмечает это для инициативы). */
    public boolean silent() { return text.isBlank(); }
}
