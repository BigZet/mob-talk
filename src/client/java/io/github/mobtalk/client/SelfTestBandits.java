package io.github.mobtalk.client;

import io.github.mobtalk.core.SpeciesCatalog;
import io.github.mobtalk.game.BanditContent;
import io.github.mobtalk.game.BanditEntity;
import java.util.*;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

/** Night bandits ({@code -Only bandits}): a walled floor in the night sky. The six looks in a row; a gang asks for a share of the
 * emeralds, is paid with a right click and leaves, vanishing out of sight; a bandit who is refused fights, snatches emeralds, runs
 * and, killed, drops them; a player without emeralds is robbed of a thing; a bandit alone mugs a villager; then the group «Бандиты в
 * городах» in F7. No LLM, no voice; the bandits come by {@code /bandits spawn}, since the test world has no city. */
final class SelfTestBandits {
    private final SelfTest test;
    private final int fx, fy, fz;
    private final List<String> chat = Collections.synchronizedList(new ArrayList<>());
    private int chatMark, savedRob, savedWait;
    private Runnable next = () -> { };

    SelfTestBandits(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test; this.fx = stageX + 440; this.fy = stageY; this.fz = stageZ;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String line = (overlay ? "[bar] " : "") + Formatting.strip(message.getString());
            chat.add(line);
            test.log("chat: " + line);
        });
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private Box area() { return new Box(fx - 24, fy - 2, fz - 24, fx + 24, fy + 8, fz + 24); }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private List<BanditEntity> bandits() { return onServer(() -> world().getEntitiesByClass(BanditEntity.class, area().expand(40), BanditEntity::isAlive)); }
    private boolean heard(String part) { synchronized (chat) { return chat.subList(Math.min(chatMark, chat.size()), chat.size()).stream().anyMatch(l -> l.contains(part)); } }
    private void mark() { chatMark = chat.size(); }
    private int emeralds() { return onServer(() -> { int n = 0; for (ItemStack s : test.serverPlayer().getInventory().main) if (s.isOf(Items.EMERALD)) n += s.getCount(); return n; }); }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("bandits stages: looks, the demand and the payment, the refusal and the robbery, the empty pockets, the mugged villager");
        test.check(BanditContent.BANDIT != null, "the bandit entity is registered");
        SpeciesCatalog.Species species = SpeciesCatalog.get("mobtalk:bandit");
        test.check(species.name().equals("Бандит") && species.hostile() && species.variants().size() == 3, "the bandit has his own character for the conversation");
        savedRob = test.bridge().config.banditRobChance; savedWait = test.bridge().config.banditDemandSeconds;
        test.bridge().config.banditRobChance = 100; test.bridge().config.banditDemandSeconds = 40;
        test.command("difficulty normal"); test.command("time set 6000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode survival " + name());
        test.command("effect give " + name() + " minecraft:slow_falling 900 1 true");
        test.command("effect give " + name() + " minecraft:resistance 900 3 true");
        test.command("effect give " + name() + " minecraft:regeneration 900 3 true");
        test.command("effect give " + name() + " minecraft:saturation 900 1 true");
        test.command("tp " + name() + " " + at(0, 1, 0));
        test.await("pause: the stage chunks load", 3, () -> false, this::build);
    }

    private void build() {
        test.command("fill " + at(-20, -1, -20) + " " + at(20, -1, 20) + " minecraft:polished_andesite");
        test.command("fill " + at(-20, 0, -20) + " " + at(20, 4, 20) + " minecraft:barrier outline");
        test.command("fill " + at(-19, 0, -19) + " " + at(19, 0, 19) + " minecraft:air"); // the outline's bottom face would be an invisible floor
        test.command("fill " + at(-19, 4, -19) + " " + at(19, 4, 19) + " minecraft:air");
        for (int i = -16; i <= 16; i += 8) test.command("setblock " + at(i, 0, -18) + " minecraft:lantern");
        for (int x = -18; x <= 18; x += 6) for (int z = -18; z <= 18; z += 6) test.command("setblock " + at(x, -1, z) + " minecraft:sea_lantern"); // street lights
        test.command("tp " + name() + " " + at(0, 0, 0) + " 180 0");
        test.await("pause: the floor is built", 2, () -> false, this::looks);
    }

    // ---- (а) The six looks ----

    private void looks() {
        for (int i = 0; i < BanditEntity.SKINS; i++)
            test.command("summon mobtalk:bandit " + at(-5 + i * 2, 0, -6) + " {NoAI:1b,Skin:" + i + ",Rotation:[0f,0f],Tags:[\"mobtalk_bandit_look\"]}");
        test.command("tp " + name() + " " + at(0, 0, 0) + " 180 8");
        test.await("six bandits stand in a row", 10, () -> bandits().size() >= BanditEntity.SKINS, () -> {
            List<BanditEntity> row = bandits();
            test.check(row.stream().map(BanditEntity::skin).distinct().count() == BanditEntity.SKINS, "six bandits, six different looks");
            shot("mobtalk-bandits-looks.png", () -> {
                test.command("tp " + name() + " " + at(0, 0, -3) + " 180 5");
                shot("mobtalk-bandits-looks-close.png", () -> {
                    test.command("kill @e[tag=mobtalk_bandit_look]");
                    test.await("the row is gone", 5, () -> bandits().isEmpty(), this::demand);
                });
            });
        });
    }

    // ---- (б) "Your money or your life", paid ----

    private void demand() {
        test.command("time set 18000");
        test.command("bandits clear");
        test.command("clear " + name());
        test.command("give " + name() + " minecraft:emerald 40");
        test.command("tp " + name() + " " + at(0, 0, 8) + " 180 0");
        mark();
        test.command("execute as " + name() + " at " + name() + " run bandits spawn 2");
        test.await("the gang asks for emeralds", 20, () -> heard("изумр.") && bandits().stream().allMatch(b -> b.mode() == BanditEntity.Mode.DEMAND), () -> {
            List<BanditEntity> gang = bandits();
            test.check(gang.size() == 2, "a gang of two came out of the dark (" + gang.size() + ")");
            test.check(gang.stream().allMatch(b -> b.mode() == BanditEntity.Mode.DEMAND), "both bandits are asking (" + gang.stream().map(BanditEntity::mode).toList() + ")");
            test.check(gang.stream().allMatch(b -> b.demand() == 12), "they ask 30% of 40 emeralds = 12 (" + gang.stream().map(BanditEntity::demand).toList() + ")");
            test.check(gang.stream().allMatch(b -> !b.getMainHandStack().isEmpty()), "each has a knife or a hatchet");
            test.check(gang.stream().allMatch(BanditEntity::lurking), "while asking they are hidden from the village's defence");
            test.await("the gang walks up", 10, () -> bandits().stream().anyMatch(b -> b.distanceTo(test.serverPlayer()) < 3.5), () -> {
                BanditEntity first = bandits().stream().min(Comparator.comparingDouble(b -> b.distanceTo(test.serverPlayer()))).orElse(null);
                if (first != null) frame(first, true);
                shot("mobtalk-bandits-demand.png", () -> { if (first != null) frame(first, false); pay(first); });
            });
        });
    }

    /** Holds the gang still and steps the player back four blocks, facing the bandit, for a photo; false lets them go again. */
    private void frame(BanditEntity bandit, boolean hold) {
        onServer(() -> {
            for (BanditEntity b : bandits()) b.setAiDisabled(hold);
            if (!hold) return null;
            ServerPlayerEntity player = test.serverPlayer();
            double dx = player.getX() - bandit.getX(), dz = player.getZ() - bandit.getZ(), d = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            player.requestTeleport(bandit.getX() + dx / d * 4, player.getY(), bandit.getZ() + dz / d * 4);
            return null;
        });
        if (hold) look(bandit);
    }

    /** Where each bandit is and what he is doing, for the log. */
    private void dump(String when) {
        onServer(() -> {
            ServerPlayerEntity player = test.serverPlayer();
            test.log(when + ": player " + player.getBlockPos().toShortString() + " " + player.interactionManager.getGameMode());
            for (BanditEntity b : bandits()) test.log("  " + b.getName().getString() + " " + b.mode() + " at " + b.getBlockPos().toShortString()
                    + String.format(Locale.ROOT, ", %.1f blocks, sees player %s, AI off %s", b.distanceTo(player), b.canSee(player), b.isAiDisabled()));
            return null;
        });
    }

    private void look(BanditEntity bandit) {
        ServerPlayerEntity player = test.serverPlayer();
        double dx = bandit.getX() - player.getX(), dz = bandit.getZ() - player.getZ(), dy = bandit.getEyeY() - player.getEyeY();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90), pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        test.command("tp " + name() + " " + player.getX() + " " + player.getY() + " " + player.getZ() + " " + yaw + " " + pitch);
    }

    private void pay(BanditEntity bandit) {
        if (bandit == null) { test.fail("no bandit to pay"); refuse(); return; }
        mark();
        test.server().execute(() -> {
            ServerPlayerEntity player = test.serverPlayer();
            player.getInventory().selectedSlot = 0;
            for (int i = 0; i < player.getInventory().main.size(); i++) if (player.getInventory().main.get(i).isOf(Items.EMERALD)) {
                ItemStack stack = player.getInventory().main.get(i);
                player.getInventory().main.set(i, player.getInventory().main.get(0)); player.getInventory().main.set(0, stack); break;
            }
            bandit.interact(player, Hand.MAIN_HAND);
        });
        test.await("the gang takes the emeralds and leaves", 10, () -> bandits().stream().allMatch(b -> b.mode() == BanditEntity.Mode.LEAVE), () -> {
            test.check(emeralds() == 28, "12 of 40 emeralds paid (" + emeralds() + " left)");
            test.check(heard("Приятно") || heard("умница") || heard("Живи"), "the bandit thanks in the chat");
            test.check(bandits().stream().allMatch(b -> b.mode() == BanditEntity.Mode.LEAVE), "the whole gang leaves");
            test.command("gamemode spectator " + name()); // a spectator sees nothing as far as the bandits care
            test.command("tp " + name() + " " + at(0, 60, 0));
            test.await("the paid gang vanishes out of sight", 20, () -> bandits().isEmpty(), () -> {
                test.check(bandits().isEmpty(), "the gang vanished once nobody saw them");
                test.command("gamemode survival " + name());
                refuse();
            });
        });
    }

    // ---- (в) Refused: the fight, the snatch, the run, the loot back ----

    private void refuse() {
        test.bridge().config.banditDemandSeconds = 6;
        test.command("bandits clear");
        test.command("tp " + name() + " " + at(0, 0, 8) + " 180 0");
        mark();
        test.command("execute as " + name() + " at " + name() + " run bandits spawn 1");
        test.await("the bandit asks", 20, () -> bandits().stream().anyMatch(b -> b.mode() == BanditEntity.Mode.DEMAND), () -> { dump("asked"); 
            test.await("time runs out and he attacks", 15, () -> heard("Время вышло") && bandits().stream().anyMatch(b -> b.mode() != BanditEntity.Mode.DEMAND), () -> {
                test.check(heard("Время вышло"), "after the wait the bandit says time is up");
                BanditEntity bandit = bandits().stream().findFirst().orElse(null);
                test.check(bandit != null && !bandit.lurking(), "a fighting bandit is no longer hidden from the defence");
                test.await("a blow snatches emeralds", 25, () -> heard("вырвал у вас"), () -> {
                    int left = emeralds();
                    BanditEntity robber = bandits().stream().filter(b -> b.lootCount() > 0).findFirst().orElse(null);
                    test.check(left < 28, "the player has fewer emeralds (" + left + " of 28)");
                    test.check(robber != null && robber.mode() == BanditEntity.Mode.FLEE, "the bandit with loot runs (" + (robber == null ? "none" : robber.mode()) + ")");
                    int loot = robber == null ? 0 : robber.lootCount();
                    test.await("pause: he runs", 2, () -> false, () -> { if (robber != null) look(robber); shot("mobtalk-bandits-flee.png", () -> {
                        if (robber != null) test.server().execute(() -> robber.kill());
                        test.await("he drops the loot", 5, () -> onServer(() -> world().getEntitiesByClass(ItemEntity.class, area().expand(40), e -> e.getStack().isOf(Items.EMERALD))
                                .stream().mapToInt(e -> e.getStack().getCount()).sum()) >= loot && loot > 0, () -> {
                            test.check(loot > 0, "killed, the robber drops the " + loot + " emeralds he took");
                            test.command("kill @e[type=minecraft:item]");
                            test.command("bandits clear");
                            empty();
                        });
                    }); });
                });
            }); });
    }

    // ---- (г) Empty pockets: no demand, a thing snatched ----

    private void empty() {
        test.command("clear " + name());
        test.command("give " + name() + " minecraft:bread 16");
        test.command("tp " + name() + " " + at(0, 0, 8) + " 180 0");
        mark();
        test.command("execute as " + name() + " at " + name() + " run bandits spawn 1");
        test.await("a bandit attacks a player without emeralds", 20, () -> (heard("Пустой") || heard("Ни изумруда") || heard("Голодранец")) && bandits().stream().anyMatch(b -> b.mode() != BanditEntity.Mode.PROWL), () -> {
            dump("empty pockets");
            test.check(heard("Пустой") || heard("Ни изумруда") || heard("Голодранец"), "no emeralds: he does not ask, he says so and attacks");
            test.await("he snatches the bread", 25, () -> heard("вырвал у вас"), () -> {
                int bread = onServer(() -> test.serverPlayer().getInventory().count(Items.BREAD));
                test.check(bread == 8, "half the bread is gone (" + bread + " of 16 left)");
                test.command("bandits clear");
                villager();
            });
        });
    }

    // ---- (д) Nobody about: he mugs a villager ----

    private void villager() {
        test.command("gamemode spectator " + name());
        test.command("tp " + name() + " " + at(0, 60, 0));
        test.command("summon minecraft:villager " + at(-6, 0, -6) + " {PersistenceRequired:1b,Tags:[\"mobtalk_bandit_victim\"]}");
        test.command("summon mobtalk:bandit " + at(-2, 0, -6));
        test.await("the bandit goes for the villager and runs", 60, () -> bandits().stream().anyMatch(b -> b.mode() == BanditEntity.Mode.FLEE), () -> {
            VillagerEntity victim = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains("mobtalk_bandit_victim")).stream().findFirst().orElse(null));
            test.check(victim != null && (victim.getHealth() < victim.getMaxHealth() || !victim.isAlive()), "the villager was struck (" + (victim == null ? "gone" : victim.getHealth() + "/" + victim.getMaxHealth()) + ")");
            test.check(bandits().stream().anyMatch(b -> b.mode() == BanditEntity.Mode.FLEE), "the mugger runs after the blow");
            test.command("bandits clear");
            test.command("kill @e[tag=mobtalk_bandit_victim]");
            lurk();
        });
    }

    // ---- (ж) Where they wait: a dark doorway under a roof beats a lit open street ----

    private void lurk() {
        test.command("gamemode survival " + name());
        test.command("tp " + name() + " " + at(0, 0, 0) + " 180 0");
        // A doorway at the west wall: a roof, an oak door, no light. A lamp post in the open at the east.
        test.command("fill " + at(-17, 0, -2) + " " + at(-15, 3, 2) + " minecraft:bricks hollow");
        test.command("setblock " + at(-15, 0, 0) + " minecraft:oak_door[half=lower,facing=east]");
        test.command("setblock " + at(-15, 1, 0) + " minecraft:oak_door[half=upper,facing=east]");
        test.command("setblock " + at(15, 0, 0) + " minecraft:glowstone");
        test.await("pause: the light settles", 2, () -> false, () -> {
            int dark = onServer(() -> io.github.mobtalk.game.BanditController.lurk(world(), new net.minecraft.util.math.BlockPos(fx - 14, fy, fz)));
            int lit = onServer(() -> io.github.mobtalk.game.BanditController.lurk(world(), new net.minecraft.util.math.BlockPos(fx + 14, fy, fz)));
            test.check(dark > lit, "a bandit would rather wait by a dark door than under a light (" + dark + " > " + lit + ")");
            revenge();
        });
    }

    // ---- (з) Revenge: a player who killed a bandit meets a gang that does not talk ----

    private void revenge() {
        test.command("give " + name() + " minecraft:emerald 20");
        test.command("summon mobtalk:bandit " + at(0, 0, -4));
        test.await("a bandit to kill", 5, () -> !bandits().isEmpty(), () -> {
            test.server().execute(() -> {
                ServerPlayerEntity player = test.serverPlayer();
                for (BanditEntity b : bandits()) b.damage(world().getDamageSources().playerAttack(player), 500f);
            });
            test.await("the bandit is dead", 5, () -> bandits().isEmpty(), () -> {
                mark();
                test.command("execute as " + name() + " at " + name() + " run bandits spawn 2");
                test.await("the gang comes for revenge", 25, () -> (heard("положил") || heard("ответишь") || heard("Мы за"))
                        && bandits().stream().anyMatch(b -> b.mode() == BanditEntity.Mode.FIGHT), () -> {
                    test.check(heard("положил") || heard("ответишь") || heard("Мы за"), "the gang names the bandit the player killed");
                    test.check(bandits().stream().allMatch(b -> b.mode() != BanditEntity.Mode.DEMAND), "a revenge gang does not ask for emeralds");
                    test.check(bandits().stream().filter(b -> b.mode() == BanditEntity.Mode.FIGHT).allMatch(b -> b.getCommandTags().contains(BanditEntity.WANTED)),
                            "a fighting bandit carries the police's «wanted» mark");
                    test.command("bandits clear");
                    city();
                });
            });
        });
    }

    // ---- (и) A real city, when this build has them: the player stands in it, a gang is called for the night ----

    private void city() {
        var id = net.minecraft.util.Identifier.of("mobtalk", "city_mix");
        var entry = onServer(() -> world().getRegistryManager().get(net.minecraft.registry.RegistryKeys.STRUCTURE)
                .getEntry(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.STRUCTURE, id)));
        if (entry.isEmpty()) { test.log("no cities in this build: the city stage is skipped"); f7(); return; }
        test.log("locating a city (may take a while)");
        var found = onServer(() -> world().getChunkManager().getChunkGenerator().locateStructure(world(),
                net.minecraft.registry.entry.RegistryEntryList.of(entry.get()), test.serverPlayer().getBlockPos(), 100, false));
        if (found == null) { test.fail("no city found within 100 chunks"); f7(); return; }
        net.minecraft.util.math.BlockPos centre = found.getFirst();
        test.log("city at " + centre.toShortString());
        test.command("gamemode spectator " + name());
        test.command("tp " + name() + " " + centre.getX() + " 140 " + centre.getZ());
        test.await("pause: the city loads", 25, () -> false, () -> {
            test.command("execute as " + name() + " at " + name() + " run spreadplayers ~ ~ 0 6 false @s");
            test.command("gamemode survival " + name());
            test.command("time set 18000");
            test.await("pause: on the ground", 5, () -> false, () -> {
                boolean inCity = onServer(() -> io.github.mobtalk.game.BanditController.of(test.server()).territory(world(), test.serverPlayer().getBlockPos()));
                test.check(inCity, "the player stands in a city (" + test.serverPlayer().getBlockPos().toShortString() + ")");
                test.command("give " + name() + " minecraft:emerald 20");
                mark();
                test.command("execute as " + name() + " at " + name() + " run bandits night");
                test.await("the night's gang comes out in the city", 60, () -> heard("изумр.") || heard("Пустой") || heard("Голодранец") || heard("Ни изумруда"), () -> {
                    List<BanditEntity> gang = onServer(() -> world().getEntitiesByClass(BanditEntity.class, test.serverPlayer().getBoundingBox().expand(40), BanditEntity::isAlive));
                    BanditEntity first = gang.stream().min(Comparator.comparingDouble(b -> b.distanceTo(test.serverPlayer()))).orElse(null);
                    test.check(first != null, "a gang came for the player in the city at night (" + gang.size() + ")");
                    test.check(first != null && first.mode() != BanditEntity.Mode.PROWL, "the gang reached the player through the city streets");
                    if (first != null) look(first);
                    shot("mobtalk-bandits-city.png", () -> {
                        test.command("bandits clear");
                        f7();
                    });
                });
            });
        });
    }

    // ---- (е) F7 → Социум → «Бандиты в городах» ----

    private void f7() {
        test.command("gamemode survival " + name());
        test.command("tp " + name() + " " + at(0, 0, 0) + " 180 0");
        var client = test.owner.client;
        test.owner.openSettings(null);
        test.await("F7 opens", 5, () -> client.currentScreen instanceof MobTalkSettingsScreen, () -> {
            if (!(client.currentScreen instanceof MobTalkSettingsScreen screen)) { test.fail("F7 did not open"); finish(); return; }
            List<String> rows = screen.showGroup("social", "bandits");
            test.log("«Бандиты в городах» rows: " + rows);
            List<String> switches = List.of("Ночные бандиты", "Грабят жителей", "И в деревнях");
            test.check(rows.containsAll(switches), "the group has its three switches");
            long sliders = rows.stream().filter(row -> !switches.contains(row)).count();
            test.check(sliders == 7, "the group has seven sliders (" + sliders + ")");
            test.check(rows.stream().noneMatch(row -> row.isBlank() || row.startsWith("mobtalk.")), "every row has a caption, none is a raw key");
            shot("mobtalk-bandits-f7.png", () -> { client.setScreen(null); finish(); });
        });
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        test.bridge().config.banditRobChance = savedRob; test.bridge().config.banditDemandSeconds = savedWait;
        test.command("bandits clear");
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: bandits stage done", 2, () -> false, next);
    }
}
