package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.game.AltarBlock;
import io.github.divineblock.game.BeamsPayload;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Church chants, all sounding from the altar itself and never covering the god: a piece greets the player who comes back
 * to the altar after a long while (and now and then sings again while he stays), and a blessing or a punishment gets its
 * music, low under the god's words and rising once he has spoken. Whatever plays sinks to a whisper while his voice
 * sounds. Each scene deals its pieces like a shuffled deck: none comes again before all the others have been heard. */
final class ChantPlayer {
    private static final List<String> ALTAR = List.of("altar_01", "altar_17", "altar_33", "altar_35", "altar_53", "altar_60");
    private static final List<String> BLESSING = List.of("blessing_03", "blessing_11", "blessing_15", "blessing_45", "blessing_46");
    private static final List<String> PUNISHMENT = List.of("punishment_02", "punishment_06", "punishment_20", "punishment_21",
            "punishment_39", "punishment_56", "punishment_57", "punishment_59");
    /** Loudness at the default setting: the altar's singing, and the music of a verdict. */
    private static final float ALTAR_VOLUME = 0.85f, MUSIC_VOLUME = 0.9f;
    /** How much of it is left while the god speaks. */
    private static final float UNDER_VOICE = 0.22f;
    private static final int NEAR = 12, FAR = 22;
    /** Away this long, the altar greets the player again when he returns; staying, he hears it again after a long pause. */
    private static final int AWAY_TICKS = 20 * 120, STAY_PAUSE = 20 * 240, STAY_PAUSE_SPREAD = 20 * 120;
    private static final Random RANDOM = new Random();
    private static final java.util.Map<List<String>, Deque<String>> decks = new java.util.HashMap<>();

    private static Chant altarChant, music;
    private static BlockPos altar;
    private static ClientWorld world;
    private static long nextAltarChant, nextScan, lastNear = Long.MIN_VALUE / 2;

    private ChantPlayer() { }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ChantPlayer::tick);
    }

    /** The god has begun to speak a verdict (his beams rise, in the colour of what is coming): a blessing or a punishment
     * gets its music; jests and omens keep the altar's song. */
    static void verdict(MinecraftClient client, int kind, BlockPos at) {
        List<String> pieces = kind == BeamsPayload.BLESSING ? BLESSING : kind == BeamsPayload.PUNISHMENT ? PUNISHMENT : null;
        if (pieces == null || volume() <= 0 || client.world == null || client.player == null) return;
        if (music != null) music.fadeOut();
        if (altarChant != null) altarChant.fadeOut();
        client.getMusicTracker().stop();
        music = new Chant(pick(pieces), MUSIC_VOLUME, at.toImmutable(), 30);
        client.getSoundManager().play(music);
        // The altar keeps quiet for a while after the verdict's music.
        nextAltarChant = client.world.getTime() + STAY_PAUSE;
    }

    private static void tick(MinecraftClient client) {
        if (client.world != world) { stopAll(client); world = client.world; }
        if (client.world == null || client.player == null) return;
        long now = client.world.getTime();
        if (volume() <= 0) { stopAll(client); return; }
        if (music != null && music.over(client)) music = null;
        if (altarChant != null && altarChant.over(client)) altarChant = null;
        if (now >= nextScan) { nextScan = now + 20; altar = nearestAltar(client); }
        boolean near = altar != null && client.player.getBlockPos().isWithinDistance(altar, FAR);
        if (altarChant != null && (!near || !altarChant.pos.equals(altar))) { altarChant.fadeOut(); altarChant = null; }
        boolean close = altar != null && client.player.getBlockPos().isWithinDistance(altar, NEAR);
        // Coming back after a long absence: the altar greets him at once, whatever pause was pending.
        if (close && now - lastNear > AWAY_TICKS) nextAltarChant = now;
        if (near) lastNear = now;
        if (altarChant == null && music == null && close && now >= nextAltarChant) {
            altarChant = new Chant(pick(ALTAR), ALTAR_VOLUME, altar, 40);
            client.getSoundManager().play(altarChant);
            nextAltarChant = now + STAY_PAUSE + RANDOM.nextInt(STAY_PAUSE_SPREAD);
        }
    }

    private static void stopAll(MinecraftClient client) {
        if (music != null) client.getSoundManager().stop(music);
        if (altarChant != null) client.getSoundManager().stop(altarChant);
        music = altarChant = null;
        altar = null;
        nextAltarChant = 0;
        lastNear = Long.MIN_VALUE / 2;
    }

    private static BlockPos nearestAltar(MinecraftClient client) {
        BlockPos center = client.player.getBlockPos(), best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(center.add(-NEAR, -6, -NEAR), center.add(NEAR, 6, NEAR))) {
            if (!(client.world.getBlockState(pos).getBlock() instanceof AltarBlock)) continue;
            double distance = pos.getSquaredDistance(center);
            if (distance < bestDistance) { bestDistance = distance; best = pos.toImmutable(); }
        }
        // An altar the player is walking away from keeps singing until he is out of earshot.
        if (best == null && altar != null && center.isWithinDistance(altar, FAR) && client.world.getBlockState(altar).getBlock() instanceof AltarBlock) return altar;
        return best;
    }

    /** The next card of this scene's deck; a fresh shuffle never starts with the piece that ended the last one. */
    private static String pick(List<String> pieces) {
        Deque<String> deck = decks.computeIfAbsent(pieces, key -> new ArrayDeque<>());
        if (deck.isEmpty()) {
            List<String> shuffled = new java.util.ArrayList<>(pieces);
            java.util.Collections.shuffle(shuffled, RANDOM);
            if (shuffled.size() > 1 && shuffled.get(0).equals(lastDealt.get(pieces))) java.util.Collections.swap(shuffled, 0, shuffled.size() - 1);
            deck.addAll(shuffled);
        }
        String piece = deck.removeFirst();
        lastDealt.put(pieces, piece);
        return piece;
    }
    private static final java.util.Map<List<String>, String> lastDealt = new java.util.HashMap<>();

    /** For the self-test: what sings now, and how loud the verdict's music is at this moment. */
    static String altarPiece() { Chant chant = altarChant; return chant == null ? null : chant.piece; }
    static String musicPiece() { Chant chant = music; return chant == null ? null : chant.piece; }
    static float musicLevel() { Chant chant = music; return chant == null ? -1 : chant.level(); }
    static List<String> allPieces() { return java.util.stream.Stream.of(ALTAR, BLESSING, PUNISHMENT).flatMap(List::stream).toList(); }

    static float volume() { return DivineBlockMod.config().chantVolume / 100f; }

    /** One piece from the altar: fades in, sinks under the god's voice, fades out when told to. How far it carries is set
     * per sound in sounds.json (attenuation_distance). */
    static final class Chant extends MovingSoundInstance {
        final BlockPos pos;
        final String piece;
        private final float base;
        private final int fadeIn;
        private final long born = System.nanoTime();
        private int age, fading = -1;
        private float duck = 1;

        Chant(String piece, float base, BlockPos pos, int fadeIn) {
            super(SoundEvent.of(Identifier.of("divineblock", "chant." + piece)), SoundCategory.AMBIENT, SoundInstance.createRandom());
            this.pos = pos;
            this.piece = piece;
            this.base = base;
            this.fadeIn = fadeIn;
            this.repeat = false;
            this.attenuationType = AttenuationType.LINEAR;
            this.x = pos.getX() + 0.5; this.y = pos.getY() + 0.5; this.z = pos.getZ() + 0.5;
            this.volume = 0.001f;
        }

        float level() { return volume; }

        /** Faded out, or played to its end: the sound engine drops a finished stream without telling the instance. */
        boolean over(MinecraftClient client) { return isDone() || System.nanoTime() - born > 3_000_000_000L && !client.getSoundManager().isPlaying(this); }

        void fadeOut() { if (fading < 0) fading = 40; }

        @Override public boolean shouldAlwaysPlay() { return true; }

        @Override public void tick() {
            age++;
            float wantDuck = GodVoicePlayer.speaking() ? UNDER_VOICE : 1f;
            // Sinks within half a second when the god speaks, comes back over two seconds after.
            duck += (wantDuck - duck) * (wantDuck < duck ? 0.2f : 0.05f);
            float fade = Math.min(1f, age / (float) fadeIn);
            if (fading >= 0) {
                fade *= fading / 40f;
                if (fading-- == 0) { setDone(); return; }
            }
            this.volume = Math.max(0.001f, base * volume() * duck * fade);
        }
    }
}
