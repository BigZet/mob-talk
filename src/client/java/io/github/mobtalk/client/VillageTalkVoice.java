package io.github.mobtalk.client;

import io.github.mobtalk.core.ConversationEngine;
import net.minecraft.entity.mob.MobEntity;
import java.util.List;

/** Болтовня деревни после переноса на сервер (трек A): пары, тексты и очередь ведёт {@code SpontaneousVoices} на сервере, строки
 * приходят как {@code MobSpeechS2C} вида {@code village} со звуком чанками. Здесь остался только путь самотеста
 * ({@link #playForTest}): уже написанные строки звучат одна за другой тем же путём, что настоящие. */
final class VillageTalkVoice {
    private final MobTalkClient owner;
    private List<ConversationEngine.ChatterLine> lines;
    private int next;

    VillageTalkVoice(MobTalkClient owner) { this.owner = owner; }

    /** Строки самотеста ещё звучат. */
    boolean active() { return lines != null; }
    boolean voicing() { return lines != null; }

    void tick(long ticks) {
        if (lines == null || ticks % 10 != 3 || owner.speaking() || owner.busyTalking()) return;
        if (next >= lines.size()) { lines = null; return; }
        ConversationEngine.ChatterLine line = lines.get(next);
        MobEntity speaker = MobSpeechSound.find(owner.world, line.speaker());
        if (speaker == null) { cancel(); return; }
        next++;
        owner.playOverheard(line.speaker(), "§7" + line.name() + ": " + line.text(), line.audio(), owner.config().villageChatterRange + 4);
    }

    private double range() { return owner.config().villageChatterRange + 4; }
    /** Самотест: разговор, уже написанный (синтетические голоса), звучит тем же путём, что настоящий. */
    void playForTest(List<ConversationEngine.ChatterLine> written) { cancel(); lines = written; next = 0; }

    /** Игрок заговорил, погиб или мир сменился: непроигранные строки самотеста сняты. */
    void cancel() {
        if (lines != null) for (int i = next; i < lines.size(); i++) if (lines.get(i).audio() != null) lines.get(i).audio().close();
        lines = null;
    }
}
