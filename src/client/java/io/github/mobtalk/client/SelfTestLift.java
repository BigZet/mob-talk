package io.github.mobtalk.client;

import io.github.mobtalk.game.LiftCabin;
import io.github.mobtalk.game.Lifts;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/** Self-test of the lifts ({@code -Dmobtalk.selftest.only=lift}, no LLM, no voice): a 9-storey test tower ({@code /city lift 9 4}),
 * its cabin spawned by the winch, a call from the landing (doors open), the ride up with the player and a villager standing in the
 * cabin, getting off level with the floor, СТОП between floors and the way down. Screenshots go to screenshots/mobtalk-lift-*.png. */
final class SelfTestLift {
    private final SelfTest test;
    private final int px, py, pz;
    private Runnable next = () -> { };
    private LiftCabin cabin;
    private java.util.UUID villager;

    SelfTestLift(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test;
        this.px = stageX + 40; this.py = stageY + 20; this.pz = stageZ;
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private <T> T onServer(Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private void serverDo(Runnable task) { onServer(() -> { task.run(); return true; }); }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("lift stages: the tower, the call, the ride up, СТОП, the way down");
        test.command("time set 6000"); test.command("weather clear"); test.command("gamerule doDaylightCycle false");
        test.command("gamemode creative " + name());
        test.command("fill " + (px - 8) + " " + (py - 1) + " " + (pz - 12) + " " + (px + 8) + " " + (py + 44) + " " + (pz + 8) + " minecraft:air");
        test.command("fill " + (px - 8) + " " + (py - 1) + " " + (pz - 12) + " " + (px + 8) + " " + (py - 1) + " " + (pz + 8) + " minecraft:smooth_stone");
        test.command("tp " + name() + " " + px + " " + py + " " + pz + " 180 0");
        test.await("pause: the stage chunks load", 3, () -> false, () -> {
            test.command("execute as " + name() + " at " + name() + " rotated 180 0 run city lift 9 4");
            test.await("the winch spawns the cabin", 20, () -> (cabin = find()) != null, this::spawned);
        });
    }

    private LiftCabin find() {
        return onServer(() -> {
            List<LiftCabin> found = world().getEntitiesByClass(LiftCabin.class, new Box(px - 10, py - 5, pz - 15, px + 10, py + 50, pz + 10), c -> true);
            return found.isEmpty() ? null : found.get(0);
        });
    }

    private double feet(int floor) { return cabin.slabs().get(floor) + 1; }

    /** Where to stand on a landing, looking at the doors: the middle of the doorway, two blocks out. */
    private String landing(int floor) {
        Lifts.Layout l = cabin.layout();
        Direction f = l.facing();
        double x = (l.left().getX() + l.right().getX()) / 2.0 + 0.5 + f.getOffsetX() * 2.5;
        double z = (l.left().getZ() + l.right().getZ()) / 2.0 + 0.5 + f.getOffsetZ() * 2.5;
        return x + " " + feet(floor) + " " + z + " " + (f.getOpposite().asRotation()) + " 8";
    }

    private void spawned() {
        test.check(cabin.floors() == 9, "the winch found 9 floors (" + cabin.floors() + ")");
        test.check(onServer(cabin::atFloor) && cabin.floor() == 0, "the cabin stands at the first floor");
        BlockPos o = cabin.layout().origin();
        test.check(onServer(() -> world().getBlockState(o.withY(cabin.slabs().get(0))).isOf(Lifts.FLOOR)), "the cabin's floor block is in the shaft");
        test.command("tp " + name() + " " + landing(0));
        shot("mobtalk-lift-1-landing-closed.png", () -> {
            serverDo(() -> cabin.call(cabin.slabs().get(0)));
            test.await("the doors open at the first floor", 5, cabin::doorsOpen, () -> test.await("pause: the doors slide", 2, () -> false,
                    () -> shot("mobtalk-lift-2-landing-open.png", this::board)));
        });
    }

    private void board() {
        Direction f = cabin.layout().facing();
        double bx = cabin.getX() - f.getOffsetX() * 0.45, bz = cabin.getZ() - f.getOffsetZ() * 0.45;
        test.command("tp " + name() + " " + bx + " " + cabin.getY() + " " + bz + " " + f.asRotation() + " 10");
        test.command("summon minecraft:villager " + (cabin.getX() + f.rotateYClockwise().getOffsetX() * 0.5) + " " + cabin.getY() + " "
                + (cabin.getZ() + f.rotateYClockwise().getOffsetZ() * 0.5) + " {PersistenceRequired:1b,NoAI:0b,Tags:[\"lifttest\"]}");
        shot("mobtalk-lift-3-inside.png", () -> {
            villager = onServer(() -> {
                List<VillagerEntity> v = world().getEntitiesByClass(VillagerEntity.class, cabin.getBoundingBox().expand(1), e -> e.getCommandTags().contains("lifttest"));
                return v.isEmpty() ? null : v.get(0).getUuid();
            });
            test.check(villager != null, "a villager stands in the cabin");
            test.command("tp " + name() + " " + bx + " " + cabin.getY() + " " + bz + " " + f.rotateYClockwise().asRotation() + " 15");
            shot("mobtalk-lift-4-panel-wall.png", () -> {
                test.owner.client.execute(() -> Lifts.openPanel.accept(clientCabin()));
                test.await("the panel screen opens", 5, () -> test.owner.client.currentScreen != null, () -> shot("mobtalk-lift-5-panel-gui.png", () -> {
                    test.owner.client.execute(() -> test.owner.client.setScreen(null));
                    test.command("tp " + name() + " " + bx + " " + cabin.getY() + " " + bz + " " + f.asRotation() + " 10");
                    serverDo(() -> cabin.press(6, test.serverPlayer()));
                    test.await("the cabin leaves for floor 7", 10, () -> cabin.moving(), this::ridingUp);
                }));
            });
        });
    }

    private LiftCabin clientCabin() {
        return test.owner.client.world.getEntityById(cabin.getId()) instanceof LiftCabin c ? c : null;
    }

    private boolean villagerRides() {
        return onServer(() -> world().getEntity(villager) instanceof VillagerEntity v && v.getVehicle() == cabin);
    }

    private void ridingUp() {
        test.check(onServer(() -> test.serverPlayer().getVehicle() == cabin), "the player rides in the cabin");
        test.check(villagerRides(), "the villager rides too");
        test.check(onServer(() -> !world().getBlockState(cabin.layout().origin().withY(cabin.slabs().get(0))).isOf(Lifts.FLOOR)), "the floor block left with the cabin");
        test.await("pause: under way", 4, () -> false, () -> {
            test.check(test.owner.client.player.hasVehicle(), "the client sees the player riding");
            // first person: a third-person camera would sit inside the shaft wall
            shot("mobtalk-lift-6-riding.png", () -> {
                test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
                test.await("the cabin arrives at floor 7", 60, () -> !cabin.moving() && cabin.floor() == 6, this::arrived);
            });
        });
    }

    private void arrived() {
        test.await("the doors open at floor 7", 5, cabin::doorsOpen, () -> {
            test.check(onServer(() -> !test.serverPlayer().hasVehicle()), "the player is off the cabin");
            double dy = onServer(() -> test.serverPlayer().getY()) - feet(6);
            test.check(Math.abs(dy) < 0.1, "the player stands level with floor 7 (" + String.format("%.3f", dy) + ")");
            test.check(onServer(() -> world().getEntity(villager) instanceof VillagerEntity v && !v.hasVehicle() && Math.abs(v.getY() - feet(6)) < 0.1),
                    "the villager got off at floor 7 too");
            test.check(onServer(() -> world().getBlockState(cabin.layout().origin().withY(cabin.slabs().get(6))).isOf(Lifts.FLOOR)), "the floor block is under the cabin at floor 7");
            test.await("pause: the doors slide", 2, () -> false, () -> shot("mobtalk-lift-7-arrived.png", this::stopTest));
        });
    }

    private void stopTest() {
        serverDo(() -> cabin.press(0, test.serverPlayer()));
        test.await("the cabin leaves for floor 1", 15, () -> cabin.moving(), () -> test.await("pause: two floors down", 4, () -> false, () -> {
            serverDo(() -> cabin.press(Lifts.STOP_BUTTON, test.serverPlayer()));
            test.await("СТОП halts the cabin", 5, () -> !cabin.moving(), () -> {
                test.check(onServer(() -> !cabin.atFloor()), "it stands between floors");
                test.check(onServer(() -> test.serverPlayer().getVehicle() == cabin), "the player is still inside");
                shot("mobtalk-lift-8-stuck.png", () -> {
                    serverDo(() -> cabin.press(0, test.serverPlayer()));
                    test.await("the cabin comes down to floor 1", 60, () -> !cabin.moving() && cabin.floor() == 0 && onServer(cabin::atFloor), () -> {
                        test.check(onServer(() -> !test.serverPlayer().hasVehicle()), "off at floor 1");
                        test.command("tp " + name() + " " + landing(3));
                        test.await("pause: a landing without the cabin", 3, () -> false, () -> shot("mobtalk-lift-9-floor4-closed.png", this::villagerRide));
                    });
                });
            });
        }));
    }

    /** A villager on the first floor's landing calls the lift by itself, rides and gets off on another floor. */
    private void villagerRide() {
        io.github.mobtalk.server.ServerConfig.update(c -> { c.liftVillagerChance = 100; return c; });
        Direction f = cabin.layout().facing();
        double x = (cabin.layout().left().getX() + cabin.layout().right().getX()) / 2.0 + 0.5 + f.getOffsetX() * 1.8;
        double z = (cabin.layout().left().getZ() + cabin.layout().right().getZ()) / 2.0 + 0.5 + f.getOffsetZ() * 1.8;
        test.command("kill @e[tag=lifttest]");
        test.command("summon minecraft:villager " + x + " " + feet(0) + " " + z + " {PersistenceRequired:1b,Tags:[\"lifttest\",\"liftrider\"]}");
        test.command("tp " + name() + " " + landing(0));
        java.util.function.Supplier<Double> y = () -> onServer(() -> {
            List<VillagerEntity> v = world().getEntitiesByClass(VillagerEntity.class, new Box(px - 10, py - 5, pz - 15, px + 10, py + 50, pz + 10),
                    e -> e.getCommandTags().contains("liftrider"));
            return v.isEmpty() || v.get(0).hasVehicle() ? feet(0) : v.get(0).getY();
        });
        java.util.function.BooleanSupplier upstairs = () -> {
            double at = y.get();
            for (int k = 1; k < cabin.floors(); k++) if (Math.abs(at - feet(k)) < 0.05) return true;
            return false;
        };
        test.await("a villager rides the lift by itself", 150, upstairs, () -> {
            test.check(upstairs.getAsBoolean(), "the villager went up by lift and got off level with a floor (y " + String.format("%.2f", y.get()) + ")");
            io.github.mobtalk.server.ServerConfig.update(c -> { c.liftVillagerChance = 25; return c; });
            finish();
        });
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        test.command("kill @e[tag=lifttest]");
        test.command("gamerule doDaylightCycle true");
        test.await("pause: lift stage cleaned up", 2, () -> false, next);
    }
}
