package io.github.mobtalk.client;

import io.github.mobtalk.core.TowerPlan;
import io.github.mobtalk.game.DefenseContent;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.Items;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

/** Village defence, stage 2 ({@code -Only towers}): a grassy village of sixteen in the sky builds two watchtowers in a minute each.
 * Checks every block of the plan stands (no holes, the door whole, the stairwell clear), the archer at the post, the archer climbing
 * back up the stair on his own, the night shift, the horn and arrows that cannot be picked up at zombies, arrows at a player who
 * struck a villager, and no arrow through a villager. Screenshots of the towers from four sides and inside, and the archer in both
 * looks. No LLM, no voice. */
final class SelfTestTowers {
    private static final String TAG = "mobtalk_tow";
    private static final int FLOOR = 47;
    private static final Pattern SITE = Pattern.compile("\\((-?\\d+) (-?\\d+) (-?\\d+)\\)");
    private final SelfTest test;
    private final int fx, fy, fz;
    private final List<String> chat = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sounds = Collections.synchronizedList(new ArrayList<>());
    private final List<BlockPos> sites = new ArrayList<>();
    private Runnable next = () -> { };
    private int chatMark, savedBuild;
    private final Set<UUID> arrowsSeen = new HashSet<>();
    private BlockPos strikeSpot;
    private boolean savedGuards;
    private int savedLarge;

    SelfTestTowers(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test; this.fx = stageX + 260; this.fy = stageY; this.fz = stageZ;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String line = (overlay ? "[bar] " : "") + Formatting.strip(message.getString());
            chat.add(line);
            test.log("chat: " + line);
        });
        test.owner.client.getSoundManager().registerListener((sound, set, range) -> sounds.add(sound.getId().toString()));
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private static String at(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private BlockPos bell() { return new BlockPos(fx, fy, fz); }
    private List<String> heard() { synchronized (chat) { return List.copyOf(chat.subList(Math.min(chatMark, chat.size()), chat.size())); } }
    private boolean heard(String part) { return heard().stream().anyMatch(line -> line.contains(part)); }
    private long count(String part) { synchronized (chat) { return chat.stream().filter(line -> line.contains(part)).count(); } }
    private void mark() { chatMark = chat.size(); }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private Box area() { return new Box(bell()).expand(FLOOR + 4, 20, FLOOR + 4); }
    /** Everyone who shoots from a tower: archers and crossbowmen. */
    private List<VillagerEntity> archers() {
        return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && DefenseContent.shooter(v)));
    }
    /** Whether the tower at this origin was built large (found from its door). */
    private final Map<BlockPos, Boolean> large = new HashMap<>();
    private static BlockPos post(VillagerEntity archer) {
        return archer.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(null);
    }
    private boolean atPost(VillagerEntity archer) {
        return onServer(() -> { BlockPos post = post(archer); return post != null && archer.squaredDistanceTo(Vec3d.ofCenter(post)) <= 2.5 * 2.5; });
    }
    /** Arrows of archers not counted before. */
    private int newArrows(boolean[] pickable) {
        return onServer(() -> {
            int fresh = 0;
            for (ArrowEntity arrow : world().getEntitiesByClass(ArrowEntity.class, area(), a -> a.getOwner() instanceof VillagerEntity)) {
                if (!arrowsSeen.add(arrow.getUuid())) continue;
                fresh++;
                if (arrow.pickupType != PersistentProjectileEntity.PickupPermission.DISALLOWED) pickable[0] = true;
            }
            return fresh;
        });
    }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("towers stages: two watchtowers built, their archers, the horn and the arrows");
        savedBuild = test.bridge().config.towerBuildMinutes; savedGuards = test.bridge().config.villageGuards; savedLarge = test.bridge().config.towerLargeResidents;
        test.bridge().config.towerLargeResidents = 0; // The first tower small; the second, once allowed, large.
        test.bridge().config.towerBuildMinutes = 1;
        test.bridge().config.villageGuards = false; // The archers alone: guards would knock the player about (their own stage is -Only guards).
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
        String[] jobs = { "farmer", "librarian", "fisherman", "shepherd" };
        for (int i = 0; i < 16; i++)
            test.command("summon minecraft:villager " + at(-9 + (i % 4) * 6, 0, -9 + (i / 4) * 6) + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:"
                    + jobs[i % 4] + "\",type:\"minecraft:plains\",level:2},Xp:20}");
        test.await("sixteen villagers on the stage", 20, () -> onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG)).size() >= 16), () -> {
            GlobalPos point = GlobalPos.create(world().getRegistryKey(), bell());
            test.server().execute(() -> { for (VillagerEntity v : world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains(TAG))) v.getBrain().remember(MemoryModuleType.MEETING_POINT, point); });
            test.command("tp " + name() + " " + at(0, 8, 20) + " 180 30");
            mark();
            test.await("the village starts a tower (chat)", 70, () -> heard("начала строить сторожевую башню"), () -> {
                BlockPos site = site();
                if (site == null) { test.fail("no tower started"); finish(); return; }
                sites.add(site);
                test.check(true, "the village started a tower at " + at(site));
                viewFrom(site, 12, 6, () -> test.await("pause: the tower half built", 40, () -> sampleBuilder(site), () -> builderChecks(site, () -> shot("mobtalk-towers-building.png", () -> {
                    test.await("the first tower is done (chat)", 180, () -> count("Сторожевая башня достроена") >= 1, () -> {
                        test.check(count("Сторожевая башня достроена") >= 1, "the first tower is finished in about a minute");
                        test.bridge().config.towerLargeResidents = 16; // sixteen residents: the next tower is a large one
                        mark();
                        test.await("the second tower starts", 70, () -> heard("начала строить сторожевую башню"), () -> {
                            BlockPos second = site();
                            if (second != null) sites.add(second);
                            test.check(second != null, "sixteen residents: a second tower is started" + (second == null ? "" : " at " + at(second)));
                            test.await("the second (large) tower is done", 240, () -> count("Сторожевая башня достроена") >= 2, this::inspect);
                        });
                    });
                }))));
            });
        });
    }

    private int builderSamples, builderThere;
    private boolean builderHolds;
    private UUID builder;
    private int sampleTick;
    private boolean arrived;
    /** Once a second while the tower grows: the villager nearest the site within 7 blocks (not an archer), and what it holds. */
    private boolean sampleBuilder(BlockPos site) {
        if (++sampleTick % 20 != 0) return false;
        VillagerEntity near = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, new Box(site).expand(7, 12, 7),
                v -> v.isAlive() && !v.isBaby() && !DefenseContent.archer(v) && Math.hypot(v.getX() - site.getX() - 0.5, v.getZ() - site.getZ() - 0.5) <= 7)
                .stream().min(Comparator.comparingDouble(v -> v.squaredDistanceTo(Vec3d.ofCenter(site)))).orElse(null));
        if (near != null) arrived = true;
        if (arrived) builderSamples++; // counted from the builder's arrival: the tower waits while it walks there
        if (near != null) {
            builderThere++;
            builder = near.getUuid();
            if (onServer(() -> near.getMainHandStack().getItem() instanceof net.minecraft.item.BlockItem)) builderHolds = true;
        }
        return false;
    }

    /** The builder stayed by the tower most of the time and held a block; a close-up of it at work. */
    private void builderChecks(BlockPos site, Runnable then) {
        test.log("builder: by the tower in " + builderThere + " of " + builderSamples + " samples, holds a block: " + builderHolds
                + ", chat: " + heard().stream().filter(line -> line.contains("стро")).toList());
        test.check(builderSamples > 0 && builderThere * 10 >= builderSamples * 7, "once there, the builder stays within 7 blocks of the growing tower (" + builderThere + "/" + builderSamples + ")");
        test.check(builderHolds, "the builder holds a block");
        VillagerEntity b = builder == null ? null : onServer(() -> world().getEntity(builder) instanceof VillagerEntity v ? v : null);
        if (b == null) { then.run(); return; }
        Vec3d at = onServer(b::getPos);
        Vec3d away = at.subtract(Vec3d.ofCenter(site)).multiply(1, 0, 1).normalize();
        Vec3d eye = at.add(away.multiply(3.5)).add(0, 0.5, 0);
        test.command("tp " + name() + " " + eye.x + " " + eye.y + " " + eye.z);
        test.await("pause: by the builder", 2, () -> false, () -> {
            test.owner.client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, onServer(b::getEyePos).subtract(0, 0.3, 0));
            shot("mobtalk-towers-builder.png", then);
        });
    }

    private BlockPos site() {
        for (String line : heard()) {
            if (!line.contains("начала строить")) continue;
            Matcher m = SITE.matcher(line);
            if (m.find()) return new BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        return null;
    }

    /** Which way the tower's door faces: the plan's door cell, tried for each turn. */
    private TowerPlan.Facing door(BlockPos origin) {
        for (boolean big : new boolean[] { false, true })
            for (TowerPlan.Facing facing : TowerPlan.Facing.values()) {
                for (TowerPlan.Block block : TowerPlan.rotated(facing, big))
                    if (block.kind() == TowerPlan.Kind.DOOR_LOWER && onServer(() -> world().getBlockState(origin.add(block.x(), block.y(), block.z())).getBlock() instanceof DoorBlock)) {
                        large.put(origin, big);
                        return facing;
                    }
            }
        return null;
    }
    private boolean large(BlockPos origin) { return large.getOrDefault(origin, false); }
    private static BlockPos local(BlockPos origin, TowerPlan.Facing door, int x, int y, int z) {
        int nx = x, nz = z;
        for (int i = 0; i < door.ordinal(); i++) { int t = nx; nx = -nz; nz = t; }
        return origin.add(nx, y, nz);
    }

    /** Every block of the plan is where it belongs; the cells the plan leaves empty are empty; the foundation is under the floor. */
    private void inspect() {
        test.check(count("Сторожевая башня достроена") >= 2, "the second tower is finished");
        for (int t = 0; t < sites.size(); t++) {
            BlockPos origin = sites.get(t);
            TowerPlan.Facing door = door(origin);
            if (door == null) { test.fail("tower " + (t + 1) + ": no door found"); continue; }
            Map<BlockPos, TowerPlan.Block> last = new LinkedHashMap<>();
            for (TowerPlan.Block block : TowerPlan.rotated(door, large(origin))) last.put(origin.add(block.x(), block.y(), block.z()), block);
            int r = TowerPlan.radius(large(origin));
            List<String> missing = new ArrayList<>(), cluttered = new ArrayList<>();
            int doorParts = 0;
            for (Map.Entry<BlockPos, TowerPlan.Block> entry : last.entrySet()) {
                BlockState state = onServer(() -> world().getBlockState(entry.getKey()));
                TowerPlan.Kind kind = entry.getValue().kind();
                if (kind == TowerPlan.Kind.AIR) { if (!state.isAir()) cluttered.add(at(entry.getKey()) + "=" + state.getBlock()); continue; }
                if (state.isAir()) missing.add(kind + "@" + at(entry.getKey()));
                if ((kind == TowerPlan.Kind.DOOR_LOWER && state.getBlock() instanceof DoorBlock && state.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER)
                        || (kind == TowerPlan.Kind.DOOR_UPPER && state.getBlock() instanceof DoorBlock && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER)) doorParts++;
            }
            int hollow = 0;
            for (int x = -r; x <= r; x++)
                for (int z = -r; z <= r; z++) { BlockPos under = origin.add(x, -1, z); if (onServer(() -> world().getBlockState(under).isAir())) hollow++; }
            test.log("tower " + (t + 1) + (large(origin) ? " (large)" : " (small)") + " at " + at(origin) + ", door " + door + ": missing " + missing + ", not cleared " + cluttered);
            test.check(missing.isEmpty(), "tower " + (t + 1) + ": no holes, every block of the plan stands (" + missing.size() + " missing)");
            test.check(cluttered.isEmpty(), "tower " + (t + 1) + ": the stairwell and the inside are clear (" + cluttered.size() + " blocked)");
            test.check(doorParts == 2, "tower " + (t + 1) + ": the door is whole, both halves (" + doorParts + ")");
            test.check(hollow == 0, "tower " + (t + 1) + ": nothing hangs in the air, the floor stands on ground (" + hollow + " hollow)");
            BlockPos toCentre = local(origin, door, 0, 0, -r - 3);
            test.check(toCentre.getSquaredDistance(bell()) < origin.getSquaredDistance(bell()), "tower " + (t + 1) + ": the door faces the village");
        }
        List<VillagerEntity> archers = archers();
        long bows = archers.stream().filter(DefenseContent::archer).count(), crossbows = archers.stream().filter(DefenseContent::crossbowman).count();
        int wanted = sites.stream().mapToInt(o -> large(o) ? 4 : 1).sum();
        test.log("shooters: " + archers.size() + " (archers " + bows + ", crossbowmen " + crossbows + "), at the post: " + archers.stream().filter(this::atPost).count());
        test.check(sites.size() == 2 && !large(sites.get(0)) && large(sites.get(1)), "the first tower is small, the second (sixteen residents) large");
        test.check(archers.size() >= wanted, "a shooter came to every post (" + archers.size() + " for " + wanted + ")");
        test.check(crossbows == 2, "the large tower has two crossbowmen (" + crossbows + ")");
        test.check(archers.stream().allMatch(a -> onServer(() -> a.getMainHandStack().isOf(DefenseContent.crossbowman(a) ? Items.CROSSBOW : Items.BOW))), "archers hold bows, crossbowmen crossbows");
        test.check(!archers.isEmpty() && archers.stream().allMatch(this::atPost), "the shooters stand by their posts");
        sides(0, 0, "mobtalk-towers-", () -> sides(1, 0, "mobtalk-towers-large-", () -> inside(this::climb)));
    }

    /** A tower from the four sides, a little above. */
    private void sides(int tower, int side, String prefix, Runnable then) {
        if (sites.size() <= tower || side >= 4) { then.run(); return; }
        BlockPos o = sites.get(tower);
        int[][] offsets = { { 0, -8 }, { 8, 0 }, { 0, 8 }, { -8, 0 } }; // Within the floor: the towers stand 10 blocks from its edge.
        String[] names = { "north", "east", "south", "west" };
        BlockPos from = o.add(offsets[side][0], 4, offsets[side][1]);
        float yaw = (float) Math.toDegrees(Math.atan2(-(o.getX() - from.getX()), o.getZ() - from.getZ()));
        test.command("tp " + name() + " " + (from.getX() + 0.5) + " " + from.getY() + " " + (from.getZ() + 0.5) + " " + yaw + " 5");
        test.await("pause: the tower from the " + names[side], 2, () -> false, () -> shot(prefix + names[side] + ".png", () -> sides(tower, side + 1, prefix, then)));
    }

    private void viewFrom(BlockPos site, int distance, int height, Runnable then) {
        Vec3d away = Vec3d.ofCenter(site).subtract(Vec3d.ofCenter(bell())).normalize();
        BlockPos from = BlockPos.ofFloored(Vec3d.ofCenter(site).subtract(away.multiply(distance))).withY(site.getY() + height);
        float yaw = (float) Math.toDegrees(Math.atan2(-(site.getX() - from.getX()), site.getZ() - from.getZ()));
        test.command("tp " + name() + " " + (from.getX() + 0.5) + " " + from.getY() + " " + (from.getZ() + 0.5) + " " + yaw + " 15");
        test.await("pause: facing the tower", 2, () -> false, then);
    }

    /** Inside: the ground floor looking up the stair, and the platform by the post. */
    private void inside(Runnable then) {
        BlockPos o = sites.getFirst();
        TowerPlan.Facing door = door(o);
        if (door == null) { then.run(); return; }
        BlockPos low = local(o, door, -1, 1, 1);
        test.command("tp " + name() + " " + (low.getX() + 0.5) + " " + low.getY() + " " + (low.getZ() + 0.5) + " 0 -45");
        test.await("pause: inside the tower", 2, () -> false, () -> {
            test.owner.client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(local(o, door, 1, 3, -1)));
            shot("mobtalk-towers-inside.png", () -> {
                BlockPos top = local(o, door, -1, TowerPlan.PLATFORM + 1, -1);
                test.command("tp " + name() + " " + (top.getX() + 0.5) + " " + top.getY() + " " + (top.getZ() + 0.5));
                test.await("pause: on the platform", 2, () -> false, () -> {
                    test.owner.client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(local(o, door, 0, TowerPlan.PLATFORM + 1, 0)));
                    shot("mobtalk-towers-platform.png", then);
                });
            });
        });
    }

    /** The archer led down outside the door climbs back to the post on his own. */
    private void climb() {
        BlockPos o = sites.getFirst();
        TowerPlan.Facing door = door(o);
        VillagerEntity archer = archers().stream().filter(a -> { BlockPos p = onServer(() -> post(a)); return p != null && p.isWithinDistance(o, 12); }).findFirst().orElse(null);
        if (archer == null || door == null) { test.fail("no archer of the first tower to climb"); night(); return; }
        BlockPos below = local(o, door, 0, 1, -TowerPlan.RADIUS - 3);
        test.command("tp " + archer.getUuid() + " " + (below.getX() + 0.5) + " " + below.getY() + " " + (below.getZ() + 0.5));
        test.command("tp " + name() + " " + at(local(o, door, 3, 3, -8)));
        long start = test.server().getOverworld().getTime();
        int[] tick = new int[1];
        test.await("the archer climbs back to the post", 60, () -> {
            if (++tick[0] % 100 == 0) test.log("climb: " + onServer(() -> {
                BlockPos post = post(archer);
                var path = post == null ? null : archer.getNavigation().findPathTo(post, 1);
                return "archer at " + archer.getBlockPos() + ", walk target " + archer.getBrain().getOptionalRegisteredMemory(MemoryModuleType.WALK_TARGET).map(w -> w.getLookTarget().getBlockPos().toShortString()).orElse("none")
                        + ", can't reach since " + archer.getBrain().getOptionalRegisteredMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE).orElse(-1L)
                        + ", path to the post " + (path == null ? "none" : (path.reachesTarget() ? "reaches it" : "stops at " + path.getEnd().getBlockPos().toShortString()) + " in " + path.getLength() + " nodes")
                        + ", activity " + archer.getBrain().getFirstPossibleNonCoreActivity().map(Object::toString).orElse("?");
            }));
            if (tick[0] == 400) ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, "mobtalk-towers-climb.png", test.owner.client.getFramebuffer(), text -> { });
            return atPost(archer);
        }, () -> {
            long took = (test.server().getOverworld().getTime() - start) / 20;
            test.check(atPost(archer), "the archer walked back up the stair to the post on his own (" + took + " s)");
            night();
        });
    }

    /** Night: the archers whose shift it is go to the post, the others may rest. */
    private void night() {
        test.command("time set 18000");
        test.command("tp " + name() + " " + at(0, 8, 20) + " 180 30");
        test.await("pause: the night shift settles", 30, () -> false, () -> {
            long nightIndex = onServer(() -> world().getTimeOfDay() / 24000L);
            int onShift = 0, onShiftAtPost = 0, offShift = 0;
            for (VillagerEntity archer : archers()) {
                boolean shift = (archer.getUuid().getLeastSignificantBits() & 1) == (nightIndex & 1);
                boolean there = atPost(archer);
                test.log("night: archer " + archer.getUuid() + (shift ? " on shift" : " off shift") + (there ? ", at the post" : ", away") + (archer.isSleeping() ? ", asleep" : ""));
                if (shift) { onShift++; if (there) onShiftAtPost++; } else offShift++;
            }
            test.log("night shifts: " + onShift + " on, " + offShift + " off");
            if (onShift > 0) test.check(onShiftAtPost == onShift, "at night the archers on shift stand at the post (" + onShiftAtPost + "/" + onShift + ")");
            else test.log("WARN: tonight both archers are off shift by their ids; the shift split is not seen in this run");
            zombies();
        });
    }

    /** Zombies by the first tower: the horn, and arrows that cannot be picked up. */
    private void zombies() {
        BlockPos o = sites.getFirst();
        Vec3d out = Vec3d.ofCenter(o).subtract(Vec3d.ofCenter(bell())).normalize();
        BlockPos spot = BlockPos.ofFloored(Vec3d.ofCenter(o).subtract(out.multiply(10))).withY(fy);
        newArrows(new boolean[1]);
        sounds.clear();
        for (int i = 0; i < 3; i++) test.command("summon minecraft:zombie " + (spot.getX() + i * 2) + " " + fy + " " + spot.getZ() + " {PersistenceRequired:1b,IsBaby:0b,Tags:[\"" + TAG + "\"]}");
        test.command("tp " + name() + " " + at(spot.add(6, 6, 6)));
        boolean[] pickable = new boolean[1];
        int[] shots = new int[1];
        test.await("the archers shoot the zombies", 25, () -> (shots[0] += newArrows(pickable)) >= 2, () -> {
            boolean horn = sounds.stream().anyMatch(id -> id.contains("goat_horn"));
            test.log("sounds heard: " + new TreeSet<>(sounds));
            test.check(horn, "the archer blows the horn when the alarm is raised");
            test.check(shots[0] >= 1, "the archers shoot at the zombies (" + shots[0] + " arrows)");
            test.check(!pickable[0], "the archers' arrows cannot be picked up");
            BlockPos look = spot;
            test.owner.client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(look));
            shot("mobtalk-towers-zombies.png", () -> {
                test.command("kill @e[type=minecraft:zombie,tag=" + TAG + "]");
                test.await("pause: the zombies gone", 3, () -> false, this::crossbows);
            });
        });
    }

    /** Zombies by the large tower: the crossbowmen shoot crossbow bolts (heard as crossbows). */
    private void crossbows() {
        if (sites.size() < 2) { strike(); return; }
        BlockPos o = sites.get(1);
        Vec3d out = Vec3d.ofCenter(o).subtract(Vec3d.ofCenter(bell())).normalize();
        BlockPos spot = BlockPos.ofFloored(Vec3d.ofCenter(o).subtract(out.multiply(12))).withY(fy);
        newArrows(new boolean[1]);
        sounds.clear();
        Set<UUID> bolts = new HashSet<>();
        for (int i = 0; i < 3; i++) test.command("summon minecraft:zombie " + (spot.getX() + i * 2) + " " + fy + " " + spot.getZ() + " {PersistenceRequired:1b,IsBaby:0b,Tags:[\"" + TAG + "\"]}");
        BlockPos ear = BlockPos.ofFloored(Vec3d.ofCenter(o).add(out.multiply(5))).withY(o.getY() + 6); // within earshot of the platform
        test.command("tp " + name() + " " + at(ear));
        test.await("the crossbowmen shoot the zombies", 25, () -> {
            for (ArrowEntity arrow : onServer(() -> world().getEntitiesByClass(ArrowEntity.class, area(), a -> a.getOwner() instanceof VillagerEntity v && DefenseContent.crossbowman(v))))
                bolts.add(arrow.getUuid());
            return !bolts.isEmpty() && sounds.stream().anyMatch(id -> id.contains("crossbow.shoot"));
        }, () -> {
            test.check(!bolts.isEmpty(), "the crossbowmen shoot at the zombies (" + bolts.size() + " bolts)");
            test.check(sounds.stream().anyMatch(id -> id.contains("crossbow.shoot")), "a crossbow is heard");
            test.owner.client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(o.up(8)));
            shot("mobtalk-towers-crossbow.png", () -> {
                test.command("kill @e[type=minecraft:zombie,tag=" + TAG + "]");
                test.await("pause: the zombies gone", 3, () -> false, this::strike);
            });
        });
    }

    /** The player strikes a villager below the first tower: the archer shoots at once. */
    private void strike() {
        BlockPos o = sites.getFirst();
        Vec3d out = Vec3d.ofCenter(o).subtract(Vec3d.ofCenter(bell())).normalize();
        BlockPos stand = BlockPos.ofFloored(Vec3d.ofCenter(o).subtract(out.multiply(12))).withY(fy);
        VillagerEntity victim = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && v.getCommandTags().contains(TAG) && !DefenseContent.archer(v)).stream().findFirst().orElse(null));
        if (victim == null) { test.fail("no villager to strike"); finish(); return; }
        test.server().execute(() -> { if (victim.isSleeping()) victim.wakeUp(); victim.setAiDisabled(true); });
        // Beside the player, off the line from the tower: the archer must not have its own villager in the way.
        Vec3d side = new Vec3d(-out.z, 0, out.x).multiply(2.5);
        test.command("tp " + victim.getUuid() + " " + (stand.getX() + 0.5 + side.x) + " " + fy + " " + (stand.getZ() + 0.5 + side.z));
        test.command("tp " + name() + " " + (stand.getX() + 0.5) + " " + fy + " " + (stand.getZ() + 0.5));
        strikeSpot = stand;
        test.await("pause: beside the villager under the tower", 2, () -> false, () -> {
            var client = test.owner.client;
            VillagerEntity seen = client.world.getEntitiesByClass(VillagerEntity.class, client.player.getBoundingBox().expand(4), v -> v.getUuid().equals(victim.getUuid())).stream().findFirst().orElse(null);
            if (seen == null) { test.fail("the villager is not beside the player"); finish(); return; }
            newArrows(new boolean[1]);
            client.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, seen.getEyePos());
            mark();
            client.interactionManager.attackEntity(client.player, seen);
            client.player.swingHand(Hand.MAIN_HAND);
            int[] shots = new int[1];
            test.await("an archer shoots at the player", 10, () -> (shots[0] += newArrows(new boolean[1])) >= 1, () -> {
                test.check(heard("подняла тревогу против вас"), "the blow raises the alarm against the player");
                test.check(shots[0] >= 1, "an archer shoots at the player at once (" + shots[0] + " arrows)");
                test.server().execute(() -> victim.setAiDisabled(false));
                shot("mobtalk-towers-strike.png", this::friendInTheWay);
            });
        });
    }

    /** A villager in the line between the archer and the player: no arrow; out of the way: arrows again. */
    private void friendInTheWay() {
        // Golems the panicking villagers called would shove the player off the line: away with them, the player back on the spot.
        test.command("kill @e[type=minecraft:iron_golem]");
        if (strikeSpot != null) test.command("tp " + name() + " " + (strikeSpot.getX() + 0.5) + " " + fy + " " + (strikeSpot.getZ() + 0.5));
        test.await("pause: the player back under the tower", 2, () -> false, this::lineOfFire);
    }

    private void lineOfFire() {
        // The archer shooting at the player: the one at a post nearest to them.
        Vec3d player = onServer(() -> test.serverPlayer().getPos());
        VillagerEntity archer = archers().stream().filter(this::atPost).min(Comparator.comparingDouble(a -> onServer(() -> a.getPos().squaredDistanceTo(player)))).orElse(null);
        if (archer == null) { test.fail("no archer at a post for the line-of-fire check"); looks(); return; }
        // A villager a step in front of the player, towards the tower: every line from the platform down to the player passes it.
        Vec3d toward = onServer(archer::getPos).subtract(player).multiply(1, 0, 1).normalize();
        Vec3d wall = player.add(toward.multiply(1.1));
        test.log("line of fire: archer at " + onServer(archer::getBlockPos).toShortString() + ", a villager put at " + BlockPos.ofFloored(wall).toShortString());
        test.command("summon minecraft:villager " + wall.x + " " + player.y + " " + wall.z + " {NoAI:1b,PersistenceRequired:1b,Tags:[\"" + TAG + "_wall\"]}");
        test.await("pause: the villager in the line of fire", 3, () -> false, () -> {
            newArrows(new boolean[1]);
            int[] shots = new int[1];
            int[] tick = new int[1];
            test.await("pause: no arrow through a villager", 10, () -> {
                shots[0] += newArrows(new boolean[1]);
                if (++tick[0] % 20 == 0) test.command("tp " + name() + " " + player.x + " " + player.y + " " + player.z); // held on the line
                return false;
            }, () -> {
                test.log("the player " + String.format(Locale.ROOT, "%.1f", Math.sqrt(onServer(() -> test.serverPlayer().getPos().squaredDistanceTo(player)))) + " blocks from where the line was drawn");
                test.check(shots[0] == 0, "the archer does not shoot through a villager in the way (" + shots[0] + " arrows)");
                shot("mobtalk-towers-friend-in-the-way.png", () -> {
                    test.command("kill @e[tag=" + TAG + "_wall]");
                    int[] again = new int[1];
                    test.await("arrows again once the way is clear", 12, () -> (again[0] += newArrows(new boolean[1])) >= 1, () -> {
                        test.check(again[0] >= 1, "with the way clear the archer shoots again");
                        test.owner.forgiveVillage();
                        looks();
                    });
                });
            });
        });
    }

    /** The archer up close in the human look and the vanilla one. */
    private void looks() { look("archer", DefenseContent::archer, () -> look("crossbowman", DefenseContent::crossbowman, this::finish)); }

    /** A shooter of the kind up close, in the human look and the vanilla one. */
    private void look(String kind, java.util.function.Predicate<VillagerEntity> which, Runnable then) {
        test.command("time set 6000");
        VillagerEntity archer = archers().stream().filter(which).findFirst().orElse(null);
        if (archer == null) { test.fail("no " + kind + " to photograph"); then.run(); return; }
        test.server().execute(() -> archer.setAiDisabled(true));
        test.command("tp " + archer.getUuid() + " " + at(0, 0, 30) + " 180 0");
        test.command("tp " + name() + " " + at(0, 0, 27) + " 0 5");
        test.owner.config().humanVillagers = true;
        test.await("pause: the " + kind + " in front of the camera", 3, () -> false, () -> shot("mobtalk-towers-" + kind + "-human.png", () -> {
            test.owner.config().humanVillagers = false;
            test.await("pause: the vanilla look", 3, () -> false, () -> shot("mobtalk-towers-" + kind + "-vanilla.png", () -> {
                test.owner.config().humanVillagers = true;
                test.server().execute(() -> archer.setAiDisabled(false));
                then.run();
            }));
        }));
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        test.bridge().config.towerBuildMinutes = savedBuild; test.bridge().config.villageGuards = savedGuards; test.bridge().config.towerLargeResidents = savedLarge;
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: towers stage done", 2, () -> false, next);
    }
}
