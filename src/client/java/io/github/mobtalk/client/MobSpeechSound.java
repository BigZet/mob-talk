package io.github.mobtalk.client;

import io.github.mobtalk.core.SpeechStream;
import io.github.mobtalk.core.VoiceFalloff;
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

/** A mob's voice, coming from the mob: the source follows it every tick and its volume falls off smoothly with distance
 * ({@link VoiceFalloff}) instead of OpenAL's 16-block linear attenuation and a hard stop at the conversation range. It stops
 * only when the mob dies or unloads, the world changes, or the player is past the point where it is already silent. It plays in the
 * «Voice/Speech» sound category: every player sets the loudness of mob voices in their own sound options. */
final class MobSpeechSound extends MovingSoundInstance {
    private final MinecraftClient client;
    private final ClientWorld world;
    private final UUID target;
    private final VoiceFalloff.Curve curve;
    private final PcmSpeechAudioStream stream;
    private final SpeechStream speech;
    private double distance;
    /** Why it stopped (self-test log): world, mob, edge, stop. */
    private String stopReason = "";

    MobSpeechSound(MinecraftClient client, ClientWorld world, UUID target, VoiceFalloff.Curve curve, SpeechStream speech) {
        super(SoundEvent.of(Identifier.of("mobtalk", "mob_speech")), SoundCategory.VOICE, SoundInstance.createRandom());
        this.client = client;
        this.world = world;
        this.target = target;
        this.curve = curve;
        this.speech = speech;
        this.stream = new PcmSpeechAudioStream(speech);
        // The volume below does the distance falloff; OpenAL keeps only the direction (panning) of the source.
        this.attenuationType = AttenuationType.NONE;
        updatePosition();
        this.volume = (float) curve.gain(distance);
    }

    static MobEntity find(ClientWorld world, UUID uuid) {
        if (world == null || uuid == null) return null;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof MobEntity mob && mob.getUuid().equals(uuid) && mob.isAlive() && !mob.isRemoved()) return mob;
        }
        return null;
    }

    @Override public boolean canPlay() {
        return client.world == world && client.player != null && !isDone() && !(curve.beyond(distance) && volume <= VoiceFalloff.INAUDIBLE);
    }
    /** Started far away it is quiet, not skipped: the player may come closer while it speaks. */
    @Override public boolean shouldAlwaysPlay() { return true; }

    @Override public void tick() {
        if (client.world != world || client.player == null) { stopReason = "world"; setDone(); return; }
        if (!updatePosition()) return;
        volume = (float) VoiceFalloff.approach(volume, curve.gain(distance), VoiceFalloff.STEP_PER_TICK);
        // Past the edge and already faded: nothing audible is cut.
        if (curve.beyond(distance) && volume <= VoiceFalloff.INAUDIBLE) { stopReason = "edge"; setDone(); }
    }

    private boolean updatePosition() {
        MobEntity mob = find(world, target);
        if (mob == null) {
            // Died, or unloaded: a real reason to stop. But while the player lies dead and just after the respawn the client briefly
            // loses track of every creature around (seen in the voicefix self-test): the line goes on from where its mob stood.
            if (diedOrGone() || !playerAway()) { stopReason = "mob"; setDone(); return false; }
            if (missingSince == 0) missingSince = System.currentTimeMillis();
            if (System.currentTimeMillis() - missingSince > MISSING_HOLD_MILLIS) { stopReason = "mob"; setDone(); return false; }
            if (client.player != null) distance = Math.sqrt(client.player.squaredDistanceTo(x, y, z));
            return true;
        }
        missingSince = 0;
        x = mob.getX();
        y = mob.getY() + mob.getHeight() * 0.5;
        z = mob.getZ();
        distance = client.player == null ? 0 : Math.sqrt(client.player.squaredDistanceTo(x, y, z));
        return true;
    }

    private long missingSince;
    private static final long MISSING_HOLD_MILLIS = 15_000;
    /** The player is dead or has just respawned (the creatures around are being sent again). */
    private boolean playerAway() { return client.player == null || !client.player.isAlive() || client.player.age < 60; }
    /** The mob is still known to the client but dead or removed for good. */
    private boolean diedOrGone() {
        for (Entity entity : world.getEntities())
            if (entity.getUuid().equals(target)) return !entity.isAlive() || entity.isRemoved();
        return false;
    }

    @Override public CompletableFuture<AudioStream> getAudioStream(SoundLoader loader, Identifier id, boolean repeatInstantly) {
        return CompletableFuture.completedFuture(stream);
    }

    UUID mob() { return target; }
    /** Current volume 0..1 and the distance it was computed for (self-test log). */
    double gain() { return volume; }
    double distance() { return distance; }
    /** All of the speech was handed to the sound system (self-test: it ended by itself, not cut). */
    boolean fullyRead() { return stream.ended(); }

    String stopReason() { return stopReason; }

    void stop() {
        stopReason = "stop";
        setDone();
        speech.close();
        client.getSoundManager().stop(this);
    }
    /** It ended on its own (or was never played): the download behind it is closed. */
    void release() { speech.close(); }
}
