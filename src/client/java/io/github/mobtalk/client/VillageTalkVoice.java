package io.github.mobtalk.client;

import io.github.mobtalk.client.game.MobGameBridge;
import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.VillageChatter;
import io.github.mobtalk.core.VillageNotices;
import net.minecraft.entity.mob.MobEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.function.Supplier;

/** Village talk on the client: takes a pair of villagers the server found ({@link VillageChatter.Cue}), has the exchange written and
 * voiced off the game thread, and plays its lines one after another when nobody else speaks; takes an announcement
 * ({@link VillageNotices.Cue}), has it written, and puts it into the chat unvoiced. The player speaking, or the world changing,
 * drops a talk that is still on. Client thread, except the writing. */
final class VillageTalkVoice {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    private final MobTalkClient owner;
    /** The talk being played, the next line, and whether the village already remembers it. */
    private List<ConversationEngine.ChatterLine> lines;
    private VillageChatter.Input input;
    private int next;
    private boolean recorded, writing, announcing;

    VillageTalkVoice(MobTalkClient owner) { this.owner = owner; }

    private long busySince, busyLogged;

    /** A talk is being written or played. */
    boolean active() { return writing || lines != null; }
    /** Written lines are being voiced one after another: nothing else speaks in between. */
    boolean voicing() { return lines != null; }

    void tick(long ticks) {
        if (ticks % 10 != 3 || !owner.enabled() || owner.client.player == null || owner.world == null || owner.client.getServer() == null) return;
        MobGameBridge game = MobGameBridge.active(owner.client.getServer());
        if (game == null) return;
        game.talk.talking = active();
        if (owner.wedding.active()) { cancel(); return; } // Трек L: на свадьбе деревня не болтает и не пишет объявлений.
        if (lines != null) { advance(game); return; }
        if (ticks % 20 != 3) return;
        announce(game);
        boolean floorBusy = writing || owner.busyTalking() || owner.witnessPending() || owner.initiatives.sceneOn() || owner.wedding.active();
        // A pair waits while the floor stays taken: say why once it has waited long, so a silent village can be read from the log.
        if (floorBusy && game.talk.waiting()) {
            long now = System.currentTimeMillis();
            if (busySince == 0) busySince = now;
            else if (now - busySince > 20_000 && now - busyLogged > 30_000) {
                busyLogged = now;
                LOGGER.info("Mob Talk: small talk waits for the floor: writing={} {} scene={} wedding={}", writing, owner.busyReason(), owner.initiatives.sceneOn(), owner.wedding.active());
            }
        } else busySince = 0;
        if (!floorBusy) write(game);
    }

    private double range() { return owner.config().villageChatterRange + 4; }

    /** The next pair the server found: the exchange is written and voiced on the bystander lane. */
    private void write(MobGameBridge game) {
        VillageChatter.Cue cue = game.talk.pollTalk();
        if (cue == null) return;
        if (!near(cue.input().a()) || !near(cue.input().b())) { LOGGER.info("Mob Talk: small talk of {} and {} dropped: out of earshot", cue.input().a().name(), cue.input().b().name()); return; }
        long generation = owner.generation();
        Supplier<ConversationEngine> engines = owner.engineSupplier();
        writing = true; game.talk.talking = true;
        owner.background(() -> {
            List<ConversationEngine.ChatterLine> written = List.of();
            try {
                ConversationEngine engine = engines.get();
                if (engine != null) written = engine.chatter(cue.input());
            } catch (Exception failure) {
                LOGGER.info("Mob Talk: village small talk skipped ({})", String.valueOf(failure.getMessage()));
            } finally { Thread.interrupted(); }
            List<ConversationEngine.ChatterLine> done = written;
            owner.client.execute(() -> {
                writing = false;
                if (done.isEmpty()) LOGGER.info("Mob Talk: small talk of {} and {} came back empty", cue.input().a().name(), cue.input().b().name());
                if (generation != owner.generation() || done.isEmpty() || owner.world == null) { discard(done, 0); return; }
                lines = done; input = cue.input(); next = 0; recorded = false;
            });
        });
    }

    /** The next line once the floor is free; a speaker who walked out of earshot ends the talk. */
    private void advance(MobGameBridge game) {
        if (owner.speaking() || owner.busyTalking()) return;
        if (next >= lines.size()) { lines = null; input = null; return; }
        ConversationEngine.ChatterLine line = lines.get(next);
        MobEntity speaker = MobSpeechSound.find(owner.world, line.speaker());
        if (speaker == null || owner.client.player.squaredDistanceTo(speaker) > range() * range()) { cancel(); return; }
        next++;
        if (!recorded) {
            recorded = true;
            LOGGER.info("Mob Talk: болтовня {} ({}) и {} ({})", input.a().name(), input.a().id(), input.b().name(), input.b().id());
            game.memory.recordChatter(input, lines.stream().map(l -> new VillageChatter.Line(l.speaker().equals(input.a().id()) ? 0 : 1, l.text())).toList(), System.currentTimeMillis());
        }
        owner.playOverheard(line.speaker(), "§7" + line.name() + ": " + line.text(), line.audio(), range());
    }

    /** The next announcement the server picked: written on the bystander lane, then posted to the chat in the villager's name. */
    private void announce(MobGameBridge game) {
        // Not in the middle of someone's voice: a chat line landing between two lines of small talk reads as a reply to it.
        if (announcing || active() || owner.busyTalking()) return;
        VillageNotices.Cue cue = game.talk.pollNotice();
        if (cue == null) return;
        Supplier<ConversationEngine> engines = owner.engineSupplier();
        announcing = true;
        owner.background(() -> {
            String text = "";
            try {
                ConversationEngine engine = engines.get();
                if (engine != null) text = engine.announce(cue.input());
            } catch (Exception failure) {
                LOGGER.info("Mob Talk: villager announcement skipped ({})", String.valueOf(failure.getMessage()));
            } finally { Thread.interrupted(); }
            String written = text;
            owner.client.execute(() -> {
                announcing = false;
                if (written.isBlank() || owner.client.getServer() == null || MobGameBridge.active(owner.client.getServer()) != game) return;
                game.memory.recordNotice(cue.input(), written, System.currentTimeMillis());
                owner.chat("§e" + cue.input().name() + "§r: " + written);
                if (cue.input().kind() == VillageNotices.Kind.WORK)
                    owner.chat("§8[Mob Talk] Подойдите к " + cue.input().name() + " ближе " + Math.round(VillageNotices.ARRIVE_RANGE) + " блоков, чтобы узнать поручение");
            });
        });
    }

    private boolean near(VillageChatter.Speaker speaker) {
        MobEntity mob = MobSpeechSound.find(owner.world, speaker.id());
        return mob != null && owner.client.player.squaredDistanceTo(mob) <= range() * range();
    }
    private static void discard(List<ConversationEngine.ChatterLine> lines, int from) {
        for (int i = from; i < lines.size(); i++) if (lines.get(i).audio() != null) lines.get(i).audio().close();
    }
    /** The player spoke or the world changed: the talk still on is over, its unplayed voices are dropped. */
    void cancel() {
        if (lines != null) discard(lines, next);
        lines = null; input = null;
    }
}
