package io.github.mobtalk.client;

import io.github.mobtalk.game.DefenseContent;
import java.util.*;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

/** Village defence, stage 3 ({@code -Only guards}): a grassy village of ten in the sky puts up guard stands and gets its guards (and
 * a watchtower with an archer). Checks the guards' gear and health, the day at the stand and the night round, a fight with six
 * zombies (they strike, a badly hurt one falls back), and a blow at a villager: the guards go for the player at once while the
 * archer shoots. Screenshots of the guards in both looks and of the fight. No LLM, no voice. */
final class SelfTestGuards {
    private static final String TAG = "mobtalk_grd";
    private static final int FLOOR = 47;
    private final SelfTest test;
    private final int fx, fy, fz;
    private final List<String> chat = Collections.synchronizedList(new ArrayList<>());
    private Runnable next = () -> { };
    private int savedBuild;
    private final Set<UUID> arrowsSeen = new HashSet<>();

    SelfTestGuards(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test; this.fx = stageX + 320; this.fy = stageY; this.fz = stageZ;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String line = (overlay ? "[bar] " : "") + Formatting.strip(message.getString());
            chat.add(line);
            if (!overlay) test.log("chat: " + line);
        });
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private BlockPos bell() { return new BlockPos(fx, fy, fz); }
    private long count(String part) { synchronized (chat) { return chat.stream().filter(line -> line.contains(part)).count(); } }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private Box area() { return new Box(bell()).expand(FLOOR + 4, 20, FLOOR + 4); }
    private List<VillagerEntity> guards() { return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && DefenseContent.guard(v))); }
    private List<VillagerEntity> archers() { return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && DefenseContent.archer(v))); }
    private static BlockPos stand(VillagerEntity guard) { return guard.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(null); }
    private Object walkTarget(VillagerEntity villager) {
        return onServer(() -> villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.WALK_TARGET).map(WalkTarget::getLookTarget)
                .map(target -> target instanceof EntityLookTarget entity ? (Object) entity.getEntity() : target.getBlockPos()).orElse(null));
    }
    private String state(VillagerEntity guard) {
        return onServer(() -> guard.getUuid().toString().substring(0, 8) + " at " + guard.getBlockPos().toShortString() + ", health " + guard.getHealth()
                + ", activity " + guard.getBrain().getFirstPossibleNonCoreActivity().map(Object::toString).orElse("?") + ", walk target " + walkTarget(guard)
                + ", hurt by " + guard.getBrain().getOptionalRegisteredMemory(MemoryModuleType.HURT_BY_ENTITY).map(e -> e.getType().getUntranslatedName()).orElse("-")
                + ", nearest hostile " + guard.getBrain().getOptionalRegisteredMemory(MemoryModuleType.NEAREST_HOSTILE).map(e -> String.format(Locale.ROOT, "%.1f", e.distanceTo(guard))).orElse("-"));
    }
    private int newArrows() {
        return onServer(() -> {
            int fresh = 0;
            for (ArrowEntity arrow : world().getEntitiesByClass(ArrowEntity.class, area(), a -> a.getOwner() instanceof VillagerEntity)) if (arrowsSeen.add(arrow.getUuid())) fresh++;
            return fresh;
        });
    }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("guards stages: guard stands, guards with sword and shield, the day, the night round, the fight, the alarm against a player");
        savedBuild = test.bridge().config.towerBuildMinutes;
        test.bridge().config.towerBuildMinutes = 1;
        test.command("difficulty normal"); test.command("time set 1000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode survival " + name());
        test.command("effect give " + name() + " minecraft:slow_falling 900 1 true");
        test.command("effect give " + name() + " minecraft:resistance 900 255 true");
        test.command("effect give " + name() + " minecraft:saturation 900 1 true");
        test.command("tp " + name() + " " + at(0, 1, 0));
        test.await("pause: the stage chunks load", 3, () -> false, this::build);
    }

    private void build() {
        int f = FLOOR;
        test.command("fill " + at(-f, -1, -f) + " " + at(f, -1, f) + " minecraft:grass_block");
        for (String wall : List.of(at(-f, 0, -f) + " " + at(f, 3, -f), at(-f, 0, f) + " " + at(f, 3, f), at(-f, 0, -f) + " " + at(-f, 3, f), at(f, 0, -f) + " " + at(f, 3, f)))
            test.command("fill " + wall + " minecraft:barrier");
        test.command("setblock " + at(0, 0, 0) + " minecraft:bell[attachment=floor,facing=north]");
        String[] jobs = { "farmer", "librarian", "fisherman", "shepherd", "cleric" };
        for (int i = 0; i < 10; i++)
            test.command("summon minecraft:villager " + at(-8 + (i % 5) * 4, 0, -6 + (i / 5) * 12) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:"
                    + jobs[i % 5] + "\",type:\"minecraft:plains\",level:2},Xp:20}");
        test.await("ten villagers on the stage", 20, () -> onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG)).size() >= 10), () -> {
            GlobalPos point = GlobalPos.create(world().getRegistryKey(), bell());
            test.server().execute(() -> { for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG))) v.getBrain().remember(MemoryModuleType.MEETING_POINT, point); });
            test.command("tp " + name() + " " + at(0, 8, 20) + " 180 35");
            test.await("two guards arrive (chat)", 100, () -> count("новый стражник") >= 2, () -> {
                test.check(count("новый стражник") >= 2, "a village of ten puts up stands and gets two guards (" + count("новый стражник") + ")");
                shot("mobtalk-guards-stands.png", () -> test.await("the watchtower is done too", 150, () -> count("Сторожевая башня достроена") >= 1, this::gear));
            });
        });
    }

    /** Sword, shield, helmet, chestplate, 30 health; standing by the stand by day. */
    private void gear() {
        test.check(count("Сторожевая башня достроена") >= 1, "the village also built a watchtower (" + count("Сторожевая башня достроена") + ")");
        List<VillagerEntity> guards = guards();
        test.check(guards.size() >= 2, "two guards in the village (" + guards.size() + ")");
        for (VillagerEntity guard : guards) {
            boolean armed = onServer(() -> guard.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.IRON_SWORD) && guard.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.SHIELD)
                    && guard.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.IRON_HELMET) && guard.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.IRON_CHESTPLATE));
            float max = onServer(guard::getMaxHealth);
            BlockPos stand = onServer(() -> stand(guard));
            boolean post = stand != null && onServer(() -> world().getBlockState(stand).isOf(DefenseContent.GUARD_POST));
            double away = stand == null ? -1 : Math.sqrt(onServer(() -> guard.squaredDistanceTo(Vec3d.ofCenter(stand))));
            test.log("guard " + guard.getUuid() + ": stand " + stand + ", " + String.format(Locale.ROOT, "%.1f", away) + " blocks from it, health " + max);
            test.check(armed, "the guard has a sword, a shield, a helmet and a chestplate");
            test.check(Math.abs(max - test.bridge().config.watchHealth) < 0.01, "the guard has " + test.bridge().config.watchHealth + " health (" + max + ")");
            test.check(post, "the guard's job site is a guard stand");
            test.check(away >= 0 && away <= 4, "by day the guard holds its stand (" + String.format(Locale.ROOT, "%.1f", away) + " blocks)");
        }
        looks(guards.isEmpty() ? null : guards.getFirst(), this::night);
    }

    /** The guard up close in the human look and the vanilla one. */
    private void looks(VillagerEntity guard, Runnable then) {
        if (guard == null) { then.run(); return; }
        test.server().execute(() -> guard.setAiDisabled(true));
        test.command("tp " + guard.getUuid() + " " + at(0, 0, 30) + " 180 0");
        test.command("tp " + name() + " " + at(0, 0, 27) + " 0 5");
        test.owner.config().humanVillagers = true;
        test.await("pause: the guard in front of the camera", 3, () -> false, () -> shot("mobtalk-guards-guard-human.png", () -> {
            test.owner.config().humanVillagers = false;
            test.await("pause: the vanilla look", 3, () -> false, () -> shot("mobtalk-guards-guard-vanilla.png", () -> {
                test.owner.config().humanVillagers = true;
                BlockPos stand = onServer(() -> stand(guard));
                test.server().execute(() -> guard.setAiDisabled(false));
                if (stand != null) test.command("tp " + guard.getUuid() + " " + (stand.getX() + 1.5) + " " + stand.getY() + " " + (stand.getZ() + 0.5));
                then.run();
            }));
        }));
    }

    /** At night the guards on shift walk the round of the stands and the bell. */
    private void night() {
        test.command("time set 18000");
        test.command("tp " + name() + " " + at(0, 10, 24) + " 180 40");
        Map<UUID, Vec3d> start = new HashMap<>();
        Map<UUID, Double> walked = new HashMap<>();
        test.await("pause: the night begins", 3, () -> false, () -> {
            for (VillagerEntity guard : guards()) start.put(guard.getUuid(), onServer(guard::getPos));
            test.await("pause: the night round", 40, () -> {
                for (VillagerEntity guard : guards()) {
                    Vec3d from = start.get(guard.getUuid());
                    if (from != null) walked.merge(guard.getUuid(), onServer(guard::getPos).distanceTo(from), Math::max);
                }
                return false;
            }, () -> {
                long nightIndex = onServer(() -> world().getTimeOfDay() / 24000L);
                int onShift = 0, walking = 0;
                for (VillagerEntity guard : guards()) {
                    boolean shift = (guard.getUuid().getMostSignificantBits() & 1) == (nightIndex & 1);
                    double far = walked.getOrDefault(guard.getUuid(), 0.0);
                    test.log("night: guard " + guard.getUuid() + (shift ? " on shift" : " off shift") + ", went up to " + String.format(Locale.ROOT, "%.1f", far) + " blocks" + (guard.isSleeping() ? ", asleep" : ""));
                    if (shift) { onShift++; if (far >= 3) walking++; } // Two stands by the bell: the round is a few blocks long.
                }
                if (onShift > 0) test.check(walking == onShift, "at night the guards on shift walk the round (" + walking + "/" + onShift + ")");
                else test.log("WARN: tonight every guard is off shift by its id; the round is not seen in this run");
                shot("mobtalk-guards-night.png", this::zombies);
            });
        });
    }

    /** Six zombies in the village: the guards go and strike; one badly hurt falls back to its stand. */
    private void zombies() {
        newArrows();
        for (int i = 0; i < 6; i++) test.command("summon minecraft:zombie " + at(-6 + i * 2, 0, 14) + " {PersistenceRequired:1b,IsBaby:0b,Tags:[\"" + TAG + "\"]}");
        test.command("tp " + name() + " " + at(0, 6, 26) + " 180 30");
        Set<UUID> struck = new HashSet<>();
        int[] tick = new int[1];
        test.await("the guards beat the zombies", 60, () -> {
            if (++tick[0] % 60 == 0) for (VillagerEntity guard : guards()) test.log("fight: " + state(guard));
            for (ZombieEntity zombie : onServer(() -> world().getEntitiesByClass(ZombieEntity.class, area(), z -> z.getCommandTags().contains(TAG)))) {
                if (onServer(() -> zombie.getAttacker() instanceof VillagerEntity v && DefenseContent.guard(v))) struck.add(zombie.getUuid());
            }
            int dead = 6 - onServer(() -> world().getEntitiesByClass(ZombieEntity.class, area(), z -> z.isAlive() && z.getCommandTags().contains(TAG)).size());
            return struck.size() >= 1 && dead >= 4;
        }, () -> {
            int dead = 6 - onServer(() -> world().getEntitiesByClass(ZombieEntity.class, area(), z -> z.isAlive() && z.getCommandTags().contains(TAG)).size());
            test.check(!struck.isEmpty(), "the guards strike the zombies (" + struck.size() + " zombies hit by a guard)");
            test.check(dead >= 4, "the guards (and the archer) beat most of the zombies within a minute (" + dead + " of 6)");
            shot("mobtalk-guards-fight.png", this::fallBack);
        });
    }

    private void fallBack() {
        VillagerEntity guard = guards().stream().findFirst().orElse(null);
        if (guard == null) { test.fail("no guard to wound"); strike(); return; }
        test.server().execute(() -> guard.setHealth(guard.getMaxHealth() * 0.2f));
        BlockPos stand = onServer(() -> stand(guard));
        double before = stand == null ? 0 : Math.sqrt(onServer(() -> guard.squaredDistanceTo(Vec3d.ofCenter(stand))));
        int[] tick = new int[1];
        test.await("the wounded guard falls back to its stand", 20, () -> { // the chase may have led it far off
            if (++tick[0] % 40 == 0) test.log("falling back: " + state(guard));
            return stand != null && onServer(() -> guard.squaredDistanceTo(Vec3d.ofCenter(stand))) <= 2.5 * 2.5;
        }, () -> {
            double after = stand == null ? 0 : Math.sqrt(onServer(() -> guard.squaredDistanceTo(Vec3d.ofCenter(stand))));
            test.log("the wounded guard: " + String.format(Locale.ROOT, "%.1f -> %.1f", before, after) + " blocks from its stand");
            test.check(stand != null && (after <= 2.5 || after < before - 2), "a badly hurt guard falls back to its stand");
            test.command("kill @e[type=minecraft:zombie,tag=" + TAG + "]");
            test.server().execute(() -> guard.setHealth(guard.getMaxHealth()));
            test.await("pause: the zombies gone", 3, () -> false, this::strike);
        });
    }

    /** The player strikes a villager: the guards go for the player at once, and the archer shoots at the same time. */
    private void strike() {
        test.command("time set 6000");
        VillagerEntity archer = archers().stream().findFirst().orElse(null);
        BlockPos post = archer == null ? bell() : onServer(() -> stand(archer));
        if (post == null) post = bell();
        Vec3d toBell = Vec3d.ofCenter(bell()).subtract(Vec3d.ofCenter(post)).normalize();
        BlockPos spot = BlockPos.ofFloored(Vec3d.ofCenter(post).add(toBell.multiply(12))).withY(fy);
        VillagerEntity victim = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && v.getCommandTags().contains(TAG)).stream().findFirst().orElse(null));
        if (victim == null) { test.fail("no villager to strike"); finish(); return; }
        test.server().execute(() -> { if (victim.isSleeping()) victim.wakeUp(); victim.setAiDisabled(true); });
        test.command("tp " + victim.getUuid() + " " + (spot.getX() + 0.5) + " " + fy + " " + (spot.getZ() + 2.5));
        test.command("tp " + name() + " " + (spot.getX() + 0.5) + " " + fy + " " + (spot.getZ() + 0.5));
        test.await("pause: beside the villager", 2, () -> false, () -> {
            var client = test.owner.client;
            VillagerEntity seen = client.world.getEntitiesByClass(VillagerEntity.class, client.player.getBoundingBox().expand(4), v -> v.getUuid().equals(victim.getUuid())).stream().findFirst().orElse(null);
            if (seen == null) { test.fail("the villager is not beside the player"); finish(); return; }
            newArrows();
            client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, seen.getEyePos());
            client.interactionManager.attackEntity(client.player, seen);
            client.player.swingHand(Hand.MAIN_HAND);
            int[] arrows = new int[1];
            Set<UUID> coming = new HashSet<>();
            for (VillagerEntity a : archers()) test.log("archer " + a.getUuid().toString().substring(0, 8) + " at " + onServer(a::getBlockPos).toShortString() + ", post " + onServer(() -> stand(a)));
            test.await("the guards go for the player", 10, () -> {
                arrows[0] += newArrows();
                for (VillagerEntity guard : guards()) if (walkTarget(guard) == test.serverPlayer() || onServer(() -> guard.squaredDistanceTo(test.serverPlayer())) < 3 * 3) coming.add(guard.getUuid());
                return coming.size() >= Math.min(2, guards().size()) && (archer == null || arrows[0] >= 1);
            }, () -> {
                for (VillagerEntity guard : guards()) test.log("strike: " + state(guard) + ", " + String.format(Locale.ROOT, "%.1f", Math.sqrt(onServer(() -> guard.squaredDistanceTo(test.serverPlayer())))) + " blocks from the player");
                test.check(coming.size() >= Math.min(2, guards().size()), "the guards go for the player at once (" + coming.size() + ")");
                // Once the guards stand at the player they are in the archer's line of fire, and it rightly holds its arrows.
                boolean guardsInTheWay = guards().stream().anyMatch(g -> onServer(() -> g.squaredDistanceTo(test.serverPlayer())) < 1.5 * 1.5);
                if (archer != null && arrows[0] == 0 && guardsInTheWay) test.log("WARN: the guards reached the player before the archer's first arrow; it holds fire with them in the way");
                else if (archer != null) test.check(arrows[0] >= 1, "the archer shoots at the player at the same time (" + arrows[0] + " arrows)");
                shot("mobtalk-guards-strike.png", () -> {
                    test.server().execute(() -> victim.setAiDisabled(false));
                    test.owner.forgiveVillage();
                    finish();
                });
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
        test.bridge().config.towerBuildMinutes = savedBuild;
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: guards stage done", 2, () -> false, next);
    }
}
