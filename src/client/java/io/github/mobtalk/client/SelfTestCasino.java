package io.github.mobtalk.client;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.game.CasinoContent;
import io.github.mobtalk.game.CasinoWorldgen;
import io.github.mobtalk.server.ServerConfig;
import java.util.*;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** The village casino ({@code -Only casino}): the five buildings placed from their templates on a grass field in the sky; the casino in
 * the house pools of the five village kinds; every piece of furniture where the plan puts it; the two bouncers tough and at their ropes;
 * a visitor sitting down to play; the slot machine spinning; the table for 21 answering; a bouncer punching a player who hit a
 * villager. Screenshots outside, inside, of each piece and of the bouncer in both looks. No LLM, no voice. */
final class SelfTestCasino {
    private final SelfTest test;
    private final int fx, fy, fz;
    private final List<String> chat = Collections.synchronizedList(new ArrayList<>());
    private Runnable next = () -> { };
    private int chatMark, savedPerTable;
    private boolean savedHuman;

    SelfTestCasino(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test; this.fx = stageX + 300; this.fy = stageY; this.fz = stageZ;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String line = (overlay ? "[bar] " : "") + Formatting.strip(message.getString());
            chat.add(line);
            test.log("chat: " + line);
        });
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private BlockPos o(int kind) { return new BlockPos(fx, fy - 1, fz + kind * 20); }
    private BlockPos at(int x, int y, int z) { return o(0).add(x, y, z); }
    private boolean heard(String... parts) {
        synchronized (chat) {
            for (String line : chat.subList(Math.min(chatMark, chat.size()), chat.size())) for (String p : parts) if (line.contains(p)) return true;
            return false;
        }
    }
    private void mark() { chatMark = chat.size(); }
    private Box area() { return new Box(o(0)).expand(24, 10, 24).offset(7, 0, 6); }

    void run(Runnable then) {
        next = then;
        MobTalkConfig config = ServerConfig.get();
        savedPerTable = config.casinoPlayersPerTable; savedHuman = test.bridge().config.humanVillagers;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("casino stages: five buildings, furniture, bouncers, a visitor, the games");
        test.command("time set 1000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode creative " + name());
        test.command("tp " + name() + " " + (fx - 6) + " " + (fy + 3) + " " + (fz + 6));
        test.await("pause: the stage chunks load", 4, () -> false, this::build);
    }

    private void build() {
        test.command("fill " + (fx - 12) + " " + (fy - 2) + " " + (fz - 8) + " " + (fx + 20) + " " + (fy - 2) + " " + (fz + 100) + " minecraft:stone");
        test.command("fill " + (fx - 12) + " " + (fy - 1) + " " + (fz - 8) + " " + (fx + 20) + " " + (fy - 1) + " " + (fz + 100) + " minecraft:grass_block");
        test.command("fill " + (fx - 12) + " " + fy + " " + (fz - 8) + " " + (fx + 20) + " " + (fy + 12) + " " + (fz + 100) + " minecraft:air");
        for (int i = 0; i < CasinoWorldgen.KINDS.size(); i++) {
            BlockPos p = o(i);
            test.command("place template mobtalk:casino/" + CasinoWorldgen.KINDS.get(i) + " " + p.getX() + " " + p.getY() + " " + p.getZ());
            // A village turns the jigsaw into its final state (the red carpet); a template placed by hand keeps it.
            test.command("setblock " + (p.getX()) + " " + (p.getY() + 1) + " " + (p.getZ() + 5) + " minecraft:red_carpet");
        }
        test.command("kill @e[type=minecraft:item]");
        test.await("the casino stands", 20, () -> onServer(() -> world().getBlockState(at(10, 1, 5)).isOf(CasinoContent.BLACKJACK)), this::checkPlan);
    }

    private void checkPlan() {
        onServer(() -> {
            ServerWorld w = world();
            test.check(w.getBlockState(at(0, 4, 6)).isOf(CasinoContent.SIGN), "the sign over the door");
            for (int x = 4; x <= 8; x++) test.check(w.getBlockState(at(x, 1, 1)).isOf(CasinoContent.SLOT) && w.getBlockState(at(x, 2, 1)).isOf(CasinoContent.SLOT), "slot machine " + x);
            test.check(w.getBlockState(at(10, 1, 2)).isOf(CasinoContent.ROULETTE), "the roulette");
            test.check(w.getBlockState(at(5, 1, 9)).isOf(CasinoContent.POKER), "the card table");
            for (int z = 8; z <= 10; z++) test.check(w.getBlockState(at(10, 1, z)).isOf(CasinoContent.BAR_COUNTER) && w.getBlockState(at(12, 1, z)).isOf(CasinoContent.BAR_SHELF), "the bar " + z);
            test.check(w.getBlockState(at(9, 1, 4)).isOf(CasinoContent.PART) && w.getBlockState(at(10, 1, 6)).isOf(CasinoContent.PART), "the table's parts");
            for (int i = 1; i < CasinoWorldgen.KINDS.size(); i++)
                test.check(w.getBlockState(o(i).add(10, 1, 5)).isOf(CasinoContent.BLACKJACK), CasinoWorldgen.KINDS.get(i) + " casino placed");
            var pools = w.getRegistryManager().get(RegistryKeys.TEMPLATE_POOL);
            for (String kind : CasinoWorldgen.KINDS) {
                StructurePool pool = pools.get(Identifier.ofVanilla("village/" + kind + "/houses"));
                long n = pool == null ? 0 : pool.getElementIndicesInRandomOrder(net.minecraft.util.math.random.Random.create(1)).stream()
                        .filter(e -> e.toString().contains("mobtalk:casino/" + kind)).count();
                test.check(n == ServerConfig.get().casinoWeight, kind + " village houses hold the casino (weight " + n + ")");
            }
            return null;
        });
        view(-9, 3.5, 6, 6, 3, 6);  // the bouncers only work near a player
        test.await("the bouncers are there", 10, () -> bouncers().size() >= 2, this::checkBouncers);
    }

    private List<VillagerEntity> bouncers() {
        return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, new Box(o(0)).expand(16, 6, 16).offset(7, 0, 6), CasinoContent::bouncer));
    }

    private void checkBouncers() {
        test.await("the bouncers toughen", 10, () -> bouncers().stream().allMatch(b -> b.getMaxHealth() >= ServerConfig.get().bouncerHealth && b.getArmor() >= 8), () -> {
            for (VillagerEntity b : bouncers()) {
                test.check(b.getMaxHealth() >= ServerConfig.get().bouncerHealth, "a bouncer is tough (" + b.getMaxHealth() + ")");
                test.check(b.getArmor() >= 8, "a bouncer wears hidden armour (" + b.getArmor() + ")");
            }
            outside();
        });
    }

    // ---- Screenshots ----

    private void view(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - y, dz = tz - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        BlockPos b = o(0);
        test.command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.1f %.1f", name(), b.getX() + x, b.getY() + y, b.getZ() + z, yaw, pitch));
    }

    private void shot(String file, Runnable then) {
        test.owner.client.options.hudHidden = true;
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, () -> { test.owner.client.options.hudHidden = false; then.run(); });
        });
    }

    private void outside() {
        test.command("gamemode spectator " + name());
        view(-9, 3.5, 6, 6, 3, 6);
        shot("mobtalk-casino-01-front.png", () -> { view(-8, 9, -6, 7, 2, 6);
        shot("mobtalk-casino-02-corner.png", () -> { view(-3, 4.6, 6, 0, 4.5, 6);
        shot("mobtalk-casino-03-sign.png", () -> { view(2.5, 2.6, 5.5, 11, 1.2, 5.5);
        shot("mobtalk-casino-04-inside-door.png", () -> { view(11.5, 3.5, 10.3, 3, 1, 2);
        shot("mobtalk-casino-05-inside-back.png", () -> { view(6.5, 2.6, 4.6, 6.5, 2, 1);
        shot("mobtalk-casino-06-slots.png", () -> { view(7.2, 3.2, 5.5, 10.5, 1.8, 5.5);
        shot("mobtalk-casino-07-blackjack.png", () -> { view(7.5, 3.2, 2.5, 10.5, 1.8, 2.5);
        shot("mobtalk-casino-08-roulette.png", () -> { view(5.5, 4.2, 5.5, 5.5, 1.8, 9);
        shot("mobtalk-casino-09-poker.png", () -> { view(7.5, 2.8, 9.5, 12, 2, 9.5);
        shot("mobtalk-casino-10-bar.png", () -> { bouncerView();
        shot("mobtalk-casino-11-bouncer-human.png", this::vanillaLook);
        }); }); }); }); }); }); }); }); }); });
    }

    /** In front of a bouncer, wherever it stands, at its eye level. */
    private void bouncerView() {
        List<VillagerEntity> all = bouncers();
        if (all.isEmpty()) { view(5, 1.7, 3, 2, 2, 2.5); return; }
        // Back to its post by the rope, facing the hall, so the camera inside sees it whole.
        BlockPos o = o(0);
        test.command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f -90 0", all.getFirst().getUuidAsString(), o.getX() + 2.5, o.getY() + 1.0, o.getZ() + 2.5));
        view(5.4, 1.0, 3.4, 2.5, 1.4, 2.5);  // view() aims from the feet: 1.4 here is the bouncer's chest at eye level
    }

    private void vanillaLook() {
        test.bridge().config.humanVillagers = false;
        io.github.mobtalk.client.human.HumanLooks.enabled = false;
        test.await("pause: vanilla looks", 2, () -> false, () -> { bouncerView(); shot("mobtalk-casino-12-bouncer-vanilla.png", () -> {
            test.bridge().config.humanVillagers = savedHuman;
            io.github.mobtalk.client.human.HumanLooks.enabled = savedHuman;
            others(1);
        }); });
    }

    private void others(int i) {
        if (i >= CasinoWorldgen.KINDS.size()) { visitor(); return; }
        BlockPos b = o(i).subtract(o(0));
        view(b.getX() - 9, b.getY() + 3.5, b.getZ() + 6, b.getX() + 6, b.getY() + 3, b.getZ() + 6);
        shot("mobtalk-casino-13-" + CasinoWorldgen.KINDS.get(i) + ".png", () -> others(i + 1));
    }

    // ---- Life ----

    private void visitor() {
        ServerConfig.get().casinoPlayersPerTable = 4;
        test.command("time set 6000");
        view(3, 4.5, 3, 9, 1, 7);  // visitors come to the tables near a player
        for (int i = 0; i < 4; i++) test.command("summon minecraft:villager " + (o(0).getX() + 6) + " " + (fy) + " " + (o(0).getZ() + 4 + i % 2) + " {PersistenceRequired:1b}");
        test.await("a visitor sits down to play", 90, () -> onServer(() -> !world().getEntitiesByClass(VillagerEntity.class, area(),
                v -> v.getVehicle() instanceof ArmorStandEntity && !CasinoContent.bouncer(v)).isEmpty()), () -> {
            view(3, 4.5, 3, 9, 1, 7);
            shot("mobtalk-casino-14-visitors.png", this::games);
        });
    }

    private void use(BlockPos pos, Direction side) {
        test.owner.client.execute(() -> test.owner.client.interactionManager.interactBlock(test.owner.client.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(pos), side, pos, false)));
    }

    private void games() {
        test.command("gamemode creative " + name());
        view(6.5, 1, 4, 6.5, 2.5, 1);
        mark();
        test.await("pause: in front of a slot machine", 1, () -> false, () -> {
            use(at(6, 1, 1), Direction.SOUTH);
            test.await("the slot machine spins", 6, () -> heard("for fun", "на интерес"), () -> {
                test.check(heard("for fun", "на интерес"), "the slot machine plays for fun");
                mark();
                view(7.5, 1, 5.5, 10.5, 1.5, 5.5);
                test.await("pause: at the table for 21", 1, () -> false, () -> {
                    use(at(9, 1, 5), Direction.WEST);
                    test.await("the table for 21 answers", 6, () -> heard("game of 21", "игры в 21", "to deal", "сдавать", "Bring someone", "Приведи"), this::trouble);
                });
            });
        });
    }

    private void trouble() {
        test.command("gamemode survival " + name());
        test.command("effect clear " + name());
        test.command("effect give " + name() + " minecraft:regeneration 60 1 true");
        test.command("summon minecraft:villager " + (o(0).getX() + 4) + " " + fy + " " + (o(0).getZ() + 6) + " {PersistenceRequired:1b,NoAI:1b,Tags:[\"casino_victim\"]}");
        view(3, 1, 6, 4.5, 1.5, 6);
        mark();
        test.await("pause: by the villager", 2, () -> false, () -> {
            Entity victim = onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.getCommandTags().contains("casino_victim"))
                    .stream().findFirst().orElse(null));
            test.check(victim != null, "a villager to hit");
            if (victim == null) { finish(); return; }
            test.owner.client.execute(() -> {
                Entity mine = test.owner.client.world.getEntityById(victim.getId());
                if (mine != null) test.owner.client.interactionManager.attackEntity(test.owner.client.player, mine);
            });
            float before = test.owner.client.player.getHealth();
            test.await("a bouncer throws the player out", 15, () -> heard("Out!", "На выход") && test.owner.client.player.getHealth() < before, () -> {
                test.check(heard("Out!", "На выход"), "the bouncer warns the troublemaker");
                test.check(test.owner.client.player.getHealth() < before, "the bouncer punches the troublemaker");
                finish();
            });
        });
    }

    private void finish() {
        ServerConfig.get().casinoPlayersPerTable = savedPerTable;
        test.command("gamemode creative " + name());
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: casino stage done", 2, () -> false, next);
    }
}
