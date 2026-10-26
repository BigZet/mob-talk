package io.github.mobtalk.client;

import io.github.mobtalk.game.city.CarEntity;
import io.github.mobtalk.game.city.CarTypes;
import io.github.mobtalk.game.city.CityCars;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Self-test of the city's cars ({@code -Dmobtalk.selftest.only=cars}, no LLM, no voice): every car type on an asphalt stage,
 * looked at from six sides (the front and rear three-quarters, the side, nose and tail, low from a crouching eye), with its doors
 * open and wheels turned, from the driver's seat, with a villager in the passenger seat, at night with the lamps (and a beacon).
 * Then the parking API: a yard row of {@link CityCars#parkAlways} cars and the {@code parking_spot} marker turning into a car.
 * {@code -Dmobtalk.selftest.cars=vaz2107,niva} limits the types, {@code -Dmobtalk.selftest.carschemes=police,taxi} adds schemes
 * (each a full set of shots). Screenshots: screenshots/mobtalk-car-*.png. */
final class SelfTestCars {
    private final SelfTest test;
    private final int px, py, pz;
    private Runnable next = () -> { };
    private final List<Runnable> steps = new ArrayList<>();
    private int step;

    SelfTestCars(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test;
        this.px = stageX + 60; this.py = stageY + 10; this.pz = stageZ;
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private <T> T onServer(Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private void serverDo(Runnable task) { onServer(() -> { task.run(); return true; }); }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.owner.client.options.hudHidden = true;
        test.log("car stages: every type from six sides, doors, seats, lamps, parking");
        test.command("time set 6000"); test.command("weather clear"); test.command("gamerule doDaylightCycle false");
        test.command("fill " + (px - 30) + " " + (py - 1) + " " + (pz - 30) + " " + (px + 30) + " " + (py + 8) + " " + (pz + 30) + " minecraft:air");
        test.command("fill " + (px - 30) + " " + (py - 1) + " " + (pz - 30) + " " + (px + 30) + " " + (py - 1) + " " + (pz + 30) + " minecraft:gray_concrete");
        test.command("fill " + (px - 30) + " " + (py - 1) + " " + (pz + 6) + " " + (px + 30) + " " + (py - 1) + " " + (pz + 30) + " minecraft:grass_block");
        test.command("tp " + name() + " " + px + " " + py + " " + (pz - 10) + " 0 10");
        String only = System.getProperty("mobtalk.selftest.cars", "");
        String extra = System.getProperty("mobtalk.selftest.carschemes", "");
        for (CarTypes.Type t : CarTypes.ALL) {
            if (!only.isBlank() && !List.of(only.split(",")).contains(t.id())) continue;
            List<String> schemes = new ArrayList<>();
            schemes.add(t.schemes().get(0));
            for (String s : extra.split(",")) if (!s.isBlank() && t.schemes().contains(s) && !schemes.contains(s)) schemes.add(s);
            for (String s : schemes) showcase(t, s);
        }
        steps.add(this::parking);
        test.await("pause: the stage chunks load", 4, () -> false, this::nextStep);
    }

    private void nextStep() {
        if (step >= steps.size()) { finish(); return; }
        steps.get(step++).run();
    }

    /** Puts the camera (the player in spectator) at an offset from the car's centre, looking at a point of the car. */
    private void camera(CarEntity car, double dx, double dy, double dz, double lx, double ly, double lz) {
        Vec3d eye = car.toWorld(dx, dy, dz), look = car.toWorld(lx, ly, lz);
        Vec3d d = look.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        test.command("tp " + name() + " " + eye.x + " " + (eye.y - 1.62) + " " + eye.z + " " + yaw + " " + pitch);
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 2, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private CarEntity car;

    private void showcase(CarTypes.Type t, String scheme) {
        String tag = "mobtalk-car-" + t.id() + "-" + scheme;
        double L = t.length(), W = t.width(), H = t.height();
        steps.add(() -> {
            test.command("time set 6000");
            test.command("gamemode spectator " + name());
            serverDo(() -> {
                world().getEntitiesByClass(CarEntity.class, new Box(px - 40, py - 5, pz - 40, px + 40, py + 10, pz + 40), e -> true).forEach(CarEntity::discard);
                world().getEntitiesByClass(VillagerEntity.class, new Box(px - 40, py - 5, pz - 40, px + 40, py + 10, pz + 40), e -> true).forEach(VillagerEntity::discard);
                car = CityCars.spawn(world(), px + 0.5, py, pz + 0.5, 0, t.id(), scheme);
            });
            test.check(car != null && car.type().id().equals(t.id()), t.id() + " spawned");
            Box box = car.getBoundingBox();
            test.check(Math.abs(box.getLengthZ() - L) < 0.05 && Math.abs(box.getLengthX() - W) < 0.05, t.id() + " box is " + W + " x " + L + " (" + box.getLengthX() + " x " + box.getLengthZ() + ")");
            double far = Math.max(L, 4) * 0.85;
            camera(car, W * 1.1 + 0.8, 1.7, L * 0.55 + 0.9, 0, H * 0.4, 0);
            shot(tag + "-1-front34.png", () -> { camera(car, -W * 1.1 - 0.8, 1.7, -L * 0.55 - 0.9, 0, H * 0.4, 0);
            shot(tag + "-2-rear34.png", () -> { camera(car, far + 1.5, 1.2, 0, 0, H * 0.45, 0);
            shot(tag + "-3-side.png", () -> { camera(car, 0.6, 1.1, L / 2 + 2.2 + W * 0.4, 0, H * 0.4, 0);
            shot(tag + "-4-front.png", () -> { camera(car, -0.6, 1.1, -L / 2 - 2.2 - W * 0.4, 0, H * 0.4, 0);
            shot(tag + "-5-rear.png", () -> { camera(car, -W / 2 - 1.4, 0.35, L * 0.2, 0, H * 0.35, -L * 0.15);
            shot(tag + "-6-low.png", this::nextStep); }); }); }); }); });
        });
        steps.add(() -> {
            serverDo(() -> { for (int i = 0; i < car.type().doors(); i++) car.openDoor(i, 20 * 60); car.drive(0, 1); });
            test.check(car.type().doors() == 0 || car.doorOpen(0), t.id() + " door 0 opens");
            camera(car, W * 1.2 + 1.2, 2.2, L * 0.45 + 0.8, 0, H * 0.4, 0);
            test.await("pause: the doors swing", 2, () -> false, () -> shot(tag + "-7-doors.png", () -> {
                serverDo(() -> { for (int i = 0; i < car.type().doors(); i++) car.openDoor(i, 0); car.drive(0, 0); });
                nextStep();
            }));
        });
        steps.add(() -> {
            test.command("gamemode creative " + name());
            camera(car, W, 1.6, 0, 0, 1, 0);
            test.await("pause: the player stands by", 1, () -> false, () -> {
                boolean sat = onServer(() -> car.seat(test.serverPlayer(), 0));
                test.check(sat && onServer(() -> car.seatOf(test.serverPlayer())) == 0, t.id() + ": the player sits in the driver's seat");
                if (t.seats().size() > 1) {
                    serverDo(() -> {
                        VillagerEntity v = new VillagerEntity(net.minecraft.entity.EntityType.VILLAGER, world());
                        v.refreshPositionAndAngles(car.getX() - 2, car.getY(), car.getZ(), 0, 0);
                        v.setPersistent();
                        world().spawnEntity(v);
                        car.seat(v, 1);
                    });
                }
                test.await("pause: sitting down", 2, () -> false, () -> {
                    test.command("tp " + name() + " ~ ~ ~ 0 8");
                    shot(tag + "-8-driver.png", () -> {
                        test.command("tp " + name() + " ~ ~ ~ 70 15");
                        shot(tag + "-9-passenger.png", () -> {
                            test.command("gamemode spectator " + name());
                            serverDo(() -> test.serverPlayer().stopRiding());
                            camera(car, -W / 2 - 1.3, 1.5, 0.4, 0, 0.9, 0);
                            shot(tag + "-10-villager.png", () -> {
                                test.command("time set 18000");
                                serverDo(() -> { car.setHeadlights(true); car.setBeacon(true); });
                                camera(car, W * 1.2 + 1.0, 1.7, L * 0.6 + 1.5, 0, H * 0.4, 0);
                                shot(tag + "-11-night.png", () -> {
                                    camera(car, -W * 1.2 - 1.0, 1.7, -L * 0.6 - 1.5, 0, H * 0.4, 0);
                                    shot(tag + "-12-night-rear.png", this::nextStep);
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    /** The API: a yard row of parked cars and the template marker. */
    private void parking() {
        test.command("time set 6000");
        test.command("gamemode spectator " + name());
        serverDo(() -> {
            world().getEntitiesByClass(CarEntity.class, new Box(px - 40, py - 5, pz - 40, px + 40, py + 10, pz + 40), e -> true).forEach(CarEntity::discard);
            world().getEntitiesByClass(VillagerEntity.class, new Box(px - 40, py - 5, pz - 40, px + 40, py + 10, pz + 40), e -> true).forEach(VillagerEntity::discard);
            net.minecraft.util.math.random.Random r = net.minecraft.util.math.random.Random.create(7);
            for (int i = 0; i < 9; i++)
                CityCars.parkAlways(world(), new net.minecraft.util.math.BlockPos(px - 12 + i * 3, py, pz), net.minecraft.util.math.Direction.SOUTH, r, CityCars.Place.YARD);
            CityCars.parkAlways(world(), new net.minecraft.util.math.BlockPos(px - 8, py, pz - 12), net.minecraft.util.math.Direction.EAST, r, CityCars.Place.POLICE);
            CityCars.parkAlways(world(), new net.minecraft.util.math.BlockPos(px + 6, py, pz - 12), net.minecraft.util.math.Direction.EAST, r, CityCars.Place.BUS);
        });
        int count = onServer(() -> world().getEntitiesByClass(CarEntity.class, new Box(px - 40, py - 5, pz - 40, px + 40, py + 10, pz + 40), e -> true).size());
        test.check(count == 11, "the parking API parked 11 cars (" + count + ")");
        test.command("tp " + name() + " " + (px + 0.5) + " " + (py + 7) + " " + (pz + 14) + " 180 30");
        shot("mobtalk-car-parking-yard.png", () -> {
            test.command("tp " + name() + " " + (px - 16) + " " + (py + 4) + " " + (pz - 18) + " -60 18");
            shot("mobtalk-car-parking-service.png", this::finish);
        });
    }

    private void finish() {
        test.owner.client.options.hudHidden = false;
        test.command("gamemode creative " + name());
        test.command("gamerule doDaylightCycle true");
        test.command("time set 6000");
        test.await("pause: car stage cleaned up", 2, () -> false, next);
    }
}
