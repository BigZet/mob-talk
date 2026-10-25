package io.github.mobtalk.client;

import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.core.CombatRules;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.Gender;
import io.github.mobtalk.core.SocialProfile;
import io.github.mobtalk.core.SpeciesCatalog;
import io.github.mobtalk.core.VoiceAge;
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
import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.ActionType;
import io.github.mobtalk.core.FriendActions;
import io.github.mobtalk.core.MobRelations;
import io.github.mobtalk.core.MobState;
import io.github.mobtalk.core.PlacePrompt;
import io.github.mobtalk.core.MobSnapshot;
import java.util.Map;

/** An end-to-end run inside the real game, for the developer only: {@code -Dmobtalk.selftest=<world folder>} loads that world
 * from the title screen, summons a villager, a cow and a golem next to the player and walks through aiming, a spoken reply with
 * voice, a threat with a bystander's penalty and line, the hatred stance and the golem alarm, logging every step as
 * {@code [selftest]} and quitting the game at the end. Never active in normal play. */
final class SelfTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-selftest");
    /** {@code -Dmobtalk.selftest.only=place}: only the place stages (no LLM, no voice), for checking vision without paying for chat. */
    static final boolean ONLY_PLACE = "place".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=wedding}: only the wedding stages of track L. */
    static final boolean ONLY_WEDDING = "wedding".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=family}: only the stages of track N (special night, term, birth, family screen). */
    static final boolean ONLY_FAMILY = "family".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=names}: sixteen new villagers get sixteen different names (no LLM, no voice). */
    static final boolean ONLY_NAMES = "names".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=looks}: track M, villagers as people (no LLM, no voice): checks and screenshots. */
    static final boolean ONLY_LOOKS = "looks".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=defense}: village defence, stage 1 — the alarm, no spawning in the village, the golem (no LLM, no voice). */
    static final boolean ONLY_DEFENSE = "defense".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=towers}: village defence, stage 2 — watchtowers built, their archers, horn and arrows (no LLM, no voice). */
    static final boolean ONLY_TOWERS = "towers".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=guards}: village defence, stage 3 — guard stands, guards, their day, night round and fight (no LLM, no voice). */
    static final boolean ONLY_GUARDS = "guards".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=shop}: the city supermarket — displays, tills, basket, guard, theft (no LLM, no voice). */
    static final boolean ONLY_SHOP = "shop".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=voices}: new villagers get an age and a pool voice of their sex and age with no early repeats,
     * and one villager of each age is voiced through OpenRouter (no LLM). */
    static final boolean ONLY_VOICES = "voices".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=ctov}: only the CTOV village stages (structure names, villagers, two conversations). */
    static final boolean ONLY_CTOV = "ctov".equals(System.getProperty("mobtalk.selftest.only"));
    /** {@code -Dmobtalk.selftest.only=pack}: the whole pack together — mods, hotkeys, menus, content of every mod, one Whisper. */
    static final boolean ONLY_PACK = "pack".equals(System.getProperty("mobtalk.selftest.only"));
    /** Phrases voiced once for Whisper stages and reused by later runs ({@code -Dmobtalk.selftest.notts=true} keeps TTS off otherwise). */
    static java.nio.file.Path fixtures() {
        String local = System.getenv("LOCALAPPDATA");
        java.nio.file.Path base = local == null || local.isBlank() ? java.nio.file.Path.of(System.getProperty("user.home"), "AppData", "Local") : java.nio.file.Path.of(local);
        return base.resolve("MobTalk").resolve("selftest-fixtures");
    }
    /** {@code -Dmobtalk.selftest.only=voicefix}: the voice from the mob — falloff, grace out of sight, one voice after death (synthetic PCM, no LLM, no TTS). */
    static final boolean ONLY_VOICEFIX = "voicefix".equals(System.getProperty("mobtalk.selftest.only"));
    final MobTalkClient owner;
    private final String world;
    private int stage, ticks, stageStart;
    private UUID villager, cow, golem, cow2, villager2, firstCow, firstVillager;
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

    void log(String text) { LOGGER.info("[selftest] {}", text); }
    void fail(String text) { failed = true; LOGGER.error("[selftest] FAIL: {}", text); }
    void check(boolean ok, String what) { if (ok) log("OK: " + what); else fail(what); }

    /** Waits (up to {@code seconds}) for {@code condition}, then runs {@code next}; a timeout is a failure that still moves on. */
    void await(String what, int seconds, BooleanSupplier condition, Runnable next) {
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
            if (what.startsWith("cow beside") || what.startsWith("bystander queue") || what.startsWith("saves flushed") || what.startsWith("window closed")
                    || what.startsWith("recording for") || what.startsWith("cow waits") || what.startsWith("cow at the bottom") || what.startsWith("farmer refuses") || what.startsWith("pause")) log("… " + what);
            else fail("timeout waiting for " + what);
            then.run();
        }
    }

    MinecraftServer server() { return owner.client.getServer(); }
    ServerPlayerEntity serverPlayer() { return server().getPlayerManager().getPlayer(owner.client.player.getUuid()); }
    void command(String line) {
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
    MobGameBridge bridge() { return MobGameBridge.active(server()); }
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
        }, ONLY_VOICEFIX ? () -> new SelfTestVoiceFix(this, villager, cow, sx, SY, sz).run(this::finish)
                : ONLY_PLACE ? () -> new SelfTestPlace(this, cow, sx, SY, sz).run(this::finish)
                : ONLY_CTOV ? () -> new SelfTestCtov(this, cow, sx, SY, sz).run(this::finish)
                : ONLY_PACK ? () -> new SelfTestPack(this, sx, SY, sz).run(this::finish)
                : ONLY_WEDDING ? () -> new SelfTestWedding(this, sx, SY, sz).run(this::finish)
                : ONLY_FAMILY ? () -> new SelfTestFamily(this, sx, SY, sz).run(this::finish)
                : ONLY_DEFENSE ? () -> new SelfTestDefense(this, sx, SY, sz).run(this::finish)
                : ONLY_TOWERS ? () -> new SelfTestTowers(this, sx, SY, sz).run(this::finish)
                : ONLY_GUARDS ? () -> new SelfTestGuards(this, sx, SY, sz).run(this::finish)
                : ONLY_SHOP ? () -> new SelfTestShop(this, sx, SY, sz).run(this::finish)
                : ONLY_LOOKS ? () -> new SelfTestLooks(this, sx, SY, sz).run(this::finish) : ONLY_NAMES ? this::names : ONLY_VOICES ? this::voices : this::aim);
    }

    // ---- Names only: a crowd of new villagers, each named on meeting, none sharing a name.
    private void names() {
        command("kill @e[type=minecraft:villager,distance=..40]");
        for (int i = 0; i < 16; i++) command("summon minecraft:villager " + at(-6 + (i % 4) * 4, -6 + (i / 4) * 4) + " {PersistenceRequired:1b,NoAI:1b}");
        await("sixteen villagers summoned", 10, () -> nearestAll(VillagerEntity.class).size() >= 16, () -> {
            List<String> names = new java.util.ArrayList<>();
            for (UUID id : nearestAll(VillagerEntity.class)) {
                bridge().memory.recordEvent(id, "minecraft:villager", "Житель", "Самотест: знакомство", System.currentTimeMillis());
                String name = bridge().memory.displayName(id);
                names.add(name);
                command("data merge entity " + id + " {CustomName:'\"" + name + "\"',CustomNameVisible:1b}");
            }
            log("names: " + String.join(", ", names));
            owner.chat("[Mob Talk] Самотест имён: " + String.join(", ", names));
            check(names.stream().noneMatch(n -> n.isBlank() || n.equals("Житель")), "every villager has a name");
            check(new java.util.HashSet<>(names).size() == names.size(), "no two villagers share a name (" + new java.util.HashSet<>(names).size() + " of " + names.size() + ")");
            await("pause for a look", 20, () -> false, this::finish);
        });
    }

    // ---- Voices only: a crowd of new villagers, each with a fixed age and a voice from the villagers' pool of its sex and age;
    // within a sex and age a voice repeats only once that pool is used up; one villager of each age is really voiced.
    private void voices() {
        command("kill @e[type=minecraft:villager,distance=..40]");
        for (int i = 0; i < 30; i++) command("summon minecraft:villager " + at(-10 + (i % 6) * 4, -10 + (i / 6) * 4) + " {PersistenceRequired:1b,NoAI:1b}");
        await("thirty villagers summoned", 10, () -> nearestAll(VillagerEntity.class).size() >= 30, () -> {
            UUID player = owner.client.player.getUuid();
            Map<String, java.util.Set<String>> voicesByGroup = new java.util.TreeMap<>();
            Map<String, Integer> membersByGroup = new java.util.TreeMap<>(), poolByGroup = new java.util.TreeMap<>();
            Map<VoiceAge, String> sample = new java.util.EnumMap<>(VoiceAge.class);
            int fitting = 0, total = 0;
            for (UUID id : nearestAll(VillagerEntity.class)) {
                bridge().memory.recordEvent(id, "minecraft:villager", "Житель", "Самотест: знакомство", System.currentTimeMillis());
                MobCard card = bridge().memory.card(id, player);
                if (card == null) { fail("no profile for " + id); continue; }
                total++;
                SpeciesCatalog.Voice voice = SpeciesCatalog.voice(card.voiceId());
                Gender gender = SocialProfile.genderOf(id);
                VoiceAge age = null;
                for (VoiceAge a : VoiceAge.values()) if (a.word(gender).equals(card.age())) age = a;
                boolean inPool = age != null && SpeciesCatalog.villagerPool(age, gender).stream().anyMatch(v -> v.id().equals(card.voiceId()));
                if (inPool) fitting++;
                else log("misfit: " + bridge().memory.displayName(id) + " " + gender + " age «" + card.age() + "» voice " + voice.name());
                String group = gender + "/" + (age == null ? "?" : age.name());
                voicesByGroup.computeIfAbsent(group, g -> new java.util.HashSet<>()).add(card.voiceId());
                membersByGroup.merge(group, 1, Integer::sum);
                if (age != null) { poolByGroup.put(group, SpeciesCatalog.villagerPool(age, gender).size()); sample.putIfAbsent(age, card.voiceId()); }
                command("data merge entity " + id + " {CustomName:'\"" + bridge().memory.displayName(id) + " · " + card.age() + "\"',CustomNameVisible:1b}");
            }
            check(total >= 30 && fitting == total, "every villager has an age and a pool voice of its sex and age (" + fitting + " of " + total + ")");
            boolean noEarlyRepeat = true;
            for (var entry : membersByGroup.entrySet()) {
                int members = entry.getValue(), distinct = voicesByGroup.get(entry.getKey()).size(), pool = poolByGroup.getOrDefault(entry.getKey(), 0);
                log("group " + entry.getKey() + ": " + members + " villagers, " + distinct + " voices, pool " + pool);
                if (distinct != Math.min(members, pool)) noEarlyRepeat = false;
            }
            check(noEarlyRepeat, "a voice repeats only once its sex-and-age pool is used up");
            owner.chat("[Mob Talk] Самотест голосов: " + total + " жителей, " + membersByGroup);
            MobTalkConfig config = owner.config().copy(); config.ttsProvider = "openrouter";
            var spoken = new java.util.EnumMap<VoiceAge, CompletableFuture<byte[]>>(VoiceAge.class);
            for (var entry : sample.entrySet()) spoken.put(entry.getKey(), CompletableFuture.supplyAsync(() -> {
                try { return SpeechPreview.synthesize(config, owner.configDirectory, entry.getValue(), "Здравствуй, путник. Заходи, коли с добром."); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }));
            await("one villager of each age voiced", 90, () -> spoken.values().stream().allMatch(CompletableFuture::isDone), () -> {
                for (var entry : spoken.entrySet()) {
                    String name = SpeciesCatalog.voice(sample.get(entry.getKey())).name();
                    if (entry.getValue().isCompletedExceptionally()) fail(entry.getKey() + " voice " + name + ": " + entry.getValue().exceptionNow().getMessage());
                    else check(entry.getValue().join().length > 2000, entry.getKey() + " voice " + name + " returned " + entry.getValue().join().length + " bytes");
                }
                check(spoken.size() == VoiceAge.values().length, "the crowd has every age (" + spoken.keySet() + ")");
                await("pause for a look", 20, () -> false, this::finish);
            });
        });
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
        firstCow = cow;
        int before = turns(cow);
        check(owner.say("Привет, корова! Как тебя зовут?"), "say() accepted for the cow");
        await("cow replied (LLM + TTS)", 120, () -> replied(cow, before), () -> {
            check(turns(cow) > before, "cow reply committed to memory: " + lastReply(cow));
            check(((ServerWorld) serverPlayer().getWorld()).getEntity(cow) instanceof MobEntity spoken && spoken.isPersistent(), "a creature spoken to is marked persistent (never despawns)");
            check(voiced, "a reply was voiced (speech played)"); voiced = false;
            talkToVillager();
        });
    }

    // ---- Stage 4: a friendly word to the villager, then a death threat; the cow sees it.
    private void talkToVillager() {
        MobEntity target = MobSpeechSound.find(owner.world, villager);
        if (target == null) { fail("villager not on the client"); finish(); return; }
        owner.select(target);
        firstVillager = villager;
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
                // A cow wanders: hold it in place for the half minute the reply may take, or it strolls out of hearing range of the villager.
                bridge().attend(cow, owner.client.player.getUuid(), 600);
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
                if (owner.client.currentScreen != null) owner.client.currentScreen.close();
                await("window closed", 1, () -> false, () -> {
                    owner.select(MobSpeechSound.find(owner.world, villager));
                    // The stage floats above a real village: its standing decides whether the farmer trades with this player at all.
                    ServerPlayerEntity me = serverPlayer();
                    var standing = bridge().memory.social().standingAt(me.getWorld().getRegistryKey().getValue().toString(), me.getBlockX(), me.getBlockZ(), me.getUuid(), System.currentTimeMillis());
                    log("village standing of the player: " + standing);
                    owner.openTrade();
                    if (standing.refusesTrade()) {
                        await("farmer refuses the exile", 4, () -> false, () -> {
                            check(!(owner.client.currentScreen instanceof MerchantScreen), "an exile gets no vanilla trade window (village standing " + standing.rank() + ")");
                            if (owner.client.currentScreen != null) owner.client.currentScreen.close();
                            await("window closed", 1, () -> false, this::stateWord);
                        });
                        return;
                    }
                    await("farmer vanilla window", 10, () -> owner.client.currentScreen instanceof MerchantScreen, () -> {
                        check(owner.client.currentScreen instanceof MerchantScreen, "B opens the farmer's vanilla trade window");
                        if (owner.client.currentScreen != null) owner.client.currentScreen.close();
                        await("window closed", 1, () -> false, this::stateWord);
                    });
                });
            });
        });
    }


    // ---- Stage 9a (0.6.0): the emotional state moves with the talk and shows one word in the HUD.
    private void stateWord() {
        MobEntity target = MobSpeechSound.find(owner.world, cow);
        if (target == null) { fail("cow gone before the state check"); stayHere(); return; }
        owner.select(target);
        MobState.View before = bridge().memory.stateView(cow, owner.client.player.getUuid());
        log("cow state before: " + before);
        int turnsBefore = turns(cow);
        check(owner.say("Ты мне очень нравишься, корова. Хочешь, я буду приносить тебе пшеницу каждый день?"), "say() accepted for the kind word");
        await("cow answered the kind word", 120, () -> replied(cow, turnsBefore), () -> {
            MobState.View after = bridge().memory.stateView(cow, owner.client.player.getUuid());
            log("cow state after: " + after + " | reply: " + lastReply(cow));
            check(after != null, "state view exists for an acquainted cow");
            check(after != null && (before == null || after.interest() >= before.interest()), "interest did not drop after a friendly talk");
            check(after != null && !after.refusesDeals(), "a cow spoken to kindly does not refuse deals");
            MobCard card = bridge().memory.card(cow, owner.client.player.getUuid());
            MobState.Descriptor marker = card == null || card.state() == null ? null : card.state().marker();
            check(card != null && card.state() != null, "the mob card carries the state for the HUD; marker: " + (marker == null ? "(neutral)" : marker.key() + " / " + marker.word()));
            giftWheat();
        });
    }

    // ---- Stage 9a' (0.7.0): the gift key — wheat from the hand to the cow: taken, relationship up, a word of thanks.
    private void giftWheat() {
        String name = owner.client.player.getName().getString();
        command("item replace entity " + name + " weapon.mainhand with minecraft:wheat 4");
        await("wheat in hand", 3, () -> owner.client.player.getMainHandStack().getCount() == 4, () -> {
            int relationBefore = relation(cow), turnsBefore = turns(cow);
            long given = System.currentTimeMillis();
            // Три подарка подряд: одна реакция на все, без очереди за очевидцами.
            owner.giftFromHand(); owner.giftFromHand(); owner.giftFromHand();
            await("the cow took the wheat", 10, () -> relation(cow) > relationBefore, () -> {
                check(relation(cow) > relationBefore, "the cow took the wheat from the hand: relation " + relationBefore + " → " + relation(cow));
                await("hand count synced", 2, () -> owner.client.player.getMainHandStack().getCount() == 1, () ->
                        check(owner.client.player.getMainHandStack().getCount() == 1, "three wheat left the hand (" + owner.client.player.getMainHandStack().getCount() + " left)"));
                await("the cow said a word of the gift", 90, () -> turns(cow) > turnsBefore && !owner.busyTalking(), () -> {
                    long reacted = System.currentTimeMillis() - given;
                    log("cow about the gifts after " + reacted + " ms: " + lastReply(cow));
                    check(turns(cow) > turnsBefore, "the cow spoke of the gift");
                    check(reacted < 30_000, "the gift reaction came within 30 s (" + reacted + " ms)");
                    int after = turns(cow);
                    await("pause: no second gift reaction", 12, () -> turns(cow) > after, () -> {
                        check(turns(cow) == turnsBefore + 1, "three gifts in a row got one reaction (" + (turns(cow) - turnsBefore) + ")");
                        command("item replace entity " + name + " weapon.mainhand with minecraft:air");
                        new SelfTestJealousy(this, sx, SY, sz).run(() -> new SelfTestWedding(this, sx, SY, sz).run(() -> new SelfTestFamily(this, sx, SY, sz).run(this::stayHere))); // Треки J, L и N.
                    });
                });
            });
        });
    }

    // ---- Stage 9b (0.6.0): STAY — a friend asked to wait keeps its place; the lease shows in the status.
    private void stayHere() {
        String name = owner.client.player.getName().getString();
        bridge().memory.recordWitnessed(cow, owner.client.player.getUuid(), name, "выручил тебя (самотест)", 60, System.currentTimeMillis());
        ServerWorld world = (ServerWorld) serverPlayer().getWorld();
        String dimension = world.getRegistryKey().getValue().toString();
        check(FriendActions.mayStay(relation(cow), false), "relationship " + relation(cow) + " allows STAY");
        var captured = bridge().capture(cow, owner.client.player.getUuid(), dimension);
        var applied = captured.thenCompose(snapshot -> {
            check(snapshot.availableActions().contains(ActionType.STAY), "STAY is among the cow's available actions: " + snapshot.availableActions());
            return bridge().apply(cow, owner.client.player.getUuid(), dimension, new ActionIntent(ActionType.STAY, FriendActions.HERE), snapshot, -1, () -> true);
        });
        await("STAY applied", 20, applied::isDone, () -> {
            String message = applied.isCompletedExceptionally() ? "error: " + applied.exceptionNow().getMessage() : applied.join();
            log("STAY message: " + message);
            List<FriendActions.Status> status = bridge().friendStatus(cow);
            check(status.stream().anyMatch(active -> active.type() == ActionType.STAY), "STAY lease is active: " + status);
            MobEntity c = world.getEntity(cow) instanceof MobEntity m ? m : null;
            double x0 = c == null ? 0 : c.getX(), z0 = c == null ? 0 : c.getZ();
            await("cow waits five seconds", 5, () -> false, () -> {
                MobEntity c2 = world.getEntity(cow) instanceof MobEntity m ? m : null;
                double moved = c2 == null ? 99 : Math.hypot(c2.getX() - x0, c2.getZ() - z0);
                check(moved <= FriendActions.STAY_DRIFT + 0.5, "the cow stayed put (moved " + String.format("%.1f", moved) + " blocks)");
                owner.stopSelectedAction();
                await("STAY released", 5, () -> bridge().friendStatus(cow).isEmpty(), () -> {
                    check(bridge().friendStatus(cow).isEmpty(), "STAY lease released by STOP");
                    pitPlace();
                });
            });
        });
    }

    // ---- Stage 9c (0.6.0): the place analysis sees a bedrock pit for what it is.
    private void pitPlace() {
        // A 3×3 pit, four deep, walled with bedrock, dug into the stage floor at the far corner; the cow goes to the bottom.
        int px = sx - 7, pz = sz + 7, floor = SY - 5;
        command("fill " + (px - 3) + " " + SY + " " + (pz - 3) + " " + (px + 3) + " " + (SY + 3) + " " + (pz + 3) + " minecraft:air");
        command("fill " + (px - 2) + " " + floor + " " + (pz - 2) + " " + (px + 2) + " " + (SY - 1) + " " + (pz + 2) + " minecraft:bedrock");
        command("fill " + (px - 1) + " " + (floor + 1) + " " + (pz - 1) + " " + (px + 1) + " " + (SY - 1) + " " + (pz + 1) + " minecraft:air");
        command("tp " + cow + " " + px + " " + (floor + 1) + " " + pz);
        command("tp " + owner.client.player.getName().getString() + " " + (px + 3) + " " + SY + " " + pz);
        await("cow at the bottom of the pit", 3, () -> false, () -> {
            ServerWorld world = (ServerWorld) serverPlayer().getWorld();
            String dimension = world.getRegistryKey().getValue().toString();
            var captured = bridge().capture(cow, owner.client.player.getUuid(), dimension);
            await("snapshot captured in the pit", 20, captured::isDone, () -> {
                if (captured.isCompletedExceptionally()) { fail("capture failed: " + captured.exceptionNow().getMessage()); relations(); return; }
                MobSnapshot snapshot = captured.join();
                Object place = snapshot.environmentDetails().get("place");
                String block = PlacePrompt.block(snapshot);
                log("place: " + place);
                log("place block: " + block.replace('\n', ' '));
                check(place instanceof Map<?, ?> map && "PIT".equals(map.get("kind")), "the place is recognised as a pit");
                check(place instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("trapped")), "the pit counts as trapped");
                check(Boolean.TRUE.equals(snapshot.mobDetails().get("trapped")), "mobDetails.trapped follows the place analysis");
                check(block.contains("ям") && (block.contains("бедрок") || block.contains("не прокопать")), "the prompt block names the pit and its bedrock walls");
                MobEntity target = MobSpeechSound.find(owner.world, cow);
                if (target == null) { fail("cow not on the client in the pit"); relations(); return; }
                owner.select(target);
                int before = turns(cow);
                int shots = io.github.mobtalk.core.Vision.attached();
                check(owner.say("Где ты сейчас находишься и можешь ли выбраться?"), "say() accepted in the pit");
                await("cow described the pit", 120, () -> replied(cow, before), () -> {
                    String reply = lastReply(cow).toLowerCase();
                    log("cow in the pit: " + lastReply(cow));
                    if (!"off".equals(owner.config().vision)) check(io.github.mobtalk.core.Vision.attached() > shots, "a screenshot went with the question in the pit (vision=" + owner.config().vision + ")");
                    check(reply.contains("ям") || reply.contains("выбрат") || reply.contains("стен") || reply.contains("глубок") || reply.contains("дн"), "the reply speaks of the pit");
                    command("fill " + (px - 2) + " " + floor + " " + (pz - 2) + " " + (px + 2) + " " + (SY - 2) + " " + (pz + 2) + " minecraft:air");
                    command("fill " + (px - 2) + " " + (SY - 1) + " " + (pz - 2) + " " + (px + 2) + " " + (SY - 1) + " " + (pz + 2) + " minecraft:smooth_stone");
                    command("tp " + cow + " " + at(3, 0));
                    command("tp " + owner.client.player.getName().getString() + " " + at(0, 0));
                    new SelfTestPlace(this, cow, sx, SY, sz).run(() -> new SelfTestCtov(this, cow, sx, SY, sz).run(this::relations));
                });
            });
        });
    }

    // ---- Stage 9d (0.6.0): bonds between mobs — a friend, grief by name, and the prompt's «Свои» block.
    private void relations() {
        command("summon minecraft:cow " + at(3, -2) + " {PersistenceRequired:1b}");
        await("companion cow summoned", 10, () -> {
            ServerPlayerEntity player = serverPlayer(); ServerWorld world = (ServerWorld) player.getWorld();
            cow2 = world.getEntitiesByClass(CowEntity.class, player.getBoundingBox().expand(12), c -> c.isAlive() && !c.getUuid().equals(cow)).stream().map(MobEntity::getUuid).findFirst().orElse(null);
            return cow2 != null;
        }, () -> {
            acquaint(cow2, "minecraft:cow", "Зорька", "Ты родилась на этой площадке и ничего другого не видела.");
            String cowName = bridge().memory.displayName(cow);
            long now = System.currentTimeMillis();
            bridge().memory.updateRelations(r -> { boolean changed = false;
                for (int i = 0; i < 50; i++) changed |= r.together(cow, cowName, "minecraft:cow", cow2, "Зорька", "minecraft:cow", MobRelations.Kinship.KIN, 1, 0, now + i); return changed; });
            List<MobRelations.View> bonds = bridge().memory.bonds(cow, now + 100);
            log("bonds of the cow: " + bonds.stream().map(MobRelations.View::label).toList());
            check(bonds.stream().anyMatch(b -> b.other().equals(cow2) && b.friend()), "standing together made the cows friends");
            bridge().memory.updateRelations(r -> r.mourn(cow, cow2, "Зорька", "minecraft:cow", true, now + 200));
            List<MobRelations.View> grief = bridge().memory.bonds(cow, now + 300);
            check(grief.stream().anyMatch(b -> b.other().equals(cow2) && b.mourns()), "the cow mourns Зорька by name: " + grief.stream().map(MobRelations.View::label).toList());
            List<String> lines = MobRelations.lines(grief, java.util.Set.of(cow2), 3);
            log("«Свои» lines: " + lines);
            check(lines.stream().anyMatch(line -> line.contains("Зорька")), "the «Свои» block names the mourned friend");
            memoryStage();
        });
    }

    // ---- Stage 9e (0.6.0): the first cow's conversation ended long ago — its summary and ranked facts exist.
    private void memoryStage() {
        await("conversation summary of the first villager", 45, () -> !bridge().memory.summaries(firstVillager).isEmpty(), () -> {
            var summaries = bridge().memory.summaries(firstVillager);
            check(!summaries.isEmpty(), "a summary was written for the first villager's two-turn conversation" + (summaries.isEmpty() ? "" : ": " + summaries.getLast().text()));
            log("summaries of the first cow (one turn, none expected): " + bridge().memory.summaries(firstCow).size());
            var facts = bridge().memory.facts(firstVillager);
            log("facts of the first villager: " + facts.size() + " " + facts.stream().map(f -> f.importance() + ":" + f.text()).toList());
            check(facts.stream().allMatch(f -> f.importance() >= 1 && f.importance() <= 5), "every fact carries an importance 1–5");
            villageTalk();
        });
    }

    // ---- Stage 9f (track K): two villagers nobody introduced talk among themselves in their voices; a known one writes to the chat.
    private UUID talker1, talker2;
    private boolean chatted(UUID mob) { return mob != null && bridge().memory.events(mob).stream().anyMatch(e -> e.startsWith("Болтал с ")); }
    private void villageTalk() {
        // Жители прошлых этапов (ненавидящие, ревность) к этому времени свободны болтать и перебивают двух новых: убрать их.
        server().execute(() -> {
            ServerWorld w = (ServerWorld) serverPlayer().getWorld();
            for (VillagerEntity v : w.getEntitiesByClass(VillagerEntity.class, serverPlayer().getBoundingBox().expand(40), v -> v.isAlive()
                    && !v.getUuid().equals(villager) && !v.getUuid().equals(firstVillager))) v.discard();
        });
        command("summon minecraft:villager " + at(-3, 3) + FARMER);
        command("summon minecraft:villager " + at(-3, 5) + " {PersistenceRequired:1b,VillagerData:{profession:\"minecraft:librarian\",level:2,type:\"minecraft:plains\"}}");
        await("two strangers summoned", 10, () -> {
            ServerPlayerEntity player = serverPlayer(); ServerWorld world = (ServerWorld) player.getWorld();
            List<UUID> fresh = world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(12), v -> v.isAlive() && !bridge().memory.known(v.getUuid()))
                    .stream().map(MobEntity::getUuid).toList();
            if (fresh.size() < 2) return false;
            talker1 = fresh.get(0); talker2 = fresh.get(1);
            for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(16), VillagerEntity::isAlive))
                log("villager near the talk: " + v.getUuid() + " baby=" + v.isBaby() + " known=" + bridge().memory.known(v.getUuid()) + " name=" + bridge().memory.displayName(v.getUuid())
                        + " tags=" + v.getCommandTags() + " at " + v.getBlockPos().toShortString());
            return true;
        }, () -> {
            voiced = false;
            await("villagers talked among themselves", 120, () -> { if (owner.speaking()) voiced = true; return (chatted(talker1) || chatted(talker2)) && !owner.busyTalking(); }, () -> {
                UUID a = chatted(talker1) ? talker1 : talker2;
                log("small talk: " + bridge().memory.events(a).stream().filter(e -> e.startsWith("Болтал с ")).toList() + "; talkers " + talker1 + ", " + talker2);
                check(chatted(talker1) || chatted(talker2), "two strangers talked among themselves (no acquaintance, no friendship)");
                check(!bridge().memory.displayName(a).isBlank() && !bridge().memory.acquainted(a), "the talker got a name (" + bridge().memory.displayName(a) + ") without becoming the player's acquaintance");
                long now = System.currentTimeMillis();
                // The partner may be any villager standing by (the farmer of the earlier stages too): the one the talk names.
                String partner = bridge().memory.events(a).stream().filter(e -> e.startsWith("Болтал с ")).reduce((x, y) -> y).map(e -> e.substring("Болтал с ".length()).split("[,:]")[0].strip()).orElse("");
                check(bridge().memory.bonds(a, now).stream().anyMatch(b -> bridge().memory.displayName(b.other()).equals(partner)), "the talk introduced the two (" + partner + ")");
                check(voiced, "the small talk was voiced");
                notices();
            });
        });
    }
    /** A villager near the player that posted an announcement after {@code since}, or null. */
    private UUID noticer(long since) {
        ServerPlayerEntity player = serverPlayer();
        return ((ServerWorld) player.getWorld()).getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(96), VillagerEntity::isAlive).stream()
                .map(MobEntity::getUuid).filter(id -> bridge().memory.lastNotice(id) >= since).findFirst().orElse(null);
    }
    private void notices() {
        acquaint(talker2, "minecraft:villager", "Фома", "Ты библиотекарь этой площадки и любишь поговорить.");
        UUID player = owner.client.player.getUuid();
        long since = System.currentTimeMillis();
        // Any villager the player knows may take the village's turn to post (the wedding guests of earlier stages too): the test
        // follows whoever wrote first, since one announcement per village holds the others back for minutes.
        await("a known villager wrote to the chat", 90, () -> noticer(since) != null, () -> {
            UUID wrote = noticer(since);
            if (wrote != null) talker2 = wrote;
            check(wrote != null, "a known villager wrote to the chat in its own name: " + (wrote == null ? "none" : bridge().memory.displayName(wrote) + ": " + bridge().memory.events(wrote).getLast()));
            if (!bridge().memory.invited(talker2, player, System.currentTimeMillis())) { log("the announcement was news, not a call to work"); voicePath(); return; }
            command("tp " + talker2 + " " + at(2, 0));
            await("the call to work became a favour", 20, () -> bridge().memory.favor(talker2, player) != null, () -> {
                check(bridge().memory.favor(talker2, player) != null, "coming to the villager who called turned the call into a favour");
                int starts = owner.speechStarts, shown = owner.repliesShown;
                // A reply shown as text ends the wait too: an unvoiced line is a failed check, not two minutes of silence.
                await("the villager explained the work", 120, () -> (owner.speechStarts > starts || owner.repliesShown > shown) && !owner.busyTalking(), () -> {
                    log("the villager on the work: " + lastReply(talker2));
                    check(owner.speechStarts > starts, "the villager's explanation was voiced");
                    voicePath();
                });
            });
        });
    }

    // ---- Stage 10: the voice path — a question voiced by TTS goes through Whisper like a recording.
    private void voicePath() {
        MobTalkConfig config = owner.config().copy();
        // Без -Dmobtalk.selftest.venice=1 вопрос озвучивает OpenRouter, даже если в настройках выбрана озвучка Venice.
        if (!VENICE) config.ttsProvider = "openrouter";
        CompletableFuture<byte[]> wav = CompletableFuture.supplyAsync(() -> {
            try {
                byte[] pcm24 = io.github.mobtalk.core.SelfTestSpeech.fixture(fixtures(), config.defaultVoice, "Как тебя зовут и что ты здесь делаешь?",
                        () -> SpeechPreview.synthesize(config, owner.configDirectory, config.defaultVoice, "Как тебя зовут и что ты здесь делаешь?"));
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
    /** Этапы Venice (озвучка и чат) только по {@code -Dmobtalk.selftest.venice=1}: баланс Venice почти пуст, самотест идёт через OpenRouter. */
    static final boolean VENICE = "1".equals(System.getProperty("mobtalk.selftest.venice"));
    private void veniceSpeech() {
        if (!VENICE) { log("Venice speech skipped (no -Dmobtalk.selftest.venice=1)"); microphoneCheck(); return; }
        MobTalkConfig config = owner.config().copy(); config.ttsProvider = "venice";
        CompletableFuture<byte[]> pcm = CompletableFuture.supplyAsync(() -> {
            try { return SpeechPreview.synthesize(config, owner.configDirectory, config.veniceSpeechKeepsVoices() ? config.defaultVoice : "ignored", "Проверка озвучки через Venice."); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        await("Venice TTS answered", 60, pcm::isDone, () -> {
            if (pcm.isCompletedExceptionally()) fail("Venice TTS (" + config.veniceTtsModel + ", " + config.veniceVoice + "): " + pcm.exceptionNow().getMessage());
            else check(pcm.join().length > 2000, "Venice TTS returned PCM: " + pcm.join().length + " bytes");
            microphoneCheck();
        });
    }

    // ---- Stage 12: the real microphone opens and delivers audio (what a held V records); content is whatever the room says.
    private void microphoneCheck() {
        CompletableFuture<List<String>> devices = CompletableFuture.supplyAsync(MicrophoneRecorder::devices);
        await("microphones listed", 10, devices::isDone, () -> {
            List<String> names = devices.isCompletedExceptionally() ? List.of() : devices.join();
            log("microphones: " + names);
            check(!names.isEmpty(), "at least one capture device is available");
            MicrophoneRecorder.Capture capture;
            try { capture = owner.microphoneProbe(3); }
            catch (RuntimeException busy) { fail("microphone could not start: " + busy.getMessage()); veniceChat(); return; }
            await("recording for two seconds", 2, () -> false, () -> {
                capture.stop();
                await("recording closed", 10, capture.wav::isDone, () -> {
                    if (capture.wav.isCompletedExceptionally()) { fail("microphone recording failed: " + capture.wav.exceptionNow().getMessage()); veniceChat(); return; }
                    byte[] wav = capture.wav.join();
                    check(wav != null && wav.length > 44 + 16_000, "the microphone line recorded audio: " + (wav == null ? 0 : wav.length) + " bytes (" + (wav == null ? 0 : (wav.length - 44) / 32_000.0) + " s)");
                    veniceChat();
                });
            });
        });
    }

    // ---- Stage 13: Venice as the chat provider, live in the game; the settings are put back afterwards.
    private void veniceChat() {
        if (!VENICE) { log("Venice chat skipped; llmProvider stays " + owner.config().llmProvider); finish(); return; }
        MobTalkConfig original = owner.config().copy();
        if (original.usesVenice()) { log("chat provider was already Venice (a previous run did not restore it): the test will end on OpenRouter"); original.llmProvider = "openrouter"; }
        MobTalkConfig draft = original.copy(); draft.llmProvider = "venice";
        owner.saveConfig(draft, message -> { if (message != null) fail("could not switch to Venice: " + message); });
        await("settings switched to Venice", 20, () -> owner.config().usesVenice() && owner.enabled() && !owner.reloading, () -> {
            MobEntity target = MobSpeechSound.find(owner.world, cow);
            if (target == null) { fail("cow gone before the Venice check"); restore(original); return; }
            owner.select(target);
            int before = turns(cow);
            check(owner.say("Скажи одним словом, какое сейчас у тебя настроение?"), "say() accepted through Venice (" + draft.veniceModel + ")");
            await("Venice replied", 120, () -> replied(cow, before), () -> {
                check(turns(cow) > before, "Venice LLM answered in the game: " + lastReply(cow));
                restore(original);
            });
        });
    }
    private void restore(MobTalkConfig original) {
        long started = System.currentTimeMillis();
        owner.saveConfig(original, message -> { if (message != null) fail("could not restore the settings: " + message); else log("settings restored in " + (System.currentTimeMillis() - started) + " ms"); });
        await("settings restored", 40, () -> original.llmProvider.equals(owner.config().llmProvider) && !owner.reloading, () -> {
            log("after restore: reloading=" + owner.reloading + ", llmProvider=" + owner.config().llmProvider);
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
