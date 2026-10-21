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

/** Church chants, kept quiet so they never cover the god: singing from the altar itself when the player comes near (heard
 * only within some sixteen blocks, with pauses between pieces), and music for a blessing or a punishment, starting low
 * under the god's words and rising once he has spoken. Whatever plays sinks to a whisper while his voice sounds. */
final class ChantPlayer {
    private static final List<String> ALTAR = List.of("altar_01", "altar_17", "altar_33", "altar_35", "altar_53", "altar_60");
    private static final List<String> BLESSING = List.of("blessing_03", "blessing_11", "blessing_15", "blessing_45", "blessing_46");
    private static final List<String> PUNISHMENT = List.of("punishment_02", "punishment_06", "punishment_20", "punishment_21",
            "punishment_39", "punishment_56", "punishment_57", "punishment_59");
    /** Loudness at the default setting: the altar's singing, and the music of a verdict. */
    private static final float ALTAR_VOLUME = 0.32f, MUSIC_VOLUME = 0.30f;
    /** How much of it is left while the god speaks. */
    private static final float UNDER_VOICE = 0.22f;
    private static final int NEAR = 12, FAR = 22;
    private static final Random RANDOM = new Random();
    private static final Deque<String> recent = new ArrayDeque<>();

    private static Chant altarChant, music;
    private static BlockPos altar;
    private static ClientWorld world;
    private static long nextAltarChant, nextScan;

    private ChantPlayer() { }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ChantPlayer::tick);
    }

    /** The god has begun to speak a verdict (his beams rise, in the colour of what is coming): a blessing or a punishment
     * gets its music; jests and omens keep the altar's song. */
    static void verdict(MinecraftClient client, int kind) {
        List<String> pieces = kind == BeamsPayload.BLESSING ? BLESSING : kind == BeamsPayload.PUNISHMENT ? PUNISHMENT : null;
        if (pieces == null || volume() <= 0 || client.world == null || client.player == null) return;
        if (music != null) music.fadeOut();
        if (altarChant != null) altarChant.fadeOut();
        client.getMusicTracker().stop();
        music = new Chant(pick(pieces), MUSIC_VOLUME, null, 30);
        client.getSoundManager().play(music);
        // The altar keeps quiet for a while after the verdict's music.
        nextAltarChant = client.world.getTime() + 20 * 60;
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
        if (altarChant == null && music == null && altar != null && client.player.getBlockPos().isWithinDistance(altar, NEAR) && now >= nextAltarChant) {
            altarChant = new Chant(pick(ALTAR), ALTAR_VOLUME, altar, 40);
            client.getSoundManager().play(altarChant);
            // The next piece after this one (about 35 s) and a pause of 10–40 s.
            nextAltarChant = now + 20 * (35 + 10 + RANDOM.nextInt(31));
        }
    }

    private static void stopAll(MinecraftClient client) {
        if (music != null) client.getSoundManager().stop(music);
        if (altarChant != null) client.getSoundManager().stop(altarChant);
        music = altarChant = null;
        altar = null;
        nextAltarChant = 0;
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

    /** A piece of this scene not heard among the last few. */
    private static String pick(List<String> pieces) {
        List<String> fresh = pieces.stream().filter(piece -> !recent.contains(piece)).toList();
        String piece = (fresh.isEmpty() ? pieces : fresh).get(RANDOM.nextInt((fresh.isEmpty() ? pieces : fresh).size()));
        recent.addFirst(piece);
        while (recent.size() > 6) recent.removeLast();
        return piece;
    }

    /** For the self-test: what sings now, and how loud the verdict's music is at this moment. */
    static String altarPiece() { Chant chant = altarChant; return chant == null ? null : chant.piece; }
    static String musicPiece() { Chant chant = music; return chant == null ? null : chant.piece; }
    static float musicLevel() { Chant chant = music; return chant == null ? -1 : chant.level(); }
    static List<String> allPieces() { return java.util.stream.Stream.of(ALTAR, BLESSING, PUNISHMENT).flatMap(List::stream).toList(); }

    static float volume() { return DivineBlockMod.config().chantVolume / 100f; }

    /** One piece: fades in, sinks under the god's voice, fades out when told to; positional at the altar or heard everywhere. */
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
            if (pos == null) {
                this.relative = true;
                this.attenuationType = AttenuationType.NONE;
                this.x = 0; this.y = 0; this.z = 0;
            } else {
                this.attenuationType = AttenuationType.LINEAR;
                this.x = pos.getX() + 0.5; this.y = pos.getY() + 0.5; this.z = pos.getZ() + 0.5;
            }
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
