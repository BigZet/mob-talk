package io.github.mobtalk.client.cards;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

/** Звуки стола: свои (раздача, переворот, фишки, тасовка — tools/cards_sounds.py) и ванильные голоса жителя. Громкость стола — множитель. */
final class TableSounds {
    private TableSounds() { }
    static final SoundEvent DEAL = SoundEvent.of(Identifier.of("mobtalk", "cards.deal"));
    static final SoundEvent FLIP = SoundEvent.of(Identifier.of("mobtalk", "cards.flip"));
    static final SoundEvent CHIPS = SoundEvent.of(Identifier.of("mobtalk", "cards.chips"));
    static final SoundEvent SHUFFLE = SoundEvent.of(Identifier.of("mobtalk", "cards.shuffle"));
    static float volume = 1;

    static void play(SoundEvent sound, float pitch, float loudness) {
        if (volume <= 0) return;
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, pitch, loudness * volume));
    }
    static void click() { play(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.5f); }
    static void villagerYes() { play(SoundEvents.ENTITY_VILLAGER_YES, 1.0f, 0.8f); }
    static void villagerNo() { play(SoundEvents.ENTITY_VILLAGER_NO, 1.0f, 0.8f); }
    static void villagerHmm() { play(SoundEvents.ENTITY_VILLAGER_AMBIENT, 1.0f, 0.7f); }
    static void win() { play(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.3f, 0.45f); }
    static void bust() { play(SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.6f, 0.8f); }
}
