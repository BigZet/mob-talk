package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.UUID;

/** Состояние моба для HUD и подписи, как его видит этот игрок: четыре оси, слово состояния ({@code word} — ключ
 * {@code mobtalk.state.<word>}, {@code wordSign} — его знак), отношение и его подпись ({@code label} — «отношение +12 (свой)»),
 * сердечки, строка услуги ({@code favor} — «услуга: Колокол 0/1» или пусто) и ход торга ({@code bargain} — ключ
 * {@code mobtalk.bargain.<key>} или пусто, {@code bargainSign}). Трек A расширил пакет раздела 12.1 полями {@code mob, wordSign,
 * favor, bargain, bargainSign}. Настроение (2026-10-25) добавило бодрость {@code energy}, мордочку над головой {@code face} (номер
 * глифа шрифта {@code mobtalk:mood}, −1 — без мордочки), долгое состояние {@code spell} (ключ {@link io.github.mobtalk.core.MoodSpell},
 * пусто — нет) с его минутами и причины для карточки {@code reasons} — строки «+дождь», «-гроза» через перевод строки;
 * {@code female} — слова состояния в женском роде (ключ {@code mobtalk.state.<word>.f}, если есть). */
public record MobStateS2C(int mobId, UUID mob, int calm, int trust, int interest, int mood, String word, int wordSign, int relationship,
                          String label, int hearts, String favor, String bargain, int bargainSign,
                          int energy, int face, String spell, int spellMinutes, String reasons, boolean female) implements CustomPayload {
    public static final Id<MobStateS2C> ID = MobTalkNet.payloadId("mob_state");
    public static final PacketCodec<PacketByteBuf, MobStateS2C> CODEC = MobTalkNet.codec(MobStateS2C::write, MobStateS2C::read);

    public MobStateS2C {
        word = word == null ? "" : word; label = label == null ? "" : label; favor = favor == null ? "" : favor; bargain = bargain == null ? "" : bargain;
        spell = spell == null ? "" : spell; reasons = reasons == null ? "" : reasons;
    }
    /** Без настроения: старые поля, бодрость 60, без мордочки и причин. */
    public MobStateS2C(int mobId, UUID mob, int calm, int trust, int interest, int mood, String word, int wordSign, int relationship,
                       String label, int hearts, String favor, String bargain, int bargainSign) {
        this(mobId, mob, calm, trust, interest, mood, word, wordSign, relationship, label, hearts, favor, bargain, bargainSign, 60, -1, "", 0, "", false);
    }

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId); buf.writeUuid(mob); buf.writeVarInt(calm); buf.writeVarInt(trust); buf.writeVarInt(interest); buf.writeVarInt(mood);
        MobTalkNet.writeText(buf, word); buf.writeVarInt(wordSign); buf.writeVarInt(relationship); MobTalkNet.writeText(buf, label); buf.writeVarInt(hearts);
        MobTalkNet.writeText(buf, favor); MobTalkNet.writeText(buf, bargain); buf.writeVarInt(bargainSign);
        buf.writeVarInt(energy); buf.writeVarInt(face); MobTalkNet.writeText(buf, spell); buf.writeVarInt(spellMinutes); MobTalkNet.writeText(buf, reasons); buf.writeBoolean(female);
    }
    private static MobStateS2C read(PacketByteBuf buf) {
        return new MobStateS2C(buf.readVarInt(), buf.readUuid(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                MobTalkNet.readText(buf), buf.readVarInt(), buf.readVarInt(), MobTalkNet.readText(buf), buf.readVarInt(),
                MobTalkNet.readText(buf), MobTalkNet.readText(buf), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), MobTalkNet.readText(buf), buf.readVarInt(), MobTalkNet.readText(buf), buf.readBoolean());
    }
    @Override public Id<MobStateS2C> getId() { return ID; }
}
