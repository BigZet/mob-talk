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

/** An end-to-end run inside the real game, for the developer only: {@code -Dmobtalk.selftest=<world folder>} loads that world
 * from the title screen, summons a villager, a cow and a golem next to the player and walks through aiming, a spoken reply with
 * voice, a threat with a bystander's penalty and line, the hatred stance and the golem alarm, logging every step as
 * {@code [selftest]} and quitting the game at the end. Never active in normal play. */
final class SelfTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-selftest");
    private final MobTalkClient owner;
    private final String world;
    private int stage, ticks, stageStart;
    private UUID villager, cow, golem;
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
            if (what.startsWith("cow beside") || what.startsWith("bystander queue") || what.startsWith("saves flushed")) log("… " + what); else fail("timeout waiting for " + what);
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
                finish();
            });
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
