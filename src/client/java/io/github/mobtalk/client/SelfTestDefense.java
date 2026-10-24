package io.github.mobtalk.client;

import io.github.mobtalk.game.MobGameBridge;
import java.util.*;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.entity.BellBlockEntity;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.SpawnHelper;

/** Village defence, stage 1 ({@code -Only defense}): a small village in the sky (a bell, beds, a smith's furnace, three villagers
 * and a golem) with an empty control floor beside it. Checks the golem's health, that natural spawning puts no monster in the
 * village but does on the control floor, the alarm at six zombies (chat, shout, bell, hiding, golem), the alarm against a player
 * who strikes a villager (shout with their name, the golem, no trade, forgive), and the golem healing and mended by the smith;
 * then the group «Защита деревни» in F7. Screenshots in screenshots. No LLM, no voice. */
final class SelfTestDefense {
    private static final String TAG = "mobtalk_def";
    /** The village floor reaches this far from the bell; the control floor lies past the zone (bell ±32 plus the margin). */
    private static final int VILLAGE = 24, CONTROL_X = 104, CONTROL = 20, PLATFORM_X = 62;
    private final SelfTest test;
    private final int fx, fy, fz;
    private final List<String> chat = Collections.synchronizedList(new ArrayList<>());
    private Runnable next = () -> { };
    private UUID golem;
    private final List<UUID> villagers = new ArrayList<>();
    private float healthBefore;
    private boolean smithCame;
    private int chatMark;

    SelfTestDefense(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test; this.fx = stageX + 200; this.fy = stageY; this.fz = stageZ;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String line = (overlay ? "[bar] " : "") + Formatting.strip(message.getString());
            chat.add(line);
            test.log("chat: " + line);
        });
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private BlockPos bell() { return new BlockPos(fx, fy, fz); }
    /** Chat lines since the last {@link #mark}. */
    private List<String> heard() { synchronized (chat) { return List.copyOf(chat.subList(Math.min(chatMark, chat.size()), chat.size())); } }
    private boolean heard(String part) { return heard().stream().anyMatch(line -> line.contains(part)); }
    private void mark() { chatMark = chat.size(); }
    /** On the server thread already (inside an execute), the task runs at once: a nested submit there waits on itself. */
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private IronGolemEntity golem() { return onServer(() -> world().getEntity(golem) instanceof IronGolemEntity g && g.isAlive() ? g : null); }
    private List<VillagerEntity> residents() {
        return onServer(() -> villagers.stream().map(id -> world().getEntity(id)).filter(e -> e instanceof VillagerEntity v && v.isAlive()).map(e -> (VillagerEntity) e).toList());
    }
    private List<ZombieEntity> zombies() {
        return onServer(() -> world().getEntitiesByClass(ZombieEntity.class, new Box(bell()).expand(VILLAGE + 2, 10, VILLAGE + 2), z -> z.isAlive() && z.getCommandTags().contains(TAG)));
    }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("defense stages: the village alarm, no spawning in the village, the golem");
        test.command("difficulty normal"); test.command("time set 18000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false"); // Only the stage's own spawns.
        test.command("gamemode survival " + name());
        test.command("effect give " + name() + " minecraft:slow_falling 600 1 true");
        test.command("effect give " + name() + " minecraft:resistance 600 255 true");
        test.command("effect give " + name() + " minecraft:saturation 600 1 true");
        test.command("tp " + name() + " " + at(0, 0, 10) + " 180 15");
        test.await("pause: the stage chunks load", 3, () -> false, this::build);
    }

    private void build() {
        int v = VILLAGE;
        test.command("fill " + at(-v, -1, -v) + " " + at(v, -1, v) + " minecraft:smooth_stone");
        test.command("fill " + at(-v, 0, -v) + " " + at(v, 6, v) + " minecraft:air");
        walls(-v, v, -v, v);
        test.command("setblock " + at(0, 0, 0) + " minecraft:bell[attachment=floor,facing=north]");
        for (int[] bed : new int[][] { { -8, -8 }, { 8, -8 }, { -8, 8 } }) {
            test.command("setblock " + at(bed[0], 0, bed[1]) + " minecraft:red_bed[facing=north,part=foot]");
            test.command("setblock " + at(bed[0], 0, bed[1] - 1) + " minecraft:red_bed[facing=north,part=head]");
        }
        test.command("setblock " + at(6, 0, -3) + " minecraft:blast_furnace");
        String[] jobs = { "armorer", "farmer", "librarian" };
        for (int i = 0; i < jobs.length; i++)
            test.command("summon minecraft:villager " + at(-3 + i * 3, 0, 3) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:"
                    + jobs[i] + "\",type:\"minecraft:plains\",level:2},Xp:20}");
        test.command("summon minecraft:iron_golem " + at(0, 0, 6) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"]}");
        test.await("three villagers and a golem on the stage", 20, () -> {
            List<UUID> found = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, new Box(bell()).expand(VILLAGE), e -> e.getCommandTags().contains(TAG)).stream().map(VillagerEntity::getUuid).toList());
            List<UUID> golems = onServer(() -> world().getEntitiesByClass(IronGolemEntity.class, new Box(bell()).expand(VILLAGE), e -> e.getCommandTags().contains(TAG)).stream().map(IronGolemEntity::getUuid).toList());
            if (found.size() < 3 || golems.isEmpty()) return false;
            villagers.clear(); villagers.addAll(found); golem = golems.getFirst();
            return true;
        }, () -> {
            if (golem == null || villagers.isEmpty()) { test.fail("the village did not appear"); finish(); return; }
            // The bell is the village's meeting point, as vanilla gives it to villagers near a bell.
            GlobalPos point = GlobalPos.create(world().getRegistryKey(), bell());
            test.server().execute(() -> { for (VillagerEntity villager : residents()) villager.getBrain().remember(MemoryModuleType.MEETING_POINT, point); });
            test.command("effect give @e[tag=" + TAG + ",type=minecraft:villager] minecraft:resistance 600 3 true");
            test.await("the defence finds the village (a zone with a golem)", 15, () -> onServer(() -> {
                ZombieEntity probe = new ZombieEntity(EntityType.ZOMBIE, world());
                probe.refreshPositionAndAngles(fx + 4.5, fy, fz + 4.5, 0, 0);
                return MobGameBridge.blocksMonsterSpawn(world(), probe);
            }), this::golemHealth);
        });
    }

    private void walls(int x0, int x1, int z0, int z1) {
        for (String wall : List.of(at(x0, 0, z0) + " " + at(x1, 5, z0), at(x0, 0, z1) + " " + at(x1, 5, z1), at(x0, 0, z0) + " " + at(x0, 5, z1), at(x1, 0, z0) + " " + at(x1, 5, z1)))
            test.command("fill " + wall + " minecraft:barrier");
    }

    /** (в) The village golem has 150 health: the attribute, its modifier and /data get as a player sees it. */
    private void golemHealth() {
        boolean outside = onServer(() -> {
            ZombieEntity probe = new ZombieEntity(EntityType.ZOMBIE, world());
            probe.refreshPositionAndAngles(fx + CONTROL_X + 0.5, fy, fz + 0.5, 0, 0);
            return MobGameBridge.blocksMonsterSpawn(world(), probe);
        });
        test.check(!outside, "outside the village the spawn is not blocked");
        IronGolemEntity g = golem();
        if (g == null) { test.fail("no golem"); finish(); return; }
        double max = onServer(() -> g.getAttributeValue(EntityAttributes.GENERIC_MAX_HEALTH));
        float health = onServer(g::getHealth);
        test.log("golem max health " + max + ", health " + health + ", modifiers " + onServer(() -> g.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH).getModifiers().toString()));
        test.check(Math.abs(max - 150) < 0.01, "the village golem has 150 max health (" + max + ")");
        test.check(Math.abs(health - 150) < 0.01, "the village golem starts at full 150 health (" + health + ")");
        mark();
        test.server().execute(() -> test.server().getCommandManager().executeWithPrefix(test.serverPlayer().getCommandSource().withLevel(4),
                "data get entity " + golem + " attributes"));
        test.await("/data get shows the golem's attributes", 5, () -> heard("generic.max_health"), () -> {
            test.check(heard("village_golem_health"), "/data get lists the modifier mobtalk:village_golem_health");
            shot("mobtalk-defense-golem-data.png", this::spawning);
        });
    }

    /** (а) Natural spawning at night: the spawn routine is run by hand on random spots of both floors, the player between them. */
    private void spawning() {
        // The control floor and the platform are built once the player is there: a fill in a chunk not loaded yet does nothing.
        test.command("tp " + name() + " " + at(PLATFORM_X, 0, 0) + " -90 10");
        test.await("pause: the control floor's chunks load", 3, () -> false, this::controlFloor);
    }

    private void controlFloor() {
        int c0 = CONTROL_X - CONTROL, c1 = CONTROL_X + CONTROL;
        test.command("fill " + at(c0, -1, -CONTROL) + " " + at(c1, -1, CONTROL) + " minecraft:smooth_stone");
        test.command("fill " + at(c0, 0, -CONTROL) + " " + at(c1, 6, CONTROL) + " minecraft:air");
        walls(c0, c1, -CONTROL, CONTROL);
        test.command("fill " + at(PLATFORM_X - 1, -1, -1) + " " + at(PLATFORM_X + 1, -1, 1) + " minecraft:smooth_stone");
        test.command("tp " + name() + " " + at(PLATFORM_X, 0, 0) + " -90 10");
        // Both floors under a stone roof for the test: dark enough to spawn whatever the sky, moon and biome light.
        String roofVillage = at(-VILLAGE, 4, -VILLAGE) + " " + at(VILLAGE, 4, VILLAGE), roofControl = at(CONTROL_X - CONTROL, 4, -CONTROL) + " " + at(CONTROL_X + CONTROL, 4, CONTROL);
        test.command("fill " + roofVillage + " minecraft:stone"); test.command("fill " + roofControl + " minecraft:stone");
        test.await("pause: the player between the floors, the roofs dark", 3, () -> false, () -> {
            test.log("player at " + test.serverPlayer().getBlockPos() + " for the spawn routine");
            test.check(onServer(() -> world().getBlockState(new BlockPos(fx + CONTROL_X, fy - 1, fz)).isOf(net.minecraft.block.Blocks.SMOOTH_STONE)), "the control floor is built");
            int[] counts = onServer(() -> {
                int[] out = new int[2];
                Random random = new Random(7);
                for (int i = 0; i < 400; i++) {
                    boolean village = i % 2 == 0;
                    int x = village ? fx - 20 + random.nextInt(41) : fx + CONTROL_X - 16 + random.nextInt(33);
                    int z = fz - 16 + random.nextInt(33);
                    BlockPos pos = new BlockPos(x, fy, z);
                    if (village && Math.abs(x - fx) < 2 && Math.abs(z - fz) < 2) continue; // The bell.
                    SpawnHelper.spawnEntitiesInChunk(SpawnGroup.MONSTER, world(), world().getWorldChunk(pos), pos, (type, p, chunk) -> true,
                            (mob, chunk) -> { if (mob instanceof Monster) out[village ? 0 : 1]++; mob.discard(); });
                }
                return out;
            });
            test.log("spawn routine, 200 tries each: village " + counts[0] + " monsters, control floor " + counts[1]);
            test.check(counts[0] == 0, "no monster spawns inside the village with a golem (" + counts[0] + ")");
            if (counts[1] > 0) test.log("OK: monsters do spawn on the control floor outside the village (" + counts[1] + ")");
            else test.fail("no monster spawned on the control floor either: the spawn check proves nothing");
            test.command("fill " + roofVillage + " minecraft:air"); test.command("fill " + roofControl + " minecraft:air");
            shot("mobtalk-defense-floors.png", this::attack);
        });
    }

    /** (а) Six zombies at the edge of the village: the attack, the bell, the villagers hide, the golem goes for them; then the all-clear. */
    private void attack() {
        test.command("tp " + name() + " " + at(6, 0, 12) + " 135 20");
        mark();
        for (int i = 0; i < 6; i++)
            test.command("summon minecraft:zombie " + at(-11, 0, -4 + i * 2) + " {PersistenceRequired:1b,IsBaby:0b,CanBreakDoors:0b,Tags:[\"" + TAG + "\"]}");
        test.await("the attack is called in the chat", 20, () -> heard("На деревню напали"), () -> {
            test.check(heard("Тревога! На деревню напали"), "the chat: «Тревога! На деревню напали»");
            test.check(heard().stream().anyMatch(line -> line.startsWith("<") && line.contains("> ")), "a villager shouts about the attack");
            test.await("the bell rings", 5, () -> onServer(() -> world().getBlockEntity(bell()) instanceof BellBlockEntity b && b.ringing), () -> {
                test.check(onServer(() -> world().getBlockEntity(bell()) instanceof BellBlockEntity b && b.ringing), "the village bell rings");
                long hiding = residents().stream().filter(v -> onServer(() -> v.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HEARD_BELL_TIME).isPresent())).count();
                test.check(hiding == residents().size() && hiding > 0, "every villager heard the bell and hides (" + hiding + "/" + residents().size() + ")");
                test.await("the golem goes for a zombie", 10, () -> { IronGolemEntity g = golem(); return g != null && onServer(() -> g.getTarget() instanceof ZombieEntity); }, () -> {
                    IronGolemEntity g = golem();
                    test.check(g != null && onServer(() -> g.getTarget() instanceof ZombieEntity), "the golem fights a zombie");
                    shot("mobtalk-defense-attack.png", () -> {
                        test.await("the zombies are beaten", 90, () -> zombies().isEmpty(), () -> {
                            int left = zombies().size();
                            test.log("zombies left after the fight: " + left);
                            if (left > 0) test.command("kill @e[type=minecraft:zombie,tag=" + TAG + "]");
                            IronGolemEntity after = golem();
                            test.log("golem after the fight: " + (after == null ? "dead" : onServer(after::getHealth) + "/" + onServer(after::getMaxHealth)) + ", villagers alive " + residents().size());
                            shot("mobtalk-defense-fight-over.png", () -> {
                                test.command("tp " + name() + " " + at(6, 0, 12) + " 135 20");
                                test.await("the all-clear (60 s of quiet)", 110, () -> heard("Отбой тревоги"), () -> {
                                    test.check(heard("Отбой тревоги"), "the chat: «Отбой тревоги» with the account");
                                    heard().stream().filter(line -> line.contains("Отбой")).findFirst().ifPresent(line -> test.log("all-clear: " + line));
                                    shot("mobtalk-defense-clear.png", this::strike);
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    /** (б) The player strikes a villager: a shout with their name, the alarm against them, the golem, no trade; forgive lifts it. */
    private void strike() {
        test.command("time set 1000"); // Day: the zombies would burn, the villagers do not sleep.
        VillagerEntity target = residents().stream().filter(v -> v.getVillagerData().getProfession() == VillagerProfession.FARMER).findFirst()
                .orElse(residents().isEmpty() ? null : residents().getFirst());
        if (target == null) { test.fail("no villager left to strike"); f7(); return; }
        UUID victim = target.getUuid();
        // Held still for the blow and the trades: the bell sends villagers running home, and a sleeper stays in bed.
        test.server().execute(() -> { if (target.isSleeping()) target.wakeUp(); target.setAiDisabled(true); });
        test.command("tp " + victim + " " + at(-3, 0, 12) + " 90 0");
        test.command("tp " + name() + " " + at(-1, 0, 12) + " 90 0");
        test.await("pause: face to face with the villager", 2, () -> false, () -> {
            var client = test.owner.client;
            VillagerEntity seen = client.world.getEntitiesByClass(VillagerEntity.class, client.player.getBoundingBox().expand(5), v -> v.getUuid().equals(victim)).stream().findFirst().orElse(null);
            if (seen == null) { test.fail("the villager is not beside the player"); f7(); return; }
            client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, seen.getEyePos());
            mark();
            client.interactionManager.attackEntity(client.player, seen);
            client.player.swingHand(Hand.MAIN_HAND);
            String player = MobGameBridge.playerName(test.serverPlayer()); // The name the village knows, not the account's.
            test.await("the village raises the alarm against the player", 10, () -> heard("подняла тревогу против вас"), () -> {
                test.check(heard("подняла тревогу против вас"), "the player hears «Деревня подняла тревогу против вас»");
                test.check(heard().stream().anyMatch(line -> line.startsWith("<") && line.contains(player)), "a villager shouts the player's name at once");
                test.await("the golem attacks the player", 10, () -> { IronGolemEntity g = golem(); return g != null && onServer(() -> g.getTarget() == test.serverPlayer()); }, () -> {
                    IronGolemEntity g = golem();
                    test.check(g != null && onServer(() -> g.getTarget() == test.serverPlayer()), "the golem goes for the player with no warning");
                    shot("mobtalk-defense-strike.png", () -> trade(victim, false, () -> forgive(victim)));
                });
            });
        });
    }

    /** Right click on the villager: refused with «Не до торговли» during the alarm, the trade window otherwise. */
    private void trade(UUID villager, boolean expectOpen, Runnable then) {
        test.command("tp " + villager + " " + at(-3, 0, 12) + " 90 0");
        test.command("tp " + name() + " " + at(-1, 0, 12) + " 90 0");
        test.await("pause: beside the villager to trade", 2, () -> false, () -> {
            var client = test.owner.client;
            VillagerEntity seen = client.world.getEntitiesByClass(VillagerEntity.class, client.player.getBoundingBox().expand(5), v -> v.getUuid().equals(villager)).stream().findFirst().orElse(null);
            if (seen == null) { test.fail("the villager is not beside the player to trade"); then.run(); return; }
            client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, seen.getEyePos());
            mark();
            client.interactionManager.interactEntity(client.player, seen, Hand.MAIN_HAND);
            if (!expectOpen) test.await("the villager refuses to trade", 5, () -> heard("Не до торговли"), () -> {
                test.check(heard("Не до торговли") && !(client.currentScreen instanceof MerchantScreen), "the villager refuses: «Не до торговли»");
                shot("mobtalk-defense-no-trade.png", then);
            });
            else test.await("the trade window opens", 5, () -> client.currentScreen instanceof MerchantScreen, () -> {
                test.check(client.currentScreen instanceof MerchantScreen, "after the all-clear the villager trades again"
                        + (heard("торг") ? " (heard: " + heard() + ")" : ""));
                shot("mobtalk-defense-trade-again.png", () -> { client.setScreen(null); test.server().execute(() -> { if (world().getEntity(villager) instanceof VillagerEntity v) v.setAiDisabled(false); }); then.run(); });
            });
        });
    }

    private void forgive(UUID villager) {
        mark();
        test.owner.forgiveVillage();
        test.await("forgive lifts the alarm against the player", 10, () -> heard("больше не держит на вас тревогу"), () -> {
            test.check(heard("больше не держит на вас тревогу"), "/mobtalk village forgive: «Деревня больше не держит на вас тревогу»");
            test.await("pause: the golem calms down after forgive", 10, () -> { IronGolemEntity g = golem(); return g != null && onServer(() -> g.getTarget() != test.serverPlayer()); }, () -> {
                IronGolemEntity g = golem();
                test.check(g != null && onServer(() -> g.getTarget() != test.serverPlayer()), "after forgive the golem leaves the player alone");
                shot("mobtalk-defense-forgiven.png", () -> {
                    mark();
                    test.await("the all-clear after the blow", 110, () -> heard("Отбой тревоги"), () -> {
                        test.check(heard("Отбой тревоги"), "the alarm after the blow ends with «Отбой тревоги»");
                        trade(villager, true, this::repair);
                    });
                });
            });
        });
    }

    /** (в) The wounded golem heals out of a fight, and the smith walks up and mends it with iron. */
    private void repair() {
        IronGolemEntity g = golem();
        if (g == null) { test.fail("no golem to mend"); f7(); return; }
        test.server().execute(() -> { g.setTarget(null); g.stopAnger(); g.setHealth(60); });
        healthBefore = 60;
        test.command("tp " + name() + " " + at(10, 1, 12) + " 135 25");
        List<Float> samples = new ArrayList<>();
        test.log("golem wounded to 60/150; waiting for regeneration and the smith");
        test.await("the smith mends the golem", 75, () -> {
            IronGolemEntity now = golem();
            if (now == null) return false;
            float health = onServer(now::getHealth);
            if (samples.isEmpty() || samples.getLast() != health) samples.add(health);
            VillagerEntity smith = residents().stream().filter(v -> v.getVillagerData().getProfession() == VillagerProfession.ARMORER).findFirst().orElse(null);
            if (smith != null && onServer(() -> smith.squaredDistanceTo(now)) <= 3.5 * 3.5) smithCame = true;
            for (int i = 1; i < samples.size(); i++) if (samples.get(i) - samples.get(i - 1) >= 20) return true;
            return false;
        }, () -> {
            IronGolemEntity now = golem();
            float health = now == null ? 0 : onServer(now::getHealth);
            test.log("golem health over time: " + samples);
            test.check(health > healthBefore, "the wounded golem heals (" + healthBefore + " -> " + health + ")");
            boolean jump = false;
            for (int i = 1; i < samples.size(); i++) if (samples.get(i) - samples.get(i - 1) >= 20) jump = true;
            test.check(smithCame, "the armorer walked up to the golem");
            test.check(jump, "the smith mended the golem by 25 at once");
            shot("mobtalk-defense-repair.png", this::f7);
        });
    }

    /** (г) F7 → Социум → «Защита деревни»: four switches and ten sliders, each with its caption. */
    private void f7() {
        var client = test.owner.client;
        test.owner.openSettings(null);
        test.await("F7 opens", 5, () -> client.currentScreen instanceof MobTalkSettingsScreen, () -> {
            if (!(client.currentScreen instanceof MobTalkSettingsScreen screen)) { test.fail("F7 did not open"); finish(); return; }
            List<String> rows = screen.showGroup("social", "defense");
            test.log("«Защита деревни» rows: " + rows);
            List<String> switches = List.of("Защита деревни", "Без монстров в деревне", "Тревога против игроков", "Тревога в чат");
            test.check(rows.containsAll(switches), "the group has its four switches");
            long sliders = rows.stream().filter(row -> !switches.contains(row)).count();
            test.check(sliders == 10, "the group has ten sliders (" + sliders + ")");
            test.check(rows.stream().noneMatch(row -> row.isBlank() || row.startsWith("mobtalk.")), "every row has a caption, none is a raw key");
            shot("mobtalk-defense-f7.png", () -> {
                client.setScreen(null);
                finish();
            });
        });
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        test.command("kill @e[tag=" + TAG + "]");
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: defense stage cleaned up", 2, () -> false, next);
    }
}
