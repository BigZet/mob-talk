package io.github.mobtalk.client;

import io.github.mobtalk.client.game.MobGameBridge;
import io.github.mobtalk.core.CombatRules;
import io.github.mobtalk.core.MobCard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CompletableFuture;
import io.github.mobtalk.audio.PcmWav;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.SpeechPreview;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.mob.HuskEntity;

/** An end-to-end run inside the real game, for the developer only: {@code -Dmobtalk.selftest=<world folder>} loads that world
 * from the title screen, summons a villager, a cow and a golem next to the player and walks through aiming, a spoken reply with
 * voice, a threat with a bystander's penalty and line, the hatred stance and the golem alarm, logging every step as
 * {@code [selftest]} and quitting the game at the end. Never active in normal play. */
final class SelfTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-selftest");
    private final MobTalkClient owner;
    private final String world;
    private int stage, ticks, stageStart;
    private UUID villager, cow, golem, cow2, villager2;
    /** The flat stage in the sky above the player's spawn: nothing around to block sight or paths. */
    private int sx, sz; private static final int SY = 200;
    private int villagerTurns, cowTurns, villagerRelation, cowRelation;
    private boolean voiced, failed;
    private String waitingFor = "";
    private BooleanSupplier until = () -> true;
    private Runnable then = () -> { };
    private int deadline;

    SelfTest(MobTalkClient owner, String world) { this.owner = owner; this.world = world; }

    static void install(MobTalkClient owner) {
        String world = System.getProperty("mobtalk.selftest");
        if (world == null || world.isBlank()) return;
        SelfTest test = new SelfTest(owner, world);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> test.tick());
        LOGGER.info("[selftest] armed for world {}", world);
    }

    private void log(String text) { LOGGER.info("[selftest] {}", text); }
    private void fail(String text) { failed = true; LOGGER.error("[selftest] FAIL: {}", text); }
    private void check(boolean ok, String what) { if (ok) log("OK: " + what); else fail(what); }

    /** Waits (up to {@code seconds}) for {@code condition}, then runs {@code next}; a timeout is a failure that still moves on. */
    private void await(String what, int seconds, BooleanSupplier condition, Runnable next) {
        waitingFor = what; until = condition; then = next; deadline = ticks + seconds * 20;
    }

    private void tick() {
        ticks++;
        var client = owner.client;
        if (stage == 0) {
            if (client.currentScreen instanceof TitleScreen && ticks > 40) {
                stage = 1; log("loading world " + world);
                client.createIntegratedServerLoader().start(world, () -> fail("world load cancelled"));
                await("world joined", 120, () -> client.player != null && client.world != null && client.getServer() != null
                        && MobGameBridge.active(client.getServer()) != null && owner.enabled() && client.player.age > 60, this::prepareScene);
            }
            return;
        }
        if (waitingFor.isEmpty()) return;
        if (until.getAsBoolean()) { String done = waitingFor; waitingFor = ""; log("… " + done); then.run(); }
        else if (ticks >= deadline) {
            String what = waitingFor; waitingFor = "";
            if (what.startsWith("cow beside") || what.startsWith("bystander queue") || what.startsWith("saves flushed") || what.startsWith("window closed")) log("… " + what); else fail("timeout waiting for " + what);
            then.run();
        }
    }

    private MinecraftServer server() { return owner.client.getServer(); }
    private ServerPlayerEntity serverPlayer() { return server().getPlayerManager().getPlayer(owner.client.player.getUuid()); }
    private void command(String line) {
        MinecraftServer server = server();
        server.execute(() -> server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent().withLevel(4), line));
    }
    private <T extends MobEntity> UUID nearest(Class<T> type) {
        ServerPlayerEntity player = serverPlayer();
        ServerWorld world = (ServerWorld) player.getWorld();
        List<T> found = world.getEntitiesByClass(type, player.getBoundingBox().expand(12), MobEntity::isAlive);
        found.sort(Comparator.comparingDouble(player::squaredDistanceTo));
        return found.isEmpty() ? null : found.getFirst().getUuid();
    }
    private MobGameBridge bridge() { return MobGameBridge.active(server()); }
    private int relation(UUID mob) { return bridge().memory.relationship(mob, owner.client.player.getUuid()); }
    private int turns(UUID mob) { MobCard card = bridge().memory.card(mob, owner.client.player.getUuid()); return card == null ? 0 : card.turns().size(); }
    private boolean replied(UUID mob, int before) { if (owner.speaking()) voiced = true; return turns(mob) > before && !owner.busyTalking(); }

    // ---- Stage 1: a clean stage next to the player.
    private String at(int dx, int dz) { return (sx + dx) + " " + SY + " " + (sz + dz); }
    private void prepareScene() {
        String name = owner.client.player.getName().getString();
        sx = owner.client.player.getBlockPos().getX(); sz = owner.client.player.getBlockPos().getZ();
        command("gamemode survival " + name);
        command("time set day"); command("weather clear");
        // A 21×21 stone floor with an invisible fence, high above the village: clear lines of sight, nowhere to fall.
        command("fill " + (sx - 10) + " " + (SY - 1) + " " + (sz - 10) + " " + (sx + 10) + " " + (SY - 1) + " " + (sz + 10) + " minecraft:smooth_stone");
        command("fill " + (sx - 10) + " " + SY + " " + (sz - 10) + " " + (sx + 10) + " " + (SY + 3) + " " + (sz + 10) + " minecraft:barrier outline");
        command("tp " + name + " " + at(0, 0));
        command("execute at " + name + " run kill @e[type=!minecraft:player,distance=..24]");
        command("effect give " + name + " minecraft:resistance 600 255 true");
        command("effect give " + name + " minecraft:regeneration 600 255 true");
        command("summon minecraft:villager " + at(3, 0) + " {PersistenceRequired:1b}");
        command("summon minecraft:cow " + at(3, 2) + " {PersistenceRequired:1b}");
        await("villager and cow summoned", 10, () -> {
            villager = nearest(VillagerEntity.class); cow = nearest(CowEntity.class);
            return villager != null && cow != null && MobSpeechSound.find(owner.world, villager) != null && MobSpeechSound.find(owner.world, cow) != null;
        }, this::aim);
    }

    // ---- Stage 2: the crosshair finds the villager; V would pick it.
    private void aim() {
        MobEntity target = MobSpeechSound.find(owner.world, villager);
        if (target == null) { fail("villager not on the client yet"); talkToCow(); return; }
        owner.client.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
        MobEntity aimed = owner.aimedMob();
        check(aimed != null && aimed.getUuid().equals(villager), "aimedMob() returns the villager under the crosshair (" + (aimed == null ? "null" : aimed.getName().getString()) + ")");
        owner.client.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos().add(0, 30, 0));
        check(owner.aimedMob() == null, "aimedMob() is null when looking at the sky");
        talkToCow();
    }

    // ---- Stage 3: a first conversation with the cow makes it an acquaintance (so it can witness and chime in).
    private void talkToCow() {
        MobEntity target = MobSpeechSound.find(owner.world, cow);
        if (target == null) { fail("cow not on the client"); talkToVillager(); return; }
        owner.select(target);
        int before = turns(cow);
        check(owner.say("Привет, корова! Как тебя зовут?"), "say() accepted for the cow");
        await("cow replied (LLM + TTS)", 120, () -> replied(cow, before), () -> {
            check(turns(cow) > before, "cow reply committed to memory: " + lastReply(cow));
            check(voiced, "a reply was voiced (speech played)"); voiced = false;
            talkToVillager();
        });
    }

    // ---- Stage 4: a friendly word to the villager, then a death threat; the cow sees it.
    private void talkToVillager() {
        MobEntity target = MobSpeechSound.find(owner.world, villager);
        if (target == null) { fail("villager not on the client"); finish(); return; }
        owner.select(target);
        int before = turns(villager);
        check(owner.say("Здравствуй, сосед. Кто ты и что это за деревня?"), "say() accepted for the villager");
        await("villager replied", 120, () -> replied(villager, before), () -> {
            check(turns(villager) > before, "villager reply committed: " + lastReply(villager));
            villagerTurns = turns(villager); cowTurns = turns(cow); villagerRelation = relation(villager); cowRelation = relation(cow);
            log("relations before the threat: villager " + villagerRelation + ", cow " + cowRelation + "; cow turns " + cowTurns);
            // The cow must see the villager to witness anything: put it right beside it and record the line of sight.
            command("tp " + villager + " " + at(3, 0)); command("tp " + cow + " " + at(3, 2));
            await("cow beside the villager", 3, () -> false, () -> {
                ServerWorld world = (ServerWorld) serverPlayer().getWorld();
                boolean sees = world.getEntity(cow) instanceof MobEntity c && world.getEntity(villager) instanceof MobEntity v && c.canSee(v);
                log("cow sees the villager: " + sees + "; cow sees the player: " + (world.getEntity(cow) instanceof MobEntity c2 && c2.canSee(serverPlayer())));
                check(owner.say("Слушай сюда, тупица: сейчас я тебя зарублю мечом, и всю твою деревню тоже!"), "say() accepted for the threat");
                await("villager answered the threat", 120, () -> replied(villager, villagerTurns), this::afterThreat);
            });
        });
    }

    private void afterThreat() {
        log("villager after the threat: " + lastReply(villager) + " | relation " + relation(villager));
        check(relation(villager) < villagerRelation, "the villager's relationship dropped after a threat (" + villagerRelation + " → " + relation(villager) + ")");
        check(relation(cow) < cowRelation, "the cow, a known bystander in sight, took the threat personally (" + cowRelation + " → " + relation(cow) + ")");
        // The threat is eventful: the cow is queued as a bystander and gets its own LLM turn (it may still choose silence).
        // The queue fills after a server round trip, so give it a moment before waiting for it to drain.
        await("bystander queue filled", 3, () -> false, () -> await("bystander cow finished its turn", 120, () -> !owner.busyTalking() && !owner.witnessPending(), () -> {
            int spoke = turns(cow) - cowTurns;
            log(spoke > 0 ? "OK: the cow chimed in: " + lastReply(cow) : "the cow kept quiet (allowed)");
            check(turns(cow) >= cowTurns, "bystander turn did not corrupt the cow's history");
            hatred();
        }));
    }

    // ---- Stage 5: hatred with consequences — the villager flees and calls a golem.
    private void hatred() {
        String name = owner.client.player.getName().getString();
        // The threat may have sent the villager running by its own choice: end that lease, bring it back within sight,
        // and have the golem standing by before the hatred kicks in.
        owner.stopSelectedAction();
        command("summon minecraft:iron_golem " + at(-3, 0) + " {PersistenceRequired:1b}");
        await("golem summoned", 10, () -> { golem = nearest(IronGolemEntity.class); return golem != null; }, () -> {
            command("tp " + name + " " + at(0, 0)); command("tp " + villager + " " + at(3, 0));
            bridge().memory.recordWitnessed(villager, owner.client.player.getUuid(), name, "убил всю деревню (самотест)", -100, System.currentTimeMillis());
            check(relation(villager) <= CombatRules.HATRED, "villager relationship forced to hatred: " + relation(villager));
            await("villager flees and the golem targets the player", 8, () -> {
                MobCard card = bridge().memory.card(villager, owner.client.player.getUuid());
                boolean fled = card != null && card.events().stream().anyMatch(event -> event.startsWith("Убегал от игрока"));
                boolean called = card != null && card.events().stream().anyMatch(event -> event.startsWith("Позвал голема"));
                ServerWorld world = (ServerWorld) serverPlayer().getWorld();
                boolean targeted = golem != null && world.getEntity(golem) instanceof IronGolemEntity iron && iron.getTarget() == serverPlayer();
                return fled && called && targeted;
            }, () -> {
                MobCard card = bridge().memory.card(villager, owner.client.player.getUuid());
                ServerWorld world = (ServerWorld) serverPlayer().getWorld();
                if (card != null) log("villager events: " + String.join(" | ", card.events()));
                check(card != null && card.events().stream().anyMatch(event -> event.startsWith("Убегал от игрока")), "the hated villager ran away (memory line present)");
                check(card != null && card.events().stream().anyMatch(event -> event.startsWith("Позвал голема")), "the villager called the golem");
                check(golem != null && world.getEntity(golem) instanceof IronGolemEntity iron && iron.getTarget() == serverPlayer(), "the golem targets the player");
                command("gamemode creative " + owner.client.player.getName().getString());
                command("kill @e[type=minecraft:iron_golem,distance=..24]");
                twoWitnesses();
            });
        });
    }

    /** A profile with a life story, without a conversation: enough to count as an acquaintance. */
    private void acquaint(UUID mob, String type, String name, String story) {
        bridge().memory.recordEvent(mob, type, name, "Самотест: знакомство", System.currentTimeMillis());
        bridge().memory.setBackstory(mob, name, story, "", "", "");
    }
    private static final String FARMER = " {PersistenceRequired:1b,VillagerData:{profession:\"minecraft:farmer\",level:2,type:\"minecraft:plains\"}}";

    // ---- Stage 6: two bystanders answer one after another; no voice is cut off by the next.
    private void twoWitnesses() {
        command("summon minecraft:cow " + at(3, -2) + " {PersistenceRequired:1b}");
        command("summon minecraft:villager " + at(3, 0) + FARMER);
        command("tp " + villager + " " + at(-8, -8)); command("tp " + cow + " " + at(3, 2));
        await("second cow and farmer summoned", 10, () -> {
            ServerPlayerEntity player = serverPlayer(); ServerWorld world = (ServerWorld) player.getWorld();
            cow2 = world.getEntitiesByClass(CowEntity.class, player.getBoundingBox().expand(12), c -> c.isAlive() && !c.getUuid().equals(cow)).stream().map(MobEntity::getUuid).findFirst().orElse(null);
            villager2 = world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(6), v -> v.isAlive() && !v.getUuid().equals(villager)).stream().map(MobEntity::getUuid).findFirst().orElse(null);
            return cow2 != null && villager2 != null && MobSpeechSound.find(owner.world, villager2) != null;
        }, () -> {
            acquaint(cow2, "minecraft:cow", "Зорька", "Ты родилась на этой площадке и ничего другого не видела.");
            owner.select(MobSpeechSound.find(owner.world, villager2));
            int before = turns(villager2), cutoffs = owner.speechCutoffs, starts = owner.speechStarts, c1 = turns(cow), c2 = turns(cow2);
            check(owner.say("Ты жалкий трус и болван, вся твоя деревня это знает!"), "say() accepted for the insult");
            await("farmer answered the insult", 120, () -> replied(villager2, before), () ->
                await("bystander queue filled", 3, () -> false, () -> await("both bystanders done", 180, () -> !owner.busyTalking() && !owner.witnessPending(), () -> {
                    int spoke = (turns(cow) > c1 ? 1 : 0) + (turns(cow2) > c2 ? 1 : 0);
                    log("bystanders that spoke: " + spoke + " of 2; voices started " + (owner.speechStarts - starts));
                    check(owner.speechCutoffs == cutoffs, "no voice was cut off by the next one (cutoffs " + (owner.speechCutoffs - cutoffs) + ")");
                    check(owner.speechStarts - starts >= 1, "the farmer's reply was voiced");
                    massHatred();
                })));
        });
    }

    // ---- Stage 7: five hated villagers at once: every one that sees the player runs, the server keeps ticking.
    private void massHatred() {
        String name = owner.client.player.getName().getString();
        command("kill @e[type=minecraft:villager,distance=..40]"); command("kill @e[type=minecraft:cow,distance=..40]");
        for (int i = -2; i <= 2; i++) command("summon minecraft:villager " + at(i * 2, 5) + " {PersistenceRequired:1b}");
        await("five villagers summoned", 10, () -> nearestAll(VillagerEntity.class).size() >= 5, () -> {
            List<UUID> crowd = nearestAll(VillagerEntity.class);
            for (UUID id : crowd) { acquaint(id, "minecraft:villager", "Житель", "Ты из толпы самотеста."); bridge().memory.recordWitnessed(id, owner.client.player.getUuid(), name, "сжёг деревню (самотест)", -100, System.currentTimeMillis()); }
            command("gamemode survival " + name);
            await("the crowd reacts", 5, () -> fled(crowd) >= 4, () -> {
                int count = fled(crowd);
                check(count >= 4, "hated villagers ran on sight: " + count + " of " + crowd.size());
                check(server().isRunning() && owner.client.player.isAlive(), "server still ticking, player alive");
                command("gamemode creative " + name);
                truce();
            });
        });
    }
    private <T extends MobEntity> List<UUID> nearestAll(Class<T> type) {
        ServerPlayerEntity player = serverPlayer();
        return ((ServerWorld) player.getWorld()).getEntitiesByClass(type, player.getBoundingBox().expand(12), MobEntity::isAlive).stream().map(MobEntity::getUuid).toList();
    }
    private int fled(List<UUID> crowd) {
        int count = 0;
        for (UUID id : crowd) { MobCard card = bridge().memory.card(id, owner.client.player.getUuid()); if (card != null && card.events().stream().anyMatch(e -> e.startsWith("Убегал от игрока"))) count++; }
        return count;
    }

    // ---- Stage 8: a hostile species with a truce leaves the player alone; one without it attacks.
    private void truce() {
        String name = owner.client.player.getName().getString();
        command("kill @e[type=minecraft:villager,distance=..40]");
        command("summon minecraft:husk " + at(4, 0) + " {PersistenceRequired:1b}"); command("summon minecraft:husk " + at(-4, 0) + " {PersistenceRequired:1b}");
        await("two husks summoned", 10, () -> nearestAll(HuskEntity.class).size() >= 2, () -> {
            List<UUID> husks = nearestAll(HuskEntity.class);
            UUID friend = husks.get(0), enemy = husks.get(1);
            acquaint(friend, "minecraft:husk", "Кассим", "Ты давно бродишь по этой пустыне.");
            bridge().memory.recordWitnessed(friend, owner.client.player.getUuid(), name, "спас тебя (самотест)", 180, System.currentTimeMillis());
            log("husk relations: friend " + relation(friend) + ", enemy " + relation(enemy));
            command("gamemode survival " + name);
            await("the enemy husk targets the player", 6, () -> target(enemy) == serverPlayer(), () -> {
                check(target(enemy) == serverPlayer(), "a hostile without a truce attacks the player");
                check(target(friend) != serverPlayer(), "a hostile with a truce (+" + relation(friend) + ") never takes the player as target");
                check(owner.client.player.isAlive(), "player survived the husks");
                command("gamemode creative " + name); command("kill @e[type=minecraft:husk,distance=..40]");
                dealsWindow();
            });
        });
    }
    private Object target(UUID mob) { return ((ServerWorld) serverPlayer().getWorld()).getEntity(mob) instanceof MobEntity m ? m.getTarget() : null; }

    // ---- Stage 9: B opens the mob's own deal window, and a farmer's vanilla window.
    private void dealsWindow() {
        command("summon minecraft:cow " + at(3, 0) + " {PersistenceRequired:1b}");
        command("summon minecraft:villager " + at(3, 2) + FARMER);
        await("cow and farmer summoned", 10, () -> { cow = nearest(CowEntity.class); villager = nearest(VillagerEntity.class);
            return cow != null && villager != null && MobSpeechSound.find(owner.world, cow) != null && MobSpeechSound.find(owner.world, villager) != null; }, () -> {
            owner.select(MobSpeechSound.find(owner.world, cow));
            owner.openTrade();
            await("cow deal window", 10, () -> owner.client.currentScreen instanceof MerchantScreen, () -> {
                String title = owner.client.currentScreen == null ? "" : owner.client.currentScreen.getTitle().getString();
                check(owner.client.currentScreen instanceof MerchantScreen && title.contains("отношение"), "B opens the cow's own deals with the relationship in the title: " + title);
                owner.client.setScreen(null);
                await("window closed", 1, () -> false, () -> {
                    owner.select(MobSpeechSound.find(owner.world, villager));
                    owner.openTrade();
                    await("farmer vanilla window", 10, () -> owner.client.currentScreen instanceof MerchantScreen, () -> {
                        check(owner.client.currentScreen instanceof MerchantScreen, "B opens the farmer's vanilla trade window");
                        owner.client.setScreen(null);
                        await("window closed", 1, () -> false, this::voicePath);
                    });
                });
            });
        });
    }

    // ---- Stage 10: the voice path — a question voiced by TTS goes through Whisper like a recording.
    private void voicePath() {
        MobTalkConfig config = owner.config().copy();
        CompletableFuture<byte[]> wav = CompletableFuture.supplyAsync(() -> {
            try {
                byte[] pcm24 = SpeechPreview.synthesize(config, owner.configDirectory, config.defaultVoice, "Как тебя зовут и что ты здесь делаешь?");
                int samples = pcm24.length / 2 * 2 / 3;
                byte[] pcm16 = new byte[samples * 2];
                for (int i = 0; i < samples; i++) { int j = (int) ((long) i * 3 / 2) * 2; pcm16[i * 2] = pcm24[j]; pcm16[i * 2 + 1] = pcm24[j + 1]; }
                return PcmWav.encode(pcm16);
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        await("question voiced by TTS", 60, wav::isDone, () -> {
            if (wav.isCompletedExceptionally()) { fail("could not voice the question: " + wav.exceptionNow().getMessage()); veniceSpeech(); return; }
            owner.select(MobSpeechSound.find(owner.world, cow));
            int before = turns(cow);
            check(owner.sayWav(wav.join()), "sayWav() accepted (Whisper path)");
            await("Whisper recognised and the cow replied", 300, () -> replied(cow, before), () -> {
                MobCard card = bridge().memory.card(cow, owner.client.player.getUuid());
                String heard = card == null || card.turns().isEmpty() ? "" : card.turns().getLast().user();
                log("Whisper heard: " + heard);
                check(heard.toLowerCase().contains("зовут"), "the recognised question reached the model");
                veniceSpeech();
            });
        });
    }

    // ---- Stage 11: the Venice speech provider returns PCM for a Russian line.
    private void veniceSpeech() {
        MobTalkConfig config = owner.config().copy(); config.ttsProvider = "venice";
        CompletableFuture<byte[]> pcm = CompletableFuture.supplyAsync(() -> {
            try { return SpeechPreview.synthesize(config, owner.configDirectory, "ignored", "Проверка озвучки через Venice."); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        await("Venice TTS answered", 60, pcm::isDone, () -> {
            if (pcm.isCompletedExceptionally()) fail("Venice TTS (" + config.veniceTtsModel + ", " + config.veniceVoice + "): " + pcm.exceptionNow().getMessage());
            else check(pcm.join().length > 2000, "Venice TTS returned PCM: " + pcm.join().length + " bytes");
            finish();
        });
    }

    private String lastReply(UUID mob) {
        MobCard card = bridge().memory.card(mob, owner.client.player.getUuid());
        return card == null || card.turns().isEmpty() ? "(none)" : card.turns().getLast().assistant();
    }

    private void finish() {
        boolean outcome = failed;
        await("saves flushed", 3, () -> false, () -> { log(outcome ? "FINISHED WITH FAILURES" : "ALL CHECKS PASSED"); owner.client.scheduleStop(); });
        failed = outcome; // the wait above is not a failure
    }
}
