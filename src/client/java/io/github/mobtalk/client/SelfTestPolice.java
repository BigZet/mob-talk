package io.github.mobtalk.client;

import io.github.mobtalk.game.CityPolice;
import io.github.mobtalk.game.PoliceContent;
import io.github.mobtalk.game.VillageSites;
import java.util.*;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.block.BlockState;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;

/** The city police ({@code -Only police}): a city is placed far off (mobtalk:city_small), fifteen residents move in, the city puts up
 * police posts with a car beside each and its policemen take them (no watchtower). At night a blow at a resident raises the alarm:
 * the siren sounds and the cars flash; the player, worn down, is arrested (handcuffs that hold the hands, a fine in emeralds), sits
 * in the cell under the post and is let out when the time is up. Then a call to the police on the player, an arrest and an escape
 * from the cell, and a wanted creature taken away. Screenshots of the policeman, the car, the lit car at night, the cell. No LLM,
 * no voice. */
final class SelfTestPolice {
    private static final String TAG = "mobtalk_pol";
    private final SelfTest test;
    private int cx, cz;
    private final List<String> chat = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> sounds = Collections.synchronizedSet(new HashSet<>());
    private Runnable next = () -> { };
    private int savedJail;
    private BlockPos centre;

    SelfTestPolice(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test; this.cx = stageX; this.cz = stageZ;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String line = (overlay ? "[bar] " : "") + Formatting.strip(message.getString());
            chat.add(line);
            if (!overlay) test.log("chat: " + line);
        });
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private long count(String part) { synchronized (chat) { return chat.stream().filter(line -> line.contains(part)).count(); } }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private Box area() { return new Box(centre.getX(), world().getBottomY(), centre.getZ(), centre.getX() + 1, world().getTopY(), centre.getZ() + 1).expand(110, 0, 110); }
    private List<VillagerEntity> police() { return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && PoliceContent.police(v))); }
    private List<VillagerEntity> residents() { return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && v.getCommandTags().contains(TAG) && !PoliceContent.police(v))); }
    private List<BlockPos> posts() {
        return onServer(() -> world().getPointOfInterestStorage().getInSquare(e -> e.matchesKey(PoliceContent.POLICE_POST_POI), centre, 90,
                PointOfInterestStorage.OccupationStatus.ANY).map(PointOfInterest::getPos).sorted(Comparator.comparingLong(BlockPos::asLong)).toList());
    }
    private BlockPos car(BlockPos post) {
        return onServer(() -> {
            for (BlockPos p : BlockPos.iterate(post.add(-10, -3, -10), post.add(10, 3, 10))) if (PoliceContent.car(world().getBlockState(p))) return p.toImmutable();
            return null;
        });
    }
    private int top(int x, int z) { return onServer(() -> { world().getChunk(x >> 4, z >> 4); return world().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z); }); }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.owner.client.getSoundManager().registerListener((sound, set, range) -> sounds.add(sound.getId().toString()));
        test.log("police stages: a city, its posts and cars, policemen; the siren and the lights; arrest, fine, the cell; a call, an escape; a wanted creature");
        savedJail = test.bridge().config.policeJailSeconds;
        test.bridge().config.policeJailSeconds = 20;
        test.command("difficulty normal"); test.command("time set 1000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode creative " + name());
        // A city as the world grows it (a /place'd structure leaves no record in the chunks, so the game would not know it for a city).
        BlockPos found = onServer(() -> {
            var structures = world().getRegistryManager().get(net.minecraft.registry.RegistryKeys.STRUCTURE);
            List<net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.gen.structure.Structure>> cities = new ArrayList<>();
            for (String kind : io.github.mobtalk.game.CityWorldgen.KINDS)
                structures.getEntry(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.STRUCTURE, net.minecraft.util.Identifier.of("mobtalk", "city_" + kind))).ifPresent(cities::add);
            var pair = world().getChunkManager().getChunkGenerator().locateStructure(world(), net.minecraft.registry.entry.RegistryEntryList.of(cities), new BlockPos(cx, 64, cz), 100, false);
            return pair == null ? null : pair.getFirst();
        });
        test.check(found != null, "a city is found in the world (" + found + ")");
        if (found == null) { finish(); return; }
        cx = found.getX(); cz = found.getZ();
        test.command("forceload add " + (cx - 128) + " " + (cz - 128) + " " + (cx + 128) + " " + (cz + 128));
        test.command("tp " + name() + " " + cx + " 160 " + cz);
        test.await("the city loads", 40, () -> citySite() != null, this::settle);
    }

    /** The placed city's site: looked for around the stage, as a natural village may stand near it too. */
    private io.github.mobtalk.core.ConversationStore.Site citySite() {
        return onServer(() -> {
            for (int dx = -96; dx <= 96; dx += 16) for (int dz = -96; dz <= 96; dz += 16) {
                var site = VillageSites.at(world(), new BlockPos(cx + dx, 64, cz + dz));
                if (site != null && site.key().contains("mobtalk:city_")) return site;
            }
            return null;
        });
    }

    private void settle() {
        var site = citySite();
        test.check(site != null && site.key().contains("mobtalk:city_"), "the city stands at the stage (" + (site == null ? "none" : site.key()) + ")");
        if (site == null) { finish(); return; }
        centre = new BlockPos(site.x(), top(site.x(), site.z()), site.z());
        test.log("city " + site.key() + ", bounds " + site.minX() + ".." + site.maxX() + " x " + site.minZ() + ".." + site.maxZ());
        test.command("tp " + name() + " " + centre.getX() + " " + (centre.getY() + 30) + " " + centre.getZ());
        test.await("pause: the city's chunks load", 5, () -> false, () -> {
            for (int i = 0; i < 15; i++) {
                int x = centre.getX() - 12 + (i % 5) * 6, z = centre.getZ() - 8 + (i / 5) * 8;
                test.command("summon minecraft:villager " + x + " " + top(x, z) + " " + z + " {PersistenceRequired:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:"
                        + List.of("farmer", "librarian", "fisherman", "shepherd", "cleric").get(i % 5) + "\",type:\"minecraft:plains\",level:2},Xp:20}");
            }
            test.await("three policemen take their posts (chat)", 140, () -> count("заступил новый полицейский") >= 3, this::posted);
        });
    }

    private void posted() {
        test.check(count("заступил новый полицейский") >= 3, "a city of fifteen puts up three police posts and gets three policemen (" + count("заступил новый полицейский") + ")");
        test.check(count("сторожевую башню") == 0, "the city builds no watchtower");
        List<VillagerEntity> police = police();
        test.check(police.size() >= 3, "three policemen in the city (" + police.size() + ")");
        for (VillagerEntity p : police) {
            boolean baton = onServer(() -> p.getMainHandStack().isOf(PoliceContent.BATON)), cuffs = onServer(() -> p.getOffHandStack().isOf(PoliceContent.HANDCUFFS));
            BlockPos site = onServer(() -> p.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(null));
            boolean post = site != null && onServer(() -> world().getBlockState(site).isOf(PoliceContent.POLICE_POST));
            test.check(baton && cuffs, "the policeman has a baton in the hand and handcuffs in the other");
            test.check(post, "the policeman's job site is a police post");
            test.check(Math.abs(onServer(p::getMaxHealth) - test.bridge().config.policeHealth) < 0.01, "the policeman has " + test.bridge().config.policeHealth + " health");
        }
        List<BlockPos> posts = posts();
        int withCar = 0;
        for (BlockPos post : posts) if (car(post) != null) withCar++;
        test.log("posts " + posts + ", with a car beside " + withCar);
        test.check(withCar >= 1, "a police car is parked by a post (" + withCar + " of " + posts.size() + ")");
        VillagerEntity one = police.isEmpty() ? null : police.getFirst();
        if (one == null) { finish(); return; }
        test.server().execute(() -> one.setAiDisabled(true));
        BlockPos p = onServer(one::getBlockPos);
        test.command("tp " + one.getUuid() + " " + (p.getX() + 0.5) + " " + p.getY() + " " + (p.getZ() + 0.5) + " 0 0");
        test.command("tp " + name() + " " + (p.getX() + 0.5) + " " + p.getY() + " " + (p.getZ() + 3.2) + " 180 8");
        test.owner.config().humanVillagers = true;
        shot("mobtalk-police-officer-human.png", () -> {
            test.owner.config().humanVillagers = false;
            test.command("tp " + name() + " " + (p.getX() + 2.5) + " " + p.getY() + " " + (p.getZ() + 2.5) + " 135 8");
            test.await("pause: the vanilla look", 3, () -> false, () -> shot("mobtalk-police-officer-vanilla.png", this::afterLooks));
        });
    }

    private void afterLooks() {
        test.owner.config().humanVillagers = true;
        List<BlockPos> posts = posts();
        for (VillagerEntity one : police()) test.server().execute(() -> one.setAiDisabled(false));
        {
            BlockPos car = posts.stream().map(this::car).filter(Objects::nonNull).findFirst().orElse(null);
            if (car == null) { night(); return; }
            test.command(view(car));
            shot("mobtalk-police-car-day.png", this::night);
        }
    }

    /** At night a blow at a resident: the alarm against the player, the siren, the light bars on. */
    private void night() {
        test.command("time set 18000");
        test.command("gamemode survival " + name());
        VillagerEntity victim = residents().stream().findFirst().orElse(null);
        if (victim == null) { test.fail("no resident to strike"); finish(); return; }
        BlockPos at = onServer(victim::getBlockPos);
        test.command("tp " + name() + " " + (at.getX() + 1.5) + " " + at.getY() + " " + (at.getZ() + 0.5));
        test.await("pause: by the resident", 2, () -> false, () -> {
            test.server().execute(() -> victim.damage(world().getDamageSources().playerAttack(test.serverPlayer()), 1f));
            test.await("the alarm against the player (chat)", 10, () -> count("Полиция города ищет вас") >= 1 || count("подняла тревогу против вас") >= 1, () -> {
                test.check(count("Полиция города ищет вас") >= 1, "a blow at a resident raises the city's alarm against the player");
                test.await("a police car flashes", 10, () -> litCar() != null, () -> {
                    BlockPos lit = litCar();
                    test.check(lit != null, "a police car's light bar is on in the alarm");
                    long before = CityPolice.sirens();
                    test.await("the siren sounds", 12, () -> sounds.contains("mobtalk:police_siren") || CityPolice.sirens() > before, () -> {
                        test.log("sounds heard by the client (none without a sound device): " + sounds.stream().filter(s -> s.startsWith("mobtalk:") || s.contains("bell")).toList());
                        test.check(sounds.contains("mobtalk:police_siren") || CityPolice.sirens() > before, "the city sounds the police siren (" + CityPolice.sirens() + " sirens)");
                        test.check(!sounds.contains("minecraft:block.bell.use"), "no village bell in the city");
                        if (lit == null) { arrest(); return; }
                        test.command(view(lit));
                        shot("mobtalk-police-car-night.png", this::arrest);
                    });
                });
            });
        });
    }

    private BlockPos litCar() {
        for (BlockPos post : posts()) {
            BlockPos car = car(post);
            if (car != null && onServer(() -> { BlockState s = world().getBlockState(car); return PoliceContent.car(s) && s.get(PoliceContent.LIT); })) return car;
        }
        return null;
    }

    /** Worn down, the player is arrested: the cuffs hold the hands, a fine, then the cell. */
    private void arrest() {
        test.server().execute(() -> {
            ServerPlayerEntity player = test.serverPlayer();
            player.clearStatusEffects(); // the harness's resistance and regeneration would keep the player from being worn down
            player.getInventory().insertStack(new ItemStack(Items.EMERALD, 7));
            player.setHealth(6f);
        });
        VillagerEntity cop = police().stream().findFirst().orElse(null);
        if (cop != null) test.command("tp " + name() + " " + onServer(() -> cop.getX() + 1.5) + " " + onServer(cop::getY) + " " + onServer(cop::getZ));
        test.await("the player is arrested", 40, () -> count("Вы задержаны") >= 1, () -> {
            test.check(count("Вы задержаны") >= 1, "a worn-down player is arrested by the police");
            int emeralds = onServer(() -> test.serverPlayer().getInventory().count(Items.EMERALD));
            test.check(emeralds == 7 - test.bridge().config.policeFine, "the fine took " + test.bridge().config.policeFine + " emeralds (left " + emeralds + ")");
            VillagerEntity someone = residents().stream().findFirst().orElse(null);
            ActionResult blow = someone == null ? ActionResult.PASS : onServer(() -> AttackEntityCallback.EVENT.invoker().interact(test.serverPlayer(), world(), Hand.MAIN_HAND, someone, null));
            test.check(blow == ActionResult.FAIL, "in handcuffs the player cannot strike (" + blow + ")");
            test.check(count("Полиция задержала игрока") >= 1, "the city hears of the arrest");
            test.server().execute(() -> test.serverPlayer().setHealth(20f));
            BlockPos first = posts().getFirst();
            test.await("the player is in the cell", 10, () -> onServer(() -> test.serverPlayer().getBlockY()) < first.getY() - 4, () -> {
                test.check(onServer(() -> test.serverPlayer().getBlockY()) < first.getY() - 4, "after the cuffs the player sits in the cell under the post");
                test.log("the quack was heard: " + sounds.contains("mobtalk:police_quack"));
                test.command("execute at " + name() + " run tp " + name() + " ~ ~ ~ 0 10"); // facing the bars (plain ~ is the console's spot)
                shot("mobtalk-police-cell.png", () ->
                    test.await("the time is up and the player is let out", 30, () -> count("Срок вышел") >= 1, () -> {
                        test.check(count("Срок вышел") >= 1, "the player is let out when the time is up");
                        test.check(onServer(() -> test.serverPlayer().getBlockY()) >= first.getY() - 1, "the player is let out by the post, above ground");
                        escape();
                    }));
            });
        });
    }

    /** A call to the police on the player (the API for the shops and the officials), an arrest, and an escape from the cell. */
    private void escape() {
        boolean called = onServer(() -> CityPolice.report(test.serverPlayer(), "кража в магазине", true));
        test.check(called, "the police take a call in the city");
        test.server().execute(() -> test.serverPlayer().setHealth(6f));
        test.await("arrested again", 40, () -> count("Вы задержаны") >= 2, () -> {
            test.server().execute(() -> test.serverPlayer().setHealth(20f));
            BlockPos first = posts().getFirst();
            test.await("in the cell again", 10, () -> onServer(() -> test.serverPlayer().getBlockY()) < first.getY() - 4, () -> {
                test.command("tp " + name() + " " + first.getX() + " " + (first.getY() + 2) + " " + (first.getZ() + 3));
                test.await("the escape is noticed", 5, () -> count("сбежали из изолятора") >= 1, () -> {
                    test.check(count("сбежали из изолятора") >= 1, "leaving the cell early is an escape");
                    test.command("gamemode creative " + name());
                    test.bridge().forgiveVillage(test.serverPlayer().getUuid());
                    wanted();
                });
            });
        });
    }

    /** A wanted creature (the bandits' tag) is arrested and taken away. */
    private void wanted() {
        test.command("time set 1000");
        VillagerEntity cop = police().stream().findFirst().orElse(null);
        if (cop == null) { test.fail("no policeman left for the wanted one"); finish(); return; }
        BlockPos at = onServer(cop::getBlockPos);
        test.command("summon minecraft:wandering_trader " + (at.getX() + 3) + " " + at.getY() + " " + at.getZ() + " {Health:5f,PersistenceRequired:1b,Tags:[\"" + CityPolice.WANTED + "\",\"" + TAG + "_w\"]}");
        test.command("tp " + name() + " " + (at.getX() + 6) + " " + (at.getY() + 2) + " " + (at.getZ() + 6) + " 135 20");
        test.await("the wanted one is taken away", 40, () -> count("Полиция задержала:") >= 1 && onServer(() ->
                world().getEntitiesByClass(WanderingTraderEntity.class, area(), w -> w.getCommandTags().contains(TAG + "_w")).isEmpty()), () -> {
            test.check(count("Полиция задержала:") >= 1, "the police arrest a wanted creature");
            test.check(onServer(() -> world().getEntitiesByClass(WanderingTraderEntity.class, area(), w -> w.getCommandTags().contains(TAG + "_w")).isEmpty()),
                    "the arrested creature is taken away");
            finish();
        });
    }

    /** A tp to an open spot a few blocks from the car with a clear view of it, looking at it. */
    private String view(BlockPos car) {
        return onServer(() -> {
            net.minecraft.util.math.Vec3d target = net.minecraft.util.math.Vec3d.ofCenter(car);
            for (int r = 4; r <= 7; r++) for (int a = 0; a < 16; a++) for (int dy = 2; dy >= -1; dy--) {
                double angle = a * Math.PI / 8;
                BlockPos feet = BlockPos.ofFloored(car.getX() + 0.5 + r * Math.cos(angle), car.getY() + dy, car.getZ() + 0.5 + r * Math.sin(angle));
                if (!world().getBlockState(feet).getCollisionShape(world(), feet).isEmpty() || !world().getBlockState(feet.up()).getCollisionShape(world(), feet.up()).isEmpty()
                        || world().getBlockState(feet.down()).getCollisionShape(world(), feet.down()).isEmpty()) continue;
                net.minecraft.util.math.Vec3d eye = net.minecraft.util.math.Vec3d.ofBottomCenter(feet).add(0, 1.62, 0);
                var hit = world().raycast(new net.minecraft.world.RaycastContext(eye, target, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                        net.minecraft.world.RaycastContext.FluidHandling.NONE, test.serverPlayer()));
                if (hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK && !PoliceContent.car(world().getBlockState(hit.getBlockPos()))) continue; // the car itself in view, not the post before it
                double dx = target.x - eye.x, dyy = target.y - eye.y, dz = target.z - eye.z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) Math.toDegrees(-Math.atan2(dyy, Math.sqrt(dx * dx + dz * dz)));
                return "tp " + name() + " " + (feet.getX() + 0.5) + " " + feet.getY() + " " + (feet.getZ() + 0.5) + " " + yaw + " " + pitch;
            }
            return "tp " + name() + " " + (car.getX() + 0.5) + " " + (car.getY() + 4) + " " + (car.getZ() + 0.5) + " 0 80";
        });
    }

    private void shot(String file, Runnable then) {
        boolean bare = file.contains("-car-"); // the cars without the hand and the bars
        test.owner.client.options.hudHidden = bare;
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.owner.client.options.hudHidden = false;
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        test.bridge().config.policeJailSeconds = savedJail;
        test.command("forceload remove all");
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: police stage done", 2, () -> false, next);
    }
}
