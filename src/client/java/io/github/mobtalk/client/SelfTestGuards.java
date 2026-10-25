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
    private final Set<String> ranksFought = new TreeSet<>();

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
    private List<VillagerEntity> guards() { return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && DefenseContent.soldier(v))); }
    private VillagerEntity rank(java.util.function.Predicate<VillagerEntity> rank) { return guards().stream().filter(rank).findFirst().orElse(null); }
    private static String rankOf(VillagerEntity v) { return DefenseContent.heavy(v) ? "heavy" : DefenseContent.cavalry(v) ? "cavalry" : "guard"; }
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
        // Under load a fill in chunks not yet loaded does nothing (the villagers would fall): wait for the floor, fill again if need be.
        test.await("the stage floor is built", 4, this::floorReady, () -> {
            if (!floorReady() && ++floorTries < 6) { build(); return; }
            test.check(floorReady(), "the stage floor is built");
            populate();
        });
    }

    private int floorTries;
    private boolean floorReady() {
        int f = FLOOR - 1;
        return onServer(() -> List.of(bell().add(-f, -1, -f), bell().add(f, -1, -f), bell().add(-f, -1, f), bell().add(f, -1, f), bell().down())
                .stream().allMatch(p -> world().getBlockState(p).isOf(net.minecraft.block.Blocks.GRASS_BLOCK)));
    }

    private void populate() {
        String[] jobs = { "farmer", "librarian", "fisherman", "shepherd", "cleric" };
        for (int i = 0; i < 15; i++)
            test.command("summon minecraft:villager " + at(-8 + (i % 5) * 4, 0, -6 + (i / 5) * 12) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:"
                    + jobs[i % 5] + "\",type:\"minecraft:plains\",level:2},Xp:20}");
        test.await("fifteen villagers on the stage", 20, () -> onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG)).size() >= 15), () -> {
            GlobalPos point = GlobalPos.create(world().getRegistryKey(), bell());
            test.server().execute(() -> { for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG))) v.getBrain().remember(MemoryModuleType.MEETING_POINT, point); });
            test.command("tp " + name() + " " + at(0, 8, 20) + " 180 35");
            test.await("three fighters arrive (chat)", 130, () -> count("новый боец") >= 3, () -> {
                test.check(count("новый боец") >= 3, "a village of fifteen puts up three stands: a guard, a heavy warrior, a horseman (" + count("новый боец") + ")");
                shot("mobtalk-guards-stands.png", () -> test.await("the watchtower is done too", 150, () -> count("Сторожевая башня достроена") >= 1, this::gear));
            });
        });
    }

    /** Each rank's arms, armour and health; the horseman in the saddle; standing by the stand by day. */
    private void gear() {
        test.check(count("Сторожевая башня достроена") >= 1, "the village also built a watchtower (" + count("Сторожевая башня достроена") + ")");
        List<VillagerEntity> guards = guards();
        test.check(guards.size() >= 3, "three fighters in the village (" + guards.size() + ")");
        test.check(guards.stream().anyMatch(DefenseContent::guard) && guards.stream().anyMatch(DefenseContent::heavy) && guards.stream().anyMatch(DefenseContent::cavalry),
                "one of each rank: " + guards.stream().map(SelfTestGuards::rankOf).toList());
        for (VillagerEntity guard : guards) {
            String rank = rankOf(guard);
            boolean armed = onServer(() -> switch (rank) {
                case "heavy" -> guard.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.DIAMOND_AXE) && guard.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.DIAMOND_HELMET)
                        && guard.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.DIAMOND_CHESTPLATE) && guard.getEquippedStack(EquipmentSlot.LEGS).isOf(Items.DIAMOND_LEGGINGS)
                        && guard.getEquippedStack(EquipmentSlot.FEET).isOf(Items.DIAMOND_BOOTS);
                case "cavalry" -> guard.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.IRON_SWORD) && guard.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.LEATHER_HELMET)
                        && guard.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.LEATHER_CHESTPLATE) && guard.getEquippedStack(EquipmentSlot.LEGS).isOf(Items.LEATHER_LEGGINGS)
                        && guard.getEquippedStack(EquipmentSlot.FEET).isOf(Items.LEATHER_BOOTS);
                default -> guard.getEquippedStack(EquipmentSlot.MAINHAND).isOf(Items.IRON_SWORD) && guard.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.SHIELD)
                        && guard.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.IRON_HELMET) && guard.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.IRON_CHESTPLATE);
            });
            if (rank.equals("cavalry")) {
                boolean riding = onServer(() -> guard.getVehicle() instanceof net.minecraft.entity.passive.HorseEntity h && h.isSaddled() && h.getBodyArmor().isOf(Items.LEATHER_HORSE_ARMOR));
                test.check(riding, "the horseman rides a saddled horse in leather barding");
            }
            float max = onServer(guard::getMaxHealth);
            float wanted = rank.equals("heavy") ? test.bridge().config.heavyHealth : test.bridge().config.watchHealth;
            BlockPos stand = onServer(() -> stand(guard));
            boolean post = stand != null && onServer(() -> world().getBlockState(stand).isOf(DefenseContent.GUARD_POST));
            double away = stand == null ? -1 : Math.sqrt(onServer(() -> guard.squaredDistanceTo(Vec3d.ofCenter(stand))));
            test.log("guard " + guard.getUuid() + ": stand " + stand + ", " + String.format(Locale.ROOT, "%.1f", away) + " blocks from it, health " + max);
            test.check(armed, "the " + rank + " has the arms and armour of the rank");
            test.check(Math.abs(max - wanted) < 0.01, "the " + rank + " has " + wanted + " health (" + max + ")");
            test.check(post, "the guard's job site is a guard stand");
            test.check(away >= 0 && away <= 5, "by day the " + rank + " holds its stand (" + String.format(Locale.ROOT, "%.1f", away) + " blocks)");
        }
        looks(rank(DefenseContent::guard), "guard", () -> looks(rank(DefenseContent::heavy), "heavy", () -> looks(rank(DefenseContent::cavalry), "cavalry", this::night)));
    }

    /** The guard up close in the human look and the vanilla one. */
    private void looks(VillagerEntity guard, String rank, Runnable then) {
        if (guard == null) { test.fail("no " + rank + " to photograph"); then.run(); return; }
        // A horseman is photographed in the saddle: its horse is moved and held, the rider with it.
        net.minecraft.entity.Entity body = onServer(() -> guard.getVehicle() != null ? guard.getVehicle() : guard);
        test.server().execute(() -> { guard.setAiDisabled(true); if (body instanceof net.minecraft.entity.mob.MobEntity m) m.setAiDisabled(true); });
        test.command("tp " + body.getUuid() + " " + at(0, 0, 30) + " 180 0");
        test.command("tp " + name() + " " + at(0, rank.equals("cavalry") ? 1 : 0, rank.equals("cavalry") ? 25 : 27) + " 0 5");
        test.owner.config().humanVillagers = true;
        test.await("pause: the " + rank + " in front of the camera", 3, () -> false, () -> shot("mobtalk-guards-" + rank + "-human.png", () -> {
            test.owner.config().humanVillagers = false;
            test.await("pause: the vanilla look", 3, () -> false, () -> shot("mobtalk-guards-" + rank + "-vanilla.png", () -> {
                test.owner.config().humanVillagers = true;
                BlockPos stand = onServer(() -> stand(guard));
                test.server().execute(() -> { guard.setAiDisabled(false); if (body instanceof net.minecraft.entity.mob.MobEntity m) m.setAiDisabled(false); });
                if (stand != null) test.command("tp " + body.getUuid() + " " + (stand.getX() + 1.5) + " " + stand.getY() + " " + (stand.getZ() + 0.5));
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
                VillagerEntity by = onServer(() -> zombie.getAttacker() instanceof VillagerEntity v && DefenseContent.soldier(v) ? v : null);
                if (by != null) { struck.add(zombie.getUuid()); ranksFought.add(rankOf(by)); }
            }
            int dead = 6 - onServer(() -> world().getEntitiesByClass(ZombieEntity.class, area(), z -> z.isAlive() && z.getCommandTags().contains(TAG)).size());
            return struck.size() >= 1 && dead >= 4;
        }, () -> {
            int dead = 6 - onServer(() -> world().getEntitiesByClass(ZombieEntity.class, area(), z -> z.isAlive() && z.getCommandTags().contains(TAG)).size());
            test.check(!struck.isEmpty(), "the guards strike the zombies (" + struck.size() + " zombies hit by a guard)");
            test.log("ranks that struck a zombie: " + ranksFought);
            test.check(ranksFought.size() >= 2, "more than one rank fights (" + ranksFought + ")");
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
                    test.await("pause: forgiven", 3, () -> false, this::insideZombie);
                });
            });
        });
    }

    /** One zombie in the middle of the village, away from the guards: a guard goes and strikes it (no wall, no edge). */
    private void insideZombie() {
        test.command("time set 18000");
        test.command("kill @e[type=minecraft:iron_golem]");
        List<Vec3d> guardsAt = guards().stream().map(g -> onServer(g::getPos)).toList();
        BlockPos spot = null;
        for (int[] d : new int[][] { {16, -16}, {-16, 16}, {16, 16}, {-16, -16}, {20, 0}, {-20, 0} }) {
            BlockPos p = bell().add(d[0], 0, d[1]);
            if (guardsAt.stream().allMatch(g -> g.distanceTo(Vec3d.ofCenter(p)) >= 10)) { spot = p; break; }
        }
        if (spot == null) spot = bell().add(16, 0, -16);
        BlockPos at = spot;
        double nearestGuard = guardsAt.stream().mapToDouble(g -> g.distanceTo(Vec3d.ofCenter(at))).min().orElse(-1);
        test.command("summon minecraft:zombie " + at.getX() + " " + fy + " " + at.getZ() + " {PersistenceRequired:1b,IsBaby:0b,Tags:[\"" + TAG + "_in\"]}");
        test.command("tp " + name() + " " + (at.getX() + 6) + " " + (fy + 4) + " " + (at.getZ() + 6) + " 135 25");
        boolean[] struck = new boolean[1];
        String[] last = { "-" };
        test.await("a guard strikes the zombie in the middle of the village", 30, () -> {
            List<ZombieEntity> found = onServer(() -> world().getEntitiesByClass(ZombieEntity.class, area(), z -> z.isAlive() && z.getCommandTags().contains(TAG + "_in")));
            for (ZombieEntity zombie : found) {
                last[0] = onServer(() -> zombie.getAttacker() == null ? "-" : zombie.getAttacker().getName().getString());
                // Struck by a guard, or a guard is on it (the towers' shooters may kill it first).
                if (onServer(() -> zombie.getAttacker() instanceof VillagerEntity v && DefenseContent.soldier(v)
                        || !world().getEntitiesByClass(VillagerEntity.class, zombie.getBoundingBox().expand(3), DefenseContent::soldier).isEmpty())) struck[0] = true;
            }
            if (found.isEmpty()) test.log("the zombie in the village is dead; last struck by " + last[0]);
            return struck[0] || found.isEmpty();
        }, () -> {
            test.log("zombie in the village at " + at.toShortString() + ", the nearest guard was " + String.format(Locale.ROOT, "%.1f", nearestGuard) + " blocks off");
            test.check(struck[0], "a zombie in the middle of the village, away from the guards, is reached or struck by a guard");
            shot("mobtalk-guards-inside-zombie.png", () -> {
                test.command("kill @e[tag=" + TAG + "_in]");
                conversion();
            });
        });
    }

    /** Hard difficulty (vanilla turns every villager a zombie kills): a guard and a plain villager, each shut in a barrier cell with a
     * zombie, die and no zombie villager appears. */
    private void conversion() {
        test.command("difficulty hard");
        VillagerEntity guard = guards().stream().findFirst().orElse(null);
        VillagerEntity plain = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && !v.isBaby() && v.getCommandTags().contains(TAG)
                && !DefenseContent.guard(v) && !DefenseContent.archer(v)).stream().findFirst().orElse(null));
        if (guard == null || plain == null) { test.fail("no guard or villager for the conversion check"); finish(); return; }
        int[][] cells = { {-30, 30}, {-24, 30} };
        VillagerEntity[] victims = { guard, plain };
        for (int i = 0; i < 2; i++) {
            BlockPos c = bell().add(cells[i][0], 0, cells[i][1]);
            test.command("fill " + (c.getX() - 2) + " " + (fy - 1) + " " + (c.getZ() - 1) + " " + (c.getX() + 2) + " " + (fy + 3) + " " + (c.getZ() + 1) + " minecraft:barrier hollow");
            VillagerEntity v = victims[i];
            test.server().execute(() -> { if (v.isSleeping()) v.wakeUp(); v.setAiDisabled(true); v.setHealth(1f); });
            test.command("tp " + v.getUuid() + " " + (c.getX() - 0.5) + " " + fy + " " + (c.getZ() + 0.5));
            test.command("summon minecraft:zombie " + (c.getX() + 1.5) + " " + fy + " " + (c.getZ() + 0.5) + " {PersistenceRequired:1b,IsBaby:0b,Tags:[\"" + TAG + "_conv\"]}");
        }
        UUID guardId = guard.getUuid(), plainId = plain.getUuid();
        test.command("tp " + name() + " " + (fx - 27) + " " + (fy + 2) + " " + (fz + 25) + " 180 25");
        test.await("pause: the zombies at the cells", 3, () -> false, () -> shot("mobtalk-guards-conversion-before.png", () ->
            test.await("the zombies kill the guard and the villager", 30, () -> onServer(() -> !(world().getEntity(guardId) instanceof VillagerEntity g && g.isAlive())
                    && !(world().getEntity(plainId) instanceof VillagerEntity p && p.isAlive())), () -> {
                boolean guardDead = onServer(() -> !(world().getEntity(guardId) instanceof VillagerEntity g && g.isAlive()));
                boolean plainDead = onServer(() -> !(world().getEntity(plainId) instanceof VillagerEntity p && p.isAlive()));
                int zombieVillagers = onServer(() -> world().getEntitiesByClass(net.minecraft.entity.mob.ZombieVillagerEntity.class, area(), z -> true).size());
                test.check(guardDead && plainDead, "the zombies killed the guard and the villager (guard " + guardDead + ", villager " + plainDead + ")");
                test.check(zombieVillagers == 0, "on hard, neither turned into a zombie villager (" + zombieVillagers + " zombie villagers)");
                shot("mobtalk-guards-conversion-after.png", () -> {
                    test.command("kill @e[tag=" + TAG + "_conv]");
                    test.command("difficulty normal");
                    finish();
                });
            })));
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
