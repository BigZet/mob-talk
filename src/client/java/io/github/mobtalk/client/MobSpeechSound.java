package io.github.mobtalk.client;

import io.github.mobtalk.core.SpeechStream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class MobSpeechSound extends MovingSoundInstance {
    private final MinecraftClient client;
    private final ClientWorld world;
    private final UUID target;
    private final double maxDistance;
    private final PcmSpeechAudioStream stream;
    private final SpeechStream speech;

    MobSpeechSound(MinecraftClient client, ClientWorld world, UUID target, double maxDistance, SpeechStream speech) {
        super(SoundEvent.of(Identifier.of("mobtalk", "mob_speech")), SoundCategory.NEUTRAL, SoundInstance.createRandom());
        this.client = client;
        this.world = world;
        this.target = target;
        this.maxDistance = maxDistance;
        this.speech = speech;
        this.stream = new PcmSpeechAudioStream(speech);
        updatePosition();
    }

    static MobEntity find(ClientWorld world, UUID uuid) {
        if (world == null || uuid == null) return null;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof MobEntity mob && mob.getUuid().equals(uuid) && mob.isAlive() && !mob.isRemoved()) return mob;
        }
        return null;
    }

    @Override public boolean canPlay() {
        MobEntity mob = find(world, target);
        return client.world == world && client.player != null && mob != null
                && client.player.squaredDistanceTo(mob) <= maxDistance * maxDistance;
    }

    @Override public void tick() {
        if (!canPlay()) setDone();
        else updatePosition();
    }

    private void updatePosition() {
        MobEntity mob = find(world, target);
        if (mob == null) { setDone(); return; }
        x = mob.getX();
        y = mob.getY() + mob.getHeight() * 0.5;
        z = mob.getZ();
    }

    @Override public CompletableFuture<AudioStream> getAudioStream(SoundLoader loader, Identifier id, boolean repeatInstantly) {
        return CompletableFuture.completedFuture(stream);
    }

    void stop() {
        setDone();
        speech.close();
        client.getSoundManager().stop(this);
    }
}
