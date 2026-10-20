package io.github.alicespeaker.client;

import io.github.alicespeaker.AliceSpeakerMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AudioStream;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundLoader;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.concurrent.CompletableFuture;

/** Alice's voice coming out of one speaker block; stops when the block or the world goes away. */
final class SpeakerVoiceSound extends MovingSoundInstance {
    private final MinecraftClient client;
    private final ClientWorld world;
    private final BlockPos pos;
    private final PcmAudioStream stream;
    private int age;

    SpeakerVoiceSound(MinecraftClient client, ClientWorld world, BlockPos pos, byte[] pcm, int rate) {
        super(SoundEvent.of(Identifier.of(AliceSpeakerMod.ID, "voice")), SoundCategory.RECORDS, SoundInstance.createRandom());
        this.client = client;
        this.world = world;
        this.pos = pos.toImmutable();
        this.stream = new PcmAudioStream(pcm, rate);
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;
        this.volume = 1.0f;
    }

    @Override public boolean canPlay() {
        return client.world == world && world.getBlockState(pos).isOf(AliceSpeakerMod.SPEAKER);
    }

    @Override public void tick() {
        if (!canPlay()) { setDone(); return; }
        if (age++ % 8 == 0)
            world.addParticle(ParticleTypes.NOTE, x, y + 0.5, z, world.random.nextInt(25) / 24.0, 0, 0);
    }

    @Override public CompletableFuture<AudioStream> getAudioStream(SoundLoader loader, Identifier id, boolean repeatInstantly) {
        return CompletableFuture.completedFuture(stream);
    }

    void stop() {
        setDone();
        client.getSoundManager().stop(this);
    }
}
