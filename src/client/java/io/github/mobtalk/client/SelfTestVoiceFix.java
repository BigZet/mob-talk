package io.github.mobtalk.client;

import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.SpeechStream;
import io.github.mobtalk.core.VoiceFalloff;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Этапы самотеста {@code -Only voicefix}: голос из моба без настоящего TTS — синус вместо речи идёт тем же путём воспроизведения.
 * Проверяет плавное затухание по расстоянию и то, что звук следует за мобом; отсрочку потери из виду (стена между игроком и мобом)
 * и из дальности; смерть и возрождение игрока посреди фоновой реплики: она договаривается, новая ждёт и не ложится поверх.
 * Ни LLM, ни TTS не вызываются. */
final class SelfTestVoiceFix {
    private final SelfTest test;
    private final UUID villager, cow;
    private final int sx, sy, sz;
    private Runnable next = () -> { };
    private VoiceFalloff.Curve curve;

    SelfTestVoiceFix(SelfTest test, UUID villager, UUID cow, int sx, int sy, int sz) {
        this.test = test; this.villager = villager; this.cow = cow; this.sx = sx; this.sy = sy; this.sz = sz;
    }

    /** Синус с огибающей: 24 кГц, 16 бит, моно — как PCM, который отдаёт TTS. */
    static SpeechStream sine(double seconds, double hertz) {
        int samples = (int) (24_000 * seconds);
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            double edge = Math.min(1, Math.min(i, samples - i) / 1200.0);
            int value = (int) Math.round(Math.sin(2 * Math.PI * hertz * i / 24_000.0) * 6000 * edge);
            pcm[2 * i] = (byte) value; pcm[2 * i + 1] = (byte) (value >> 8);
        }
        return SpeechStream.of(pcm);
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private String at(double x, double z) { return x + " " + sy + " " + z; }
    private MobEntity mob(UUID id) { return MobSpeechSound.find(test.owner.world, id); }
    private MobSpeechSound voiceOf(UUID id) {
        for (MobSpeechSound voice : test.owner.voices.voices()) if (voice.mob().equals(id)) return voice;
        return null;
    }
    private boolean playing(MobSpeechSound voice) { return voice != null && test.owner.client.getSoundManager().isPlaying(voice); }
    private int playingCount() { int n = 0; for (MobSpeechSound v : test.owner.voices.voices()) if (playing(v)) n++; return n; }

    void run(Runnable then) {
        next = then;
        test.log("voicefix stages: the voice comes from the mob and fades with distance, survives a moment out of sight, one voice after death");
        test.owner.config().initiative = false; test.owner.config().villageChatter = false; test.owner.config().villageNotices = false;
        test.owner.config().witnessReplies = 0;
        curve = VoiceFalloff.Curve.of(test.owner.config());
        test.log("curve: full " + curve.full() + ", silent " + curve.silent() + "; conversation range " + test.owner.config().maxDistance
                + "; grace " + test.owner.config().voiceGraceSeconds + " s");
        test.command("data merge entity " + villager + " {NoAI:1b}");
        test.command("data merge entity " + cow + " {NoAI:1b}");
        test.command("tp " + villager + " " + at(sx + 3.5, sz + 0.5));
        test.command("tp " + cow + " " + at(sx - 2.5, sz + 3.5));
        // A walkway to the west, out to past the edge of hearing.
        int far = (int) Math.ceil(curve.silent()) + 16;
        test.command("fill " + (sx - far) + " " + (sy - 1) + " " + (sz - 1) + " " + (sx - 11) + " " + (sy - 1) + " " + (sz + 1) + " minecraft:smooth_stone");
        test.command("tp " + name() + " " + at(sx + 0.5, sz + 0.5));
        test.await("pause: walkway built", 2, () -> false, this::falloff);
    }

    // ---- 1. Затухание по расстоянию.
    private final List<double[]> steps = new ArrayList<>();
    private MobSpeechSound fading;
    private int cutoffsBefore;
    private double lastGain = 1;

    private void falloff() {
        test.owner.playVoice(villager, sine(40, 330), 0);
        fading = voiceOf(villager);
        cutoffsBefore = test.owner.speechCutoffs;
        test.check(playing(fading), "a synthetic voice plays at the villager");
        double s = curve.silent();
        double[] distances = { 2, curve.full() + 2, s * 0.25, s * 0.5, s * 0.75, s * 0.9 };
        step(distances, 0);
    }
    private void step(double[] distances, int index) {
        if (index == distances.length) { checkFalloff(); return; }
        double d = distances[index];
        test.command("tp " + name() + " " + at(sx + 3.5 - d, sz + 0.5));
        test.await("pause: listening at " + Math.round(d) + " blocks", 2, () -> false, () -> {
            boolean on = playing(fading);
            double gain = fading == null ? -1 : fading.gain(), distance = fading == null ? -1 : fading.distance();
            test.log(String.format(java.util.Locale.ROOT, "voice at %.1f blocks: gain %.3f (curve %.3f), playing %s", distance, gain, curve.gain(distance), on));
            steps.add(new double[] { distance, gain, on ? 1 : 0 });
            if (index == 3) followCheck(() -> step(distances, index + 1));
            else step(distances, index + 1);
        });
    }
    /** The source moves with the mob. */
    private void followCheck(Runnable after) {
        test.command("tp " + villager + " " + at(sx + 3.5, sz + 6.5));
        test.await("pause: the villager stepped aside", 1, () -> false, () -> {
            MobEntity m = mob(villager);
            test.check(m != null && fading != null && Math.abs(fading.getZ() - m.getZ()) < 0.6 && Math.abs(fading.getX() - m.getX()) < 0.6,
                    "the voice follows the mob (sound z " + (fading == null ? "-" : Math.round(fading.getZ() * 10) / 10.0) + ", mob z " + (m == null ? "-" : Math.round(m.getZ() * 10) / 10.0) + ")");
            test.command("tp " + villager + " " + at(sx + 3.5, sz + 0.5));
            test.await("pause: the villager is back", 1, () -> false, after);
        });
    }
    private void checkFalloff() {
        boolean allPlaying = steps.stream().allMatch(row -> row[2] == 1);
        test.check(allPlaying, "the voice keeps playing out to 90 % of the hearing edge (" + Math.round(curve.silent() * 0.9) + " blocks)");
        boolean falling = true, close = true;
        for (int i = 1; i < steps.size(); i++) if (steps.get(i)[1] >= steps.get(i - 1)[1] && steps.get(i)[0] > curve.full()) falling = false;
        for (double[] row : steps) if (Math.abs(row[1] - curve.gain(row[0])) > 0.03) close = false;
        test.check(falling, "the volume falls step by step with distance");
        test.check(close, "the volume follows the falloff curve");
        double beyondRange = test.owner.config().maxDistance + 2;
        if (beyondRange < curve.silent()) test.check(curve.gain(beyondRange) > 0.01 && steps.stream().anyMatch(row -> row[0] > test.owner.config().maxDistance && row[2] == 1),
                "past the conversation range the voice is quieter, not cut off");
        // Past the edge: it has faded out and stops by itself, not by a cut.
        test.command("tp " + name() + " " + at(sx + 3.5 - (curve.silent() + 6), sz + 0.5));
        test.await("the voice past the hearing edge fades out and stops", 6, () -> {
            if (playing(fading)) { lastGain = fading.gain(); return false; }
            return true;
        }, () -> {
            test.log(String.format(java.util.Locale.ROOT, "last volume heard before it stopped: %.3f", lastGain));
            test.check(lastGain <= 0.05, "it stopped only once it was (almost) silent");
            test.check(test.owner.speechCutoffs == cutoffsBefore, "no voice was cut off on the way");
            test.command("tp " + name() + " " + at(sx + 0.5, sz + 0.5));
            test.await("pause: back on the stage", 2, () -> false, this::sight);
        });
    }

    // ---- 2. Короткая потеря из виду и из дальности.
    private MobSpeechSound held;
    private void sight() {
        MobEntity m = mob(villager);
        if (m == null) { test.fail("the villager is gone"); death(); return; }
        test.owner.select(m);
        test.owner.startVoice(villager, sine(30, 392), false);
        held = voiceOf(villager);
        cutoffsBefore = test.owner.speechCutoffs;
        boolean seenFirst = server(() -> world().getEntity(villager) instanceof MobEntity mob && test.bridge().sees(mob, test.serverPlayer()));
        test.check(seenFirst, "in the open the villager sees the player");
        test.command("fill " + (sx + 2) + " " + sy + " " + (sz - 2) + " " + (sx + 2) + " " + (sy + 3) + " " + (sz + 2) + " minecraft:stone");
        test.await("pause: a wall between the player and the villager", 1, () -> false, () -> {
            boolean[] seen = server(() -> { var w = world(); var v = w.getEntity(villager); var p = test.serverPlayer();
                return v instanceof MobEntity mob ? new boolean[] { mob.canSee(p), test.bridge().sees(mob, p) } : new boolean[] { true, false }; });
            test.check(!seen[0], "the wall hides the player from the villager");
            test.check(seen[1], "right behind the wall the villager still counts the player as seen (grace)");
            test.await("pause: two seconds behind the wall", 2, () -> false, () -> {
                test.check(playing(held), "the voice keeps playing behind the wall");
                long wait = Math.max(1, test.owner.config().voiceGraceSeconds) + 1;
                test.await("pause: the grace runs out", (int) wait, () -> false, () -> {
                    boolean still = server(() -> { var v = world().getEntity(villager); return v instanceof MobEntity mob && test.bridge().sees(mob, test.serverPlayer()); });
                    test.check(!still, "after the grace the villager no longer counts the player as seen");
                    test.command("fill " + (sx + 2) + " " + sy + " " + (sz - 2) + " " + (sx + 2) + " " + (sy + 3) + " " + (sz + 2) + " minecraft:air");
                    reach();
                });
            });
        });
    }
    private void reach() {
        double out = test.owner.config().maxDistance + 3;
        if (out >= curve.silent() - 2) { test.log("conversation range near the hearing edge: the reach check is skipped"); finishSight(); return; }
        test.command("tp " + name() + " " + at(sx + 3.5 - out, sz + 0.5));
        test.await("pause: a step out of the conversation range", 1, () -> false, () -> {
            MobEntity m = mob(villager);
            test.check(m != null && !test.owner.withinRange(m) && test.owner.inReach(m), "just out of range the conversation is held (grace)");
            test.check(playing(held), "the answer keeps playing out of range (quieter), not cancelled");
            test.await("pause: out of range past the grace", Math.max(1, test.owner.config().voiceGraceSeconds) + 1, () -> false, () -> {
                MobEntity again = mob(villager);
                test.check(again != null && !test.owner.inReach(again), "after the grace the conversation is out of reach");
                test.check(playing(held), "the voice still sounds from the villager, fading, until it ends");
                test.check(test.owner.speechCutoffs == cutoffsBefore, "nothing was cut off");
                finishSight();
            });
        });
    }
    private void finishSight() {
        test.owner.stopSpeech();
        test.owner.clearSelection("самотест: выбор снят");
        test.command("tp " + name() + " " + at(sx + 0.5, sz + 0.5));
        test.await("pause: back for the death check", 2, () -> false, this::death);
    }

    // ---- 3. Смерть и возрождение посреди фоновой реплики.
    private MobSpeechSound before;
    private Object deadPlayer;
    private int startsBefore, maxPlaying;
    private boolean overlapSeen, beforeEndedByItself;
    private void death() {
        test.command("spawnpoint " + name() + " " + (sx) + " " + sy + " " + (sz));
        test.command("gamerule doImmediateRespawn false");
        List<ConversationEngine.ChatterLine> talk = List.of(
                new ConversationEngine.ChatterLine(villager, "Житель", "самотест: первая реплика", sine(8, 440)),
                new ConversationEngine.ChatterLine(cow, "Корова", "самотест: вторая реплика", sine(3, 523)));
        test.owner.village.playForTest(talk);
        test.await("the background line starts", 5, () -> voiceOf(villager) != null, () -> {
            before = voiceOf(villager);
            cutoffsBefore = test.owner.speechCutoffs;
            test.owner.voices.resetStats();
            test.await("pause: the line is under way", 2, () -> false, () -> {
                deadPlayer = test.owner.client.player;
                test.command("kill " + name());
                test.await("the player died", 5, () -> test.owner.client.player != null && !test.owner.client.player.isAlive(), () ->
                        test.await("pause: on the death screen", 1, () -> false, () -> {
                            test.log(state("after death"));
                            test.check(playing(before), "the background line goes on after the player's death");
                            test.check(test.owner.speechCutoffs == cutoffsBefore, "the death cut nothing off");
                            test.check(!test.owner.village.voicing(), "the rest of that talk is dropped");
                            test.owner.client.player.requestRespawn();
                            test.owner.client.setScreen(null);
                            test.await("the player respawned", 15, () -> test.owner.client.player != null && test.owner.client.player != deadPlayer
                                    && test.owner.client.player.isAlive(), this::afterRespawn);
                        }));
            });
        });
    }
    private void afterRespawn() {
        test.log("respawned; " + state("after respawn"));
        test.command("tp " + name() + " " + at(sx + 0.5, sz + 0.5)); // A respawn far away would fade the old line out by distance.
        test.await("pause: back on the stage after the respawn", 1, () -> false, this::newLine);
    }
    private void newLine() {
        test.log(state("on the stage again"));
        startsBefore = test.owner.speechStarts;
        test.owner.village.playForTest(List.of(new ConversationEngine.ChatterLine(cow, "Корова", "самотест: после возрождения", sine(3, 587))));
        test.await("the new background line waits for the old one, then plays", 30, () -> {
            int now = playingCount();
            maxPlaying = Math.max(maxPlaying, now);
            MobSpeechSound fresh = voiceOf(cow);
            if (fresh != null && test.owner.speechStarts > startsBefore) {
                if (playing(before)) overlapSeen = true;
                beforeEndedByItself = before != null && before.fullyRead();
                return true;
            }
            return false;
        }, () -> {
            test.check(!overlapSeen && maxPlaying <= 1, "after the respawn the new line did not start over the old one (at most " + maxPlaying + " voice at a time)");
            test.check(beforeEndedByItself, "the old line was played to its end");
            test.check(test.owner.voices.overlaps() == 0 && test.owner.voices.peak() <= 1, "the floor saw no overlap (" + test.owner.voices.overlaps() + " overlaps, peak " + test.owner.voices.peak() + ")");
            test.check(test.owner.speechCutoffs == cutoffsBefore, "no voice was cut off around the death");
            test.command("effect give " + name() + " minecraft:resistance 600 255 true");
            test.await("the last line ends", 10, () -> !test.owner.speaking(), next);
        });
    }

    private String state(String when) {
        return when + ": old line playing " + playing(before) + (before == null ? "" : ", done " + before.isDone() + " (" + before.stopReason() + "), gain "
                + Math.round(before.gain() * 1000) / 1000.0 + " at " + Math.round(before.distance()) + " blocks, read out " + before.fullyRead())
                + "; voices " + test.owner.voices.count() + ", speaking " + test.owner.speaking();
    }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private <T> T server(java.util.function.Supplier<T> work) { return test.server().submit(work::get).join(); }
}
